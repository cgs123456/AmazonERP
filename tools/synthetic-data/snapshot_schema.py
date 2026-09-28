# -*- coding: utf-8 -*-
"""Build / verify the DDL-derived schema snapshot used by the synthetic-data tooling.

Why this exists (spec 7.9 requirement 1):
    the generator's column list must be derived from the repository DDL - never
    hand-copied - otherwise a fourth schema copy appears and drifts (appendix B).

Usage:
    python tools/synthetic-data/snapshot_schema.py            # write snapshot + report
    python tools/synthetic-data/snapshot_schema.py --check     # CI gate: fail on drift
"""

from __future__ import annotations

import argparse
import glob
import io
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ddl_parser as P  # noqa: E402

TOOL_VERSION = '1.2.0'
SNAPSHOT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'schema')
SNAPSHOT_PATH = os.path.join(SNAPSHOT_DIR, 'schema-snapshot.json')
SOURCES_PATH = os.path.join(SNAPSHOT_DIR, 'DDL_SOURCES.json')
REPORT_PATH = os.path.join(SNAPSHOT_DIR, 'SCHEMA_REPORT.md')

# group -> (glob patterns, db-hint resolver)
DB_FALLBACK = {
    # amz-service-report has no datasource in application.yml yet (P0-07); the
    # target database is amz_report. Marked as inferred so drift stays visible.
    'amz-service-report': ('amz_report', 'inferred:spec-P0-07'),
}

# Six compose-init files (28-33) were appended without a USE statement. Their
# module ownership is stated in the file header and must be supplied to the
# parser. This maps file names only; every column remains DDL-derived.
COMPOSE_DB_HINTS = {
    '28-init-tables-p1-listing-monitor.sql': ('amz_product', 'file-map:header-P1-1'),
    '29-init-tables-p1-order-audit.sql': ('amz_order', 'file-map:header-P1-2'),
    '30-init-tables-p1-realtime-profit.sql': ('amz_report', 'file-map:header-P1-3'),
    '31-init-tables-p1-multi-warehouse.sql': ('amz_logistics', 'file-map:header-P1-4'),
    '32-init-tables-p2-multiplatform.sql': ('amz_multiplatform', 'file-map:header-P2-1'),
    '33-init-tables-p2-ai-tools.sql': ('amz_ai', 'file-map:header-P2-4'),
}


def repo_root() -> str:
    return os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))


def module_db_hint(module_dir: str):
    yml = os.path.join(module_dir, 'src', 'main', 'resources', 'application.yml')
    module = os.path.basename(module_dir)
    if os.path.isfile(yml):
        text = io.open(yml, encoding='utf-8-sig').read()
        found = re.findall(r"jdbc:mysql://[^\"'\s]*?/([A-Za-z0-9_]+)", text)
        if found:
            return sorted(set(found))[0], 'application.yml'
    if module in DB_FALLBACK:
        return DB_FALLBACK[module]
    return None, 'unknown'


def collect_sources(root: str):
    sources = []
    pattern = os.path.join(root, 'docker', 'init-sql', '*.sql')
    for path in sorted(glob.glob(pattern)):
        hint, hint_source = COMPOSE_DB_HINTS.get(os.path.basename(path), (None, 'use-statement'))
        sources.append((path, 'compose-init-sql', hint, hint_source))
    for module_dir in sorted(glob.glob(os.path.join(root, 'amz-service', 'amz-service-*'))):
        if not os.path.isdir(module_dir):
            continue
        db, db_source = module_db_hint(module_dir)
        for path in sorted(glob.glob(os.path.join(module_dir, 'src', 'main', 'resources', 'db', 'migration', '*.sql'))):
            sources.append((path, 'flyway-migration', db, db_source))
        for path in sorted(glob.glob(os.path.join(module_dir, 'src', 'main', 'resources', 'db', '*.sql'))):
            if os.path.basename(os.path.dirname(path)) != 'db':
                continue
            sources.append((path, 'aux-ddl', db, db_source))
    root_sql = os.path.join(root, 'init_all_tables.sql')
    if os.path.isfile(root_sql):
        sources.append((root_sql, 'root-stale-dump', None, 'use-statement'))
    return sources


def rel(root: str, path: str) -> str:
    return os.path.relpath(path, root).replace('\\', '/')


MIGRATION_VERSION_RE = re.compile(r"(?:^|/)V(\d+(?:[._]\d+)*)__")


def migration_rank(path: str):
    """Flyway ordering key: V1 < V2 < V10 (never lexicographic)."""
    m = MIGRATION_VERSION_RE.search(path.replace('\\', '/'))
    if not m:
        return (1, (), path)
    version = tuple(int(part) for part in re.split(r"[._]", m.group(1)) if part != '')
    return (0, version, path)


def _rename_in_key_columns(table: dict, old: str, new: str):
    if table.get('primary_key'):
        table['primary_key'] = [new if c == old else c for c in table['primary_key']]
    for bucket in ('unique_keys', 'indexes'):
        for entry in table.get(bucket) or []:
            entry['columns'] = [new if c == old else c for c in (entry.get('columns') or [])]
    for fk in table.get('foreign_keys') or []:
        fk['columns'] = [new if c == old else c for c in (fk.get('columns') or [])]


def apply_operation(table: dict, op: dict, alter: dict):
    """Apply one ALTER operation to an already created table definition.

    Returns a record for the report.  `applied=False` means the operation could not change
    anything (column missing, definition unparsable) and is surfaced in the report instead of
    being silently dropped - a snapshot that ignores migrations would validate the wrong schema.
    """
    record = {'path': alter['path'], 'line': alter.get('line'), 'table': alter['table'],
              'kind': op['kind'], 'column': op.get('column') or op.get('name'),
              'new_name': op.get('new_name'), 'applied': False, 'note': None}
    kind = op['kind']
    columns = table['columns']
    if kind == 'add_column':
        if any(c['name'] == op['column'] for c in columns):
            record['note'] = 'column already present in the CREATE TABLE body'
            return record
        if op['definition'] is None:
            record['note'] = 'column definition could not be parsed'
            return record
        column = dict(op['definition'])
        columns.append(column)
        if column.get('inline_primary_key') and not table.get('primary_key'):
            table['primary_key'] = [column['name']]
        if column.get('inline_unique'):
            table['unique_keys'].append({'name': None, 'columns': [column['name']]})
        record['applied'] = True
        return record
    if kind == 'modify_column':
        for column in columns:
            if column['name'] == op['column']:
                if op['definition'] is None:
                    record['note'] = 'column definition could not be parsed'
                    return record
                for field in ('type', 'args', 'unsigned', 'nullable', 'default', 'auto_increment',
                              'generated', 'on_update', 'comment'):
                    column[field] = op['definition'].get(field)
                if 'enum_values' in op['definition']:
                    column['enum_values'] = op['definition']['enum_values']
                record['applied'] = True
                return record
        record['note'] = 'no such column in the snapshot'
        return record
    if kind == 'change_column':
        for column in columns:
            if column['name'] == op['column']:
                if op['definition'] is None:
                    record['note'] = 'column definition could not be parsed'
                    return record
                for field in ('type', 'args', 'unsigned', 'nullable', 'default', 'auto_increment',
                              'generated', 'on_update', 'comment'):
                    column[field] = op['definition'].get(field)
                if 'enum_values' in op['definition']:
                    column['enum_values'] = op['definition']['enum_values']
                column['name'] = op['new_name']
                _rename_in_key_columns(table, op['column'], op['new_name'])
                record['applied'] = True
                return record
        record['note'] = 'no such column in the snapshot'
        return record
    if kind == 'rename_column':
        for column in columns:
            if column['name'] == op['column']:
                column['name'] = op['new_name']
                _rename_in_key_columns(table, op['column'], op['new_name'])
                record['applied'] = True
                return record
        record['note'] = 'no such column in the snapshot'
        return record
    if kind == 'rename_index':
        renamed = False
        for bucket in ('indexes', 'unique_keys'):
            for entry in table.get(bucket) or []:
                if entry.get('name') == op['name']:
                    entry['name'] = op['new_name']
                    renamed = True
        record['applied'] = renamed
        if not renamed:
            record['note'] = 'no such index in the snapshot'
        return record
    if kind == 'drop_column':
        before = len(columns)
        table['columns'] = [c for c in columns if c['name'] != op['column']]
        record['applied'] = len(table['columns']) != before
        if not record['applied']:
            record['note'] = 'no such column in the snapshot'
        return record
    record['note'] = 'unsupported operation'
    return record


def apply_alters(tables_by_key: dict, alters: list):
    """Replay ALTER TABLE operations on the CREATE TABLE bodies, in Flyway order."""
    applied = []
    for alter in sorted(alters, key=lambda a: (migration_rank(a['path']), a.get('line') or 0)):
        operations = alter.get('operations') or []
        if not operations:
            continue
        for key, occurrences in tables_by_key.items():
            database, _, name = key.partition('.')
            if name != alter['table']:
                continue
            if alter.get('database') and database != alter['database']:
                continue
            for occurrence in occurrences:
                for op in operations:
                    applied.append(apply_operation(occurrence, op, alter))
                occurrence['table_hash'] = P.table_hash(occurrence)
    return applied


def build(root: str) -> dict:
    tables_by_key = {}
    source_stats = []
    issues = []
    dynamic_ddl = []
    other_ddl = []
    alters = []
    for path, group, db_hint, db_source in collect_sources(root):
        tables, file_alters, stats, file_issues = P.parse_sql_file(path, group, db_hint)
        for alter in file_alters:
            alter['path'] = rel(root, alter.pop('file'))
            alters.append(alter)
        stats['db_source'] = db_source
        stats['path'] = rel(root, path)
        stats['dynamic_ddl'] = sum(1 for issue in file_issues if issue['kind'] == 'dynamic-ddl')
        source_stats.append(stats)
        for issue in file_issues:
            issue['path'] = rel(root, issue.pop('file'))
            if issue['kind'] == 'dynamic-ddl':
                dynamic_ddl.append(issue)
                continue
            if issue['kind'] == 'non-table-ddl' and re.match(r"^CREATE\s+(OR\s+REPLACE\s+)?VIEW\b", issue['detail'], re.I):
                issue['kind'] = 'view'
                issue['name'] = None
                m = re.match(r"^CREATE\s+(?:OR\s+REPLACE\s+)?VIEW\s+(`[^`]+`|[A-Za-z_]\w*)", issue['detail'], re.I)
                if m:
                    issue['name'] = P.unquote_ident(m.group(1))
                other_ddl.append(issue)
                continue
            issues.append(issue)
        for table in tables:
            table['path'] = rel(root, path)
            key = '%s.%s' % (table['database'] or '?', table['name'])
            tables_by_key.setdefault(key, []).append(table)
    applied_alters = apply_alters(tables_by_key, alters)
    return {'tables_by_key': tables_by_key, 'sources': source_stats,
            'issues': issues, 'dynamic_ddl': dynamic_ddl, 'other_ddl': other_ddl, 'alters': alters,
            'applied_alters': applied_alters}


def column_diff(a: dict, b: dict):
    ca = {c['name']: c for c in a['columns']}
    cb = {c['name']: c for c in b['columns']}
    only_a = sorted(set(ca) - set(cb))
    only_b = sorted(set(cb) - set(ca))
    changed = []
    for name in sorted(set(ca) & set(cb)):
        x, y = ca[name], cb[name]
        fields = ('type', 'args', 'unsigned', 'nullable', 'default', 'auto_increment', 'generated', 'on_update')
        diffs = {f: [x.get(f), y.get(f)] for f in fields if x.get(f) != y.get(f)}
        if diffs:
            changed.append({'column': name, 'diff': diffs})
    return {'only_a': only_a, 'only_b': only_b, 'changed': changed}


def summarise(root: str, data: dict):
    by_key = data['tables_by_key']
    group_tables = {}
    for stats in data['sources']:
        group_tables.setdefault(stats['group'], {'files': 0, 'tables': 0})
        group_tables[stats['group']]['files'] += 1
        group_tables[stats['group']]['tables'] += stats['tables']
    duplicates = []
    union = {}
    for key, occurrences in sorted(by_key.items()):
        union[key] = occurrences[0]
        if len(occurrences) > 1:
            hashes = {o['table_hash'] for o in occurrences}
            entry = {'key': key, 'occurrences': [{'path': o['path'], 'line': o['line'],
                                                  'group': o['source_group'],
                                                  'hash': o['table_hash'][:12]} for o in occurrences],
                     'drift': len(hashes) > 1}
            if entry['drift']:
                base = occurrences[0]
                entry['diffs'] = []
                for other in occurrences[1:]:
                    if other['table_hash'] == base['table_hash']:
                        continue
                    entry['diffs'].append({'a': base['path'], 'b': other['path'],
                                           'columns': column_diff(base, other)})
            duplicates.append(entry)
    conditional = {}
    for key, occurrences in by_key.items():
        counts = {}
        for occurrence in occurrences:
            for column in occurrence['columns']:
                counts[column['name']] = counts.get(column['name'], 0) + 1
        missing = sorted(name for name, cnt in counts.items() if cnt < len(occurrences))
        if missing:
            conditional[key] = missing
    no_pk = sorted(k for k, t in union.items() if not t['primary_key'])
    with_fk = sorted(k for k, t in union.items() if t['foreign_keys'])
    databases = sorted({(t['database'] or '?') for t in union.values()})
    return {
        'group_tables': group_tables,
        'union_table_count': len(union),
        'union': union,
        'duplicates': duplicates,
        'conditional': conditional,
        'drifted': [d['key'] for d in duplicates if d['drift']],
        'tables_without_primary_key': no_pk,
        'tables_with_foreign_keys': with_fk,
        'databases': databases,
        'views': sorted({v['name'] for v in data['other_ddl'] if v.get('name')}),
    }


def union_columns(occurrences):
    """Union of every definition's columns.

    `columns` must list every column that can exist in some deployment path, with
    provenance: `sources` (which DDL files define it), `conditional` (not present in
    all definitions) and `conflicts` (differing definitions).  The generator uses
    `conditional` to avoid emitting columns that the deployed DDL will not have.
    """
    index = {}
    ordered = []
    for occurrence in occurrences:
        for column in occurrence['columns']:
            entry = index.get(column['name'])
            if entry is None:
                entry = dict(column)
                entry['sources'] = []
                entry['conditional'] = False
                entry['conflicts'] = []
                index[column['name']] = entry
                ordered.append(entry)
    total = len(occurrences)
    fields = ('type', 'args', 'unsigned', 'nullable', 'default', 'auto_increment', 'generated',
              'on_update', 'inline_primary_key', 'inline_unique', 'enum_values')
    for occurrence in occurrences:
        for column in occurrence['columns']:
            entry = index[column['name']]
            entry['sources'].append(occurrence['path'])
            diff = {f: [entry.get(f), column.get(f)] for f in fields
                    if entry.get(f) != column.get(f)}
            if diff and diff not in [c['diff'] for c in entry['conflicts']]:
                entry['conflicts'].append({'path': occurrence['path'], 'diff': diff})
    for entry in ordered:
        entry['sources'] = sorted(set(entry['sources']))
        entry['conditional'] = len(entry['sources']) < total
    return ordered


def snapshot_payload(root: str, data: dict, summary: dict) -> dict:
    tables = []
    for key, table in sorted(summary['union'].items()):
        occurrences = data['tables_by_key'][key]
        columns = union_columns(occurrences)
        conflicting = sorted(c['name'] for c in columns if c['conflicts'])
        tables.append({
            'key': key,
            'database': table['database'],
            'name': table['name'],
            'primary_key': table['primary_key'],
            'columns': columns,
            'column_sources': {c['name']: c['sources'] for c in columns},
            'conditional_columns': [c['name'] for c in columns if c['conditional']],
            'conflicting_columns': conflicting,
            'definition_count': len(occurrences),
            'unique_keys': table['unique_keys'],
            'indexes': table['indexes'],
            'foreign_keys': table['foreign_keys'],
            'engine': table['engine'],
            'charset': table['charset'],
            'comment': table['comment'],
            'table_hash': table['table_hash'],
            'definitions': [{'path': o['path'], 'line': o['line'], 'group': o['source_group'],
                             'hash': o['table_hash']} for o in occurrences],
        })
    incompatible = [{'path': a['path'], 'line': a['line'], 'table': a['table'], 'sql': a['sql'],
                     'sql_version_floor': 'MySQL 8 (compose/k8s image: mysql:8.0)'}
                    for a in data['alters'] if a['mysql8_incompatible_add_column']]
    return {
        'tool_version': TOOL_VERSION,
        'generated_from': 'repository DDL (no hand-written column lists)',
        'sources': data['sources'],
        'stats': {
            'tables_by_group': summary['group_tables'],
            'union_table_count': summary['union_table_count'],
            'databases': summary['databases'],
            'duplicate_definitions': len(summary['duplicates']),
            'drifted_definitions': summary['drifted'],
            'tables_without_primary_key': summary['tables_without_primary_key'],
            'tables_with_foreign_keys': summary['tables_with_foreign_keys'],
            'views': summary['views'],
            'tables_with_conditional_columns': sorted(summary['conditional']),
            'conditional_columns_total': sum(len(v) for v in summary['conditional'].values()),
            'alter_table_statements': len(data['alters']),
            'add_column_statements': sum(len(a['add_columns']) for a in data['alters']),
            'applied_alter_operations': sum(1 for a in data['applied_alters'] if a['applied']),
            'unapplied_alter_operations': sum(1 for a in data['applied_alters'] if not a['applied']),
            'renamed_columns': sorted({(a['table'], a['column'], a['new_name'])
                                       for a in data['applied_alters']
                                       if a['applied'] and a['kind'] in ('change_column', 'rename_column')}),
            'mysql8_incompatible_add_column': incompatible,
            'column_conflicts_total': sum(len(t['conflicting_columns']) for t in tables),
        },
        'tables': tables,
        'alter_table': data['alters'],
        'parse_issues': data['issues'],
        'dynamic_ddl': data['dynamic_ddl'],
    }


def render_report(payload: dict, summary: dict) -> str:
    out = []
    add = out.append
    add('# DDL 快照与漂移报告（自动生成，请勿手工编辑）')
    add('')
    add('生成器：`tools/synthetic-data/snapshot_schema.py`（工具版本 %s）' % payload['tool_version'])
    add('')
    add('本报告由仓库内 DDL 解析得出，是模拟数据生成器的**唯一列清单来源**（spec §7.9 要求 1：禁止手抄列定义）。')
    add('')
    add('## 1. 来源与表数')
    add('')
    add('| 来源分组 | 文件数 | CREATE TABLE 数 |')
    add('|---|---|---|')
    for group, stats in sorted(summary['group_tables'].items()):
        add('| `%s` | %d | %d |' % (group, stats['files'], stats['tables']))
    add('| **去重并集** | %d 个文件 | **%d** 张表 |' %
        (len(payload['sources']), payload['stats']['union_table_count']))
    add('')
    add('数据库（%d 个）：%s' % (len(summary['databases']), '、'.join('`%s`' % d for d in summary['databases'])))
    add('')
    add('## 2. 同一张表的多份定义')
    add('')
    add('共 %d 张表出现多份定义，其中 **%d 张存在列级差异**。' %
        (len(summary['duplicates']), len(summary['drifted'])))
    add('')
    if summary['drifted']:
        add('| 表 | 定义来源 | 列差异（仅第一份 vs 其它） |')
        add('|---|---|---|')
        for dup in summary['duplicates']:
            if not dup['drift']:
                continue
            detail = []
            for diff in dup.get('diffs', []):
                cols = diff['columns']
                bits = []
                if cols['only_a']:
                    bits.append('仅 A 有：%s' % ','.join(cols['only_a']))
                if cols['only_b']:
                    bits.append('仅 B 有：%s' % ','.join(cols['only_b']))
                if cols['changed']:
                    bits.append('类型/默认值不同：%s' % ','.join(c['column'] for c in cols['changed']))
                detail.append('`%s` → `%s`：%s' % (diff['a'], diff['b'], '；'.join(bits) or '顺序或索引差异'))
            add('| `%s` | %s | %s |' % (dup['key'],
                                       '<br>'.join('`%s`' % o['path'] for o in dup['occurrences']),
                                       '<br>'.join(detail)))
    else:
        add('未发现列级差异。')
    add('')
    add('## 3. 结构风险清单')
    add('')
    add('- 无主键表：**%d** 张%s' % (len(summary['tables_without_primary_key']),
                                    ('（' + '、'.join('`%s`' % t for t in summary['tables_without_primary_key'][:12]) + '）')
                                    if summary['tables_without_primary_key'] else ''))
    add('- 显式外键：**%d** 张表（其余表的关联仅存在于应用层，模拟数据生成器必须自行保证顺序）'
        % len(summary['tables_with_foreign_keys']))
    add('- 视图：%d 个%s' % (len(summary['views']),
                            ('（' + '、'.join('`%s`' % v for v in summary['views']) + '）') if summary['views'] else ''))
    add('- 阻断性解析问题：**%d** 条' % len(payload['parse_issues']))
    add('- 守卫式动态 DDL：%d 条（不参与静态表清单展开）' % len(payload['dynamic_ddl']))
    add('- 列定义不一致（同一列在不同定义中类型/默认值不同）：**%d** 列' % payload['stats']['column_conflicts_total'])
    add('- 存在`条件列`（并非每份定义都有）的表：**%d** 张 / 共 %d 列 -> 生成器默认**不写入**这些列，'
        '只有 `--include-conditional` 才写入（对应升级脚本执行成功的库）'
        % (len(payload['stats']['tables_with_conditional_columns']),
           payload['stats']['conditional_columns_total']))
    if payload['stats']['tables_with_conditional_columns']:
        add('')
        add('| 表 | 条件列（缺失于部分定义） |')
        add('|---|---|')
        for key in payload['stats']['tables_with_conditional_columns']:
            table = next(t for t in payload['tables'] if t['key'] == key)
            add('| `%s` | %s |' % (key, '、'.join('`%s`' % c for c in table['conditional_columns'])))
    add('')
    add('## 4. ALTER TABLE 事实（MySQL 8 兼容性，P0-39 机器可核证据）')
    add('')
    add('- ALTER TABLE 语句：**%d** 条；其中 ADD COLUMN：**%d** 条' %
        (payload['stats']['alter_table_statements'], payload['stats']['add_column_statements']))
    incompatible = payload['stats']['mysql8_incompatible_add_column']
    add('- 使用 `ADD COLUMN IF NOT EXISTS` 的语句：**%d** 条（MySQL 8 不支持该语法，执行即 `ERROR 1064`）' % len(incompatible))
    if incompatible:
        add('')
        add('| 文件 | 行 | 表 | 语句 |')
        add('|---|---|---|---|')
        for item in incompatible:
            add('| `%s` | %d | `%s` | `%s` |' % (item['path'], item['line'], item['table'],
                                                   item['sql'].replace('|', '\|')[:120]))
    unapplied = [a for a in payload.get('applied_alters', []) if not a['applied']]
    add('- 快照重放的 ALTER 操作：**%d** 条生效 / **%d** 条未生效（列重命名 %d 处）'
        % (payload['stats']['applied_alter_operations'], payload['stats']['unapplied_alter_operations'],
           len(payload['stats']['renamed_columns'])))
    if payload['stats']['renamed_columns']:
        add('')
        add('| 表 | 列重命名 |')
        add('|---|---|')
        for table, old, new in payload['stats']['renamed_columns']:
            add('| `%s` | `%s` → `%s` |' % (table, old, new))
    if unapplied:
        add('')
        add('| 文件 | 行 | 表 | 未生效的 ALTER | 原因 |')
        add('|---|---|---|---|---|')
        for item in unapplied[:20]:
            add('| `%s` | %s | `%s` | `%s` | %s |' % (item['path'], item['line'], item['table'],
                                                       item['kind'], item['note']))
    if payload['parse_issues']:
        add('')
        add('| 文件 | 行 | 类型 | 详情 |')
        add('|---|---|---|---|')
        for issue in payload['parse_issues'][:20]:
            add('| `%s` | %d | %s | `%s` |' % (issue['path'], issue['line'], issue['kind'],
                                               issue['detail'].replace('|', '\\|')[:90]))
    add('')
    return '\n'.join(out) + '\n'


def write(path: str, text: str):
    io.open(path, 'wb').write(text.replace('\r\n', '\n').encode('utf-8'))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--check', action='store_true',
                    help='compare against the committed snapshot instead of writing it')
    args = ap.parse_args()
    root = repo_root()
    data = build(root)
    if data['issues']:
        print('[schema] FAIL: %d blocking parse issue(s)' % len(data['issues']))
        for issue in data['issues'][:10]:
            print('   %s:%s %s' % (issue['path'], issue['line'], issue['detail'][:100]))
        return 2
    summary = summarise(root, data)
    payload = snapshot_payload(root, data, summary)
    text = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + '\n'
    report = render_report(payload, summary)
    sources_doc = json.dumps({'tool_version': TOOL_VERSION, 'sources': data['sources']},
                             ensure_ascii=False, indent=2) + '\n'
    if args.check:
        if not os.path.isfile(SNAPSHOT_PATH):
            print('[schema] FAIL: snapshot missing: %s' % rel(root, SNAPSHOT_PATH))
            return 2
        current = io.open(SNAPSHOT_PATH, encoding='utf-8').read()
        if current != text:
            print('[schema] FAIL: DDL drift detected - regenerate schema snapshot')
            return 1
        print('[schema] OK: snapshot matches repository DDL (%d tables, %d databases)'
              % (summary['union_table_count'], len(summary['databases'])))
        return 0
    if not os.path.isdir(SNAPSHOT_DIR):
        os.makedirs(SNAPSHOT_DIR)
    write(SNAPSHOT_PATH, text)
    write(SOURCES_PATH, sources_doc)
    write(REPORT_PATH, report)
    print('[schema] tables by group: %s' % ', '.join('%s=%d' % (g, s['tables'])
                                                     for g, s in sorted(summary['group_tables'].items())))
    stats = payload['stats']
    print('[schema] union=%d databases=%d duplicates=%d drifted=%d no_pk=%d with_fk=%d views=%d'
          % (summary['union_table_count'], len(summary['databases']), len(summary['duplicates']),
             len(summary['drifted']), len(summary['tables_without_primary_key']),
             len(summary['tables_with_foreign_keys']), len(summary['views'])))
    print('[schema] conditional_columns: %d table(s) / %d column(s); column_conflicts=%d'
          % (len(stats['tables_with_conditional_columns']), stats['conditional_columns_total'],
             stats['column_conflicts_total']))
    print('[schema] alter_table=%d add_column=%d mysql8_incompatible_add_column=%d'
          % (stats['alter_table_statements'], stats['add_column_statements'],
             len(stats['mysql8_incompatible_add_column'])))
    print('[schema] wrote %s' % rel(root, SNAPSHOT_PATH))
    print('[schema] wrote %s' % rel(root, SOURCES_PATH))
    print('[schema] wrote %s' % rel(root, REPORT_PATH))
    return 0


if __name__ == '__main__':
    sys.exit(main())
