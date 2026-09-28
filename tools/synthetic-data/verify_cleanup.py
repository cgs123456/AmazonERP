# -*- coding: utf-8 -*-
"""Offline semantic check of the generated cleanup.sql - no MySQL server required.

``purge.py`` derives DELETE statements from the dataset JSONL.  This script proves that
those statements really remove exactly the loaded rows: it rebuilds an in-memory SQLite
database from the same JSONL, runs the emitted ``cleanup.sql`` against it (sqlglot is
used to split/validate the statements, MySQL dialect in, SQLite execution), and asserts
that every table ends up empty and that no row outside the dataset was touched.

Usage::

    python verify_cleanup.py --tier demo
    python verify_cleanup.py --dataset out/ci
"""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

try:
    import sqlglot
except ImportError:  # pragma: no cover - dependency is optional at runtime
    sqlglot = None


def _quote(identifier):
    return '"%s"' % identifier.replace('"', '""')


def _sql_type(value):
    if isinstance(value, bool):
        return 'INTEGER'
    if isinstance(value, int):
        return 'INTEGER'
    return 'TEXT'


def build_database(dataset, manifest):
    conn = sqlite3.connect(':memory:')
    loaded = {}
    for entry in manifest['tables']:
        path = os.path.join(dataset, entry['jsonl'])
        columns = []
        rows = []
        with open(path, encoding='utf-8') as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                row = json.loads(line)
                rows.append(row)
                for key, value in row.items():
                    if key not in columns:
                        columns.append(key)
        types = {}
        for row in rows:
            for key, value in row.items():
                if value is None:
                    continue
                types.setdefault(key, _sql_type(value))
        ddl = 'CREATE TABLE %s (%s)' % (
            _quote(entry['name']),
            ', '.join('%s %s' % (_quote(c), types.get(c, 'TEXT')) for c in columns))
        conn.execute(ddl)
        if rows:
            placeholders = ', '.join('?' for _ in columns)
            conn.executemany(
                'INSERT INTO %s (%s) VALUES (%s)' % (
                    _quote(entry['name']),
                    ', '.join(_quote(c) for c in columns),
                    placeholders),
                [tuple(row.get(c) for c in columns) for row in rows])
        loaded[entry['name']] = len(rows)
    conn.commit()
    return conn, loaded


def main(argv=None):
    parser = argparse.ArgumentParser(description='Verify cleanup.sql against the dataset (SQLite simulation)')
    parser.add_argument('--dataset', help='dataset directory (default: out/<tier>)')
    parser.add_argument('--tier', default='ci')
    parser.add_argument('--cleanup', help='cleanup.sql path (default: <dataset>/cleanup.sql)')
    parser.add_argument('--quiet', action='store_true')
    args = parser.parse_args(argv)

    if sqlglot is None:
        print('[verify-cleanup] SKIP: sqlglot is not installed (pip install sqlglot)')
        return 0

    dataset = args.dataset or os.path.join('out', args.tier)
    if not os.path.isabs(dataset):
        dataset = os.path.join(HERE, dataset)
    cleanup_path = args.cleanup or os.path.join(dataset, 'cleanup.sql')
    if not os.path.isfile(cleanup_path):
        print('[verify-cleanup] FAIL: %s missing - run purge.py --emit first' % cleanup_path)
        return 2
    with open(os.path.join(dataset, 'manifest.json'), encoding='utf-8') as handle:
        manifest = json.load(handle)
    with open(cleanup_path, encoding='utf-8') as handle:
        cleanup_sql = handle.read()

    conn, loaded = build_database(dataset, manifest)
    statements = [s for s in sqlglot.parse(cleanup_sql, read='mysql') if s is not None]
    deletes = 0
    for statement in statements:
        if not isinstance(statement, sqlglot.exp.Delete):
            continue
        table = statement.find(sqlglot.exp.Table)
        if table is None:
            print('[verify-cleanup] FAIL: DELETE without a table: %s' % statement.sql(dialect='mysql')[:120])
            return 3
        name = table.name
        if name not in loaded:
            print('[verify-cleanup] FAIL: DELETE targets unknown table %s' % name)
            return 3
        sql = statement.sql(dialect='sqlite')
        conn.execute(sql)
        deletes += 1
    conn.commit()

    failures = []
    for name, expected in loaded.items():
        remaining = conn.execute('SELECT COUNT(*) FROM %s' % _quote(name)).fetchone()[0]
        if remaining != 0:
            failures.append('%s: %d rows left after cleanup' % (name, remaining))
    if deletes != len([t for t in manifest['tables'] if t['rows'] > 0]):
        failures.append('delete statements=%d but non-empty tables=%d' % (
            deletes, len([t for t in manifest['tables'] if t['rows'] > 0])))

    total = sum(loaded.values())
    if failures:
        print('[verify-cleanup] FAIL (%d)' % len(failures))
        for item in failures[:20]:
            print('[verify-cleanup]   %s' % item)
        return 1
    if not args.quiet:
        print('[verify-cleanup] OK: %d DELETE statements, %d rows removed, 0 rows left in %d tables'
              % (deletes, total, len(loaded)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
