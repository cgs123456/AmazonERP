# -*- coding: utf-8 -*-
"""Regression tests for the fail-closed DDL parser."""

from __future__ import annotations

import os
import tempfile
import unittest

import ddl_parser


class ParseSqlFileTest(unittest.TestCase):
    def _parse(self, sql: str):
        handle = tempfile.NamedTemporaryFile('w', suffix='.sql', delete=False, encoding='utf-8')
        try:
            handle.write(sql)
            handle.close()
            return ddl_parser.parse_sql_file(handle.name, 'test')
        finally:
            if not handle.closed:
                handle.close()
            os.unlink(handle.name)

    def test_create_index_is_classified_and_not_a_parse_issue(self):
        tables, alters, stats, issues = self._parse(
            'CREATE TABLE t (id BIGINT PRIMARY KEY, status VARCHAR(16));\n'
            'CREATE INDEX idx_t_status ON t (status);\n'
        )
        self.assertEqual(1, len(tables))
        self.assertEqual([], alters)
        self.assertEqual([], issues)
        self.assertEqual(1, stats['tables'])

    def test_unexpected_create_is_still_fail_closed(self):
        tables, alters, stats, issues = self._parse('CREATE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW SET @x = 1;')
        self.assertEqual([], tables)
        self.assertEqual([], alters)
        self.assertEqual(1, len(issues))
        self.assertEqual('non-table-ddl', issues[0]['kind'])


if __name__ == '__main__':
    unittest.main()