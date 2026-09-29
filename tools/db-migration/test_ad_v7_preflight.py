# -*- coding: utf-8 -*-
"""Regression tests for the V7 preflight gate (ad_v7_preflight.py).

These tests never touch a database: they drive ``run_preflight`` with a stub
client, so the gate logic itself is covered on every CI run without MySQL.
"""

from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ad_v7_preflight as pf


class FakeClient(object):
    """Returns canned rows for the first ``needle`` matched in the SQL."""

    def __init__(self, responses, default='0'):
        self.responses = responses
        self.default = default
        self.queries = []

    def rows(self, sql):
        self.queries.append(sql)
        for needle, value in self.responses:
            if needle in sql:
                return value
        return [(self.default,)]

    def scalar(self, sql):
        rows = self.rows(sql)
        return rows[0][0] if rows else None


def _healthy(**overrides):
    responses = [
        ('SELECT VERSION()', [('8.0.46',)]),
        ("table_name IN ('amz_ad_keyword'", [('amz_ad_keyword',), ('amz_ad_converting_terms',),
                                             ('amz_ad_asin_keyword',)]),
        ("table_name='flyway_schema_history'", [('1',)]),
        ('FROM flyway_schema_history', [('1', '1'), ('2', '1'), ('3', '1'),
                                        ('4', '1'), ('5', '1'), ('6', '1')]),
        ('CHECKSUM TABLE', [('amz_ad_keyword', '123'), ('amz_ad_converting_terms', '456'),
                            ('amz_ad_asin_keyword', '789')]),
        ('WHERE asin IS NULL', [('0',)]),
        ("asin IS NOT NULL AND TRIM(asin) = ''", [('0',)]),
    ]
    for needle, value in overrides.items():
        responses.insert(0, (needle, value))
    return FakeClient(responses)


def _codes(stops):
    return [code for code, _ in stops]


class PreflightTest(unittest.TestCase):

    def test_clean_database_has_no_stop(self):
        report, stops = pf.run_preflight(_healthy())
        self.assertEqual([], _codes(stops))
        self.assertTrue(any('MySQL 版本' in line for line in report))
        self.assertTrue(any('基线 checksum' in line for line in report))

    def test_non_mysql80_is_a_stop(self):
        _, stops = pf.run_preflight(_healthy(**{'SELECT VERSION()': [('5.7.44',)]}))
        self.assertIn('server-version', _codes(stops))

    def test_missing_tables_skip_data_checks(self):
        client = FakeClient([
            ('SELECT VERSION()', [('8.0.46',)]),
            ("table_name IN ('amz_ad_keyword'", [('amz_ad_keyword',)]),
            ("table_name='flyway_schema_history'", [('1',)]),
            ('FROM flyway_schema_history', [('1', '1'), ('2', '1'), ('3', '1'),
                                            ('4', '1'), ('5', '1'), ('6', '1')]),
        ])
        _, stops = pf.run_preflight(client)
        self.assertIn('missing-tables', _codes(stops))
        # 表都不全时不该继续发数据类查询
        self.assertFalse(any('CHECKSUM TABLE' in q for q in client.queries))

    def test_missing_flyway_history_is_a_stop(self):
        client = _healthy(**{"table_name='flyway_schema_history'": [('0',)]})
        _, stops = pf.run_preflight(client)
        self.assertIn('no-flyway-history', _codes(stops))

    def test_failed_history_row_is_a_stop(self):
        client = _healthy(**{'FROM flyway_schema_history': [('1', '1'), ('2', '0')]})
        _, stops = pf.run_preflight(client)
        self.assertIn('flyway-failed-history', _codes(stops))

    def test_v7_already_applied_is_a_stop(self):
        client = _healthy(**{'FROM flyway_schema_history': [('1', '1'), ('2', '1'), ('3', '1'),
                                                            ('4', '1'), ('5', '1'), ('6', '1'),
                                                            ('7', '1')]})
        _, stops = pf.run_preflight(client)
        self.assertIn('v7-already-applied', _codes(stops))

    def test_incomplete_v1_v6_is_a_stop(self):
        client = _healthy(**{'FROM flyway_schema_history': [('1', '1'), ('2', '1')]})
        _, stops = pf.run_preflight(client)
        self.assertIn('v1-v6-not-applied', _codes(stops))

    def test_normalized_empty_keyword_is_a_stop(self):
        needle = "FROM amz_ad_keyword WHERE LOWER(TRIM(COALESCE(keyword, ''))) = ''"
        client = _healthy(**{needle: [('3',)]})
        _, stops = pf.run_preflight(client)
        self.assertIn('keyword-empty', _codes(stops))

    def test_normalized_empty_search_term_is_a_stop(self):
        client = _healthy(**{'LOWER(TRIM(COALESCE(search_term, \'\')))': [('2',)]})
        _, stops = pf.run_preflight(client)
        self.assertIn('search-term-empty', _codes(stops))

    def test_null_and_blank_asin_are_reported_separately(self):
        client = _healthy(**{'WHERE asin IS NULL': [('5',)],
                             "asin IS NOT NULL AND TRIM(asin) = ''": [('1',)]})
        report, stops = pf.run_preflight(client)
        self.assertIn('asin-empty', _codes(stops))
        detail = [d for c, d in stops if c == 'asin-empty'][0]
        self.assertIn('NULL 5 行', detail)
        self.assertIn('空白 1 行', detail)
        self.assertTrue(any('NULL 5 / 空白 1' in line for line in report))

    def test_campaign_id_mixed_is_a_stop(self):
        client = _healthy(**{"WHEN TRIM(campaign_id) = '' THEN 'blank'": [('4',)]})
        _, stops = pf.run_preflight(client)
        self.assertIn('campaign-id-mixed', _codes(stops))

    def test_converting_terms_conflict_is_a_stop(self):
        client = _healthy(**{'COUNT(DISTINCT status) > 1': [('7',)]})
        _, stops = pf.run_preflight(client)
        self.assertIn('converting-conflict', _codes(stops))

    def test_keyword_value_conflict_is_a_stop(self):
        client = _healthy(**{"COALESCE(base_bid, '')": [('9',)]})
        _, stops = pf.run_preflight(client)
        self.assertIn('keyword-value-conflict', _codes(stops))

    def test_ambiguous_last_checked_is_a_stop(self):
        client = _healthy(**{'COUNT(DISTINCT last_checked)': [('2',)]})
        _, stops = pf.run_preflight(client)
        self.assertIn('asin-last-checked-ambiguous', _codes(stops))

    def test_duplicate_counts_are_reported_even_without_stop(self):
        client = _healthy(**{'GROUP BY shop_id, campaign_id, LOWER(TRIM(keyword))': [('3',)]})
        report, _ = pf.run_preflight(client)
        self.assertTrue(any('重复组数 keyword: 3' in line for line in report))


if __name__ == '__main__':
    unittest.main()
