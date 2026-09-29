# -*- coding: utf-8 -*-
"""amz-service-ad V7（业务唯一键迁移）可执行预检。

为什么要有这个脚本
------------------
``docs/superpowers/runbooks/ad-business-uniqueness-migration.md`` 第 3 节写清了
预检 SQL 和 STOP 条件，但它们是**文档里的 SQL**：要靠人肉复制粘贴执行、靠人肉判断
"有没有命中 STOP"。V7 是破坏性归并（UPDATE 规范化 + DELETE 旧重复行 + 加唯一键），
旧重复行删掉后不可恢复，所以预检必须是一条**会自己给出退出码**的命令，能直接串进
发布流程：命中任一 STOP 就退出非 0，发布流程随之中断。

本脚本只做只读查询（SELECT / CHECKSUM TABLE / information_schema），
**不执行 V7、不修改任何数据**。

退出码
------
* ``0`` —— 未命中任何 STOP，可以进入备份与执行阶段（Runbook §4/§5）。
* ``2`` —— 命中至少一个 STOP。**不得执行 V7**。
* ``1`` —— 脚本自身出错（连不上库、SQL 执行失败等）；同样不得执行 V7。

STOP 条件与 Runbook 的对应关系
------------------------------
==========  ================================================================
code        来源
==========  ================================================================
server-version            §2.1 目标必须是 MySQL 8.0
missing-tables            §2.1 三张表必须存在
no-flyway-history         §2.1 必须已由 Flyway 执行 V1-V6
flyway-failed-history     §2.1 / §5 历史里不能有 success=0
v7-already-applied        §2.1 重复执行没有意义，且说明状态与预期不符
v1-v6-not-applied         §2.1 V7 依赖 V1-V6
keyword-empty             §3.2 amz_ad_keyword.keyword 规范化后为空
search-term-empty         §3.2 amz_ad_converting_terms.search_term 规范化后为空
asin-empty                §3.2 amz_ad_asin_keyword.asin 规范化后为空
asin-keyword-empty        §3.2 amz_ad_asin_keyword.keyword 规范化后为空
campaign-id-mixed         §3.2 同键组内 NULL/空白/非空白 campaign_id 语义混杂
converting-conflict       §3.2 同键组内 status / is_added_to_keyword 冲突
keyword-value-conflict    §3.2 同键组内 bid / base_bid / state 没有明确责任行
asin-last-checked-ambiguous §3.2 同键组内 last_checked 为空或不唯一
==========  ================================================================

用法
----
    python ad_v7_preflight.py --host 127.0.0.1 --port 3306 --user root \\
        --database amz_ad

密码从 ``--password`` 或环境变量 ``MYSQL_PWD`` 读取（推荐后者：不出现在进程列表里），
与 ``tools/synthetic-data/apply_migrations.py`` 保持一致。
``--mysql-path`` 用于指定 mysql 客户端（默认 PATH 里的 ``mysql``）。

边界
----
本脚本证明的是"预检这条命令能跑、能命中 STOP"，**不**证明 V7 对你的数据做了正确的事。
空库跑出 rc=0 只是"没有触发任何 STOP 条件"，不是"生产可以无脑执行"。
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys

REQUIRED_TABLES = ('amz_ad_keyword', 'amz_ad_converting_terms', 'amz_ad_asin_keyword')
REQUIRED_VERSIONS = ('1', '2', '3', '4', '5', '6')
TARGET_VERSION = '7'

# 业务唯一键的规范化表达式，必须与 V7__ad_business_uniqueness.sql 完全一致，
# 否则预检判定的"重复组"与 V7 真正会归并的组不是同一批。
KW_KEY = ("shop_id, campaign_id, LOWER(TRIM(keyword)), "
          "UPPER(TRIM(COALESCE(NULLIF(match_type, ''), 'EXACT')))")
CT_KEY = "shop_id, TRIM(COALESCE(campaign_id, '')), LOWER(TRIM(search_term))"
AK_KEY = "shop_id, UPPER(TRIM(asin)), LOWER(TRIM(keyword))"


class MysqlError(RuntimeError):
    """mysql 客户端执行失败。"""


class MysqlClient(object):
    """用 mysql 客户端做只读查询（与 apply_migrations.py 同一套做法）。"""

    def __init__(self, path, host, port, user, password, database):
        self.base = [path, '--protocol=TCP', '-h', host, '-P', str(port), '-u', user,
                     '--default-character-set=utf8mb4', '--database=' + database]
        self.env = dict(os.environ)
        if password is not None:
            self.env['MYSQL_PWD'] = password

    def rows(self, sql):
        code, out, err = self._run(sql)
        if code != 0:
            raise MysqlError('SQL 执行失败（退出码 %d）：%s%s' % (code, err.strip(), sql))
        return [tuple(line.split('\t')) for line in out.splitlines() if line != '']

    def scalar(self, sql):
        rows = self.rows(sql)
        return rows[0][0] if rows else None

    def _run(self, sql):
        proc = subprocess.run(self.base + ['-N', '-B', '-e', sql],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              env=self.env)
        return (proc.returncode,
                proc.stdout.decode('utf-8', 'replace'),
                proc.stderr.decode('utf-8', 'replace'))


def _dup_groups_sql(key, table):
    return ("SELECT COUNT(*) FROM (SELECT 1 FROM %s GROUP BY %s HAVING COUNT(*) > 1) d"
            % (table, key))


def _dup_deletes_sql(key, table):
    return ("SELECT COALESCE(SUM(cnt - 1), 0) FROM "
            "(SELECT COUNT(*) AS cnt FROM %s GROUP BY %s HAVING COUNT(*) > 1) d"
            % (table, key))


def run_preflight(client):
    """执行全部预检；返回 (report_lines, stops)。

    ``stops`` 是 ``(code, detail)`` 列表。为空即表示未命中任何 STOP。
    """
    report = []
    stops = []

    def stop(code, detail):
        stops.append((code, detail))

    version = client.scalar('SELECT VERSION()')
    report.append('MySQL 版本: %s' % version)
    if not str(version).startswith('8.0'):
        stop('server-version', 'Runbook §2.1 要求 MySQL 8.0，实际为 %s' % version)

    found = set(row[0] for row in client.rows(
        "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() "
        "AND table_name IN ('amz_ad_keyword', 'amz_ad_converting_terms', "
        "'amz_ad_asin_keyword')"))
    missing = [name for name in REQUIRED_TABLES if name not in found]
    report.append('目标表: %s' % (', '.join(REQUIRED_TABLES),))
    if missing:
        stop('missing-tables', '缺少表：%s' % ', '.join(missing))

    has_history = client.scalar(
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
        "AND table_name='flyway_schema_history'")
    if str(has_history) != '1':
        stop('no-flyway-history',
             '库内没有 flyway_schema_history，无法确认 V1-V6 已执行；'
             '若库由裸 SQL 建立，请先补 baseline 行再预检')
        report.append('Flyway 历史: 缺失')
    else:
        applied, failed = set(), []
        for row in client.rows(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"):
            if str(row[1]) == '1':
                applied.add(str(row[0]))
            else:
                failed.append(str(row[0]))
        report.append('Flyway 已成功版本: %s' % (', '.join(sorted(applied)) or '(无)'))
        if failed:
            stop('flyway-failed-history', '存在 success=0 的历史行，版本：%s'
                 % ', '.join(failed))
        if TARGET_VERSION in applied:
            stop('v7-already-applied',
                 'V7 已执行过（success=1）。重复执行无意义，请确认变更单与实际状态一致')
        pending = [v for v in REQUIRED_VERSIONS if v not in applied]
        if pending:
            stop('v1-v6-not-applied', '尚未成功执行的版本：%s' % ', '.join(pending))

    if missing:
        return report, stops

    for table in REQUIRED_TABLES:
        report.append('基线行数 %s: %s' % (table, client.scalar('SELECT COUNT(*) FROM %s' % table)))
    for row in client.rows('CHECKSUM TABLE %s' % ', '.join(REQUIRED_TABLES)):
        report.append('基线 checksum: %s = %s' % (row[0], row[1]))

    for label, key, table in (('keyword', KW_KEY, 'amz_ad_keyword'),
                              ('converting_term', CT_KEY, 'amz_ad_converting_terms'),
                              ('asin_keyword', AK_KEY, 'amz_ad_asin_keyword')):
        groups = client.scalar(_dup_groups_sql(key, table))
        deletes = client.scalar(_dup_deletes_sql(key, table))
        report.append('重复组数 %s: %s（将物理删除约 %s 行）' % (label, groups, deletes))

    checks = [
        ('keyword-empty', 'amz_ad_keyword.keyword 规范化后为空',
         "SELECT COUNT(*) FROM amz_ad_keyword "
         "WHERE LOWER(TRIM(COALESCE(keyword, ''))) = ''"),
        ('search-term-empty', 'amz_ad_converting_terms.search_term 规范化后为空',
         "SELECT COUNT(*) FROM amz_ad_converting_terms "
         "WHERE LOWER(TRIM(COALESCE(search_term, ''))) = ''"),
        ('asin-keyword-empty', 'amz_ad_asin_keyword.keyword 规范化后为空',
         "SELECT COUNT(*) FROM amz_ad_asin_keyword "
         "WHERE LOWER(TRIM(COALESCE(keyword, ''))) = ''"),
        ('campaign-id-mixed', '同键组内 campaign_id 的 NULL/空白/非空白混杂',
         "SELECT COUNT(*) FROM (SELECT 1 FROM amz_ad_converting_terms "
         "GROUP BY shop_id, LOWER(TRIM(search_term)) "
         "HAVING COUNT(DISTINCT CASE WHEN campaign_id IS NULL THEN 'null' "
         "WHEN TRIM(campaign_id) = '' THEN 'blank' ELSE 'value' END) > 1) d"),
        ('converting-conflict', '同键组内 status / is_added_to_keyword 冲突',
         "SELECT COUNT(*) FROM (SELECT 1 FROM amz_ad_converting_terms GROUP BY %s "
         "HAVING COUNT(DISTINCT status) > 1 OR COUNT(DISTINCT is_added_to_keyword) > 1) d"
         % CT_KEY),
        ('keyword-value-conflict', '同键组内 bid / base_bid / state 无明确责任行',
         "SELECT COUNT(*) FROM (SELECT 1 FROM amz_ad_keyword GROUP BY %s "
         "HAVING COUNT(*) > 1 AND COUNT(DISTINCT CONCAT_WS('|', COALESCE(bid, ''), "
         "COALESCE(base_bid, ''), COALESCE(state, ''))) > 1) d" % KW_KEY),
        ('asin-last-checked-ambiguous', '同键组内 last_checked 为空或不唯一',
         "SELECT COUNT(*) FROM (SELECT 1 FROM amz_ad_asin_keyword GROUP BY %s "
         "HAVING COUNT(*) > 1 AND (COUNT(last_checked) <> COUNT(*) "
         "OR COUNT(DISTINCT last_checked) <> 1)) d" % AK_KEY),
    ]
    for code, title, sql in checks:
        count = client.scalar(sql)
        report.append('STOP %s = %s' % (code, count))
        if str(count) not in ('0', 'None'):
            stop(code, '%s：命中 %s 处' % (title, count))

    # asin 单独拆开：V7 会把空白 asin 规范成 NULL，而 NULL 不进唯一键，
    # 所以 NULL 与空白都要报，但让执行人看清构成。
    asin_null = client.scalar('SELECT COUNT(*) FROM amz_ad_asin_keyword WHERE asin IS NULL')
    asin_blank = client.scalar(
        "SELECT COUNT(*) FROM amz_ad_asin_keyword WHERE asin IS NOT NULL AND TRIM(asin) = ''")
    total = int(asin_null or 0) + int(asin_blank or 0)
    report.append('STOP asin-empty = %s（NULL %s / 空白 %s）' % (total, asin_null, asin_blank))
    if total:
        stop('asin-empty', 'amz_ad_asin_keyword.asin 规范化后为空：NULL %s 行 / 空白 %s 行'
             % (asin_null, asin_blank))

    return report, stops


def build_parser():
    parser = argparse.ArgumentParser(description='amz-service-ad V7 迁移预检（只读）')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', default='3306')
    parser.add_argument('--user', default='root')
    parser.add_argument('--password', default=None,
                        help='不传则使用环境变量 MYSQL_PWD')
    parser.add_argument('--database', default='amz_ad')
    parser.add_argument('--mysql-path', default='mysql')
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    password = args.password if args.password is not None else os.environ.get('MYSQL_PWD')
    client = MysqlClient(args.mysql_path, args.host, args.port, args.user, password,
                         args.database)
    try:
        report, stops = run_preflight(client)
    except MysqlError as exc:
        sys.stderr.write('预检无法完成：%s%s' % (exc, os.linesep))
        return 1
    except FileNotFoundError:
        sys.stderr.write('找不到 mysql 客户端：%s（用 --mysql-path 指定）%s'
                         % (args.mysql_path, os.linesep))
        return 1

    sys.stdout.write('=== V7 预检报告（库 %s@%s:%s）===%s' % (args.database, args.host, args.port, os.linesep))
    for line in report:
        sys.stdout.write(line + os.linesep)
    if stops:
        sys.stdout.write('%s=== STOP：命中 %d 项，禁止执行 V7 ===%s' % (os.linesep, len(stops), os.linesep))
        for code, detail in stops:
            sys.stdout.write('  [%s] %s%s' % (code, detail, os.linesep))
        return 2
    sys.stdout.write('%s=== 未命中 STOP：可进入备份与执行阶段（Runbook §4/§5）===%s'
                     % (os.linesep, os.linesep))
    return 0


if __name__ == '__main__':
    sys.exit(main())
