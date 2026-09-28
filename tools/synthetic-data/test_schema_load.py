# -*- coding: utf-8 -*-
"""Unit tests for verify_schema_load.py.

The tool currently passes on both generated datasets, which is only meaningful if it
can also fail.  These tests build a tiny throw-away schema/dataset pair and assert that
every MySQL-8-strict-mode violation the tool claims to detect is really detected, and
that a clean dataset still passes.
"""

from __future__ import annotations

import io
import json
import os
import shutil
import tempfile
import unittest
from contextlib import redirect_stdout

import verify_schema_load


def column(name, typ, args='', nullable=True, default=None, auto_increment=False,
           generated=False, inline_pk=False, inline_unique=False, unsigned=False):
    return {
        'name': name, 'type': typ, 'args': args, 'nullable': nullable, 'default': default,
        'auto_increment': auto_increment, 'generated': generated,
        'inline_primary_key': inline_pk, 'inline_unique': inline_unique, 'unsigned': unsigned,
        'on_update': False, 'comment': None, 'sources': ['test'], 'conflicts': [],
        'conditional': False,
    }


COLUMNS = [
    column('id', 'BIGINT', inline_pk=True, auto_increment=True),
    column('name', 'VARCHAR', '10', nullable=False),
    column('amount', 'DECIMAL', '5,2'),
    column('flag', 'TINYINT'),
    column('payload', 'JSON'),
    column('created', 'DATETIME'),
    column('code', 'VARCHAR', '8', inline_unique=False),
]

TABLE = {
    'key': 't_db.t_demo', 'name': 't_demo', 'database': 't_db', 'columns': COLUMNS,
    'primary_key': ['id'], 'unique_keys': [{'name': 'uk_code', 'columns': ['code']}],
    'indexes': [{'name': 'idx_name', 'columns': ['name']}],
    'foreign_keys': [], 'definitions': [], 'charset': 'utf8mb4', 'engine': 'InnoDB',
    'comment': None, 'conditional_columns': [], 'conflicting_columns': [],
}

GOOD_ROW = {
    'id': 1, 'name': 'abcdef', 'amount': '12.34', 'flag': 3,
    'payload': '{"a": 1}', 'created': '2026-01-02 03:04:05', 'code': 'A1',
}


def make_dataset(root, rows):
    os.makedirs(os.path.join(root, 'jsonl'), exist_ok=True)
    names = [c['name'] for c in COLUMNS]
    path = os.path.join(root, 'jsonl', 't_db.t_demo.jsonl')
    with io.open(path, 'w', encoding='utf-8') as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + '\n')
    manifest = {
        'tier': 'unit', 'dataset_id': 'unit-test', 'seed': 1, 'tables': [{
            'name': 't_demo', 'table': 't_db.t_demo', 'database': 't_db',
            'columns': names, 'rows': len(rows), 'jsonl': 'jsonl/t_db.t_demo.jsonl',
            'sql': 'sql/t_db.t_demo.sql', 'jsonl_bytes': 0, 'sql_bytes': 0,
        }],
    }
    with io.open(os.path.join(root, 'manifest.json'), 'w', encoding='utf-8') as handle:
        json.dump(manifest, handle)
    snapshot = {'tool_version': 'unit', 'tables': [TABLE], 'stats': {'views': []},
                'sources': [], 'alter_table': [], 'parse_issues': [], 'dynamic_ddl': []}
    snapshot_path = os.path.join(root, 'snapshot.json')
    with io.open(snapshot_path, 'w', encoding='utf-8') as handle:
        json.dump(snapshot, handle)
    return snapshot_path


def run(rows):
    root = tempfile.mkdtemp(prefix='schema-load-test-')
    try:
        snapshot_path = make_dataset(root, rows)
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = verify_schema_load.main([
                '--dataset', root, '--snapshot', snapshot_path, '--max-errors', '50'])
        return code, buffer.getvalue()
    finally:
        shutil.rmtree(root, ignore_errors=True)


class SchemaLoadChecks(unittest.TestCase):

    def test_clean_dataset_passes(self):
        code, output = run([GOOD_ROW, dict(GOOD_ROW, id=2, code='A2')])
        self.assertEqual(code, 0, output)
        self.assertIn('2/2 rows loaded', output)

    def test_text_length_is_detected(self):
        code, output = run([dict(GOOD_ROW, name='x' * 40)])
        self.assertEqual(code, 1, output)
        self.assertIn('text-length', output)

    def test_integer_range_is_detected(self):
        code, output = run([dict(GOOD_ROW, flag=500)])
        self.assertEqual(code, 1, output)
        self.assertIn('integer-range', output)

    def test_not_null_is_detected(self):
        code, output = run([dict(GOOD_ROW, name=None)])
        self.assertEqual(code, 1, output)
        self.assertIn('not-null', output)

    def test_invalid_json_is_detected(self):
        code, output = run([dict(GOOD_ROW, payload='{not json')])
        self.assertEqual(code, 1, output)
        self.assertIn('json-invalid', output)

    def test_decimal_magnitude_is_detected(self):
        code, output = run([dict(GOOD_ROW, amount='99999.99')])
        self.assertEqual(code, 1, output)
        self.assertIn('decimal-range', output)

    def test_invalid_calendar_date_is_detected(self):
        code, output = run([dict(GOOD_ROW, created='2026-02-30 10:00:00')])
        self.assertEqual(code, 1, output)
        self.assertIn('date-invalid', output)

    def test_duplicate_primary_key_is_rejected_by_the_schema(self):
        code, output = run([GOOD_ROW, GOOD_ROW])
        self.assertEqual(code, 1, output)
        self.assertIn('rejected by the schema', output)

    def test_duplicate_unique_key_is_rejected_by_the_schema(self):
        code, output = run([GOOD_ROW, dict(GOOD_ROW, id=2)])
        self.assertEqual(code, 1, output)
        self.assertIn('rejected by the schema', output)

    def test_missing_not_null_column_is_reported(self):
        root = tempfile.mkdtemp(prefix='schema-load-test-')
        try:
            snapshot_path = make_dataset(root, [GOOD_ROW])
            with io.open(os.path.join(root, 'manifest.json'), encoding='utf-8') as handle:
                manifest = json.load(handle)
            manifest['tables'][0]['columns'] = [c['name'] for c in COLUMNS if c['name'] != 'name']
            with io.open(os.path.join(root, 'manifest.json'), 'w', encoding='utf-8') as handle:
                json.dump(manifest, handle)
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = verify_schema_load.main([
                    '--dataset', root, '--snapshot', snapshot_path])
            self.assertEqual(code, 1, buffer.getvalue())
            self.assertIn('NOT NULL columns without default are never written', buffer.getvalue())
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_unknown_column_is_reported(self):
        root = tempfile.mkdtemp(prefix='schema-load-test-')
        try:
            snapshot_path = make_dataset(root, [GOOD_ROW])
            with io.open(os.path.join(root, 'manifest.json'), encoding='utf-8') as handle:
                manifest = json.load(handle)
            manifest['tables'][0]['columns'] = manifest['tables'][0]['columns'] + ['ghost_column']
            with io.open(os.path.join(root, 'manifest.json'), 'w', encoding='utf-8') as handle:
                json.dump(manifest, handle)
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = verify_schema_load.main([
                    '--dataset', root, '--snapshot', snapshot_path])
            self.assertEqual(code, 1, buffer.getvalue())
            self.assertIn('ghost_column', buffer.getvalue())
        finally:
            shutil.rmtree(root, ignore_errors=True)


if __name__ == '__main__':
    unittest.main()
