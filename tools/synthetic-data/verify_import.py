# -*- coding: utf-8 -*-
"""Verify a loaded dataset against a LIVE MySQL 8 server.

This is the missing half of the evidence chain: everything else (``verify.py``,
``verify_cleanup.py``, ``verify_schema_load.py``) is offline.  This script talks to a real
server through the ``mysql`` command line client and answers two questions:

1. did every table really receive the number of rows the manifest declares?
2. after running ``cleanup.sql``, is the dataset really gone (and nothing else touched)?

It deliberately uses the ``mysql`` CLI instead of a Python driver so it works anywhere the
client is installed (local server, docker exec wrapper, remote host) with no dependency.

Usage::

    python verify_import.py --tier ci --host 127.0.0.1 --port 3306 --user root
    python verify_import.py --tier demo --cleanup        # then assert every table is empty
    python verify_import.py --tier ci --mysql-path C:\\tools\\mysql8\\bin\\mysql.exe
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL_VERSION = '1.0.0'


def quote(identifier):
    return '`%s`' % str(identifier).replace('`', '``')


class Mysql:
    def __init__(self, path, host, port, user, password, extra=None):
        self.base = [path, '--protocol=TCP', '-h', host, '-P', str(port), '-u', user,
                     '--default-character-set=utf8mb4', '--batch', '--skip-column-names']
        self.extra = list(extra or [])
        self.env = dict(os.environ)
        if password is not None:
            self.env['MYSQL_PWD'] = password

    def run(self, sql=None, database=None, stdin_path=None, check=False):
        args = list(self.base) + self.extra
        if database:
            args.append('--database=' + database)
        if sql is not None:
            args += ['-e', sql]
        stdin = open(stdin_path, 'rb') if stdin_path else subprocess.DEVNULL
        try:
            proc = subprocess.run(args, stdin=stdin, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, env=self.env)
        finally:
            if stdin_path:
                stdin.close()
        out = proc.stdout.decode('utf-8', 'replace')
        err = proc.stderr.decode('utf-8', 'replace')
        if check and proc.returncode:
            raise RuntimeError('mysql failed: %s' % err.strip())
        return proc.returncode, out, err

    def scalar(self, sql, database=None):
        code, out, err = self.run(sql, database=database)
        if code:
            raise RuntimeError(err.strip())
        return out.strip()


def counts(mysql, tables):
    """Return {table_key: row_count} using one UNION ALL query per database."""
    by_db = {}
    for entry in tables:
        by_db.setdefault(entry['database'], []).append(entry['name'])
    result = {}
    for database, names in by_db.items():
        parts = []
        for name in names:
            parts.append("SELECT '%s' AS t, COUNT(*) AS c FROM %s" % (name, quote(name)))
        sql = ' UNION ALL '.join(parts)
        code, out, err = mysql.run(sql, database=database)
        if code:
            raise RuntimeError('%s: %s' % (database, err.strip()))
        for line in out.strip().split('\n'):
            if not line.strip():
                continue
            name, _, count = line.partition('\t')
            result['%s.%s' % (database, name)] = int(count)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description='Verify a loaded dataset on a live MySQL server')
    parser.add_argument('--dataset', help='dataset directory (default: out/<tier>)')
    parser.add_argument('--tier', default='ci')
    parser.add_argument('--mysql-path', default='mysql', help='path to the mysql client')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', default='3306')
    parser.add_argument('--user', default='root')
    parser.add_argument('--password', default=os.environ.get('MYSQL_PWD'),
                        help='password (prefer MYSQL_PWD, never pass on a shared shell)')
    parser.add_argument('--cleanup', action='store_true',
                        help='run cleanup.sql afterwards and assert the dataset is gone')
    parser.add_argument('--baseline',
                        help='JSON of row counts taken BEFORE the load; counts are then '
                             'compared as baseline + manifest rows, because the Flyway '
                             'migrations already seed reference data (bootstrap user, fee '
                             'tables, templates, ...) that must not be counted as dataset')
    parser.add_argument('--save-baseline', help='write the current row counts to this JSON')
    parser.add_argument('--baseline-only', action='store_true',
                        help='with --save-baseline: write the counts and exit 0 without '
                             'comparing (use it on a freshly migrated, unloaded database)')
    parser.add_argument('--report', help='write a JSON report to this path')
    parser.add_argument('--quiet', action='store_true')
    args = parser.parse_args(argv)

    dataset = args.dataset or os.path.join('out', args.tier)
    if not os.path.isabs(dataset):
        dataset = os.path.join(HERE, dataset)
    manifest_path = os.path.join(dataset, 'manifest.json')
    if not os.path.isfile(manifest_path):
        print('[verify-import] FAIL: %s missing' % manifest_path)
        return 2
    with open(manifest_path, encoding='utf-8') as handle:
        manifest = json.load(handle)

    mysql = Mysql(args.mysql_path, args.host, args.port, args.user, args.password)
    baseline = {}
    if args.baseline:
        path = args.baseline
        if not os.path.isabs(path):
            path = os.path.join(HERE, path)
        if not os.path.isfile(path):
            print('[verify-import] FAIL: baseline %s missing' % path)
            return 2
        with open(path, encoding='utf-8') as handle:
            baseline = json.load(handle)
    try:
        version = mysql.scalar('SELECT VERSION()')
        sql_mode = mysql.scalar('SELECT @@sql_mode')
    except (RuntimeError, FileNotFoundError) as exc:
        print('[verify-import] FAIL: cannot reach MySQL at %s:%s as %s (%s)'
              % (args.host, args.port, args.user, exc))
        return 2

    strict = 'STRICT_TRANS_TABLES' in sql_mode or 'STRICT_ALL_TABLES' in sql_mode
    before = counts(mysql, manifest['tables'])
    if args.save_baseline:
        path = args.save_baseline
        if not os.path.isabs(path):
            path = os.path.join(HERE, path)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w', encoding='utf-8') as handle:
            json.dump(before, handle, ensure_ascii=False, indent=2, sort_keys=True)
        print('[verify-import] baseline saved: %d tables -> %s' % (len(before), path))
        if args.baseline_only:
            return 0

    expected = {}
    for t in manifest['tables']:
        key = '%s.%s' % (t['database'], t['name'])
        expected[key] = baseline.get(key, 0) + t['rows']
    missing = [k for k in expected if k not in before]
    mismatched = ['%s: %d rows in the server, expected %d (baseline %d + dataset %d)'
                  % (k, before[k], expected[k], baseline.get(k, 0),
                     expected[k] - baseline.get(k, 0)) for k in expected
                  if k in before and before[k] != expected[k]]

    registry = None
    try:
        code, out, err = mysql.run(
            'SELECT dataset_id, seed, tier, `tables`, `rows`, is_demo FROM '
            'amz_synthetic_dataset_registry ORDER BY id DESC LIMIT 1;', database='amz_ops')
        if code == 0 and out.strip():
            registry = out.strip().replace('\t', ' | ')
    except RuntimeError:
        registry = None

    cleanup_summary = None
    if args.cleanup:
        cleanup_path = os.path.join(dataset, 'cleanup.sql')
        if not os.path.isfile(cleanup_path):
            print('[verify-import] FAIL: %s missing - run purge.py --emit first' % cleanup_path)
            return 2
        code, out, err = mysql.run(stdin_path=cleanup_path)
        if code:
            print('[verify-import] FAIL: cleanup.sql aborted: %s' % err.strip()[:400])
            return 3
        after = counts(mysql, manifest['tables'])
        left = ['%s: %d rows left, baseline is %d' % (k, after.get(k, -1), baseline.get(k, 0))
                for k in expected if after.get(k, 0) != baseline.get(k, 0)]
        cleanup_summary = {'rows_removed': sum(before.values()) - sum(after.values()),
                           'tables_with_rows_left': left}

    failures = list(missing) + list(mismatched)
    if cleanup_summary and cleanup_summary['tables_with_rows_left']:
        failures.extend(cleanup_summary['tables_with_rows_left'])

    if args.report:
        payload = {
            'tool': 'verify_import.py', 'tool_version': TOOL_VERSION,
            'server_version': version, 'sql_mode': sql_mode, 'strict_mode': strict,
            'tier': manifest.get('tier'), 'dataset_id': manifest.get('dataset_id'),
            'baseline_tables': len(baseline),
            'tables': len(expected), 'rows_expected': sum(expected.values()),
            'rows_in_server': sum(before.values()),
            'missing_tables': missing, 'mismatched': mismatched,
            'registry': registry, 'cleanup': cleanup_summary,
        }
        path = args.report
        if not os.path.isabs(path):
            path = os.path.join(HERE, path)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w', encoding='utf-8') as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)

    if failures:
        print('[verify-import] FAIL (%d)' % len(failures))
        for item in failures[:25]:
            print('[verify-import]   %s' % item)
        return 1

    if not args.quiet:
        print('[verify-import] OK: MySQL %s (strict=%s) - %d/%d rows in %d tables'
              % (version, strict, sum(before.values()), sum(expected.values()), len(expected)))
        if registry:
            print('[verify-import]    registry: %s' % registry)
        if cleanup_summary:
            print('[verify-import]    cleanup: %d rows removed, %d table(s) with rows left'
                  % (cleanup_summary['rows_removed'],
                     len(cleanup_summary['tables_with_rows_left'])))
    return 0


if __name__ == '__main__':
    sys.exit(main())
