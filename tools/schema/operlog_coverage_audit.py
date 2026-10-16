#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""全仓写端点 @OperLog 覆盖盘点（人读的台账，刻意不进 CI）。

Why this exists (HANDOFF §S27.6):
    补 MultiplatformController 那 14 个端点的 @OperLog 时（决策②），暴露出一个
    事实——**全仓没有一张表回答"其它模块的写端点审计覆盖如何"**。已有
    OperLogCoverageContractTest 只管 11 个"受管敏感域"，且刻意不追求全量
    （给「搜索历史删除」这类低价值端点机械加注解只会稀释审计表）。缺的是中间那层：
    一份能看、能分类、能决定下一步补哪里的全仓台账。

    与那个门禁测试的分工：**门禁守住"受管域不能退化"，本脚本回答"还有什么没进受管"**。
    本脚本刻意不做成 CI 闸门——全量覆盖率是产品决策（哪些端点值得审计），
    机械设阈值会把「有人故意不审计的端点」变成必须填的注解，注释也说了那只会稀释审计表。

用法：
    python tools/schema/operlog_coverage_audit.py                 # 全仓概览
    python tools/schema/operlog_coverage_audit.py --gaps          # 只看缺口
    python tools/schema/operlog_coverage_audit.py --module ad     # 单模块明细

口径（与 OperLogCoverageContractTest 一致，三处刻意不同，全部记在下面）：
    - 覆盖全部模块（门禁测试只列受管敏感域）；
    - **把 @PatchMapping 也算写**：Spring 4 起支持，漏算等于漏报一个维度；
    - **机器端点单列、不计缺口**：它们没有"当前用户"可记（webhook 走 HMAC 验签、
      oauth token 走 appKey+appSecret），加了 @OperLog 只会记出一堆无操作人的行。

    分母必须打印。**未匹配一律披露不静默跳过**：解析失败会伪装成 0 缺口，
    那正是 §S20.7 的教训（凭印象代替枚举）。扫不到任何端点时直接报错退出。
"""
import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

# 写端点注解。含 PatchMapping：既有的门禁测试没算它，那是它的口径；
# 盘点口径必须比门禁宽，否则"还没进门禁的端点"在盘点里也看不见。
WRITE_MAPPING = re.compile(r"@(PostMapping|PutMapping|DeleteMapping|PatchMapping)\b")
OPER_LOG = re.compile(r"@OperLog\b")
REQUIRE_ROLE = re.compile(r"@RequireRole\b")
METHOD_START = re.compile(r"^[a-z]+\s+[\w<>\[\].,?\s]+\s+\w+\s*\(")
METHOD_NAME = re.compile(r"^[a-z]+\s+[\w<>\[\].,?\s]+\s+(\w+)\s*\(")
URL_IN_ANNOTATION = re.compile(r'"/([^"]*)"')

# 机器端点：没有"当前用户"可记，唯一鉴权是自带的。
# 与 MultiplatformWriteEndpointGuardContractTest.MACHINE_ENDPOINTS 同口径。
MACHINE_ENDPOINTS = {
    "receiveWebhook": "平台回调（Temu/TikTok/Shein），白名单放行，HMAC 验签",
    "generateToken": "开放 API，外部系统凭 appKey+appSecret 换 token",
}


def find_repo_root() -> Path:
    current = Path(__file__).resolve()
    for depth in range(8):
        current = current.parent
        if (current / ".github" / "workflows" / "ci.yml").is_file() and (current / "amz-service").is_dir():
            return current
    raise SystemExit("无法定位仓库根目录（应含 .github/workflows/ci.yml 与 amz-service/）")


def scan_controller(path: Path):
    """返回该 Controller 的写端点清单。判定口径与门禁测试一致：方法级，不看文件级。"""
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    endpoints = []
    last_method_start = -1
    for i, line in enumerate(lines):
        trimmed = line.strip()
        if METHOD_START.match(trimmed) and trimmed.startswith(("public ", "protected ", "private ")):
            last_method_start = i
            continue
        mapping = WRITE_MAPPING.search(trimmed)
        if not mapping:
            continue
        # 注解区：上一个方法起始行之后到本 Mapping 行。只看这个区间，
        # 免得把别的方法的 @OperLog 算到本端点头上（文件级判定的假绿）。
        start = last_method_start + 1 if last_method_start >= 0 else 0
        block = "\n".join(lines[start:i + 1])
        name = method_name_after(lines, i)
        url = url_after(lines, i)
        endpoints.append({
            "line": i + 1,
            # PostMapping -> POST。切掉 7 字符 "Mapping"；@PatchMapping 同理得到 PATCH。
            "verb": mapping.group(1)[:-7].upper(),
            "method": name,
            "url": url,
            "oper_log": bool(OPER_LOG.search(block)),
            "role": bool(REQUIRE_ROLE.search(block)),
            "machine": name in MACHINE_ENDPOINTS,
        })
    return endpoints


def method_name_after(lines, mapping_line):
    for i in range(mapping_line, min(mapping_line + 5, len(lines))):
        match = METHOD_NAME.match(lines[i].strip())
        if match:
            return match.group(1)
    return lines[mapping_line].strip()


def url_after(lines, mapping_line):
    """从 Mapping 行往下找 URL 字面量（跨行签名最多容忍 3 行）。"""
    for i in range(mapping_line, min(mapping_line + 3, len(lines))):
        match = URL_IN_ANNOTATION.search(lines[i])
        if match:
            return match.group(1)
    return ""


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--gaps", action="store_true", help="只列缺口（缺 @OperLog 的人操作端点）")
    parser.add_argument("--module", help="只看该模块（子串匹配，如 ad / spapi）")
    args = parser.parse_args()

    root = find_repo_root()
    rows = []
    for controller in sorted((root / "amz-service").glob("*/src/main/java/com/amz/controller/*.java")):
        # 模块名取相对 amz-service 的第一段。**不能写 controller.parts[1]**：
        # parts 是绝对路径切片，[1] 在 Windows 上会是当前用户名（Users），
        # 于是所有行都归到一个叫 "Users" 的假模块下——聚合结果看着正常、其实全错。
        module = controller.relative_to(root / "amz-service").parts[0]
        if args.module and args.module not in module:
            continue
        for endpoint in scan_controller(controller):
            endpoint.update({"module": module, "file": controller.name})
            rows.append(endpoint)

    # 分母必须打印：扫不到端点就是解析器失效，不是"没问题"
    if not rows:
        print("ERROR: 没有扫到任何写端点——扫描逻辑失效了，这不是通过", file=sys.stderr)
        return 2

    machine = [r for r in rows if r["machine"]]
    gaps = [r for r in rows if not r["oper_log"] and not r["machine"]]
    covered = len(rows) - len(machine) - len(gaps)

    if args.gaps:
        print(f"{'模块':26} {'文件':34} {'行':>4} {'动作':6} {'URL':40} {'方法':24} {'已锁角色'}")
        print("-" * 130)
        for r in gaps:
            print(f"{r['module']:26} {r['file']:34} {r['line']:>4} {r['verb']:6} "
                  f"{('/' + r['url'] if r['url'] else '-'):40} {r['method']:24} {'Y' if r['role'] else 'N'}")
        print()
    else:
        by_module = defaultdict(lambda: [0, 0, 0])  # 缺口 / 其中已锁角色 / 角色也没有
        for r in gaps:
            by_module[r["module"]][0] += 1
            by_module[r["module"]][1 if r["role"] else 2] += 1

        print(f"{'模块':26} {'缺口':>4} {'已锁角色':>7} {'角色也没有':>8}")
        print("-" * 52)
        total = [0, 0, 0]
        for module, (a, b, c) in sorted(by_module.items(), key=lambda kv: -kv[1][0]):
            print(f"{module:26} {a:>4} {b:>7} {c:>8}")
            total[0] += a
            total[1] += b
            total[2] += c
        print("-" * 52)
        print(f"{'合计':26} {total[0]:>4} {total[1]:>7} {total[2]:>8}")
        print()
        print(f"写端点总数（含 PATCH）: {len(rows)}")
        print(f"已有 @OperLog: {covered}  ({covered * 100 // len(rows)}%)")
        print(f"人操作缺口: {len(gaps)}（其中 {total[1]} 个已有 @RequireRole，补审计是纯增益；"
              f"{total[2]} 个连角色都没有，先补角色再谈审计）")
        print(f"机器端点（不计缺口）: {len(machine)}"
              + (f"（{', '.join(sorted({r['method'] for r in machine}))}）" if machine else ""))
        print()
        print("明细：加 --gaps。单模块：--module ad --gaps")
    return 0


if __name__ == "__main__":
    sys.exit(main())
