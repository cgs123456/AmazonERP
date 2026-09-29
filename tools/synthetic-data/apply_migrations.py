# -*- coding: utf-8 -*-
"""Create the 14 service schemas on a MySQL 8 server and apply every Flyway migration.

Why this exists
---------------
``verify_schema_load.py`` replays the dataset against a *reconstructed* schema in
SQLite, which is a simulation, not mysqld. This script is the missing half: it
builds the real schemas from the very same Flyway migrations the services run,
so ``load.ps1`` / ``load.sh`` + ``verify_import.py`` can prove the dataset really
goes into MySQL 8 (strict mode, real types, real unique keys).

Flyway baseline (important)
--------------------------
The migrations here are applied with the ``mysql`` client, i.e. *without* Flyway.
That leaves a fully populated schema with no ``flyway_schema_history`` table, and
every service runs with ``baseline-on-migrate: false`` + ``baseline-version: 1``.
On such a database Flyway refuses to start *before running any DDL*: "Found
non-empty schema(s) ... but no schema history table". Before 2026-09-30 the flag was
``true``, which was strictly worse -- Flyway baselined at v1 and then replayed
V2..Vn on top of tables that already exist, failing with ``Duplicate key name`` and
leaving a half-migrated schema plus a failed history row (MySQL DDL is not
transactional; reproduced 2026-09-28: amz_ad V2, SQL State 42000). Either way the
synthetic database is not startable until this script writes one baseline row at the
module highest version, which is Flyway's documented way of adopting a pre-existing
schema. Production deployments should instead start from the 14 *empty* databases
created by ``docker/init-sql`` and let Flyway apply everything (that path is covered
by ``AllModulesFlywayMySqlIT``).

Usage
-----
    python apply_migrations.py --host 127.0.0.1 --port 3399 --user amz
    python apply_migrations.py --reset            # DROP + CREATE first (destructive)
    python apply_migrations.py --mysql-path C:\\tools\\mysql8\\bin\\mysql.exe

The password is read from ``--password`` or ``MYSQL_PWD`` (preferred: it never
shows up in the process list). Database names are derived from each service's
``application.yml`` JDBC url, so they cannot drift away from the services.
"""
from __future__ import annotations

import argparse
import io
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir))
SERVICE_ROOT = os.path.join(REPO, 'amz-service')

# Fallback used only when no application.yml can be parsed at all.
FALLBACK_DATABASES = [
    'amz_ad', 'amz_ai', 'amz_customer', 'amz_finance', 'amz_logistics',
    'amz_multiplatform', 'amz_ops', 'amz_order', 'amz_procurement', 'amz_product',
    'amz_report', 'amz_search', 'amz_spapi', 'amz_user',
]


class Mysql(object):
    def __init__(self, path, host, port, user, password):
        self.base = [path, '--protocol=TCP', '-h', host, '-P', str(port), '-u', user,
                     '--default-character-set=utf8mb4']
        self.env = dict(os.environ)
        if password is not None:
            self.env['MYSQL_PWD'] = password

    def run(self, sql=None, database=None, stdin_path=None):
        args = list(self.base)
        if database:
            args.append('--database=' + database)
        if sql is not None:
            args += ['-e', sql]
        stdin = open(stdin_path, 'rb') if stdin_path else subprocess.DEVNULL
        try:
            proc = subprocess.run(args + ['--show-warnings'], stdin=stdin,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  env=self.env)
        finally:
            if stdin_path:
                stdin.close()
        return (proc.returncode,
                proc.stdout.decode('utf-8', 'replace'),
                proc.stderr.decode('utf-8', 'replace'))

FLYWAY_HISTORY_DDL = (
    "CREATE TABLE IF NOT EXISTS flyway_schema_history ("
    " installed_rank int NOT NULL,"
    " version varchar(50) DEFAULT NULL,"
    " description varchar(200) NOT NULL,"
    " type varchar(20) NOT NULL,"
    " script varchar(1000) NOT NULL,"
    " checksum int DEFAULT NULL,"
    " installed_by varchar(100) NOT NULL,"
    " installed_on timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,"
    " execution_time int NOT NULL,"
    " success tinyint(1) NOT NULL,"
    " PRIMARY KEY (installed_rank),"
    " KEY flyway_schema_history_s_idx (success)"
    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
)


def ensure_flyway_baseline(mysql, database, max_version):
    """Tell Flyway that every migration up to ``max_version`` is already applied.

    The migrations were applied with raw SQL, so Flyway has no history table. Left
    alone, the next service start sees "non-empty schema + no schema history table"
    and refuses to start before running any DDL (``baseline-on-migrate: false``).
    Inserting a baseline row at the module's highest version is the documented way
    to adopt a pre-existing schema, and makes Flyway accept the database cleanly
    with nothing left to replay.

    Returns ``(created, error_or_None)``. ``created`` is False when a history table
    already exists (the services have genuinely run there), in which case nothing
    is touched.
    """
    code, out, err = mysql.run(
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='%s'"
        " AND table_name='flyway_schema_history';" % database)
    if code:
        return False, err.strip()
    numbers = [int(token) for token in out.split() if token.isdigit()]
    if numbers and numbers[-1] > 0:
        return False, None
    code, _, err = mysql.run(database=database, sql=FLYWAY_HISTORY_DDL)
    if code:
        return False, err.strip()
    code, _, err = mysql.run(database=database, sql=(
        "INSERT INTO flyway_schema_history (installed_rank, version, description,"
        " type, script, checksum, installed_by, execution_time, success)"
        " VALUES (1, '%d', '<< Flyway Baseline >>', 'BASELINE',"
        " '<< Flyway Baseline >>', NULL, USER(), 0, 1);" % max_version))
    if code:
        return True, err.strip()
    return True, None


def discover_services():
    """Return [(service_dir, database)] parsed from each application.yml."""
    services = []
    if not os.path.isdir(SERVICE_ROOT):
        return services
    for name in sorted(os.listdir(SERVICE_ROOT)):
        yml = os.path.join(SERVICE_ROOT, name, 'src', 'main', 'resources', 'application.yml')
        if not os.path.isfile(yml):
            continue
        text = io.open(yml, encoding='utf-8', errors='replace').read()
        # primary: spring.datasource.url / any jdbc url with a schema name
        m = re.search(r'jdbc:mysql://[^/\s"\']*/([A-Za-z_0-9]+)', text)
        if not m:
            continue
        services.append((name, m.group(1)))
    return services


def main(argv=None):
    parser = argparse.ArgumentParser(description='Apply every Flyway migration to a MySQL 8 server')
    parser.add_argument('--mysql-path', default='mysql', help='mysql client (default: $PATH mysql)')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=3306)
    parser.add_argument('--user', default='root')
    parser.add_argument('--password', default=None, help='prefer MYSQL_PWD')
    parser.add_argument('--reset', action='store_true',
                        help='DROP the schemas first - destructive, every row goes away')
    parser.add_argument('--dry-run', action='store_true',
                        help='print what would be applied, run nothing')
    args = parser.parse_args(argv)

    mysql = Mysql(args.mysql_path, args.host, args.port, args.user, args.password)
    services = discover_services()
    databases = sorted({db for _, db in services}) or list(FALLBACK_DATABASES)
    if not services:
        print('[apply-migrations] WARN: no application.yml parsed, using the built-in database list')

    if args.dry_run:
        print('[apply-migrations] dry-run: %d databases' % len(databases))
        for service, db in services:
            mdir = os.path.join(SERVICE_ROOT, service, 'src', 'main', 'resources', 'db', 'migration')
            files = sorted(f for f in os.listdir(mdir) if re.match(r'^V\d+__.*\.sql$', f)) \
                if os.path.isdir(mdir) else []
            print('  %-28s -> %-18s %d migration(s)' % (service, db, len(files)))
        return 0

    code, out, err = mysql.run('SELECT VERSION();')
    if code:
        print('[apply-migrations] FAIL: cannot connect - %s' % err.strip())
        return 1
    print('[apply-migrations] server %s' % out.strip())

    if args.reset:
        for db in databases:
            code, _, err = mysql.run('DROP DATABASE IF EXISTS `%s`;' % db)
            if code:
                print('[apply-migrations] FAIL drop %s: %s' % (db, err.strip()))
                return 1
        print('[apply-migrations] dropped %d databases' % len(databases))

    for db in databases:
        code, _, err = mysql.run('CREATE DATABASE IF NOT EXISTS `%s` '
                                 'DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;' % db)
        if code:
            print('[apply-migrations] FAIL create %s: %s' % (db, err.strip()))
            return 1
    print('[apply-migrations] %d databases ready' % len(databases))

    total = 0
    failed = 0
    for service, db in services:
        mdir = os.path.join(SERVICE_ROOT, service, 'src', 'main', 'resources', 'db', 'migration')
        if not os.path.isdir(mdir):
            print('  %-28s -> %-18s (no flyway migrations)' % (service, db))
            continue
        files = []
        for f in os.listdir(mdir):
            m = re.match(r'^V(\d+)__.*\.sql$', f)
            if m:
                files.append((int(m.group(1)), f))
        files.sort()
        ok = 0
        for _, f in files:
            code, _, err = mysql.run(database=db, stdin_path=os.path.join(mdir, f))
            total += 1
            if code:
                failed += 1
                print('  FAIL %s/%s -> %s' % (service, f, db))
                print('       %s' % err.strip().split('\n')[0])
                # keep going: later migrations in other services are independent
                continue
            ok += 1
            if err.strip():
                print('  WARN %s/%s: %s' % (service, f, err.strip().split('\n')[0][:160]))
        if files:
            created, baseline_error = ensure_flyway_baseline(mysql, db, files[-1][0])
            if baseline_error:
                failed += 1
                print('  FAIL baseline %s -> %s' % (service, db))
                print('       %s' % baseline_error.split('\n')[0])
            elif created:
                print('       flyway_schema_history baselined @ v%d' % files[-1][0])
        print('  %-28s -> %-18s %d migrations ok' % (service, db, ok))

    print('[apply-migrations] TOTAL migrations=%d failed=%d' % (total, failed))
    if failed:
        print('[apply-migrations] NOTE: re-running against a database that already has these '
              'tables fails on CREATE TABLE - use --reset for a clean rebuild')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
