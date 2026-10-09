#!/usr/bin/env bash
# Thin wrapper around the dependency-free Python benchmark runner.
# The runner targets the gateway origin directly and uses the token header.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_BASE_URL="${TARGET_BASE_URL:-http://127.0.0.1:10010}"
AUTH_TOKEN="${AUTH_TOKEN:-${TOKEN:-}}"
SHOP_ID="${SHOP_ID:-}"
CONCURRENCY="${CONCURRENCY:-10}"
REQUESTS="${REQUESTS:-100}"
TIMEOUT_SEC="${TIMEOUT_SEC:-30}"
WARMUP="${WARMUP:-3}"
SCENARIO="${SCENARIO:-all}"
OUTPUT="${OUTPUT:-}"

if [[ -z "${AUTH_TOKEN}" ]]; then
  echo "AUTH_TOKEN is required. Obtain a gateway JWT first." >&2
  exit 2
fi

args=(python3 "${SCRIPT_DIR}/bench.py" \
  --base-url "${TARGET_BASE_URL}" \
  --token "${AUTH_TOKEN}" \
  --concurrency "${CONCURRENCY}" \
  --requests "${REQUESTS}" \
  --timeout "${TIMEOUT_SEC}" \
  --warmup "${WARMUP}" \
  --scenario "${SCENARIO}")

if [[ -n "${SHOP_ID}" ]]; then
  args+=(--shop-id "${SHOP_ID}")
fi
if [[ -n "${OUTPUT}" ]]; then
  args+=(--output "${OUTPUT}")
fi

exec "${args[@]}"
