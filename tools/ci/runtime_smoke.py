"""服务启动冒烟：真正把 fat jar 跑起来，而不是只检查配置文本。

存在动因（2026-09-30 实测）：`logback-spring.xml` 的 conversionRule 指向了依赖里不存在的类，
logback 产生 ERROR status，Spring Boot 随即抛 IllegalStateException —— 16 个服务启动即失败，
而当时全绿的契约测试只断言 XML 里有没有某个字符串，抓不到这一类故障。
本脚本把"能否启动到 Started"变成 CI 可执行的红绿灯。

判定所需的纯函数与进程管理分离，便于单测（见 test_runtime_smoke.py）。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field

# 启动成功标记（Spring Boot：Started XxxApplication in 10.098 seconds）
STARTED_RE = re.compile(r"Started\s+\S+\s+in\s+[\d.]+\s+seconds")

# 命中即判失败：都是"进程已经死了或正在死"的硬信号，不是可忽略的告警。
FATAL_SIGNATURES: tuple[tuple[str, str], ...] = (
    ("logback-configuration-error", "Logback configuration error detected"),
    ("converter-instantiate-failure", "Failed to instantiate converter class"),
    ("application-failed-to-start", "APPLICATION FAILED TO START"),
)

# 16 个模块的 console pattern 都含 [%traceId]；无 agent 时渲染 "[TID: N/A]"，
# 有 agent 时渲染 "[TID:<id>]"。若 conversionWord 解析不出来，方括号会变空——
# 正是这次故障在日志里的形状，因此把它当作必须存在的最小证据。
TRACE_SLOT_MARKER = "[TID"

# 冒烟必须与开发者本机在跑的中间件彻底隔离：全部指向已关闭端口。
# 依赖缺失只会让后台重试报错，不影响启动（实测 message 模块 10.1s 起得来）。
ISOLATED_ENV = {
    "MYSQL_HOST": "127.0.0.1",
    "MYSQL_SLAVE_HOST": "127.0.0.1",
    "MYSQL_PORT": "1",
    "REDIS_HOST": "127.0.0.1",
    "REDIS_PORT": "1",
    "RABBITMQ_HOST": "127.0.0.1",
    "RABBITMQ_PORT": "1",
    "NACOS_ADDR": "127.0.0.1:1",
    "DB_PASSWORD": "runtime-smoke-unused",
    "REDIS_PASSWORD": "runtime-smoke-unused",
    "RABBITMQ_PASSWORD": "runtime-smoke-unused",
    "JWT_SECRET_KEY": "runtime-smoke-only-dummy-secret-0123456789abcdef",
    "CRYPTO_KEY": "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
}


@dataclass
class Verdict:
    passed: bool
    reasons: list[str] = field(default_factory=list)


def classify(log_text: str, *, expect_trace_slot: bool = True) -> Verdict:
    """纯函数：给定（可能不完整的）进程输出，判定冒烟结果。"""
    reasons: list[str] = []

    for name, needle in FATAL_SIGNATURES:
        if needle in log_text:
            line = next((l for l in log_text.splitlines() if needle in l), needle)
            reasons.append(f"{name}: {line.strip()[:300]}")

    if not STARTED_RE.search(log_text):
        reasons.append("未出现启动成功标记（Started <App> in N seconds）——进程启动失败或超时")

    if expect_trace_slot and TRACE_SLOT_MARKER not in log_text:
        reasons.append(
            f"日志里找不到 {TRACE_SLOT_MARKER} 槽位：console pattern 的 %traceId 未被解析"
            "（转换词指向不存在的类时就是这个形状）")

    return Verdict(passed=not reasons, reasons=reasons)


def build_command(jar: str, profile: str, port: int, extra: list[str]) -> list[str]:
    cmd = [
        "java", "-jar", jar,
        f"--spring.profiles.active={profile}",
        f"--server.port={port}",
        "--spring.cloud.nacos.discovery.enabled=false",
        "--spring.cloud.nacos.config.enabled=false",
        "--spring.cloud.nacos.discovery.register-enabled=false",
    ]
    cmd.extend(extra)
    return cmd


def run_smoke(jar: str, *, profile: str = "mock", port: int = 18899,
              timeout: int = 180, expect_trace_slot: bool = True,
              poll_interval: float = 1.0) -> tuple[Verdict, str]:
    """启动 jar，轮询判定；命中致命信号立刻失败，成功标记出现即通过。"""
    if not os.path.isfile(jar):
        return Verdict(False, [f"jar 不存在：{jar}"]), ""

    env = {**os.environ, **ISOLATED_ENV}
    proc = subprocess.Popen(
        build_command(jar, profile, port, []),
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", env=env)

    chunks: list[str] = []
    deadline = time.monotonic() + timeout
    try:
        assert proc.stdout is not None
        while time.monotonic() < deadline:
            line = proc.stdout.readline()
            if line:
                chunks.append(line)
                text = "".join(chunks)
                if STARTED_RE.search(text):
                    return classify(text, expect_trace_slot=expect_trace_slot), text
                for _, needle in FATAL_SIGNATURES:
                    if needle in text:
                        return classify(text, expect_trace_slot=False), text
            elif proc.poll() is not None:
                break
            else:
                time.sleep(poll_interval)
        text = "".join(chunks)
        if proc.poll() is not None and not STARTED_RE.search(text):
            text += f"\n进程提前退出，exit={proc.returncode}\n"
        return classify(text, expect_trace_slot=expect_trace_slot), text
    finally:
        _terminate(proc)


def _terminate(proc: subprocess.Popen) -> None:
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=20)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, help="Spring Boot fat jar 路径")
    parser.add_argument("--profile", default="mock")
    parser.add_argument("--port", type=int, default=18899)
    parser.add_argument("--timeout", type=int, default=180, help="等待启动的上限秒数")
    parser.add_argument("--no-trace-slot-check", action="store_true",
                        help="跳过 %traceId 槽位检查（仅在其他模块刻意不改 pattern 时使用）")
    parser.add_argument("--dump-log", default="", help="把进程输出写到该路径，便于 CI 归档")
    args = parser.parse_args(argv)

    verdict, log_text = run_smoke(
        args.jar, profile=args.profile, port=args.port, timeout=args.timeout,
        expect_trace_slot=not args.no_trace_slot_check)

    if args.dump_log and log_text:
        with open(args.dump_log, "w", encoding="utf-8") as fh:
            fh.write(log_text)

    if verdict.passed:
        print(f"RUNTIME_SMOKE PASS jar={os.path.basename(args.jar)}")
        return 0

    print(f"RUNTIME_SMOKE FAIL jar={os.path.basename(args.jar)}")
    for reason in verdict.reasons:
        print(f"  - {reason}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
