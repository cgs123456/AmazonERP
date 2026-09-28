# -*- coding: utf-8 -*-
"""Cleanup / registry artefacts for a generated synthetic dataset.

The generator (``generate.py``) writes a deterministic dataset that is tagged with the
reserved id band (see ``ID_BANDS`` below), ``SYNTHETIC`` text markers and, where the
schema has one, a ``source`` / ``origin`` / ``data_source`` column.  This script turns
that dataset into two operational artefacts:

* ``cleanup.sql``  - removes exactly the rows this dataset loaded (id ranges taken from
  the generated JSONL, never hand-written), so a shared/legacy database can be cleaned
  without a TRUNCATE that would also delete real rows.
* ``registry.sql`` - a small marker table (``amz_ops.amz_synthetic_dataset_registry``)
  plus one row describing the loaded dataset.  The presence of a row is the machine
  readable answer to "does this database contain simulated data?" - the schema has no
  ``is_demo`` column, and adding one to 113 tables is not something to do implicitly.

Safety rules (fail closed):

* only single column integer primary keys are turned into ranges; anything else is
  reported as MANUAL and never emitted;
* ranges below ``LOW_BAND_LIMIT`` (the small ``amz_user`` / ``amz_product`` bands) are
  only emitted with ``--allow-low-band``;
* ``--mode truncate`` (which deletes *everything* in the target tables) requires
  ``--allow-destructive``;
* a non zero exit code is returned when any table could not be covered automatically.

Usage::

    python purge.py --tier demo                     # dry run: report only
    python purge.py --tier demo --emit              # write cleanup.sql
    python purge.py --tier demo --emit --registry   # also write registry.sql
"""

from __future__ import annotations

import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL_VERSION = '1.0.0'

# Mirrors generate.py: the big reserved band plus the two small bands that exist because
# amz_order.user_id / amz_order.product_id are INT in the current schema.
ID_BAND_BASE = 900000000000000000
LOW_BAND_LIMIT = 10 ** 12
# generate.py also uses two small bands because amz_order.user_id / product_id are INT.
# They are reserved by the generator too, so cleanup may target them by default; anything
# outside these bands and below LOW_BAND_LIMIT is UNKNOWN and never emitted without
# --allow-low-band (a real row could legitimately live there).
RESERVED_LOW_BANDS = ((100000000, 199999999), (200000000, 299999999))
MARKER_COLUMNS = ('data_origin', 'data_source', 'source', 'origin')
MARKER_VALUE = 'SYNTHETIC'
REGISTRY_DB = 'amz_ops'
REGISTRY_TABLE = 'amz_synthetic_dataset_registry'


def _load_json(path):
    with open(path, encoding='utf-8') as handle:
        return json.load(handle)


def _pk_of(snapshot, key):
    for table in snapshot['tables']:
        if table['key'] == key:
            return table['primary_key'] or [], table['columns']
    return [], []


def _ranges(values):
    """Collapse a sorted set of ints into inclusive [start, end] ranges."""
    out = []
    for value in values:
        if out and value == out[-1][1] + 1:
            out[-1][1] = value
        else:
            out.append([value, value])
    return out


def _iter_jsonl(path):
    with open(path, encoding='utf-8') as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            yield json.loads(line)


def analyse(dataset_dir, emit_registry=False):
    manifest = _load_json(os.path.join(dataset_dir, 'manifest.json'))
    snapshot = _load_json(os.path.join(HERE, 'schema', 'schema-snapshot.json'))
    plans = []
    for entry in manifest['tables']:
        key = '%s.%s' % (entry['database'], entry['name'])
        pk, columns = _pk_of(snapshot, key)
        markers = [c['name'] for c in columns if c['name'] in MARKER_COLUMNS]
        plan = {
            'table': key,
            'database': entry['database'],
            'name': entry['name'],
            'rows': entry['rows'],
            'primary_key': pk,
            'marker_columns': markers,
            'status': 'OK',
            'ranges': [],
            'min': None,
            'max': None,
        }
        if entry['rows'] <= 0:
            plan['status'] = 'EMPTY'
            plans.append(plan)
            continue
        if len(pk) != 1:
            plan['status'] = 'MANUAL_COMPOSITE_PK'
            plans.append(plan)
            continue
        jsonl = os.path.join(dataset_dir, entry['jsonl'])
        values = set()
        marker_values = set()
        marker_null = False
        non_int = False
        for row in _iter_jsonl(jsonl):
            raw = row[pk[0]]
            if isinstance(raw, bool) or not isinstance(raw, int):
                non_int = True
                break
            values.add(raw)
            for marker in markers:
                value = row.get(marker)
                if value is None:
                    marker_null = True
                else:
                    marker_values.add(str(value))
        if non_int:
            plan['status'] = 'MANUAL_NON_INTEGER_PK'
            plans.append(plan)
            continue
        plan['marker_null'] = marker_null
        plan['marker_sample'] = sorted(marker_values)[:5]
        plan['marker_distinct'] = len(marker_values)
        # a LIKE guard is only emitted when every generated row shares a prefix, so the
        # guard can never silently delete nothing (the earlier hard coded
        # "marker = 'SYNTHETIC'" did exactly that: the generator writes SYN-source-000000)
        plan['marker_prefix'] = None
        if markers and marker_values and not marker_null:
            prefix = os.path.commonprefix(list(marker_values))
            if len(prefix) >= 4:
                plan['marker_prefix'] = prefix
        ranges = _ranges(sorted(values))
        plan['ranges'] = ranges
        plan['min'] = ranges[0][0]
        plan['max'] = ranges[-1][1]
        plan['distinct_ids'] = len(values)
        if plan['distinct_ids'] != plan['rows']:
            plan['pk_not_unique'] = True
        if plan['min'] >= ID_BAND_BASE:
            plan['status'] = 'OK'
        elif any(lo <= plan['min'] and plan['max'] <= hi for lo, hi in RESERVED_LOW_BANDS):
            plan['status'] = 'LOW_BAND_RESERVED'
        else:
            plan['status'] = 'LOW_BAND_UNKNOWN'
        plans.append(plan)
    return manifest, plans


def _esc(value):
    return str(value).replace('\\', '\\\\').replace("'", "''")


def render_cleanup(manifest, plans, mode, allow_low_band, allow_destructive):
    lines = []
    lines.append('-- SYNTHETIC DATA CLEANUP - generated by tools/synthetic-data/purge.py v%s' % TOOL_VERSION)
    lines.append('-- dataset_id=%s seed=%s tier=%s generated_at=%s' % (
        manifest['dataset_id'], manifest['seed'], manifest['tier'], manifest.get('generated_at')))
    lines.append('-- mode=%s rows=%d tables=%d' % (mode, manifest['totals']['rows'], len(plans)))
    lines.append('--')
    lines.append('-- DANGER: this file deletes rows. It was derived from the dataset manifest and the')
    lines.append('-- generated JSONL, so it only targets rows this dataset loaded, but ALWAYS take a')
    lines.append('-- backup and run the SELECT verification queries in cleanup-report.json first.')
    lines.append('-- Never run it against production; demo data belongs in a disposable database.')
    lines.append('')
    if mode == 'truncate':
        if not allow_destructive:
            return None, 'truncate mode requires --allow-destructive (it deletes every row, including real ones)'
        lines.append('-- TRUNCATE mode: deletes ALL rows in the tables below (real rows included).')
        lines.append('')
        current_db = None
        for plan in reversed(plans):
            if plan['status'] == 'EMPTY':
                continue
            if plan['database'] != current_db:
                current_db = plan['database']
                lines.append('USE `%s`;' % current_db)
            lines.append('TRUNCATE TABLE `%s`;' % plan['name'])
        lines.append('')
        return '\n'.join(lines), None

    skipped = []
    current_db = None
    for plan in reversed(plans):
        if plan['status'] == 'EMPTY':
            continue
        if plan['status'] == 'LOW_BAND_UNKNOWN' and not allow_low_band:
            skipped.append(plan)
            continue
        if plan['status'] not in ('OK', 'LOW_BAND_RESERVED', 'LOW_BAND_UNKNOWN'):
            skipped.append(plan)
            continue
        if plan['database'] != current_db:
            current_db = plan['database']
            lines.append('USE `%s`;' % current_db)
        pk = plan['primary_key'][0]
        conds = []
        for start, end in plan['ranges']:
            if start == end:
                conds.append('`%s` = %d' % (pk, start))
            else:
                conds.append('`%s` BETWEEN %d AND %d' % (pk, start, end))
        # AND with the marker column when the schema has one: a second, independent guard
        # so a real row that happens to sit inside the band is not deleted.
        extra = ''
        if plan.get('marker_prefix'):
            guard = plan['marker_prefix'].replace('\\', '\\\\').replace("'", "''").replace('%', '\\%')
            extra = " AND `%s` LIKE '%s%%'" % (plan['marker_columns'][0], guard)
        note = ''
        if plan['status'] == 'LOW_BAND_RESERVED':
            note = ', LOW BAND (generator reserved: verify no real rows inside)'
        elif plan['status'] == 'LOW_BAND_UNKNOWN':
            note = ', LOW BAND UNKNOWN (emitted only with --allow-low-band)'
        lines.append('-- %s (%d rows, %d range(s)%s)' % (
            plan['table'], plan['rows'], len(plan['ranges']), note))
        lines.append('DELETE FROM `%s` WHERE (%s)%s;' % (plan['name'], ' OR '.join(conds), extra))
    lines.append('')
    if skipped:
        lines.append('-- TABLES NOT COVERED (delete manually, see cleanup-report.json):')
        for plan in skipped:
            lines.append('--   %s: %s' % (plan['table'], plan['status']))
    return '\n'.join(lines), (None if not skipped else skipped)


def render_registry(manifest, plans):
    usable = [p for p in plans if p['status'] in ('OK', 'LOW_BAND') and p['min'] is not None]
    band_min = min(p['min'] for p in usable) if usable else 0
    band_max = max(p['max'] for p in usable) if usable else 0
    rows = sum(p['rows'] for p in plans)
    sql = []
    sql.append('-- SYNTHETIC DATA REGISTRY - generated by tools/synthetic-data/purge.py v%s' % TOOL_VERSION)
    sql.append('-- Load this BEFORE the dataset: a row in %s.%s is the machine readable' % (REGISTRY_DB, REGISTRY_TABLE))
    sql.append('-- proof that the database holds simulated data (the schema has no is_demo column).')
    sql.append('USE `%s`;' % REGISTRY_DB)
    sql.append('CREATE TABLE IF NOT EXISTS `%s` (' % REGISTRY_TABLE)
    sql.append('  `id` BIGINT NOT NULL AUTO_INCREMENT,')
    sql.append('  `dataset_id` VARCHAR(64) NOT NULL,')
    sql.append('  `seed` BIGINT NOT NULL,')
    sql.append('  `tier` VARCHAR(16) NOT NULL,')
    sql.append('  `generated_at` VARCHAR(32) NOT NULL,')
    sql.append('  `loaded_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,')
    sql.append('  `loaded_by` VARCHAR(128) DEFAULT NULL,')
    sql.append('  `tables` INT NOT NULL DEFAULT 0,')
    sql.append('  `rows` BIGINT NOT NULL DEFAULT 0,')
    sql.append('  `band_min` BIGINT NOT NULL DEFAULT 0,')
    sql.append('  `band_max` BIGINT NOT NULL DEFAULT 0,')
    sql.append('  `is_demo` TINYINT NOT NULL DEFAULT 1,')
    sql.append('  PRIMARY KEY (`id`),')
    sql.append('  UNIQUE KEY `uk_synthetic_dataset` (`dataset_id`, `seed`)')
    sql.append(') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4')
    sql.append("  COMMENT='SYNTHETIC demo dataset registry - a row means this DB contains simulated data';")
    sql.append('INSERT INTO `%s` (`dataset_id`,`seed`,`tier`,`generated_at`,`loaded_by`,`tables`,`rows`,`band_min`,`band_max`,`is_demo`)'
               % REGISTRY_TABLE)
    sql.append("VALUES ('%s', %s, '%s', '%s', CURRENT_USER(), %d, %d, %d, %d, 1) AS new" % (
        _esc(manifest['dataset_id']), manifest['seed'], _esc(manifest['tier']),
        _esc(manifest.get('generated_at', '')), len(plans), rows, band_min, band_max))
    # The unique key is (dataset_id, seed) and does NOT include tier, so reloading a
    # different tier into the same database updates the existing row instead of
    # inserting a new one. `tier` / `generated_at` must therefore be refreshed too,
    # otherwise the audit row can claim tier=ci while carrying the demo row count.
    # MySQL 8.0.19+ row alias syntax: VALUES() is deprecated (warning 1287) and is
    # scheduled for removal, so the upsert references the inserted row as `new`.
    sql.append('ON DUPLICATE KEY UPDATE `loaded_at` = NOW(), `loaded_by` = CURRENT_USER(),'
               ' `tier` = new.`tier`, `generated_at` = new.`generated_at`,'
               ' `tables` = new.`tables`, `rows` = new.`rows`,'
               ' `band_min` = new.`band_min`, `band_max` = new.`band_max`,'
               ' `is_demo` = new.`is_demo`;')
    sql.append('')
    return '\n'.join(sql)


def main(argv=None):
    parser = argparse.ArgumentParser(description='Emit cleanup.sql / registry.sql for a synthetic dataset')
    parser.add_argument('--dataset', help='dataset directory (default: out/<tier>)')
    parser.add_argument('--tier', default='ci', help='used only to resolve the default dataset directory')
    parser.add_argument('--emit', action='store_true', help='write cleanup.sql (otherwise: report only)')
    parser.add_argument('--registry', action='store_true', help='also write registry.sql')
    parser.add_argument('--mode', choices=('delete', 'truncate'), default='delete')
    parser.add_argument('--allow-low-band', action='store_true',
                        help='emit deletes for the small amz_user / amz_product id bands')
    parser.add_argument('--allow-destructive', action='store_true', help='required for --mode truncate')
    parser.add_argument('--out', help='cleanup.sql path (default: <dataset>/cleanup.sql)')
    parser.add_argument('--report', help='report json path (default: <dataset>/cleanup-report.json)')
    parser.add_argument('--quiet', action='store_true')
    args = parser.parse_args(argv)

    dataset = args.dataset or os.path.join('out', args.tier)
    if not os.path.isabs(dataset):
        dataset = os.path.join(HERE, dataset)
    manifest_path = os.path.join(dataset, 'manifest.json')
    if not os.path.isfile(manifest_path):
        print('[purge] FAIL: no manifest at %s - generate the dataset first' % manifest_path)
        return 2

    manifest, plans = analyse(dataset)
    body, skipped = render_cleanup(manifest, plans, args.mode, args.allow_low_band, args.allow_destructive)
    if body is None:
        print('[purge] FAIL: %s' % skipped)
        return 2

    report = {
        'tool_version': TOOL_VERSION,
        'dataset': os.path.relpath(dataset, HERE),
        'dataset_id': manifest['dataset_id'],
        'seed': manifest['seed'],
        'tier': manifest['tier'],
        'generated_at': manifest.get('generated_at'),
        'mode': args.mode,
        'rows_total': manifest['totals']['rows'],
        'tables': plans,
        'skipped': [{'table': p['table'], 'status': p['status']} for p in (skipped or [])],
        'verify_queries': [
            "SELECT COUNT(*) FROM `%s`.`%s` WHERE `%s` BETWEEN %d AND %d;" % (
                p['database'], p['name'], p['primary_key'][0], p['min'], p['max'])
            for p in plans if p['status'] in ('OK', 'LOW_BAND_RESERVED') and p['min'] is not None
        ],
        'registry_query': "SELECT dataset_id, seed, tier, loaded_at, `rows`, is_demo FROM `%s`.`%s`;"
                          % (REGISTRY_DB, REGISTRY_TABLE),
    }
    report_path = args.report or os.path.join(dataset, 'cleanup-report.json')
    with open(report_path, 'w', encoding='utf-8') as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2, sort_keys=True)

    cleanup_path = args.out or os.path.join(dataset, 'cleanup.sql')
    if args.emit:
        with open(cleanup_path, 'w', encoding='utf-8') as handle:
            handle.write(body)
        if args.registry:
            registry_path = os.path.join(dataset, 'registry.sql')
            with open(registry_path, 'w', encoding='utf-8') as handle:
                handle.write(render_registry(manifest, plans))
            if not args.quiet:
                print('[purge] wrote %s' % registry_path)

    if not args.quiet:
        covered = [p for p in plans if p['status'] in ('OK', 'LOW_BAND_RESERVED')
                   or (p['status'] == 'LOW_BAND_UNKNOWN' and args.allow_low_band)]
        print('[purge] dataset=%s tier=%s rows=%d tables=%d' % (
            os.path.relpath(dataset, HERE), manifest['tier'], manifest['totals']['rows'], len(plans)))
        print('[purge] mode=%s covered=%d skipped=%d' % (args.mode, len(covered), len(report['skipped'])))
        for item in report['skipped']:
            print('[purge]   SKIP %s (%s)' % (item['table'], item['status']))
        print('[purge] report=%s' % report_path)
        if args.emit:
            print('[purge] cleanup=%s' % cleanup_path)
        else:
            print('[purge] dry run: pass --emit to write cleanup.sql')
    return 1 if report['skipped'] else 0


if __name__ == '__main__':
    sys.exit(main())
