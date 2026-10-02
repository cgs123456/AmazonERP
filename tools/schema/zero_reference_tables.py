#!/usr/bin/env python3
"""零引用表清点（只读，不删不改任何表）。

动因：docs 里那份「12 张零引用表」是手工三路 grep 得出的，而这一判定天生易错——
`amz_field_permission` 曾被误列，实际由 `FieldPermissionServiceImpl` 用原生 SQL 读。
所以把三路扫描固化成脚本，输出带命中类型与出处的表清单，任何人都能复核与重跑。

三路引用来源（缺一即会误判）：
1. 实体绑定：MyBatis-Plus `@TableName("x")`；
2. XML mapper：`*Mapper.xml` 里出现的表名；
3. 原生 SQL：main 代码里的字符串字面量出现该表名。

命中 test/docs/migration 只算「被提到」，不算「被使用」：迁移脚本里的建表/改列语句不是运行期引用，
这一点必须区分开，否则 V3 改过 `amz_coupon` 的列就会被读成「这张表在用」。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

CREATE_RE = re.compile(r"CREATE\s+(?:OR\s+REPLACE\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([A-Za-z0-9_]+)`?", re.I)
TABLE_NAME_RE = re.compile(r'@TableName\(\s*(?:value\s*=\s*)?"([^"]+)"')
SQL_LITERAL_RE = re.compile(r'"([^"\n]{0,600})"')
PLACEHOLDER = re.compile(r"\{(\d+)\}")


def collect_tables(root: Path) -> dict[str, list[str]]:
    tables: dict[str, set[str]] = {}
    for sql in sorted(root.rglob("db/migration/*.sql")):
        if "target" in sql.parts:
            continue
        text = sql.read_text(encoding="utf-8", errors="replace")
        for m in CREATE_RE.finditer(text):
            name = m.group(1)
            if name.lower().startswith("flyway") or name.lower() == "dual":
                continue
            tables.setdefault(name, set()).add(sql.relative_to(root).as_posix())
    return {k: sorted(v) for k, v in tables.items()}


def classify_reference(root: Path, table: str) -> dict[str, list[str]]:
    hits: dict[str, list[str]] = {"entity": [], "xml": [], "native_sql": [], "mentioned_only": []}
    pat = re.compile(r"\b" + re.escape(table) + r"\b")

    for java in sorted(root.rglob("*.java")):
        if "target" in java.parts:
            continue
        text = java.read_text(encoding="utf-8", errors="replace")
        rel = java.relative_to(root).as_posix()
        if pat.search(text) is None:
            continue
        is_main = "/src/main/" in "/" + rel
        bound = [t for t in TABLE_NAME_RE.findall(text) if t.lower() == table.lower()]
        native = []
        if is_main and not bound:
            for lit in SQL_LITERAL_RE.findall(text):
                if pat.search(lit) and not PLACEHOLDER.search(lit):
                    native.append(lit.strip()[:120])
        if bound and is_main:
            hits["entity"].append(rel)
        elif native:
            hits["native_sql"].append("%s :: %s" % (rel, native[0]))
        else:
            hits["mentioned_only"].append(rel)

    for xml in sorted(root.rglob("*.xml")):
        if "target" in xml.parts or "Mapper" not in xml.name:
            continue
        text = xml.read_text(encoding="utf-8", errors="replace")
        if pat.search(text):
            hits["xml"].append(xml.relative_to(root).as_posix())
    return hits


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--json", default="")
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()

    tables = collect_tables(root)
    rows = []
    for name, sources in sorted(tables.items()):
        refs = classify_reference(root, name)
        used = bool(refs["entity"] or refs["xml"] or refs["native_sql"])
        rows.append({
            "table": name,
            "used": used,
            "entity": refs["entity"],
            "xml": refs["xml"],
            "native_sql": refs["native_sql"],
            "mentioned_only": refs["mentioned_only"],
            "created_in": sources,
        })

    unused = [r for r in rows if not r["used"]]
    payload = {
        "schemaVersion": 1,
        "tables_total": len(rows),
        "tables_zero_reference": len(unused),
        "zero_reference": [
            {"table": r["table"], "created_in": r["created_in"],
             "mentioned_in_tests_or_docs": r["mentioned_only"]}
            for r in unused
        ],
    }
    text = json.dumps(payload, ensure_ascii=False, indent=2)
    if args.json:
        Path(args.json).write_text(text + "\n", encoding="utf-8")
    # 控制台可能是 GBK：只输出 ASCII 安全摘要，完整结果落 --json
    print("tables_total=%d zero_reference=%d" % (payload["tables_total"], payload["tables_zero_reference"]))
    for r in payload["zero_reference"]:
        print("  " + r["table"] + "  created_in=" + ",".join(Path(p).name for p in r["created_in"]))
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(errors="replace")
    raise SystemExit(main(sys.argv[1:]))
