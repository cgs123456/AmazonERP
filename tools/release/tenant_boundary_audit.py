#!/usr/bin/env python3
"""Tenant-boundary audit for the AmazonERP service modules.

Why this exists
---------------
Three real defects in one session all had the same shape: a row was loaded by
id (or by a key without ``shop_id``) and then read/written without checking
which shop it belongs to.  Each time the audit was done by grepping for
``selectById`` and eyeballing a few lines around it, which produced both
false positives and missed defects.

This script replaces the eyeballing.  It does two structural passes:

1. **Endpoints** - parse every ``@XxxMapping`` in every ``*Controller*.java``,
   walking *upwards* over the annotation block (not a fixed line window) to
   find ``@ShopScoped`` / ``@RequireRole`` and whether the signature mentions
   ``shopId``.
2. **Data access** - for every ``selectById`` / ``selectList`` / ``selectCount``
   / ``selectPage``, walk *upwards* to the enclosing method and *downwards* a
   fixed window, then decide whether a tenant guard is present.

It then prints a candidate list.  **A candidate is not a defect.**  The point
of the script is to make the candidate list complete and reproducible so a
human can read each one instead of guessing from grep output.

Exit codes
----------
0  no candidates (nothing to read)
1  candidates found (read them; most are legitimate)
2  usage / IO error

Only the standard library is used, matching the other tools in this directory.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

MAPPING = re.compile(r"@(Get|Post|Put|Delete|Patch|Request)Mapping")
DATA_ACCESS = re.compile(
    r"\.selectById\(|\.selectList\(|\.selectCount\(|\.selectPage\(|\.selectOne\(|"
    r"\.updateById\(|\.deleteById\("
)

# A tenant guard is any of these appearing between the method start and the
# data access, or within GUARD_LOOKAHEAD lines after it.
GUARD = re.compile(
    r"ShopId|shop_id|isShopAllowed|requireShop|getShopId|"
    r"UserContext\.|currentShop"
)
GUARD_LOOKAHEAD = 12

# Methods that are themselves the guard, or helpers whose callers guard.
# Guard helper names.  ``require\w*Access`` covers the family the repo actually
# uses — requireShopAccess / requireRuleAccess / requireSupplierAccess /
# requireScheduleAccess — instead of enumerating each one (the enumeration was
# how requireRuleAccess came to be reported as unguarded).
GUARD_NAME = re.compile(
    r"require\w*Access|requireAccessible|mustGetAuthorized|getShipment|"
    r"requireShop|requireOwner"
)


@dataclass(frozen=True)
class Candidate:
    path: str
    line: int
    kind: str
    detail: str

    def render(self) -> str:
        return f"{self.path}:{self.line}  [{self.kind}]  {self.detail}"


def _annotation_block(lines: Sequence[str], i: int) -> str:
    """Walk upwards from a mapping annotation over its annotation block."""
    j = i - 1
    out: list[str] = []
    while j >= 0:
        stripped = lines[j].strip()
        if stripped.startswith(("@", "//", "*", "*/")):
            out.append(stripped)
            j -= 1
            continue
        break
    signature = lines[j] if j >= 0 else ""
    return "\n".join(out) + "\n" + signature


# A method declaration: a modifier at class-body indentation (<= 4 spaces)
# that opens a block on the same line.  The indentation bound is what keeps
# control-flow openers out: try { and for (...) { are always nested deeper.
# An earlier version also excluded them by keyword; scanning every module found
# no control-flow line at <= 4 spaces, so that filter was unreachable -- deleted
# rather than kept just in case.
METHOD_DECL = re.compile(
    r"^\s{0,4}(public|private|protected|static|final|abstract|synchronized)"
)


def _method_spans(lines: Sequence[str]) -> list[tuple[int, int]]:
    """All (start, end) line spans of method-like blocks, found going forward.

    Going forward is the only direction that works: scanning backwards from a
    data-access line stops at the nearest ``try {`` because that block's
    closing brace sits *below* the access and is never counted, so the guard
    written above the ``try`` was never seen.
    """
    spans: list[tuple[int, int]] = []
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("@"):
            continue
        if not METHOD_DECL.match(line) or "(" not in line or "{" not in line:
            continue
        depth = 0
        end = len(lines) - 1
        for k in range(i, len(lines)):
            depth += lines[k].count("{") - lines[k].count("}")
            if depth <= 0 and k > i:
                end = k
                break
        spans.append((i, end))
    return spans


def _enclosing_method_has_guard(lines: Sequence[str], i: int) -> bool:
    """Decide per-method, not per-window: scan the whole method body."""
    spans = _method_spans(lines)
    best: tuple[int, int] | None = None
    for start, end in spans:
        if start <= i <= end and (best is None or start > best[0]):
            best = (start, end)
    if best is None:
        return False
    start, end = best
    body = "\n".join(lines[start : end + 1])
    return bool(GUARD.search(body)) or bool(GUARD_NAME.search(body))


def _is_method_mapping(lines: Sequence[str], i: int) -> bool:
    """True when the mapping at line ``i`` is on a method, not on the class."""
    for line in lines[i + 1 : i + 6]:
        stripped = line.strip()
        if not stripped or stripped.startswith("@"):
            continue
        return not stripped.startswith(("public class", "class ", "abstract class"))
    return False


def audit_endpoints(root: Path, module: str) -> list[Candidate]:
    found: list[Candidate] = []
    for path in sorted(root.glob(f"{module}/src/main/java/**/*Controller*.java")):
        rel = path.as_posix()
        lines = path.read_text(encoding="utf-8", errors="replace").split("\n")
        for i, line in enumerate(lines):
            if not MAPPING.search(line):
                continue
            if not _is_method_mapping(lines, i):
                continue
            block = _annotation_block(lines, i)
            mentions_shop = "shopId" in block
            if not mentions_shop:
                continue
            if "@ShopScoped" in block:
                continue
            # Un-scoped but shop-related: only a candidate if there is no
            # explicit guard either (many endpoints validate in the body).
            window = "\n".join(lines[i : i + GUARD_LOOKAHEAD])
            if GUARD.search(window):
                continue
            found.append(
                Candidate(
                    rel,
                    i + 1,
                    "endpoint",
                    f"{line.strip()[:60]} — shop 相关但既无 @ShopScoped 也无显式校验",
                )
            )
    return found


def audit_data_access(root: Path, module: str) -> list[Candidate]:
    found: list[Candidate] = []
    for path in sorted(root.glob(f"{module}/src/main/java/**/*.java")):
        rel = path.as_posix()
        lines = path.read_text(encoding="utf-8", errors="replace").split("\n")
        for i, line in enumerate(lines):
            if not DATA_ACCESS.search(line):
                continue
            window = "\n".join(lines[i + 1 : i + 1 + GUARD_LOOKAHEAD])
            if GUARD.search(window):
                continue
            if _enclosing_method_has_guard(lines, i):
                continue
            found.append(
                Candidate(rel, i + 1, "data-access", line.strip()[:70])
            )
    return found


def audit_module(root: Path, module: str) -> list[Candidate]:
    return audit_endpoints(root, module) + audit_data_access(root, module)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument(
        "--modules",
        nargs="*",
        default=None,
        help="module directory names under amz-service (default: all)",
    )
    args = parser.parse_args(argv)

    service_root = args.root.resolve() / "amz-service"
    if not service_root.is_dir():
        print(f"missing service root: {service_root}", file=sys.stderr)
        return 2

    if args.modules:
        modules = list(args.modules)
    else:
        modules = sorted(p.name for p in service_root.iterdir() if p.is_dir())

    total = 0
    for module in modules:
        candidates = audit_module(service_root, module)
        total += len(candidates)
        if not candidates:
            print(f"{module}: clean")
            continue
        print(f"{module}: {len(candidates)} candidate(s)")
        for c in candidates:
            print("   " + c.render())

    print(f"\ntotal candidates: {total}")
    if total:
        print(
            "候选不等于缺陷：逐条读。多数是后台任务、全局参考数据、"
            "或 shopId 在 @RequestBody 内（切面拦不到，需显式校验）。"
        )
    return 1 if total else 0


if __name__ == "__main__":
    raise SystemExit(main())
