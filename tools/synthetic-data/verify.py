# -*- coding: utf-8 -*-
"""Independent verifier for the AmazonERP synthetic dataset toolchain (spec 7.9).

Checks
------
1. structure   primary keys, unique keys and NOT NULL columns
2. references  weak referential integrity between generated tables (no FK exists in the DDL)
3. markers     free-text / credential columns are visibly synthetic
4. determinism two independent runs are byte-identical and carry no wall clock
5. manifest    row counts and sha256 digests match the files on disk
6. snapshot    the schema snapshot is still in sync with the repository DDL

The verifier never trusts the generator's own bookkeeping: it re-derives id universes from the
generated parent rows and re-hashes the files on disk.

Usage
-----
    python tools/synthetic-data/verify.py
    python tools/synthetic-data/verify.py --tier ci --orders 200
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile

TOOL_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(TOOL_DIR, '..', '..'))
sys.path.insert(0, TOOL_DIR)

import generate  # noqa: E402

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

# column name -> (parent table key, parent column) that must supply the value
FK_TARGETS = {
    'order_id': ('amz_order.amz_order', 'id'),
    'amazon_order_id': ('amz_order.amz_order', 'amazon_order_id'),
    'shop_id': ('amz_user.amz_shop', 'id'),
    'user_id': ('amz_user.amz_user', 'id'),
    'product_id': ('amz_product.amz_product', 'id'),
    'warehouse_id': ('amz_logistics.amz_warehouse', 'id'),
    'supplier_id': ('amz_procurement.amz_supplier', 'id'),
    'purchase_order_id': ('amz_procurement.amz_purchase_order', 'id'),
    'campaign_id': ('amz_ad.amz_ad_campaign', 'campaign_id'),
    'keyword_id': ('amz_ad.amz_ad_keyword', 'id'),
    'asin': ('amz_product.amz_product', 'asin'),
    'amazon_asin': ('amz_product.amz_product', 'asin'),
    'sku': ('amz_product.amz_product', 'sku'),
    'amazon_sku': ('amz_product.amz_product', 'sku'),
}

MARKER_NAME_SUFFIXES = ('_name', '_email', '_reason', '_remark', '_note', '_title', '_address',
                        '_description', '_comment', '_secret', '_token', '_password', '_message',
                        '_detail', '_address_line', '_text')
MARKER_EXACT = ('name', 'title', 'description', 'comment', 'remark', 'message', 'content', 'detail',
                'address', 'city', 'reason')
MARKER_SECRETS = ('token', 'secret', 'password', 'private_key', 'access_key', 'credential', 'signature')
MARKER_TEXT_TYPES = ('VARCHAR', 'CHAR', 'TINYTEXT', 'TEXT', 'MEDIUMTEXT', 'LONGTEXT', 'JSON')


def sha256_file(path):
    digest = hashlib.sha256()
    with io.open(path, 'rb') as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def build_generator(tier, orders=None, shops=None, marketplaces=None, include_conditional=False):
    snapshot = generate.load_snapshot()
    scale = generate.build_scale(tier, orders=orders, shops=shops, marketplaces=marketplaces)
    world = generate.World(scale, chaos=bool(generate.TIERS[tier]['chaos']))
    generator = generate.Generator(snapshot, world, tier, include_conditional=include_conditional)
    generator.generate()
    return snapshot, scale, generator


# --------------------------------------------------------------------------- #
# checks
# --------------------------------------------------------------------------- #
def check_structure(generator, snapshot):
    failures = []
    checked_rows = 0
    for key, rows in generator.rows.items():
        entry = snapshot['tables'][key]
        checked_rows += len(rows)
        if not rows:
            continue
        present = set(rows[0].keys())
        keys = list(entry['unique_keys'])
        if entry['primary_key']:
            keys.append({'name': '__primary_key', 'columns': list(entry['primary_key'])})
        for unique in keys:
            columns = [c for c in unique['columns'] if c in present]
            if len(columns) != len(unique['columns']):
                continue
            bucket = {}
            for position, row in enumerate(rows):
                signature = tuple(row[c] for c in columns)
                if signature in bucket:
                    failures.append('%s %s duplicate %r at rows %d and %d'
                                    % (key, unique['name'], signature, bucket[signature], position))
                bucket[signature] = position
        for column in entry['columns']:
            if column['name'] not in present or column['nullable']:
                continue
            for position, row in enumerate(rows):
                if row[column['name']] is None:
                    failures.append('%s.%s is NULL at row %d but the DDL says NOT NULL'
                                    % (key, column['name'], position))
                    break
    return failures, checked_rows


def reference_universes(generator):
    universes = {}
    wanted = set(FK_TARGETS.values())
    for table_key, column in wanted:
        rows = generator.rows.get(table_key)
        if rows is None:
            continue
        universes[(table_key, column)] = set(row[column] for row in rows)
    universes[(None, 'marketplace_id')] = set(entry[0] for entry in generate.MARKETPLACES)
    return universes


def check_references(generator, universes):
    failures = []
    columns_checked = 0
    for key, rows in generator.rows.items():
        if not rows:
            continue
        for column in rows[0].keys():
            if column == 'marketplace_id' or column == 'source_marketplace_id':
                target = (None, 'marketplace_id')
            else:
                target = FK_TARGETS.get(column)
            if target is None:
                continue
            universe = universes.get(target)
            if universe is None:
                failures.append('%s.%s references %s.%s but the parent has no rows'
                                % (key, column, target[0], target[1]))
                continue
            universe_text = set(str(value) for value in universe)
            columns_checked += 1
            bad = None
            for row in rows:
                value = row[column]
                if value is not None and str(value) not in universe_text:
                    bad = value
                    break
            if bad is not None:
                failures.append('%s.%s contains %r which is not in %s.%s (%d values)'
                                % (key, column, bad, target[0], target[1], len(universe)))
    return failures, columns_checked


def marker_kind(name):
    if name in MARKER_SECRETS or any(word in name for word in MARKER_SECRETS):
        return 'secret'
    if name.endswith('_url') or name in ('url', 'image_url', 'logo_url', 'label_url'):
        return 'url'
    if name.endswith('_json') or name.endswith('_data'):
        return 'json'
    if name in MARKER_EXACT or name.endswith(MARKER_NAME_SUFFIXES):
        return 'text'
    return None


def check_id_types(snapshot, generator):
    """Columns that reference a parent id but were declared with a different type.

    This is a property of the repository DDL, not of the generator: e.g. amz_order.id is BIGINT
    while amz_finance.amz_payment_collection.order_id is VARCHAR(64).  It is reported as a
    finding (P0-41 candidate) instead of failing the reference check.
    """
    findings = []
    for key, rows in generator.rows.items():
        if not rows:
            continue
        entry = snapshot['tables'][key]
        for name in rows[0].keys():
            if name in ('marketplace_id', 'source_marketplace_id'):
                continue
            target = FK_TARGETS.get(name)
            if target is None or key == target[0]:
                continue
            parent = snapshot['tables'][target[0]]['by_name'][target[1]]
            child = entry['by_name'][name]
            if parent['type'] == child['type']:
                continue
            findings.append('%s.%s is %s(%s) but %s.%s is %s(%s)' % (
                key, name, child['type'], child['args'], target[0], target[1],
                parent['type'], parent['args']))
    return findings


def check_markers(generator, snapshot):
    failures = []
    columns_checked = 0
    columns_skipped = 0
    for key, rows in generator.rows.items():
        if not rows:
            continue
        entry = snapshot['tables'][key]
        for column in rows[0].keys():
            kind = marker_kind(column)
            if kind is None:
                continue
            col = entry['by_name'].get(column)
            if col is not None and col['type'] not in MARKER_TEXT_TYPES:
                # e.g. amz_platform_account.token_expires_at is DATETIME: the name matches the
                # credential rule but the column can only hold a timestamp, so no text marker applies.
                columns_skipped += 1
                continue
            columns_checked += 1
            for row in rows:
                value = row[column]
                if value is None:
                    continue
                text = str(value)
                if kind == 'url':
                    ok = 'example.invalid' in text
                elif kind == 'secret':
                    ok = text.upper().startswith('SYNTHETIC')
                elif kind == 'json':
                    ok = 'synthetic' in text.lower()
                else:
                    ok = text.lower().startswith('syn')
                if not ok:
                    failures.append('%s.%s value %r is not marked as synthetic' % (key, column, text[:60]))
                    break
    return failures, columns_checked, columns_skipped


def check_determinism(tier, orders, shops, marketplaces, workdir):
    trees = []
    for run in ('run-a', 'run-b'):
        out_dir = os.path.join(workdir, run)
        with contextlib.redirect_stdout(io.StringIO()):
            code = generate.main(['--tier', tier, '--orders', str(orders), '--shops', str(shops),
                                  '--marketplaces', str(marketplaces), '--out', out_dir, '--quiet'])
        if code != 0:
            return ['generate.py exited with %d for %s' % (code, run)], 0
        trees.append(out_dir)
    first, second = trees
    files_a = sorted(collect_files(first))
    files_b = sorted(collect_files(second))
    failures = []
    if files_a != files_b:
        failures.append('file lists differ: %s vs %s' % (sorted(set(files_a) ^ set(files_b))[:5], ''))
    compared = 0
    for name in files_a:
        if name not in files_b:
            continue
        path_a = os.path.join(first, name)
        path_b = os.path.join(second, name)
        compared += 1
        if sha256_file(path_a) != sha256_file(path_b):
            failures.append('byte difference in %s across runs' % name)
    manifest_path = os.path.join(first, 'manifest.json')
    if os.path.isfile(manifest_path):
        with io.open(manifest_path, encoding='utf-8') as handle:
            manifest = json.load(handle)
        if manifest.get('generated_at') != generate.CLOCK_UTC:
            failures.append('manifest generated_at %r is not the fixed clock %r'
                            % (manifest.get('generated_at'), generate.CLOCK_UTC))
    return failures, compared


def collect_files(root):
    out = []
    for current, _dirs, names in os.walk(root):
        for name in names:
            out.append(os.path.relpath(os.path.join(current, name), root).replace('\\', '/'))
    return out


def check_manifest(out_dir):
    manifest_path = os.path.join(out_dir, 'manifest.json')
    if not os.path.isfile(manifest_path):
        return ['no manifest at %s' % manifest_path], 0
    with io.open(manifest_path, encoding='utf-8') as handle:
        manifest = json.load(handle)
    failures = []
    files_checked = 0
    for table in manifest['tables']:
        for field, hash_field in (('sql', 'sql_sha256'), ('jsonl', 'jsonl_sha256')):
            path = os.path.join(out_dir, table[field])
            if not os.path.isfile(path):
                failures.append('missing file %s' % table[field])
                continue
            files_checked += 1
            if sha256_file(path) != table[hash_field]:
                failures.append('%s does not match %s' % (table[field], hash_field))
        jsonl_path = os.path.join(out_dir, table['jsonl'])
        if os.path.isfile(jsonl_path):
            with io.open(jsonl_path, encoding='utf-8') as handle:
                lines = [line for line in handle.read().splitlines() if line.strip()]
            if len(lines) != table['rows']:
                failures.append('%s has %d jsonl lines but the manifest says %d rows'
                                % (table['jsonl'], len(lines), table['rows']))
            for line in lines[:50]:
                json.loads(line)
    declared = sum(table['rows'] for table in manifest['tables'])
    if declared != manifest['totals']['rows']:
        failures.append('totals.rows=%d but table rows sum to %d'
                        % (manifest['totals']['rows'], declared))
    return failures, files_checked


def check_snapshot():
    script = os.path.join(TOOL_DIR, 'snapshot_schema.py')
    result = subprocess.run([sys.executable, script, '--check'], cwd=REPO_ROOT,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = result.stdout.decode('utf-8', 'replace').strip()
    return result.returncode == 0, output


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #
def parse_args(argv):
    parser = argparse.ArgumentParser(description='verify the synthetic dataset toolchain')
    parser.add_argument('--tier', default='ci', choices=sorted(generate.TIERS))
    parser.add_argument('--orders', type=int, default=200)
    parser.add_argument('--shops', type=int, default=1)
    parser.add_argument('--marketplaces', type=int, default=1)
    parser.add_argument('--dataset', help='also verify an existing dataset directory (manifest check)')
    parser.add_argument('--skip-determinism', action='store_true')
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    failures = []
    print('[verify] tier=%s orders=%d shops=%d marketplaces=%d' % (
        args.tier, args.orders, args.shops, args.marketplaces))

    snapshot, scale, generator = build_generator(args.tier, args.orders, args.shops, args.marketplaces)
    rows = sum(len(value) for value in generator.rows.values())
    print('[verify] generated %d tables, %d rows in memory' % (len(generator.rows), rows))

    structure, checked_rows = check_structure(generator, snapshot)
    failures.extend(structure)
    print('[verify] structure  %s (%d rows, %d violations)' % (
        'FAIL' if structure else 'OK', checked_rows, len(structure)))

    universes = reference_universes(generator)
    references, columns_checked = check_references(generator, universes)
    failures.extend(references)
    print('[verify] references %s (%d pooled columns, %d universes, %d violations)' % (
        'FAIL' if references else 'OK', columns_checked, len(universes), len(references)))

    id_types = check_id_types(snapshot, generator)
    print('[verify] id types   %s (%d columns reference a parent id with a different DDL type)'
          % ('FINDING' if id_types else 'OK', len(id_types)))
    for finding in id_types[:5]:
        print('[verify]   finding: %s' % finding)
    if len(id_types) > 5:
        print('[verify]   ... %d more' % (len(id_types) - 5))

    markers, marker_columns, markers_skipped = check_markers(generator, snapshot)
    failures.extend(markers)
    print('[verify] markers    %s (%d marker columns, %d violations, %d name/type conflicts skipped)' % (
        'FAIL' if markers else 'OK', marker_columns, len(markers), markers_skipped))

    conditional = sorted(k for k, v in generator.skipped_conditional.items() if v)
    print('[verify] conditional columns excluded by default: %s' % (
        ', '.join('%s(%s)' % (k, ','.join(generator.skipped_conditional[k])) for k in conditional) or 'none'))

    if not args.skip_determinism:
        with tempfile.TemporaryDirectory(prefix='amz-synth-determinism-') as workdir:
            determinism, compared = check_determinism(args.tier, args.orders, args.shops,
                                                      args.marketplaces, workdir)
        failures.extend(determinism)
        print('[verify] determinism %s (%d files byte-identical across 2 runs)' % (
            'FAIL' if determinism else 'OK', compared))

    if args.dataset:
        manifest_failures, files_checked = check_manifest(args.dataset)
        failures.extend(manifest_failures)
        print('[verify] manifest   %s (%d files re-hashed)' % (
            'FAIL' if manifest_failures else 'OK', files_checked))

    snapshot_ok, snapshot_output = check_snapshot()
    if not snapshot_ok:
        failures.append('snapshot_schema.py --check failed: %s' % snapshot_output)
    print('[verify] snapshot   %s (%s)' % ('OK' if snapshot_ok else 'FAIL', snapshot_output))

    for failure in failures[:40]:
        print('[verify] FAILURE: %s' % failure)
    if len(failures) > 40:
        print('[verify] ... %d more failures' % (len(failures) - 40))
    print('[verify] %s' % ('PASS' if not failures else 'FAILED (%d)' % len(failures)))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())