#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""源码文件行数棘轮（防增长门禁）。

Why this exists (HANDOFF §S31 / P1):
    CLAUDE.md 规定「单个文件不得超过 500 行」。全仓实测有 **51 个文件违反**
    （后端 main 22 + 后端 test 7 + 前端 22），最极端的 `LogisticsDashboard.vue`
    3259 行。一次性拆完 51 个不现实，也不该混进任何功能改动的 diff 里
    （§S28 的同一教训：两件事的 diff 混在一起会失败原因不可归因）。

    所以本门禁的立场是**冻结存量、禁止增长**，而不是「马上全部合规」：
    存量登记进基线，任何文件**只许变短不许变长**，新增超限文件直接红。
    这样技术债会随拆分单调递减，而不会一边拆一边长。

    与 repository_hygiene.py 的 `large-file` 规则不是重复：那条按**字节**判、
    阈值 10MB，防的是误提交产物；本条按**行**判、阈值 500，防的是模块边界退化。
    两者量的是不同的东西。

口径刻意收窄（否则会误报成一堆与代码质量无关的违规）：
    - 只扫**源码**：`*.java`（main + test）、`*.vue`、`*.ts`。
    - **排除**构建产物与依赖（target / node_modules / dist / build）、
      自动生成的类型（见 GENERATED_NAME_HINTS）、Flyway 迁移目录，
      以及**静态数据表**（见 STATIC_DATA_TABLE_RULES）——后者是「整文件都是常量条目、
      零控制流」的目录型文件，把它拆成几块只会更难维护。
    - 空行与注释**照常计入**——判据是「这个文件读起来有多长」，不是「有多少逻辑行」。
      去掉注释计数的话，加注释会变成规避手段，而注释恰恰是长文件最需要的东西。

用法：
    python tools/release/file_size_ratchet.py                    # 检查
    python tools/release/file_size_ratchet.py --update-baseline   # 重新生成基线
    python tools/release/file_size_ratchet.py --report out.json   # 机读输出
"""
from __future__ import annotations

import argparse
import os
import re
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

LIMIT_LINES = 500
RULES_VERSION = 1
BASELINE_NAME = "file-size-baseline.json"

# 参与统计的源码扩展名。只列这三种：CLAUDE.md 的 500 行规则讲的是代码模块，
# 而 md/json/yaml/sql 的"长"往往是数据或文档（迁移 SQL 尤其），按同一把尺子量
# 只会制造噪音——那些文件该由各自的门禁管（迁移不可变性已由 Flyway 契约测试钉住）。
SOURCE_SUFFIXES = {".java", ".vue", ".ts"}

# 排除的路径片段（小写比较）。node_modules/target/dist/build 是产物；
# db/migration 是 Flyway 脚本目录（里面主要是 .sql，但 java 迁移回调也一并豁免，
# 因为迁移一旦落库就不可改，行数增长不是"退化"而是"没写完"）。
EXCLUDED_PATH_PARTS = {
    "target", "node_modules", "dist", "build", ".gradle", "out",
    "generated", "generated-sources", "db", "migration",
}

# 名字里含这些片段的文件视为自动生成/数据表，不计入。
# 判据是「人不该手改它」——改了也会被覆盖，因此对它报"太长"毫无意义。
GENERATED_NAME_HINTS = (
    "generated", ".d.ts", "grpc_generated", "_pb2",
)

SKIPPED_DIR_NAMES = {
    ".git", ".idea", ".mvn", ".pytest_cache", ".venv", "__pycache__",
    "logs", "coverage", "playwright-report", "test-results",
}


@dataclass(frozen=True, order=True)
class Finding:
    rule: str
    path: str
    detail: str

    def to_dict(self) -> dict[str, str]:
        return {"rule": self.rule, "path": self.path, "detail": self.detail}


def find_repo_root(start: Path | None = None) -> Path:
    current = (start or Path(__file__).resolve()).resolve()
    for _ in range(8):
        current = current.parent
        if (current / ".github" / "workflows" / "ci.yml").is_file() and (current / "amz-service").is_dir():
            return current
    raise SystemExit("无法定位仓库根目录（应含 .github/workflows/ci.yml 与 amz-service/）")


def count_lines(path: Path) -> int:
    """按换行符计行数；末行无换行也算一行（与 wc -l 的直觉一致）。"""
    text = path.read_text(encoding="utf-8", errors="replace")
    if not text:
        return 0
    return len(text.splitlines())


def source_files(root: Path) -> list[Path]:
    """全部参与统计的源码文件（Git 已跟踪的 + 工作区未跟踪的新源码）。

    **只取已跟踪文件会漏掉正要提交的新文件**——而那恰恰是棘轮最该拦住的对象。
    CI 上工作区干净，两条路等价；本地则能提前拦住。
    """
    candidates: set[Path] = set()
    tracked = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z"],
        check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if tracked.returncode == 0:
        for raw in tracked.stdout.split(b"\0"):
            if raw:
                candidates.add(root / raw.decode("utf-8", errors="surrogateescape"))
    # 未跟踪文件（含 staged）
    untracked = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z", "--others", "--exclude-standard"],
        check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if untracked.returncode == 0:
        for raw in untracked.stdout.split(b"\0"):
            if raw:
                candidates.add(root / raw.decode("utf-8", errors="surrogateescape"))

    if not candidates:
        # git 不可用（不是仓库、或 CI 里 .git 缺失）时退化为遍历目录。
        # **不能退化成"什么都没扫到"**——那会让门禁在 --root 指向非 git 目录时
        # 静默通过，而输出长得和"全部合规"一模一样。
        for current_root, dirs, names in os.walk(root):
            dirs[:] = [d for d in dirs if d not in SKIPPED_DIR_NAMES]
            for name in names:
                candidates.add(Path(current_root) / name)

    selected: list[Path] = []
    for path in candidates:
        if not path.is_file():
            continue
        try:
            relative = path.relative_to(root)
        except ValueError:
            continue
        if qualifies(relative, path):
            selected.append(path)
    return sorted(selected, key=lambda p: p.relative_to(root).as_posix())


CONTROL_FLOW = re.compile(r"\b(if|for|while|switch|try|catch)\b\s*\(")
STATIC_FINAL = re.compile(r"\bstatic\s+final\b")
# 判据阈值。static final >= 2 是为了避开那些「恰好声明了 1 个常量」的普通文件
# （实测 LogisticsOpsDashboardServiceImplTest 只有 1 个，不该被豁免）。
MIN_STATIC_FINALS = 2


def _strip_java_comments(text: str) -> str:
    """去掉 Java 注释与字符串字面量，避免把注释里的 if/for 算成控制流。"""
    out = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == chr(92):
                    i += 1
                i += 1
        elif text.startswith("//", i):
            while i < n and text[i] != chr(10):
                i += 1
        elif text.startswith("/*", i):
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        else:
            out.append(ch)
        i += 1
    return "".join(out)


def is_static_data_table(path: Path) -> bool:
    """是否「整文件都是静态数据条目」的目录型文件（不该按代码模块拆分）。

    <b>判据是内容特征，不是文件名</b>：名字里没有 generated 的目录表（如
    {@code SpApiOperationCatalog}，751 行全部是 {@code new SpApiOperationSpec(...)}
    条目、0 个 if/for/switch）同样该豁免。按名字判会漏掉它，而它的"长"是数据的长，
    不是复杂度的长——拆成几块只会让一条完整的操作定义被撕开。

    三条同时成立才豁免（实测全仓 .java 中只有 SpApiOperationCatalog 命中）：
      1. 控制流语句为 0；
      2. static final 声明 >= 2（避开"恰好有 1 个常量"的普通文件与测试）；
      3. 非测试文件——测试的"长"往往是 fixture 数据，但该由测试自己重构，不由本门禁豁免。
    """
    try:
        text = _strip_java_comments(path.read_text(encoding="utf-8", errors="replace"))
    except OSError:
        return False
    if len(CONTROL_FLOW.findall(text)) != 0:
        return False
    return len(STATIC_FINAL.findall(text)) >= MIN_STATIC_FINALS


def qualifies(relative: Path, absolute: Path | None = None) -> bool:
    """该相对路径是否参与行数统计。

    `absolute` 用于需要读文件内容的判据（静态数据表）。测试可只传 relative，
    此时跳过按内容判定——判据与路径规则分开测，失败原因才好归因。
    """
    """该相对路径是否参与行数统计。"""
    parts = [part.lower() for part in relative.parts[:-1]]
    if any(part in EXCLUDED_PATH_PARTS for part in parts):
        return False
    if any(part in SKIPPED_DIR_NAMES for part in parts):
        return False
    name = relative.name.lower()
    if any(hint in name for hint in GENERATED_NAME_HINTS):
        return False
    if relative.suffix.lower() != ".java":
        return relative.suffix.lower() in SOURCE_SUFFIXES
    # 静态数据表豁免：只针对非测试的 java（测试的长该由测试自己重构）
    if absolute is not None and "/src/test/" not in relative.as_posix()             and is_static_data_table(absolute):
        return False
    return True


def scan(
    root: Path,
    baseline: dict[str, int],
    explicit_files: Sequence[Path] | None = None,
) -> tuple[list[Finding], dict[str, int]]:
    """返回（违规清单, 建议的新基线）。

    `explicit_files` 是给测试留的接缝：传了就跳过 git 发现，直接按这些路径判。
    判定逻辑（棘轮的三个方向）与收集逻辑（什么算源码）是两件事，缠在一起测的话，
    测判定得先造一个 git 仓库——失败原因会不可归因。

    新基线在这里一并算出来，是为了让 `--update-baseline` 与检查逻辑**共用同一份
    口径**——两份实现分别数一遍，早晚会在排除规则上走岔。
    """
    findings: list[Finding] = []
    counts: dict[str, int] = {}
    files = sorted(explicit_files, key=lambda p: p.as_posix()) if explicit_files is not None         else source_files(root)
    for path in files:
        # 绝对路径与相对路径的换算用 os.path.relpath 而不靠 Path.relative_to 的
        # 层级假设：explicit_files 可能指向别的根（测试里是临时目录）。
        relative = Path(path).relative_to(root).as_posix()
        lines = count_lines(path)
        if lines <= LIMIT_LINES:
            # 合规文件直接跳过。第一版在这里忘了 continue，于是**每一个不在基线里的
            # 源码文件**都被报成 oversized-new-file（107 行的文件报"超过 500 行"）——
            # 报错信息与判据脱节，比漏报更难查：它会让人去查那个文件，而坏的是扫描器。
            continue
        counts[relative] = lines
        allowed = baseline.get(relative)
        if allowed is None:
            # 新出现的超限文件：要么拆，要么显式加进基线（后者必须是决策，不能顺手）
            findings.append(Finding(
                "oversized-new-file", relative,
                f"{lines} lines exceeds {LIMIT_LINES} and is not in the baseline"))
        elif lines > allowed:
            findings.append(Finding(
                "oversized-file-grew", relative,
                f"{lines} lines exceeds its recorded {allowed} "
                f"(+{lines - allowed}); the ratchet only allows shrinking"))
    # 基线里已经不合规/已删除的条目 → 陈旧，必须清掉，否则基线只增不减
    for relative, allowed in sorted(baseline.items()):
        if relative in counts:
            continue
        on_disk = root / relative
        actual = count_lines(on_disk) if on_disk.is_file() else None
        reason = ("file no longer exceeds the limit"
                  if actual is not None else "file is missing or no longer counted")
        findings.append(Finding(
            "stale-baseline-entry", relative,
            f"{reason} (recorded {allowed}, now {actual if actual is not None else 'absent'}); "
            f"re-run --update-baseline"))
    return findings, counts


def load_baseline(path: Path) -> dict[str, int]:
    if not path.is_file():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    entries = data.get("files")
    if not isinstance(entries, dict):
        raise SystemExit(f"{path}: 缺少 files 对象——基线格式变了？")
    return {str(k): int(v) for k, v in entries.items()}


def dump_baseline(path: Path, counts: dict[str, int]) -> None:
    payload = {
        "schemaVersion": 1,
        "rulesVersion": RULES_VERSION,
        "limitLines": LIMIT_LINES,
        "summary": {
            "files": len(counts),
            "totalLines": sum(counts.values()),
            "largest": max(counts.items(), key=lambda kv: kv[1])[0] if counts else None,
        },
        # 排序输出：基线进 Git，无序 diff 会让每次 --update 产生一片无关改动，
        # 掩盖"到底哪个文件变长了"这条真正的信息。
        "files": {k: counts[k] for k in sorted(counts)},
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def report(findings: Sequence[Finding], counts: dict[str, int]) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "rulesVersion": RULES_VERSION,
        "limitLines": LIMIT_LINES,
        "summary": {
            "findings": len(findings),
            "overLimitFiles": len(counts),
            "overLimitTotalLines": sum(counts.values()),
        },
        "findings": [finding.to_dict() for finding in sorted(findings)],
    }


def parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="源码文件行数棘轮（防增长门禁）")
    parser.add_argument("--root", type=Path, default=None)
    parser.add_argument("--baseline", type=Path, default=None)
    parser.add_argument("--update-baseline", action="store_true",
                        help="用当前扫描结果重写基线（拆完文件后运行，让债务单调递减）")
    parser.add_argument("--report", type=Path, default=None, help="把机读结果写到该文件")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    root = (args.root or find_repo_root()).resolve()
    baseline_path = args.baseline or (Path(__file__).resolve().parent / BASELINE_NAME)

    baseline = load_baseline(baseline_path)
    findings, counts = scan(root, baseline)

    # 分母守卫：一条源码都没扫到，说明口径失效（目录结构变了、git 不可用等）。
    # 这时候"0 违规"是最危险的输出——它会被读成"全部合规"。
    if not counts and not baseline:
        print("ERROR: 没有扫到任何超限源码文件，且基线为空——扫描逻辑失效了，这不是通过",
              file=sys.stderr)
        return 2

    if args.update_baseline:
        dump_baseline(baseline_path, counts)
        print(f"baseline written: {baseline_path} ({len(counts)} files, "
              f"{sum(counts.values())} lines)")
        return 0

    payload = report(findings, counts)
    if args.report:
        args.report.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                                encoding="utf-8")
    if findings:
        print(f"file size ratchet FAILED: {len(findings)} finding(s)")
        for finding in sorted(findings):
            print(f"  [{finding.rule}] {finding.path}: {finding.detail}")
        print("\n拆小它，或（若确属例外）运行 "
              "python tools/release/file_size_ratchet.py --update-baseline 并在评审里说明理由")
        return 1
    print("file size ratchet passed")
    print(json.dumps(payload["summary"], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
