# -*- coding: utf-8 -*-
"""Prove that the generated dataset really fits the schema it was generated for.

The previous evidence chain was:

* ``verify.py``        - the JSONL/SQL is internally consistent and deterministic
* ``verify_cleanup.py`` - the emitted DELETE statements remove the dataset again, but
  it rebuilds the tables from *inferred* types, so a column that does not exist in the
  real DDL, a value longer than ``VARCHAR(n)`` or a missing ``NOT NULL`` column would
  never be noticed.

This script closes that gap.  It rebuilds all 14 databases from ``schema-snapshot.json``
(the snapshot is derived from the Flyway migrations, i.e. from the real DDL), then loads
the JSONL into that schema and enforces - in Python, because SQLite is dynamically typed -
the constraints MySQL 8 would enforce in STRICT mode:

* every JSONL column exists in the schema
* every ``NOT NULL`` column without default / auto_increment / generated expression is
  present and non-null
* integer columns receive integers and stay inside the signed/unsigned range
* ``DECIMAL(p,s)`` values stay inside the precision and scale budget
* ``VARCHAR(n)`` / ``CHAR(n)`` values are not longer than ``n`` characters
* ``DATE`` / ``DATETIME`` values parse as real dates
* ``JSON`` columns contain valid JSON
* duplicate primary keys / unique keys are rejected (SQLite enforces these for real)

Optionally it then runs the emitted ``cleanup.sql`` against the same schema and asserts
that every table is empty again - i.e. load -> cleanup is a closed loop on the real
schema, not on an inferred one.

HONEST LIMITATION: SQLite is still not MySQL 8.  It has no ENUM, no unsigned integers,
no ``ON UPDATE CURRENT_TIMESTAMP``, no generated-column semantics and no strict type
checking of its own, which is exactly why the checks above are done in Python.  This
script raises the confidence level from "the SQL text is well formed" to "the data fits
the real column types and constraints", it does NOT replace loading into a real
MySQL 8 instance.

Usage::

    python verify_schema_load.py --tier ci
    python verify_schema_load.py --tier demo --cleanup
    python verify_schema_load.py --tier demo --report out/demo/schema-load-report.json
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import shutil
import sqlite3
import sys
import tempfile
from collections import Counter, OrderedDict
from decimal import Decimal, InvalidOperation

HERE = os.path.dirname(os.path.abspath(__file__))

try:
    import sqlglot
except ImportError:  # pragma: no cover - only needed for --cleanup
    sqlglot = None

TOOL_VERSION = '1.0.0'

# --------------------------------------------------------------------------- type mapping

INTEGER_RANGES = {
    'TINYINT': (-128, 127),
    'SMALLINT': (-32768, 32767),
    'MEDIUMINT': (-8388608, 8388607),
    'INT': (-2147483648, 2147483647),
    'INTEGER': (-2147483648, 2147483647),
    'BIGINT': (-9223372036854775808, 9223372036854775807),
}

INTEGER_TYPES = set(INTEGER_RANGES)
TEXT_TYPES = {'VARCHAR', 'CHAR', 'TEXT', 'TINYTEXT', 'MEDIUMTEXT', 'LONGTEXT', 'JSON'}
DATE_TYPES = {'DATE', 'DATETIME', 'TIMESTAMP', 'TIME', 'YEAR'}

DATE_RE = re.compile(r'^\d{4}-\d{2}-\d{2}')
DATETIME_RE = re.compile(r'^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d+)?$')


def _args_list(raw):
    if not raw:
        return []
    return [a.strip() for a in str(raw).split(',') if a.strip()]


def sqlite_type(column):
    """Map a MySQL column type onto the closest SQLite storage class."""
    typ = (column.get('type') or '').upper()
    if typ in INTEGER_TYPES:
        return 'INTEGER'
    if typ in ('DECIMAL', 'NUMERIC'):
        return 'NUMERIC'
    if typ in ('DOUBLE', 'FLOAT', 'REAL'):
        return 'REAL'
    return 'TEXT'


def quote(identifier):
    return '"%s"' % str(identifier).replace('"', '""')


# --------------------------------------------------------------------------- DDL building

def _literal_default(raw):
    """Return a DEFAULT expression SQLite understands, or None to skip it."""
    if raw is None:
        return None
    text = str(raw).strip()
    if not text:
        return None
    upper = text.upper()
    if upper in ('CURRENT_TIMESTAMP', 'CURRENT_DATE', 'CURRENT_TIME'):
        return text
    if upper.startswith('CURRENT_TIMESTAMP('):
        return 'CURRENT_TIMESTAMP'
    if text.startswith("'") and text.endswith("'") and len(text) >= 2:
        return text
    if re.match(r'^-?\d+(\.\d+)?$', text):
        return text
    if upper in ('NULL',):
        return None
    return None


def build_ddl(table, skip_generated):
    """Return (create_sql, extra_sqls, column_meta) for one snapshot table."""
    parts = []
    meta = OrderedDict()
    pk_columns = [c for c in (table.get('primary_key') or [])]
    for column in table['columns']:
        name = column['name']
        if column.get('generated') and skip_generated:
            # SQLite generated columns use a different syntax; the generator never
            # writes them, so they are created as plain (nullable) columns.
            pass
        decl = '%s %s' % (quote(name), sqlite_type(column))
        if column.get('inline_primary_key'):
            if sqlite_type(column) == 'INTEGER':
                decl += ' PRIMARY KEY'
                if column.get('auto_increment'):
                    decl += ' AUTOINCREMENT'
            else:
                decl += ' PRIMARY KEY'
        elif not column.get('nullable'):
            decl += ' NOT NULL'
        default = _literal_default(column.get('default'))
        if default is not None:
            decl += ' DEFAULT %s' % default
        if column.get('inline_unique') and not column.get('inline_primary_key'):
            decl += ' UNIQUE'
        parts.append(decl)
        meta[name] = column

    if pk_columns and len(pk_columns) > 1:
        parts.append('PRIMARY KEY (%s)' % ', '.join(quote(c) for c in pk_columns))
    elif pk_columns and not any(meta[c].get('inline_primary_key') for c in pk_columns if c in meta):
        parts.append('PRIMARY KEY (%s)' % ', '.join(quote(c) for c in pk_columns))

    extra = []
    for unique in (table.get('unique_keys') or []):
        columns = unique.get('columns') or []
        if not columns:
            continue
        if any(c not in meta for c in columns):
            continue
        # SQLite index/constraint names are database-wide, MySQL names are per table,
        # so the table name is prefixed to avoid false collisions.
        parts.append('CONSTRAINT %s UNIQUE (%s)' % (
            quote('uq_%s__%s' % (table['name'], unique.get('name') or '_'.join(columns))),
            ', '.join(quote(c) for c in columns)))
    for index in (table.get('indexes') or []):
        columns = index.get('columns') or []
        if not columns or any(c not in meta for c in columns):
            continue
        extra.append('CREATE INDEX %s ON %s (%s)' % (
            quote('%s__%s' % (table['name'], index.get('name') or 'idx_' + '_'.join(columns))),
            quote(table['name']),
            ', '.join(quote(c) for c in columns)))

    create = 'CREATE TABLE %s (\n  %s\n)' % (quote(table['name']), ',\n  '.join(parts))
    return create, extra, meta


# --------------------------------------------------------------------------- value checks

def _check_integer(column, value):
    typ = (column.get('type') or '').upper()
    low, high = INTEGER_RANGES[typ]
    if column.get('unsigned'):
        low, high = 0, (high * 2 + 1)
    if isinstance(value, bool):
        value = int(value)
    if isinstance(value, float):
        if value != int(value):
            return [('error', 'integer-fraction', '%r has a fractional part' % value)]
        value = int(value)
    if not isinstance(value, int):
        return [('error', 'integer-type', '%s (%r) is not an integer' % (type(value).__name__, value))]
    if not (low <= value <= high):
        return [('error', 'integer-range', '%d outside %s%s range [%d, %d]' % (
            value, '' if not column.get('unsigned') else 'UNSIGNED ', typ, low, high))]
    return []


def _check_decimal(column, value):
    args = _args_list(column.get('args'))
    precision = int(args[0]) if args and args[0].isdigit() else 10
    scale = int(args[1]) if len(args) > 1 and args[1].isdigit() else 0
    if isinstance(value, bool):
        return [('error', 'decimal-type', 'boolean in DECIMAL column')]
    if isinstance(value, str):
        try:
            dec = Decimal(value)
        except (InvalidOperation, ValueError):
            return [('error', 'decimal-parse', '%r is not a number' % value)]
        notes = [('note', 'numeric-as-string',
                  'DECIMAL(%d,%d) receives the string %r (MySQL coerces it, JSONL keeps '
                  'it as text to avoid float rounding)' % (precision, scale, value))]
    elif isinstance(value, (int, float)):
        dec = Decimal(str(value))
        notes = []
    else:
        return [('error', 'decimal-type', '%s in DECIMAL column' % type(value).__name__)]
    if dec.copy_abs() >= Decimal(10) ** (precision - scale):
        notes.append(('error', 'decimal-range',
                      '%s exceeds DECIMAL(%d,%d) magnitude' % (dec, precision, scale)))
    exponent = -dec.as_tuple().exponent
    if exponent > scale:
        notes.append(('warning', 'decimal-scale',
                      '%s has %d fractional digits but scale is %d (MySQL rounds)' % (
                          dec, exponent, scale)))
    return notes


def _check_text(column, value):
    typ = (column.get('type') or '').upper()
    if not isinstance(value, str):
        return [('error', 'text-type', '%s in %s column' % (type(value).__name__, typ))]
    args = _args_list(column.get('args'))
    if typ in ('VARCHAR', 'CHAR') and args and args[0].isdigit():
        limit = int(args[0])
        if len(value) > limit:
            return [('error', 'text-length',
                     '%d characters exceeds %s(%d)' % (len(value), typ, limit))]
    if typ == 'JSON':
        try:
            json.loads(value)
        except (ValueError, TypeError):
            return [('error', 'json-invalid', 'value is not valid JSON: %.60r' % value)]
    return []


def _check_date(column, value):
    typ = (column.get('type') or '').upper()
    if not isinstance(value, str):
        return [('error', 'date-type', '%s in %s column' % (type(value).__name__, typ))]
    if typ == 'DATETIME' or typ == 'TIMESTAMP':
        if not DATETIME_RE.match(value) and not DATE_RE.match(value):
            return [('error', 'datetime-format', '%r is not a MySQL datetime' % value)]
    elif not DATE_RE.match(value):
        return [('error', 'date-format', '%r is not a MySQL date' % value)]
    head = value[:10].replace('/', '-')
    try:
        datetime.date(int(head[0:4]), int(head[5:7]), int(head[8:10]))
    except ValueError:
        return [('error', 'date-invalid', '%r is not a real calendar date' % value)]
    return []


def make_checker(column):
    """Return a callable(value) -> list[(severity, kind, detail)] for one column."""
    typ = (column.get('type') or '').upper()

    def checker(value):
        if value is None:
            if not column.get('nullable') and not column.get('generated'):
                return [('error', 'not-null', 'NULL in NOT NULL column')]
            return []
        if typ in INTEGER_TYPES:
            return _check_integer(column, value)
        if typ in ('DECIMAL', 'NUMERIC'):
            return _check_decimal(column, value)
        if typ in ('DOUBLE', 'FLOAT', 'REAL'):
            if isinstance(value, bool) or not isinstance(value, (int, float, str)):
                return [('error', 'numeric-type', '%s in %s column' % (type(value).__name__, typ))]
            if isinstance(value, str):
                try:
                    float(value)
                except ValueError:
                    return [('error', 'numeric-parse', '%r is not a number' % value)]
                return [('note', 'numeric-as-string', 'DOUBLE receives the string %r' % value)]
            return []
        if typ in TEXT_TYPES:
            return _check_text(column, value)
        if typ in DATE_TYPES:
            return _check_date(column, value)
        return []

    return checker


# --------------------------------------------------------------------------- loading

def iter_rows(path):
    with open(path, encoding='utf-8') as handle:
        for line in handle:
            line = line.strip()
            if line:
                yield json.loads(line)


def load_dataset(conns, dataset, manifest, snapshot_index, max_errors):
    """Create the schema and load every table.  Returns a per-table result dict."""
    results = OrderedDict()
    errors = []
    warnings = Counter()
    notes = Counter()
    attached = set()

    for entry in manifest['tables']:
        key = entry['table']
        table = snapshot_index.get(key)
        result = {
            'table': key,
            'rows_expected': entry['rows'],
            'rows_loaded': 0,
            'errors': 0,
            'warnings': 0,
            'notes': 0,
            'status': 'ok',
        }
        results[key] = result
        if table is None:
            result['status'] = 'missing-schema'
            errors.append('%s: table is not in the schema snapshot' % key)
            continue

        database = entry.get('database') or table['database']
        if database not in conns:
            raise RuntimeError('database %s has no connection' % database)
        conn = conns[database]

        create, extra, meta = build_ddl(table, skip_generated=True)
        try:
            conn.execute(create)
        except sqlite3.Error as exc:
            result['status'] = 'ddl-failed'
            errors.append('%s: DDL failed: %s' % (key, exc))
            continue
        for statement in extra:
            try:
                conn.execute(statement)
            except sqlite3.Error as exc:
                errors.append('%s: index failed: %s' % (key, exc))

        columns = list(entry['columns'])
        unknown = [c for c in columns if c not in meta]
        if unknown:
            result['status'] = 'unknown-columns'
            errors.append('%s: JSONL columns absent from DDL: %s' % (key, ', '.join(unknown)))
            continue

        required = []
        for name, column in meta.items():
            if name in columns or column.get('generated') or column.get('auto_increment'):
                continue
            if column.get('default') is not None:
                continue
            if not column.get('nullable'):
                required.append(name)
        if required:
            result['status'] = 'missing-columns'
            errors.append('%s: NOT NULL columns without default are never written: %s'
                          % (key, ', '.join(required)))
            continue

        if result['status'] == 'ddl-failed':
            continue

        checkers = [(name, make_checker(meta[name])) for name in columns]
        placeholders = ', '.join('?' for _ in columns)
        insert_sql = 'INSERT INTO %s (%s) VALUES (%s)' % (
            quote(table['name']),
            ', '.join(quote(c) for c in columns), placeholders)

        path = os.path.join(dataset, entry['jsonl'])
        batch = []
        batch_rows = []
        row_index = 0
        table_errors = 0

        def flush():
            nonlocal batch, batch_rows
            if not batch:
                return
            try:
                conn.executemany(insert_sql, batch)
            except sqlite3.IntegrityError:
                for values, index in zip(batch, batch_rows):
                    try:
                        conn.execute(insert_sql, values)
                    except sqlite3.IntegrityError as exc:
                        if table_errors < max_errors:
                            errors.append('%s: row %d rejected by the schema: %s'
                                          % (key, index, exc))
                        bump(result, 'errors')
            batch = []
            batch_rows = []

        for row in iter_rows(path):
            values = []
            bad = False
            for name, checker in checkers:
                value = row.get(name)
                issues = checker(value)
                for severity, kind, detail in issues:
                    if severity == 'error':
                        bad = True
                        if table_errors < max_errors:
                            errors.append('%s: row %d column %s - %s: %s'
                                          % (key, row_index, name, kind, detail))
                        bump(result, 'errors')
                    elif severity == 'warning':
                        warnings['%s.%s/%s' % (key, name, kind)] += 1
                        bump(result, 'warnings')
                    else:
                        notes['%s.%s/%s' % (key, name, kind)] += 1
                        bump(result, 'notes')
                values.append(value)
            row_index += 1
            if bad:
                continue
            batch.append(tuple(values))
            batch_rows.append(row_index)
            if len(batch) >= 2000:
                flush()
        flush()
        conn.commit()

        result['rows_loaded'] = row_index - result['errors']
        try:
            actual = conn.execute('SELECT COUNT(*) FROM %s' % quote(table['name'])).fetchone()[0]
        except sqlite3.Error:
            actual = -1
        result['rows_in_db'] = actual
        if result['errors']:
            result['status'] = 'errors'
        elif actual != entry['rows']:
            result['status'] = 'count-mismatch'
            errors.append('%s: %d rows in the database but the manifest declares %d'
                          % (key, actual, entry['rows']))

    return results, errors, warnings, notes


def bump(result, field):
    result[field] += 1


# --------------------------------------------------------------------------- cleanup

def run_cleanup(conns, cleanup_sql, results, attached):
    """Execute cleanup.sql (MySQL text) against the attached SQLite schemas."""
    statements = [s for s in sqlglot.parse(cleanup_sql, read='mysql') if s is not None]
    current = None
    deletes = 0
    failures = []
    for statement in statements:
        if isinstance(statement, sqlglot.exp.Use):
            current = statement.this.name if statement.this else None
            continue
        if not isinstance(statement, sqlglot.exp.Delete):
            continue
        table = statement.find(sqlglot.exp.Table)
        if table is None:
            failures.append('DELETE without a table: %s' % statement.sql(dialect='mysql')[:100])
            continue
        database = table.db or current
        name = table.name
        if database is None or database not in conns:
            failures.append('DELETE targets unknown database: %s' % statement.sql(dialect='mysql')[:100])
            continue
        conn = conns[database]
        try:
            conn.execute(statement.sql(dialect='sqlite'))
        except sqlite3.Error as exc:
            failures.append('%s.%s: %s' % (database, name, exc))
            continue
        deletes += 1
    conn.commit()
    remaining = []
    for key, result in results.items():
        if result.get('status') in ('missing-schema', 'ddl-failed'):
            continue
        database, _, name = key.partition('.')
        conn = conns.get(database)
        if conn is None:
            continue
        try:
            count = conn.execute('SELECT COUNT(*) FROM %s' % quote(name)).fetchone()[0]
        except sqlite3.Error:
            continue
        if count:
            remaining.append('%s: %d rows left' % (key, count))
    return deletes, failures, remaining


# --------------------------------------------------------------------------- main

def main(argv=None):
    parser = argparse.ArgumentParser(
        description='Load the dataset into a SQLite rebuild of the real schema')
    parser.add_argument('--dataset', help='dataset directory (default: out/<tier>)')
    parser.add_argument('--tier', default='ci')
    parser.add_argument('--snapshot', help='schema snapshot (default: schema/schema-snapshot.json)')
    parser.add_argument('--cleanup', action='store_true', help='also run cleanup.sql and assert empty')
    parser.add_argument('--cleanup-file', help='explicit cleanup.sql path')
    parser.add_argument('--report', help='write a JSON report to this path')
    parser.add_argument('--max-errors', type=int, default=25)
    parser.add_argument('--strict', action='store_true', help='treat warnings as failures')
    parser.add_argument('--quiet', action='store_true')
    args = parser.parse_args(argv)

    dataset = args.dataset or os.path.join('out', args.tier)
    if not os.path.isabs(dataset):
        dataset = os.path.join(HERE, dataset)
    snapshot_path = args.snapshot or os.path.join(HERE, 'schema', 'schema-snapshot.json')
    manifest_path = os.path.join(dataset, 'manifest.json')
    for path in (snapshot_path, manifest_path):
        if not os.path.isfile(path):
            print('[schema-load] FAIL: %s is missing' % path)
            return 2

    with open(snapshot_path, encoding='utf-8') as handle:
        snapshot = json.load(handle)
    with open(manifest_path, encoding='utf-8') as handle:
        manifest = json.load(handle)

    views = set(snapshot.get('stats', {}).get('views') or [])
    snapshot_index = {}
    databases = []
    for table in snapshot['tables']:
        if table['name'] in views:
            continue
        snapshot_index[table['key']] = table
        if table['database'] not in databases:
            databases.append(table['database'])

    workdir = tempfile.mkdtemp(prefix='schema-load-')
    try:
        conns = {}
        attached = set()
        for database in databases:
            path = os.path.join(workdir, '%s.sqlite3' % database)
            conns[database] = sqlite3.connect(path)
            attached.add(database)

        results, errors, warnings, notes = load_dataset(
            conns, dataset, manifest, snapshot_index, args.max_errors)

        cleanup_summary = None
        if args.cleanup:
            if sqlglot is None:
                print('[schema-load] FAIL: --cleanup needs sqlglot (pip install sqlglot)')
                return 2
            cleanup_path = args.cleanup_file or os.path.join(dataset, 'cleanup.sql')
            if not os.path.isfile(cleanup_path):
                print('[schema-load] FAIL: %s missing - run purge.py --emit first' % cleanup_path)
                return 2
            with open(cleanup_path, encoding='utf-8') as handle:
                cleanup_sql = handle.read()
            deletes, failures, remaining = run_cleanup(conns, cleanup_sql, results, attached)
            cleanup_summary = {'deletes': deletes, 'failures': failures, 'remaining': remaining}
            errors.extend(failures)
            errors.extend(remaining)
        for _conn in conns.values():
            _conn.close()
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    total_rows = sum(r['rows_expected'] for r in results.values())
    loaded = sum(r.get('rows_in_db', 0) for r in results.values())
    failed_tables = [k for k, r in results.items() if r['status'] != 'ok']
    warning_total = sum(warnings.values())
    note_total = sum(notes.values())

    if args.report:
        payload = {
            'tool': 'verify_schema_load.py',
            'tool_version': TOOL_VERSION,
            'tier': manifest.get('tier'),
            'dataset_id': manifest.get('dataset_id'),
            'snapshot': os.path.relpath(snapshot_path, HERE),
            'databases': len(databases),
            'tables': len(results),
            'rows_expected': total_rows,
            'rows_loaded': loaded,
            'failed_tables': failed_tables,
            'errors': errors,
            'warnings': dict(warnings.most_common(50)),
            'warning_total': warning_total,
            'notes_total': note_total,
            'note_columns': len(notes),
            'cleanup': cleanup_summary,
            'limitations': [
                'SQLite has no ENUM, unsigned integers, generated columns or strict typing; '
                'the type/length/range checks are performed in Python instead.',
                'This is not a MySQL 8 import test - it proves the data fits the real column '
                'definitions, not that mysqld accepts the dump.',
            ],
        }
        report_path = args.report
        if not os.path.isabs(report_path):
            report_path = os.path.join(HERE, report_path)
        os.makedirs(os.path.dirname(report_path), exist_ok=True)
        with open(report_path, 'w', encoding='utf-8') as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)

    if errors or (args.strict and warning_total):
        print('[schema-load] FAIL: %d error(s), %d table(s) not ok' % (len(errors), len(failed_tables)))
        for item in errors[:args.max_errors]:
            print('[schema-load]   %s' % item)
        return 1

    if not args.quiet:
        print('[schema-load] OK: %d tables / %d databases rebuilt from the schema snapshot, '
              '%d/%d rows loaded' % (len(results), len(databases), loaded, total_rows))
        if warning_total:
            print('[schema-load]    warnings=%d (top: %s)' % (
                warning_total, ', '.join('%s x%d' % (k, v) for k, v in warnings.most_common(3))))
        if note_total:
            print('[schema-load]    notes=%d values across %d column(s): %s' % (
                note_total, len(notes),
                ', '.join('%s x%d' % (k, v) for k, v in list(notes.most_common(2)))))
        if cleanup_summary:
            print('[schema-load]    cleanup: %d DELETE, %d failure(s), %d table(s) with rows left'
                  % (cleanup_summary['deletes'], len(cleanup_summary['failures']),
                     len(cleanup_summary['remaining'])))
        print('[schema-load]    limitation: SQLite emulation of the real DDL, not a MySQL 8 import')
    return 0


if __name__ == '__main__':
    sys.exit(main())
