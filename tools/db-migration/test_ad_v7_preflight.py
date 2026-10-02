# -*- coding: utf-8 -*-
"""Regression tests for the V7 preflight gate (ad_v7_preflight.py).

These tests never touch a database: they drive ``run_preflight`` with a stub
client, so the gate logic itself is covered on every CI run without MySQL.
"""

from __future__ import annotations

import os
import re
import sys
import unittest
from pathlib import Path

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


V7_SQL_PATH = (Path(__file__).resolve().parents[2] / 'amz-service' / 'amz-service-ad'
               / 'src' / 'main' / 'resources' / 'db' / 'migration'
               / 'V7__ad_business_uniqueness.sql')

# 只吃 ``UPDATE <表> SET ...``。V7 的合并语句写成 ``UPDATE <表> AS keeper``，
# 因为中间隔了别名，天然落在匹配外面。
_NORMALIZE_UPDATE = re.compile(r'UPDATE\s+(amz_ad_\w+)\s+SET\s+(.+?)\s*;', re.I | re.S)
_IDENTIFIER = re.compile(r'[a-z_][a-z_0-9]*', re.I)
_NON_COLUMN_WORDS = {'lower', 'upper', 'trim', 'coalesce', 'nullif', 'case', 'when',
                     'then', 'else', 'end', 'is', 'not', 'null', 'or', 'and'}


def _canon(expression):
    return ' '.join(expression.split())


def _split_top_level(clause):
    """按顶层逗号切分；括号里的逗号（如 COALESCE 的参数）不切。"""
    parts, buffer, depth = [], [], 0
    for char in clause:
        if char == '(':
            depth += 1
        elif char == ')':
            depth -= 1
        if char == ',' and depth == 0:
            parts.append(''.join(buffer))
            buffer = []
        else:
            buffer.append(char)
    parts.append(''.join(buffer))
    return [item.strip() for item in parts if item.strip()]


def _v7_normalizations(sql):
    """从 V7 第一步解析出 {表: {列: 规范化表达式}}。

    右值里没有标识符的赋值（``campaign_id = ''``）是 NULL 预处理，不是键的规范化。
    """
    found = {}
    for table, clause in _NORMALIZE_UPDATE.findall(sql):
        clause = re.split(r'\bWHERE\b', clause, maxsplit=1, flags=re.I)[0]
        for item in _split_top_level(clause):
            column, assigned, expression = item.partition('=')
            if not assigned or not _IDENTIFIER.search(expression):
                continue
            found.setdefault(table, {})[column.strip().lower()] = _canon(expression)
    return found


def _group_column(term):
    """分组项作用的列名：剥掉字面量后，第一个既不是函数名、后面也不带括号的标识符。"""
    stripped = re.sub(r"'[^']*'", ' ', term)
    for match in _IDENTIFIER.finditer(stripped):
        word = match.group(0)
        if word.lower() in _NON_COLUMN_WORDS:
            continue
        if stripped[match.end():].lstrip().startswith('('):
            continue
        return word.lower()
    raise AssertionError(u'无法从分组项认出列名：%s' % term)


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
        client = _healthy(**{'SELECT 1 FROM amz_ad_keyword GROUP BY': [('3',)]})
        report, _ = pf.run_preflight(client)
        self.assertTrue(any('重复组数 keyword: 3' in line for line in report))

    # 预检要给出可信的"会删多少行"，分组键就得与 V7 第一步的规范化逐列等价；
    # 历史上少写的正是 campaign_id 的 TRIM。期望值从迁移 SQL 现读，不手抄——
    # 手抄的话，改了 SQL 而预检没跟上时这条测试会跟着一起绿。
    KEYS_BY_TABLE = {
        'amz_ad_keyword': pf.KW_KEY,
        'amz_ad_converting_terms': pf.CT_KEY,
        'amz_ad_asin_keyword': pf.AK_KEY,
    }
    # V7 用两条语句完成的事（先 ``SET campaign_id = '' WHERE campaign_id IS NULL``
    # 再 ``TRIM(campaign_id)``），预检合写成一个 TRIM(COALESCE(...))：同一个函数。
    KEY_EQUIVALENCES = {
        ('amz_ad_converting_terms', 'campaign_id'): "TRIM(COALESCE(campaign_id, ''))",
    }
    # V7 规范化、但不属于业务键的载荷列（合并时由 COALESCE 带走）。
    PAYLOAD_COLUMNS = {('amz_ad_converting_terms', 'asin')}

    def test_group_keys_match_v7_step1_normalizations(self):
        norms = _v7_normalizations(V7_SQL_PATH.read_text(encoding='utf-8'))
        self.assertEqual(set(self.KEYS_BY_TABLE), set(norms),
                         u'没能从 V7 解析出三张目标表的规范化赋值（SQL 写法变了？'
                         u'解析失配会让本测试空跑）：%s' % sorted(norms))
        total = sum(len(columns) for columns in norms.values())
        self.assertGreaterEqual(total, 6, u'V7 规范化赋值解析过少，先确认解析器：%s' % norms)

        equivalences_used = set()
        for table, key in self.KEYS_BY_TABLE.items():
            normalized = norms[table]
            key_columns = set()
            for term in _split_top_level(key):
                column = _group_column(term)
                key_columns.add(column)
                actual = _canon(term)
                if column not in normalized:
                    self.assertEqual(column, actual,
                                     u'%s.%s 未被 V7 规范化，预检键却对它做了加工，'
                                     u'这会高估删除量。实际项：%s' % (table, column, term))
                    continue
                equivalent = self.KEY_EQUIVALENCES.get((table, column))
                if equivalent is not None and actual == _canon(equivalent):
                    equivalences_used.add((table, column))
                    continue
                self.assertEqual(
                    normalized[column], actual,
                    u'%s.%s：预检键与 V7 的规范化不一致（V7=%s / 预检=%s）。'
                    u'预检若少一个表达式，被该步折叠掉的重复行就漏算，'
                    u'"将物理删除约 N 行"变成低估。' % (table, column, normalized[column], term))
            unkeyed = set(normalized) - key_columns
            expected_unkeyed = {column for table_, column in self.PAYLOAD_COLUMNS
                                if table_ == table}
            self.assertEqual(expected_unkeyed, unkeyed,
                             u'%s：V7 规范化了这些列但预检键里没有，也不在载荷列清单里：%s'
                             % (table, sorted(unkeyed)))

        self.assertEqual(equivalences_used, set(self.KEY_EQUIVALENCES),
                         u'KEY_EQUIVALENCES 里有条目已经用不上了（V7 或预检改过），'
                         u'留着它会让下一个真实不一致被放过：%s'
                         % sorted(set(self.KEY_EQUIVALENCES) - equivalences_used))

    def test_deletion_estimate_uses_the_normalized_key(self):
        sql = pf._dup_deletes_sql(pf.KW_KEY, 'amz_ad_keyword')
        self.assertIn('TRIM(campaign_id)', sql)
        self.assertIn("UPPER(TRIM(COALESCE(NULLIF(match_type, ''), 'EXACT')))", sql)


if __name__ == '__main__':
    unittest.main()
