"""静态核对：实体声明的列 vs Flyway/初始化 SQL 里该表真实存在的列。

动因：Product 实体（@Deprecated）映射 name/type/image/time/sales/user_id/stock，
而 amz_product 的 Flyway DDL 里没有这些列；同一个 mapper 又被已接线的
searchProducts 使用。先量清同类漂移有多少，再决定改哪边。
"""
import io
import os
import re
import sys
from collections import defaultdict

# 取第一个非选项参数作根目录：曾把 '--self-test' 当成 ROOT，导致真文件检查全部走"文件不存在"分支
ROOT = next((a for a in sys.argv[1:] if not a.startswith('-')), '.')

TABLE_RE = re.compile(r'@TableName\(\s*"?([A-Za-z0-9_]+)"?\s*\)')
# 字段：private X y;  带 @TableField("col") 时优先用它，否则驼峰转下划线
FIELD_RE = re.compile(r'private\s+(?:static\s+final\s+)?[\w<>,.\[\] ]+\s+(\w+)\s*;')
TABLEFIELD_RE = re.compile(r'@TableField\(\s*(?:value\s*=\s*)?"([^"]+)"')
EXIST_RE = re.compile(r'@TableField\s*\(\s*exist\s*=\s*false', re.I)
CREATE_RE = re.compile(r'CREATE TABLE (?:IF NOT EXISTS )?`?(\w+)`?\s*\((.*)\)\s*(?:ENGINE[^;]*)?$', re.S | re.I)
ALTER_RE = re.compile(r'ALTER TABLE\s+`?(\w+)`?\s+(.*)$', re.S | re.I)
ADD_COL_RE = re.compile(r'\bADD\s+(?:COLUMN\s+|FIELD\s+)?`?(\w+)`?', re.I)
ADD_LIST_RE = re.compile(r'\bADD\s*\((.*?)\)', re.S | re.I)
DROP_COL_RE = re.compile(r'\bDROP\s+(?:COLUMN\s+|FIELD\s+)?`?(\w+)`?', re.I)
RENAME_RE = re.compile(r'\b(?:CHANGE|RENAME)\s+(?:COLUMN\s+|FIELD\s+)?`?(\w+)`?\s+(?:TO\s+)?`?(\w+)`?', re.I)
KEYWORDS = {'primary', 'unique', 'key', 'index', 'constraint', 'foreign', 'fulltext',
            'check', 'modify', 'modify', 'drop', 'add', 'alter', 'column', 'data', 'autoincrement'}


def split_statements(text):
    """按分号切语句，但分号在引号 / 反引号 / 注释里时不算结束。

    动因：V5 里 `COMMENT 'LWA or RDT; existing rows are LWA'` 的字符串分号，会让
    非贪婪的 `ALTER TABLE ... ;` 在第一条 ADD COLUMN 处截断，把真实存在的列误报成漂移。
    """
    out, cur, quote, i, n = [], [], None, 0, len(text)
    while i < n:
        ch = text[i]
        if quote:
            cur.append(ch)
            if ch == quote:
                # SQL 里 '' 是转义，反引号里 `` 同理
                if i + 1 < n and text[i + 1] == quote and quote in "'`":
                    cur.append(text[i + 1])
                    i += 2
                    continue
                quote = None
            elif quote == "'" and ch == '\\' and i + 1 < n:
                cur.append(text[i + 1])
                i += 2
                continue
            i += 1
            continue
        if ch in ("'", '"', '`'):
            quote = ch
            cur.append(ch)
            i += 1
            continue
        if ch == '-' and text[i:i + 2] == '--':
            j = text.find('\n', i)
            i = n if j < 0 else j + 1
            continue
        if text[i:i + 2] == '/*':
            j = text.find('*/', i)
            i = n if j < 0 else j + 2
            continue
        if ch == ';':
            out.append(''.join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    if ''.join(cur).strip():
        out.append(''.join(cur))
    return [s for s in out if s.strip()]


def camel_to_snake(name):
    out = []
    for i, ch in enumerate(name):
        if ch.isupper() and i and not name[i - 1].isupper():
            out.append('_')
        out.append(ch.lower())
    return ''.join(out)


def split_top_level(body):
    """按最外层逗号切 CREATE TABLE 的条目（跳过括号内与引号内）。"""
    items, depth, in_str, cur = [], 0, None, []
    for ch in body:
        if in_str:
            cur.append(ch)
            if ch == in_str:
                in_str = None
            continue
        if ch in ("'", '"', '`'):
            in_str = ch
            cur.append(ch)
            continue
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        if ch == ',' and depth == 0:
            items.append(''.join(cur))
            cur = []
        else:
            cur.append(ch)
    if ''.join(cur).strip():
        items.append(''.join(cur))
    return items


def column_names(body):
    cols = set()
    for item in split_top_level(body):
        parts = item.strip().split()
        if not parts:
            continue
        first = parts[0].strip('`').lower()
        if first in KEYWORDS:
            continue
        cols.add(parts[0].strip('`'))
    return cols


# --self-test：先证明解析器本身能咬住驱动它重写的那两个缺陷，再谈它的输出可信
if '--self-test' in sys.argv:
    LEGACY_ALTER_RE = re.compile(r'ALTER TABLE\s+`?(\w+)`?\s+(.*?);', re.S | re.I)
    failed = 0

    def check(name, cond):
        global failed
        print('%-52s %s' % (name, 'PASS' if cond else 'FAIL'))
        if not cond:
            failed += 1

    # 缺陷 1：一条多子句 ALTER 里，字符串分号把 body 截断，后面的 ADD COLUMN 全丢
    # （忠实复刻 V5__spapi_outbox_token_source.sql 的写法：一条 ALTER、四个 ADD、
    #   第一个 COMMENT 含 ';'）
    tricky = ("ALTER TABLE `amz_x`\n"
              "    ADD COLUMN `a` INT NOT NULL COMMENT 'first; with semicolon' AFTER `id`,\n"
              "    ADD COLUMN `b` VARCHAR(32) AFTER `a`,\n"
              "    ADD COLUMN `c` INT AFTER `b`;\n")
    one = split_statements(tricky)
    cols = set()
    for s in one:
        m = ALTER_RE.search(s)
        if m:
            for a in ADD_COL_RE.finditer(m.group(2)):
                cols.add(a.group(1))
    check('quoted semicolon keeps every ADD COLUMN', cols == {'a', 'b', 'c'})
    # 对照：旧的非贪婪写法确实会吞掉 b/c —— 证明这条 fixture 咬到了 bug
    legacy_cols = set()
    for m in LEGACY_ALTER_RE.finditer(tricky):
        for a in ADD_COL_RE.finditer(m.group(2)):
            legacy_cols.add(a.group(1))
    check('fixture reproduces the old truncation (legacy loses a col)',
          'b' not in legacy_cols or 'c' not in legacy_cols)

    # 缺陷 2：注释里带分号/括号；以及字符串里带 -- 不能被当注释
    noisy = ("CREATE TABLE amz_y (id BIGINT, -- inline; comment with semicolon\n"
             "note VARCHAR(64) DEFAULT 'a--b', PRIMARY KEY (id)) ENGINE=InnoDB;")
    ycols = set()
    for s in split_statements(noisy):
        m = CREATE_RE.search(s)
        if m:
            ycols = column_names(m.group(2))
    check('comment semicolon does not split CREATE', 'note' in ycols and 'id' in ycols)
    check('PRIMARY KEY not counted as a column', 'PRIMARY' not in ycols)

    # DROP / ADD(list) / CHANGE 都要生效
    ops = set()
    for s in split_statements("ALTER TABLE amz_z ADD (p INT, q INT), DROP COLUMN r, "
                              "CHANGE COLUMN s t VARCHAR(20);"):
        m = ALTER_RE.search(s)
        body = m.group(2)
        for li in ADD_LIST_RE.finditer(body):
            ops.update(column_names(li.group(1)))
        for a in ADD_COL_RE.finditer(body):
            ops.add(a.group(1))
    check('ADD(list) parsed', {'p', 'q'} <= ops)

    # 真文件回归：V5 的四个 ADD 必须全部解析出来（曾经被误报成 3 列硬漂移）
    v5 = os.path.join(ROOT, 'amz-service', 'amz-service-spapi', 'src', 'main', 'resources',
                      'db', 'migration', 'V5__spapi_outbox_token_source.sql')
    if os.path.exists(v5):
        real = set()
        for s in split_statements(io.open(v5, encoding='utf-8').read()):
            m = ALTER_RE.search(s)
            if m:
                for a in ADD_COL_RE.finditer(m.group(2)):
                    real.add(a.group(1))
        check('real V5 file: all 4 ADD COLUMN parsed', len(real) == 4 and 'token_source' in real)
    else:
        check('real V5 file present', False)
    sys.exit(1 if failed else 0)

# 1) 收集所有 SQL 里的表定义（Flyway 为准，docker/init-sql-legacy 单独标注）
tables = defaultdict(dict)   # table -> {source: set(cols)}
for dirpath, dirnames, filenames in os.walk(ROOT):
    if 'target' in dirpath.split(os.sep) or 'node_modules' in dirpath:
        continue
    for fn in filenames:
        if not fn.endswith('.sql'):
            continue
        path = os.path.join(dirpath, fn)
        src = 'legacy-init' if 'init-sql' in path.replace('\\', '/') else 'flyway'
        try:
            text = io.open(path, encoding='utf-8', errors='replace').read()
        except OSError:
            continue
        for stmt in split_statements(text):
            m = CREATE_RE.search(stmt)
            if m:
                tables[m.group(1)].setdefault(src, set()).update(column_names(m.group(2)))
                continue
            m = ALTER_RE.search(stmt)
            if not m:
                continue
            table, body = m.group(1), m.group(2)
            bucket = tables[table].setdefault(src, set())
            for r in RENAME_RE.finditer(body):
                bucket.discard(r.group(1))
                if r.group(2).lower() not in KEYWORDS:
                    bucket.add(r.group(2))
            for li in ADD_LIST_RE.finditer(body):
                bucket.update(column_names(li.group(1)))
            for a in ADD_COL_RE.finditer(body):
                if a.group(1).lower() not in KEYWORDS:
                    bucket.add(a.group(1))
            for d in DROP_COL_RE.finditer(body):
                bucket.discard(d.group(1))

# 2) 每个实体：列集合与 flyway 定义比对
rows = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    if 'target' in dirpath.split(os.sep) or 'node_modules' in dirpath:
        continue
    for fn in filenames:
        if not fn.endswith('.java'):
            continue
        p = os.path.join(dirpath, fn)
        # 用相对路径打印：同名实体（各模块都有自己的 Shop.java）只写文件名无法归因
        rel = os.path.relpath(p, ROOT).replace('\\', '/').lstrip('./')
        text = io.open(p, encoding='utf-8', errors='replace').read()
        if '@TableName' not in text:
            continue
        if '/src/main/java/' not in p.replace('\\', '/'):
            continue
        t = TABLE_RE.search(text)
        if not t:
            continue
        table = t.group(1)
        # 逐字段：@TableField 在前一行/两行内则用它
        cols, ignored = [], set()
        lines = text.splitlines()
        pending = None
        skip_next = False
        for ln in lines:
            if EXIST_RE.search(ln):
                skip_next = True
                continue
            m = TABLEFIELD_RE.search(ln)
            if m:
                pending = m.group(1)
                continue
            fm = FIELD_RE.search(ln)
            if fm:
                name = fm.group(1)
                if name == 'serialVersionUID':
                    pending = None
                    continue
                if skip_next:
                    ignored.add(name)
                else:
                    cols.append(pending or camel_to_snake(name))
                pending = None
                skip_next = False
            elif ln.strip().startswith('@'):
                pass
            else:
                pending = None
        if not cols:
            continue
        fly = tables.get(table, {}).get('flyway')
        leg = tables.get(table, {}).get('legacy-init')
        if fly is None:
            rows.append((table, rel, 'NO-FLYWAY-DDL', len(cols), [], []))
            continue
        missing = sorted(set(cols) - fly)
        only_ddl = sorted(fly - set(cols))
        legacy_covers = sorted(set(missing) - (leg or set()))
        rows.append((table, rel, 'ok' if not missing else 'MISSING', len(cols), missing, legacy_covers))

print('%-26s %-52s %-6s %s' % ('TABLE', 'ENTITY', 'HARD', 'hard-missing（Flyway 与 legacy-init 都没有） / soft（只缺 Flyway，legacy 建表里有）'))
hard_total = soft_total = 0
for table, entity, verdict, n, missing, legacy_only in sorted(rows):
    hard = legacy_only     # = missing - legacy 列，两边都没有
    soft = [c for c in missing if c not in hard]
    if hard:
        hard_total += 1
    elif soft:
        soft_total += 1
    if not missing:
        continue
    print('%-26s %-52s %-6s %s' % (table, entity, 'YES' if hard else 'no',
          'HARD=[%s]  soft=[%s]' % (', '.join(hard), ', '.join(soft))))
print('\nentities checked=%d  hard-mismatch=%d  flyway-only-mismatch=%d' % (len(rows), hard_total, soft_total))
