# -*- coding: utf-8 -*-
"""Fail-closed MySQL DDL parser for the AmazonERP synthetic-data tooling.

Design constraints (spec 7.9):
  * the column list must be derived from the repository DDL, never hand-copied;
  * parsing must be fail-closed: an unclassified entry inside a CREATE TABLE body
    raises instead of being skipped, so a silent mis-parse can never become a
    fabricated schema snapshot that later "validates" synthetic data.

Scope: only the MySQL syntax this repository actually uses - CREATE TABLE,
inline/table keys, foreign keys, ENUM/SET, COMMENT, DEFAULT expressions.
"""

from __future__ import annotations

import hashlib
import json
import re

IDENT_TAIL = r"[A-Za-z_][A-Za-z0-9_$]*"


class DdlParseError(Exception):
    """Raised when the parser meets DDL it cannot classify deterministically."""


# --------------------------------------------------------------------------- #
# lexical helpers
# --------------------------------------------------------------------------- #
def mask_comments(sql: str) -> str:
    """Blank out SQL comments while preserving every newline (for line numbers)."""
    out = []
    i, n = 0, len(sql)
    while i < n:
        ch = sql[i]
        if ch in ("'", '"', '`'):
            quote = ch
            out.append(ch)
            i += 1
            while i < n:
                cur = sql[i]
                if cur == '\\' and quote != '`':
                    out.append(cur)
                    i += 1
                    if i < n:
                        out.append(sql[i])
                        i += 1
                    continue
                out.append(cur)
                if cur == quote:
                    if i + 1 < n and sql[i + 1] == quote:
                        out.append(sql[i + 1])
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            continue
        if ch == '-' and sql.startswith('--', i):
            while i < n and sql[i] != '\n':
                out.append(' ')
                i += 1
            continue
        if ch == '#':
            while i < n and sql[i] != '\n':
                out.append(' ')
                i += 1
            continue
        if ch == '/' and sql.startswith('/*', i):
            out.append(' ')
            out.append(' ')
            i += 2
            while i < n and not sql.startswith('*/', i):
                out.append('\n' if sql[i] == '\n' else ' ')
                i += 1
            if i < n:
                out.append(' ')
                out.append(' ')
                i += 2
            continue
        out.append(ch)
        i += 1
    return ''.join(out)


def line_of(text: str, index: int) -> int:
    return text.count('\n', 0, index) + 1


def split_statements(sql: str):
    """Split on top-level semicolons -> [(statement, line_number)]."""
    masked = mask_comments(sql)
    stmts = []
    start = None
    i, n = 0, len(masked)
    while i < n:
        ch = masked[i]
        if ch in ("'", '"', '`'):
            quote = ch
            i += 1
            while i < n:
                cur = masked[i]
                if cur == '\\' and quote != '`':
                    i += 2
                    continue
                if cur == quote:
                    if i + 1 < n and masked[i + 1] == quote:
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            continue
        if ch == ';':
            if start is not None:
                stmts.append((masked[start:i], line_of(masked, start)))
            start = None
            i += 1
            continue
        if start is None and not ch.isspace():
            start = i
        i += 1
    if start is not None:
        tail = masked[start:]
        if tail.strip():
            stmts.append((tail, line_of(masked, start)))
    return stmts


def split_top_level(body: str):
    """Split a CREATE TABLE body / key column list on top-level commas."""
    items = []
    depth = 0
    start = 0
    i, n = 0, len(body)
    while i < n:
        ch = body[i]
        if ch in ("'", '"', '`'):
            quote = ch
            i += 1
            while i < n:
                cur = body[i]
                if cur == '\\' and quote != '`':
                    i += 2
                    continue
                if cur == quote:
                    if i + 1 < n and body[i + 1] == quote:
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            continue
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        elif ch == ',' and depth == 0:
            items.append(body[start:i])
            start = i + 1
        i += 1
    tail = body[start:]
    if tail.strip():
        items.append(tail)
    return items


def match_paren(text: str, open_index: int) -> int:
    depth = 0
    i, n = open_index, len(text)
    while i < n:
        ch = text[i]
        if ch in ("'", '"', '`'):
            quote = ch
            i += 1
            while i < n:
                cur = text[i]
                if cur == '\\' and quote != '`':
                    i += 2
                    continue
                if cur == quote:
                    if i + 1 < n and text[i + 1] == quote:
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            continue
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise DdlParseError('unbalanced parentheses')


def unquote_ident(token: str) -> str:
    token = token.strip()
    if token.startswith('`') and token.endswith('`') and len(token) >= 2:
        return token[1:-1]
    return token


def ident_match(token: str):
    """Match a leading identifier and retain where its spelling ends in the token.

    Returning the regex match (rather than only the unquoted name) is required for
    backtick-quoted identifiers: len("limit") != len("`limit`").
    """
    return re.match(r"\s*(`[^`]+`|%s)" % IDENT_TAIL, token)


def first_ident(token: str):
    m = ident_match(token)
    return unquote_ident(m.group(1)) if m else None
# --------------------------------------------------------------------------- #
# CREATE TABLE parsing
# --------------------------------------------------------------------------- #
CREATE_TABLE_RE = re.compile(
    r"^\s*CREATE\s+(?:TEMPORARY\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?"
    r"(?P<name>`[^`]+`|%s)(?:\s*\.\s*(?P<name2>`[^`]+`|%s))?\s*\(" % (IDENT_TAIL, IDENT_TAIL),
    re.IGNORECASE | re.DOTALL,
)

CONSTRAINT_START_RE = re.compile(
    r"^\s*(PRIMARY\s+KEY|UNIQUE(?:\s+(?:KEY|INDEX))?|KEY|INDEX|CONSTRAINT|FOREIGN\s+KEY|FULLTEXT|SPATIAL|CHECK|PERIOD)\b",
    re.IGNORECASE,
)

TYPE_RE = re.compile(r"^\s*(?P<type>[A-Za-z]+)\s*(?:\((?P<args>[^)]*)\))?(?P<rest>.*)$", re.DOTALL)

DEFAULT_RE = re.compile(
    r"\bDEFAULT\s+(?P<val>'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|[-+]?\d+(?:\.\d+)?|\w+\s*\([^)]*\)|\w+)",
    re.IGNORECASE,
)
COMMENT_RE = re.compile(r"\bCOMMENT\s+'(?P<val>(?:[^']|'')*)'", re.IGNORECASE)
STRING_RE = re.compile(r"'(?P<val>(?:[^']|'')*)'")


ALTER_TABLE_RE = re.compile(
    r"^ALTER\s+TABLE\s+(`(?:[^`]|``)+`|[A-Za-z_][A-Za-z0-9_$]*)\s+(?P<body>[\s\S]+)$", re.IGNORECASE)
ADD_COLUMN_RE = re.compile(
    r"\bADD\s+(?:COLUMN\s+)?(?P<ine>IF\s+NOT\s+EXISTS\s+)?(?P<name>`(?:[^`]|``)+`|[A-Za-z_][A-Za-z0-9_$]*)",
    re.IGNORECASE)
# ADD INDEX / ADD CONSTRAINT ... are not column additions; do not mistake them for columns.
NON_COLUMN_ADD = frozenset({'index', 'constraint', 'unique', 'primary', 'key', 'foreign', 'partition',
                            'check', 'column', 'fulltext', 'spatial', 'period'})


IDENT = r"`(?:[^`]|``)+`|[A-Za-z_][A-Za-z0-9_$]*"

MODIFY_COLUMN_RE = re.compile(
    r"\bMODIFY\s+(?:COLUMN\s+)?(?P<name>%s)(?P<rest>[\s\S]*)$" % IDENT, re.IGNORECASE)
CHANGE_COLUMN_RE = re.compile(
    r"\bCHANGE\s+(?:COLUMN\s+)?(?P<old>%s)\s+(?P<new>%s)(?P<rest>[\s\S]*)$"
    % (IDENT, IDENT), re.IGNORECASE)
RENAME_COLUMN_RE = re.compile(
    r"\bRENAME\s+COLUMN\s+(?P<old>%s)\s+TO\s+(?P<new>%s)\s*;?\s*$" % (IDENT, IDENT), re.IGNORECASE)
RENAME_INDEX_RE = re.compile(
    r"\bRENAME\s+(?:INDEX|KEY)\s+(?P<old>%s)\s+TO\s+(?P<new>%s)\s*;?\s*$" % (IDENT, IDENT),
    re.IGNORECASE)
DROP_COLUMN_RE = re.compile(r"\bDROP\s+(?:COLUMN\s+)?(?P<name>%s)\s*;?\s*$" % IDENT, re.IGNORECASE)


def _column_definition_or_none(name: str, rest: str):
    """Parse `<name> <type> ...` for MODIFY/CHANGE; None when the tail is not a column body."""
    try:
        return parse_column(name + ' ' + rest.strip())
    except DdlParseError:
        return None


def parse_alter_table(stmt: str) -> dict:
    """Classify one ALTER TABLE statement (fail-closed: unknown shapes keep their raw SQL).

    Besides `ADD COLUMN` the schema snapshot has to understand the operations that *change an
    already created* table - `MODIFY COLUMN`, `CHANGE COLUMN`, `RENAME COLUMN`, `RENAME INDEX`
    and `DROP COLUMN`.  Without them the snapshot would keep reporting the pre-migration column
    and every downstream check (synthetic data, reference types) would validate the wrong schema.
    """
    m = ALTER_TABLE_RE.match(stmt.strip())
    if not m:
        raise DdlParseError("cannot read ALTER TABLE statement: %r" % stmt[:80])
    adds = []
    operations = []
    for item in split_top_level(m.group('body')):
        clause = item.strip()
        am = ADD_COLUMN_RE.match(clause)
        if am:
            name = unquote_ident(am.group('name'))
            if name.lower() in NON_COLUMN_ADD:
                continue
            definition = None
            try:
                definition = parse_column(am.group('name') + clause[am.end():])
            except DdlParseError:
                definition = None
            adds.append({'column': name, 'if_not_exists': bool(am.group('ine')),
                         'definition': definition})
            operations.append({'kind': 'add_column', 'column': name,
                               'if_not_exists': bool(am.group('ine')), 'definition': definition})
            continue
        mm = MODIFY_COLUMN_RE.search(clause)
        if mm:
            name = unquote_ident(mm.group('name'))
            operations.append({'kind': 'modify_column', 'column': name,
                               'definition': _column_definition_or_none(mm.group('name'), mm.group('rest'))})
            continue
        cm = CHANGE_COLUMN_RE.search(clause)
        if cm:
            old = unquote_ident(cm.group('old'))
            new_name = unquote_ident(cm.group('new'))
            operations.append({'kind': 'change_column', 'column': old, 'new_name': new_name,
                               'definition': _column_definition_or_none(cm.group('new'), cm.group('rest'))})
            continue
        rm = RENAME_COLUMN_RE.search(clause)
        if rm:
            operations.append({'kind': 'rename_column', 'column': unquote_ident(rm.group('old')),
                               'new_name': unquote_ident(rm.group('new')), 'definition': None})
            continue
        im = RENAME_INDEX_RE.search(clause)
        if im:
            operations.append({'kind': 'rename_index', 'name': unquote_ident(im.group('old')),
                               'new_name': unquote_ident(im.group('new'))})
            continue
        dm = DROP_COLUMN_RE.search(clause)
        if dm:
            operations.append({'kind': 'drop_column', 'column': unquote_ident(dm.group('name'))})
            continue
    return {
        'table': unquote_ident(m.group(1)),
        'add_columns': adds,
        'mysql8_incompatible_add_column': any(a['if_not_exists'] for a in adds),
        'operations': operations,
    }


def _enum_values(args: str):
    return [v.replace("''", "'") for v in STRING_RE.findall(args)]


def _column_names(column_list: str):
    names = []
    for part in split_top_level(column_list):
        name = first_ident(part)
        if name is None:
            raise DdlParseError('cannot read column name from %r' % part.strip()[:80])
        names.append(name)
    return names


def parse_column(item: str) -> dict:
    name_match = ident_match(item)
    if name_match is None:
        raise DdlParseError('cannot read column name from %r' % item.strip()[:80])
    name = unquote_ident(name_match.group(1))
    rest = item[name_match.end():]
    m = TYPE_RE.match(rest)
    if not m:
        raise DdlParseError('cannot read type for column %s' % name)
    base_type = m.group('type').upper()
    args = (m.group('args') or '').strip()
    tail = m.group('rest') or ''
    flags = rest[len(rest) - len(tail):] if False else tail
    inline_pk = bool(re.search(r"\bPRIMARY\s+KEY\b", flags, re.IGNORECASE))
    inline_unique = bool(re.search(r"\bUNIQUE\b", flags, re.IGNORECASE))
    not_null = bool(re.search(r"\bNOT\s+NULL\b", flags, re.IGNORECASE))
    default = None
    dm = DEFAULT_RE.search(flags)
    if dm:
        default = dm.group('val').strip()
    comment = None
    cm = COMMENT_RE.search(flags)
    if cm:
        comment = cm.group('val').replace("''", "'")
    column = {
        'name': name,
        'type': base_type,
        'args': args,
        'unsigned': bool(re.search(r"\bUNSIGNED\b", flags, re.IGNORECASE)),
        'nullable': not not_null,
        'default': default,
        'auto_increment': bool(re.search(r"\bAUTO_INCREMENT\b", flags, re.IGNORECASE)),
        'generated': bool(re.search(r"\bGENERATED\b|\bAS\s*\(", flags, re.IGNORECASE)),
        'on_update': bool(re.search(r"\bON\s+UPDATE\b", flags, re.IGNORECASE)),
        'inline_primary_key': inline_pk,
        'inline_unique': inline_unique,
        'comment': comment,
    }
    if base_type in ('ENUM', 'SET'):
        column['enum_values'] = _enum_values(args)
        if not column['enum_values']:
            raise DdlParseError('ENUM/SET without values: %s' % name)
    return column


def parse_body_item(item: str) -> dict:
    if CONSTRAINT_START_RE.match(item):
        return parse_constraint(item)
    return {'kind': 'column', 'value': parse_column(item)}


def parse_constraint(item: str) -> dict:
    text = item.strip()
    pk = re.match(r"^PRIMARY\s+KEY\s*(?:USING\s+\w+\s*)?\((?P<cols>.*)\)\s*$", text, re.IGNORECASE | re.DOTALL)
    if pk:
        return {'kind': 'primary_key', 'columns': _column_names(pk.group('cols'))}
    uq = re.match(
        r"^UNIQUE(?:\s+(?:KEY|INDEX))?\s*(?P<name>`[^`]+`|%s)?\s*(?:USING\s+\w+\s*)?\((?P<cols>.*)\)\s*$" % IDENT_TAIL,
        text, re.IGNORECASE | re.DOTALL)
    if uq:
        return {'kind': 'unique', 'name': unquote_ident(uq.group('name')) if uq.group('name') else None,
                'columns': _column_names(uq.group('cols'))}
    idx = re.match(
        r"^(?:KEY|INDEX)\s*(?P<name>`[^`]+`|%s)?\s*(?:USING\s+\w+\s*)?\((?P<cols>.*)\)\s*$" % IDENT_TAIL,
        text, re.IGNORECASE | re.DOTALL)
    if idx:
        return {'kind': 'index', 'name': unquote_ident(idx.group('name')) if idx.group('name') else None,
                'columns': _column_names(idx.group('cols'))}
    fk = re.match(
        r"^(?:CONSTRAINT\s+(?P<cname>`[^`]+`|%s)\s+)?FOREIGN\s+KEY\s*\((?P<cols>.*?)\)\s*REFERENCES\s+"
        r"(?P<rtbl>`[^`]+`|%s)\s*\((?P<rcols>.*?)\)(?P<tail>.*)$" % (IDENT_TAIL, IDENT_TAIL),
        text, re.IGNORECASE | re.DOTALL)
    if fk:
        return {'kind': 'foreign_key',
                'name': unquote_ident(fk.group('cname')) if fk.group('cname') else None,
                'columns': _column_names(fk.group('cols')),
                'ref_table': unquote_ident(fk.group('rtbl')),
                'ref_columns': _column_names(fk.group('rcols'))}
    raise DdlParseError('unclassified constraint: %r' % text[:120])


def parse_create_table(stmt: str) -> dict:
    m = CREATE_TABLE_RE.match(stmt)
    if not m:
        raise DdlParseError('not a CREATE TABLE statement: %r' % stmt.strip()[:80])
    name = unquote_ident(m.group('name2') or m.group('name'))
    open_index = stmt.index('(', m.end() - 1)
    close_index = match_paren(stmt, open_index)
    body = stmt[open_index + 1:close_index]
    options = stmt[close_index + 1:]

    columns, primary_key, uniques, indexes, fks = [], [], [], [], []
    for item in split_top_level(body):
        parsed = parse_body_item(item)
        if parsed['kind'] == 'column':
            columns.append(parsed['value'])
        elif parsed['kind'] == 'primary_key':
            primary_key = parsed['columns']
        elif parsed['kind'] == 'unique':
            uniques.append({'name': parsed['name'], 'columns': parsed['columns']})
        elif parsed['kind'] == 'index':
            indexes.append({'name': parsed['name'], 'columns': parsed['columns']})
        elif parsed['kind'] == 'foreign_key':
            fks.append(parsed)
    if not columns:
        raise DdlParseError('table %s has no columns' % name)
    for col in columns:
        if col['inline_primary_key'] and not primary_key:
            primary_key = [col['name']]
        if col['inline_unique']:
            uniques.append({'name': None, 'columns': [col['name']]})

    engine = None
    em = re.search(r"\bENGINE\s*=\s*(\w+)", options, re.IGNORECASE)
    if em:
        engine = em.group(1)
    charset = None
    cm = re.search(r"(?:DEFAULT\s+)?(?:CHARSET|CHARACTER\s+SET)\s*=?\s*(\w+)", options, re.IGNORECASE)
    if cm:
        charset = cm.group(1)
    table_comment = None
    tcm = COMMENT_RE.search(options)
    if tcm:
        table_comment = tcm.group('val').replace("''", "'")

    table = {
        'name': name,
        'columns': columns,
        'primary_key': primary_key,
        'unique_keys': uniques,
        'indexes': indexes,
        'foreign_keys': fks,
        'engine': engine,
        'charset': charset,
        'comment': table_comment,
        'options_raw': ' '.join(options.split()),
    }
    table['table_hash'] = table_hash(table)
    return table


def table_hash(table: dict) -> str:
    canonical = {
        'name': table['name'],
        'columns': [
            [c['name'], c['type'], c['args'], c['unsigned'], c['nullable'], c['default'],
             c['auto_increment'], c['generated'], c['on_update'], c.get('enum_values')]
            for c in table['columns']
        ],
        'primary_key': table['primary_key'],
        'unique_keys': [[u['name'], u['columns']] for u in table['unique_keys']],
        'indexes': [[i['name'], i['columns']] for i in table['indexes']],
        'foreign_keys': [[f['name'], f['columns'], f['ref_table'], f['ref_columns']] for f in table['foreign_keys']],
    }
    blob = json.dumps(canonical, sort_keys=True, ensure_ascii=False, separators=(',', ':'))
    return hashlib.sha256(blob.encode('utf-8')).hexdigest()


def _read_bytes(path: str) -> bytes:
    with open(path, 'rb') as source:
        return source.read()

def _text_sha256(path: str) -> str:
    # Git may check the same blob out with LF (Linux CI) or CRLF (Windows).
    # Hashing raw bytes made the committed snapshot machine-dependent, so the
    # drift gate only ever passed on the machine that generated it.
    blob = _read_bytes(path)
    if b'\x00' not in blob:
        blob = blob.replace(b'\r\n', b'\n').replace(b'\r', b'\n')
    return hashlib.sha256(blob).hexdigest()


def parse_sql_file(path: str, group: str, db_hint=None):
    """Parse one .sql file -> (tables, alters, stats, issues)."""
    import io
    with io.open(path, encoding='utf-8-sig') as source:
        text = source.read()
    masked = mask_comments(text)
    tables, alters, issues = [], [], []
    use_db = db_hint
    inserts = 0
    for stmt, line in split_statements(text):
        head = stmt.strip()
        if not head:
            continue
        use = re.match(r"^USE\s+(`[^`]+`|%s)" % IDENT_TAIL, head, re.IGNORECASE)
        if use:
            use_db = unquote_ident(use.group(1))
            continue
        if re.match(r"^CREATE\s+(DATABASE|SCHEMA)\b", head, re.IGNORECASE):
            continue
        if re.match(r"^CREATE\s+(?:UNIQUE\s+)?INDEX\b", head, re.IGNORECASE):
            # Flyway migrations may add an index without creating a table.
            # It is valid DDL and does not contribute a table definition.
            continue
        if re.match(r"^CREATE\s+(?!TABLE)", head, re.IGNORECASE):
            issues.append({'file': path, 'line': line, 'kind': 'non-table-ddl',
                           'detail': head[:120]})
            continue
        if re.match(r"^CREATE\s+(TEMPORARY\s+)?TABLE\b", head, re.IGNORECASE):
            try:
                table = parse_create_table(head)
            except DdlParseError as exc:
                issues.append({'file': path, 'line': line, 'kind': 'parse-error', 'detail': str(exc)})
                continue
            table['source_file'] = path
            table['source_group'] = group
            table['database'] = use_db
            table['line'] = line
            tables.append(table)
            continue
        if re.match(r"^(PREPARE|EXECUTE|DEALLOCATE)\b", head, re.IGNORECASE):
            issues.append({'file': path, 'line': line, 'kind': 'dynamic-ddl',
                           'detail': head[:120]})
            continue
        if re.match(r"^ALTER\s+TABLE\b", head, re.IGNORECASE):
            try:
                alter = parse_alter_table(head)
            except DdlParseError as exc:
                issues.append({'file': path, 'line': line, 'kind': 'parse-error', 'detail': str(exc)})
                continue
            alter.update({'file': path, 'line': line, 'database': use_db,
                          'sql': ' '.join(head.split())[:240]})
            alters.append(alter)
            continue
        if re.match(r"^(INSERT|REPLACE|SET|DELETE|UPDATE|ALTER|DROP|GRANT|FLUSH|CREATE\s+USER|LOCK|UNLOCK)\b",
                    head, re.IGNORECASE):
            if re.match(r"^(INSERT|REPLACE)\b", head, re.IGNORECASE):
                inserts += 1
            continue
        if head.strip():
            issues.append({'file': path, 'line': line, 'kind': 'unclassified-statement',
                           'detail': head[:120]})
    stats = {
        'file': path,
        'group': group,
        'bytes': len(text.encode('utf-8')),
        'lines': text.count('\n') + 1,
        'sha256': _text_sha256(path),
        'tables': len(tables),
        'insert_statements': inserts,
        'db_hint': db_hint,
        'alter_table_statements': len(alters),
        'add_column_statements': sum(len(a['add_columns']) for a in alters),
        'mysql8_incompatible_add_column': sum(1 for a in alters if a['mysql8_incompatible_add_column']),
    }
    return tables, alters, stats, issues
