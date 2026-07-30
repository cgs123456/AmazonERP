#!/usr/bin/env bash
# =====================================================================
# Amazon-ERP 快速基准测试脚本（Linux / macOS）
# 使用 curl + ab（ApacheBench）对关键接口进行快速压测
# 适用：CI 冒烟、本地排查、上线前自检
# =====================================================================
set -uo pipefail

# ====== 默认配置（可通过环境变量覆盖） ======
HOST="${TARGET_HOST:-erp.amz.local}"
PORT="${TARGET_PORT:-80}"
PROTO="${TARGET_PROTO:-http}"
API_BASE="${PROTO}://${HOST}:${PORT}/api"
TOKEN="${AUTH_TOKEN:-}"
CONCURRENCY="${CONCURRENCY:-50}"          # 并发数
REQUESTS="${REQUESTS:-500}"              # 总请求数
TIMEOUT=10

# 颜色
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log()  { echo -e "${BLUE}[$(date '+%H:%M:%S')]${NC} $*"; }
ok()   { echo -e "${GREEN}[PASS]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }

# ====== 依赖检查 ======
check_deps() {
  command -v curl >/dev/null 2>&1 || { fail "curl 未安装"; exit 1; }
  if ! command -v ab >/dev/null 2>&1; then
    warn "ApacheBench (ab) 未安装，将仅执行 curl 探活。"
    warn "  Ubuntu/Debian: sudo apt-get install -y apache2-utils"
    warn "  macOS: brew install httpd"
    HAS_AB=0
  else
    HAS_AB=1
  fi
}

# ====== 单接口压测 ======
bench() {
  local name="$1"; local method="$2"; local url="$3"; local data="${4:-}"
  log "→ $name ($method $url) concurrency=${CONCURRENCY} requests=${REQUESTS}"
  if [ "$HAS_AB" = "1" ]; then
    if [ "$method" = "POST" ] && [ -n "$data" ]; then
      ab -n "$REQUESTS" -c "$CONCURRENCY" -s "$TIMEOUT" \
         -H "Authorization: Bearer ${TOKEN}" \
         -H "Content-Type: application/json" \
         -p <(printf '%s' "$data") "$url" 2>&1 | \
         grep -E "^(Complete requests|Failed requests|Time per request|Requests per second|Transfer rate|Time taken|Non-2xx)" | \
         sed "s/^/    /"
    else
      ab -n "$REQUESTS" -c "$CONCURRENCY" -s "$TIMEOUT" \
         -H "Authorization: Bearer ${TOKEN}" \
         -H "Content-Type: application/json" \
         "$url" 2>&1 | \
         grep -E "^(Complete requests|Failed requests|Time per request|Requests per second|Transfer rate|Time taken|Non-2xx)" | \
         sed "s/^/    /"
    fi
  else
    # 退化到 curl 单次探活
    local code
    code=$(curl -s -o /dev/null -w "%{http_code} %{time_total}s" \
        -X "$method" -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        ${data:+-d "$data"} --max-time "$TIMEOUT" "$url" 2>/dev/null)
    echo "    curl 探活结果: $code"
  fi
  echo ""
}

# ====== 健康检查 ======
health_check() {
  log "=== 健康检查 ==="
  local endpoints=(
    "网关:${PROTO}://${HOST}:${PORT}/actuator/health"
    "网关 readiness:${PROTO}://${HOST}:${PORT}/actuator/health/readiness"
  )
  for ep in "${endpoints[@]}"; do
    local name="${ep%%:*}"
    local url="${ep#*:}"
    local result
    result=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$url" 2>/dev/null || echo "000")
    if [ "$result" = "200" ]; then
      ok "$name (HTTP $result)"
    else
      fail "$name (HTTP $result)"
    fi
  done
  echo ""
}

# ====== 主流程 ======
main() {
  check_deps

  cat <<EOF
╔══════════════════════════════════════════════════════════════════╗
║              Amazon-ERP Quick Benchmark                          ║
╠══════════════════════════════════════════════════════════════════╣
║ Target : ${API_BASE}
║ Concurrency : ${CONCURRENCY}
║ Requests    : ${REQUESTS}
║ Token       : ${TOKEN:+已设置 (}${TOKEN:-(未设置)}${TOKEN:+)}
╚══════════════════════════════════════════════════════════════════╝
EOF
  echo ""

  health_check

  # 1. 登录接口
  bench "登录" POST "${API_BASE}/user/login" \
    '{"username":"admin","password":"admin123"}'

  # 2. 订单列表
  bench "订单列表" GET "${API_BASE}/order/list?page=1&size=20"

  # 3. 库存列表
  bench "库存列表" GET "${API_BASE}/inventory/list?page=1&size=50"

  # 4. Agent 聊天
  bench "Agent 聊天" POST "${API_BASE}/ai/agent/chat" \
    '{"sessionId":"quick-bench","message":"分析上周销量","context":{"marketplace":"US"}}'

  log "基准测试完成。"
}

main "$@"
