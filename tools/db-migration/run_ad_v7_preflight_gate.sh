#!/usr/bin/env bash
# ad/V7 预检门禁：在真实 MySQL 8.0 上验证 ad_v7_preflight.py 的退出码契约（0 / 2 / 1）。
#
# 为什么要有这个门禁
# ------------------
# ad_v7_preflight.py 是 V7（破坏性归并：UPDATE 规范化 + DELETE 旧重复行 + 加唯一键）
# 之前的唯一自动闸门。它一旦腐化——STOP 条件失效、退出码写错——发布流程会在最需要
# 它的时候静默放行。CI 上跑单测只覆盖 stub 路径；这个脚本用真实 MySQL 把三类退出码
# 各跑一遍，证明「命中 STOP 就退出非 0」这条契约仍然成立。
#
# 本脚本**不执行 V7**，只用 V1-V6 建一个一次性库，最后 DROP 掉，不触碰任何生产对象。
#
# 环境变量
#   MYSQL_PWD   必填（密码走环境变量，不进进程列表）
#   MYSQL_HOST  默认 127.0.0.1
#   MYSQL_PORT  默认 3306
#   MYSQL_USER  默认 root
#   PYTHON      默认 python3
#   AD_V7_GATE_DB  一次性库名，默认 amz_ad_v7_gate
#
# 退出码
#   0 = 三类退出码契约全部兑现
#   1 = 契约被打破（门禁失败）

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIG="$ROOT/amz-service/amz-service-ad/src/main/resources/db/migration"
HOST="${MYSQL_HOST:-127.0.0.1}"
PORT="${MYSQL_PORT:-3306}"
USER="${MYSQL_USER:-root}"
DB="${AD_V7_GATE_DB:-amz_ad_v7_gate}"
PY="${PYTHON:-python3}"

MYSQL=(mysql -h "$HOST" -P "$PORT" -u "$USER")

fail () { printf 'GATE FAIL: %s\n' "$1" >&2; exit 1; }

[ -n "${MYSQL_PWD:-}" ] || fail 'MYSQL_PWD 未设置'

# 1) 用 V1-V6 原始 SQL 建一次性库（明确不执行 V7）
"${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$DB\`; CREATE DATABASE \`$DB\` DEFAULT CHARSET utf8mb4;" \
  || fail '无法创建演练库'
for v in 1 2 3 4 5 6; do
  f="$(ls "$MIG"/V${v}__*.sql 2>/dev/null | head -1)"
  [ -n "$f" ] || fail "找不到 V${v} 迁移文件"
  "${MYSQL[@]}" -D "$DB" < "$f" || fail "V${v} 应用失败"
done

# 2) 补 flyway_schema_history fixture（V1-V6 全部 success=1）。
#    这是手工构造的 fixture，只用于驱动脚本读 history 的逻辑，
#    不能当作 Flyway 的真实执行记录（checksum 为 NULL）。
"${MYSQL[@]}" -D "$DB" <<'SQL' || fail 'flyway_schema_history fixture 建不起来'
CREATE TABLE flyway_schema_history (
  installed_rank INT NOT NULL PRIMARY KEY,
  version VARCHAR(50) NULL,
  description VARCHAR(200) NOT NULL,
  type VARCHAR(20) NOT NULL,
  script VARCHAR(1000) NOT NULL,
  checksum INT NULL,
  installed_by VARCHAR(100) NOT NULL,
  installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  execution_time INT NOT NULL,
  success TINYINT(1) NOT NULL
) ENGINE=InnoDB;
INSERT INTO flyway_schema_history
  (installed_rank, version, description, type, script, installed_by, execution_time, success)
VALUES
  (1, '1', 'init',                        'SQL', 'V1__init.sql',                        'ci', 1, 1),
  (2, '2', 'campaign metadata unique',    'SQL', 'V2__campaign_metadata_unique.sql',    'ci', 1, 1),
  (3, '3', 'ad daily report update time', 'SQL', 'V3__ad_daily_report_update_time.sql', 'ci', 1, 1),
  (4, '4', 'search term keyword key type','SQL', 'V4__search_term_keyword_key_type.sql','ci', 1, 1),
  (5, '5', 'ad resource tenant scope',    'SQL', 'V5__ad_resource_tenant_scope.sql',    'ci', 1, 1),
  (6, '6', 'keyword base bid',            'SQL', 'V6__keyword_base_bid.sql',            'ci', 1, 1);
SQL

# 3) 单测（不连库）
(cd "$ROOT/tools/db-migration" && "$PY" -m unittest -v test_ad_v7_preflight) || fail '单测失败'

run_pf () {
  (cd "$ROOT/tools/db-migration" \
    && "$PY" ad_v7_preflight.py --host "$HOST" --port "$PORT" --user "$USER" --database "$1")
}

# 4) case A：V1-V6 干净态，未命中 STOP -> rc=0
run_pf "$DB" > /tmp/ad_v7_gate_a.txt 2>&1; rc=$?
[ $rc -eq 0 ] || { cat /tmp/ad_v7_gate_a.txt; fail "case A 期望 rc=0，实际 $rc"; }

# 5) case B：插入规范化后为空的 keyword（命中 keyword-empty）-> rc=2
"${MYSQL[@]}" -D "$DB" \
  -e "INSERT INTO amz_ad_keyword (campaign_id, shop_id, keyword) VALUES ('ci-campaign', 1, '   ');" \
  || fail '插入 STOP fixture 失败'
run_pf "$DB" > /tmp/ad_v7_gate_b.txt 2>&1; rc=$?
[ $rc -eq 2 ] || { cat /tmp/ad_v7_gate_b.txt; fail "case B 期望 rc=2，实际 $rc"; }
grep -q 'keyword-empty' /tmp/ad_v7_gate_b.txt \
  || { cat /tmp/ad_v7_gate_b.txt; fail 'case B 没报 keyword-empty（退出码对了但 STOP 码不对）'; }

# 6) case C：库不存在 -> rc=1（fail-closed，同样不得执行 V7）
run_pf "${DB}_does_not_exist" > /tmp/ad_v7_gate_c.txt 2>&1; rc=$?
[ $rc -eq 1 ] || { cat /tmp/ad_v7_gate_c.txt; fail "case C 期望 rc=1，实际 $rc"; }

# 7) 清理
"${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$DB\`;"

printf 'GATE OK: ad/V7 预检退出码契约兑现（rc=0 放行 / rc=2 STOP / rc=1 脚本错误）\n'
