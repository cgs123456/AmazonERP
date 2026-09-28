# -*- coding: utf-8 -*-
"""Regression tests for the synthetic dataset cleanup / registry tool (purge.py)."""

from __future__ import annotations

import json
import os
import shutil
import tempfile
import unittest

import purge


def _write_dataset(root, tables):
    """tables: list of (db, name, rows) where rows is a list of dicts."""
    os.makedirs(os.path.join(root, 'sql'), exist_ok=True)
    os.makedirs(os.path.join(root, 'jsonl'), exist_ok=True)
    entries = []
    total = 0
    for index, (db, name, values) in enumerate(tables, start=1):
        rel = 'jsonl/%02d-%s.%s.jsonl' % (index, db, name)
        with open(os.path.join(root, rel), 'w', encoding='utf-8') as handle:
            for value in values:
                if isinstance(value, dict):
                    handle.write(json.dumps(value) + '\n')
                else:
                    handle.write(json.dumps({'id': value}) + '\n')
        entries.append({
            'database': db,
            'name': name,
            'rows': len(values),
            'jsonl': rel,
            'sql': 'sql/%02d-%s.%s.sql' % (index, db, name),
            'columns': ['id'],
        })
        total += len(values)
    manifest = {
        'dataset_id': 'unit-test-dataset',
        'seed': 1,
        'tier': 'ci',
        'generated_at': '2026-09-27T00:00:00Z',
        'totals': {'rows': total},
        'tables': entries,
    }
    with open(os.path.join(root, 'manifest.json'), 'w', encoding='utf-8') as handle:
        json.dump(manifest, handle)
    return manifest


class PurgeTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix='syn-purge-')
        self.addCleanup(shutil.rmtree, self.root, True)

    def _run(self, argv):
        return purge.main(argv)

    def test_ranges_cover_the_dataset_and_nothing_more(self):
        # amz_order sits in the reserved big band, amz_user in the reserved small band,
        # and the third table uses ids outside every reserved band -> must be refused.
        _write_dataset(self.root, [
            ('amz_order', 'amz_order', [900000000000700000 + i for i in range(10)]),
            ('amz_user', 'amz_user', [100000001 + i for i in range(3)]),
            ('amz_product', 'amz_product', [500 + i for i in range(4)]),
        ])
        code = self._run(['--dataset', self.root, '--emit'])
        self.assertEqual(1, code, 'unknown low band must not be emitted by default')

        with open(os.path.join(self.root, 'cleanup-report.json'), encoding='utf-8') as handle:
            report = json.load(handle)
        by_table = {p['table']: p for p in report['tables']}
        self.assertEqual('OK', by_table['amz_order.amz_order']['status'])
        self.assertEqual('LOW_BAND_RESERVED', by_table['amz_user.amz_user']['status'])
        self.assertEqual('LOW_BAND_UNKNOWN', by_table['amz_product.amz_product']['status'])
        self.assertEqual([{'table': 'amz_product.amz_product', 'status': 'LOW_BAND_UNKNOWN'}], report['skipped'])

        body = open(os.path.join(self.root, 'cleanup.sql'), encoding='utf-8').read()
        self.assertIn('BETWEEN 900000000000700000 AND 900000000000700009', body)
        self.assertIn('BETWEEN 100000001 AND 100000003', body)
        self.assertNotIn('BETWEEN 500 AND 503', body)

        # the emitted ranges must not cover a single id the dataset did not produce
        for plan in report['tables']:
            if not plan['ranges']:
                continue
            width = sum(end - start + 1 for start, end in plan['ranges'])
            self.assertEqual(plan['distinct_ids'], width)

        # with --allow-low-band every table is covered and the run succeeds
        code = self._run(['--dataset', self.root, '--emit', '--allow-low-band'])
        self.assertEqual(0, code)
        body = open(os.path.join(self.root, 'cleanup.sql'), encoding='utf-8').read()
        self.assertIn('BETWEEN 500 AND 503', body)

    def test_marker_guard_is_derived_from_the_generated_values(self):
        """The guard used to be a hard coded ``source = 'SYNTHETIC'``, which deleted
        nothing because the generator writes SYN-<column>-000000.  It must be derived
        from the data, and omitted when the values share no usable prefix."""
        _write_dataset(self.root, [
            ('amz_customer', 'amz_email_task', [
                {'id': 900000000820000000 + i, 'source': 'SYN-source-%06d' % i} for i in range(3)]),
            ('amz_customer', 'amz_customer_ticket', [
                {'id': 900000000819000000 + i, 'source': 'x%d' % (i * 7)} for i in range(3)]),
        ])
        self.assertEqual(0, self._run(['--dataset', self.root, '--emit', '--quiet']))
        body = open(os.path.join(self.root, 'cleanup.sql'), encoding='utf-8').read()
        self.assertIn("`source` LIKE 'SYN-source-", body)
        self.assertNotIn("= 'SYNTHETIC'", body)
        # no usable common prefix -> no guard at all, the id band alone is used
        ticket_line = [line for line in body.split('\n') if 'amz_customer_ticket' in line
                       and line.startswith('DELETE')][0]
        self.assertNotIn('LIKE', ticket_line)

    def test_registry_marks_the_database_as_demo(self):
        _write_dataset(self.root, [('amz_order', 'amz_order', [900000000000700000])])
        self.assertEqual(0, self._run(['--dataset', self.root, '--emit', '--registry']))
        sql = open(os.path.join(self.root, 'registry.sql'), encoding='utf-8').read()
        self.assertIn('amz_synthetic_dataset_registry', sql)
        self.assertIn('is_demo', sql)
        self.assertIn('unit-test-dataset', sql)
        self.assertIn('ON DUPLICATE KEY UPDATE', sql, 'reload must refresh the row, not duplicate it')
        # regression: the unique key is (dataset_id, seed) without tier, so a reload
        # of another tier updates the existing row; tier must be refreshed with it or
        # the audit row records the old tier next to the new row count.
        self.assertIn('`tier` = new.`tier`', sql,
                      'reload must refresh tier, not leave the previous tier in place')
        self.assertIn('`is_demo` = new.`is_demo`', sql)
        # MySQL 8.0.19+ row alias syntax; VALUES() is deprecated (warning 1287)
        self.assertIn('AS new', sql)
        self.assertNotIn('VALUES(`tier`)', sql)

    def test_truncate_mode_is_destructive_and_refused_by_default(self):
        _write_dataset(self.root, [('amz_order', 'amz_order', [900000000000700000])])
        self.assertEqual(2, self._run(['--dataset', self.root, '--emit', '--mode', 'truncate']))
        self.assertEqual(0, self._run(['--dataset', self.root, '--emit', '--mode', 'truncate',
                                       '--allow-destructive']))
        body = open(os.path.join(self.root, 'cleanup.sql'), encoding='utf-8').read()
        self.assertIn('TRUNCATE TABLE `amz_order`', body)

    def test_missing_manifest_is_fail_closed(self):
        self.assertEqual(2, self._run(['--dataset', os.path.join(self.root, 'nope')]))


if __name__ == '__main__':
    unittest.main()
