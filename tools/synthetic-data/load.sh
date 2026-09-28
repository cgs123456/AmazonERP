#!/usr/bin/env bash
# Load a generated synthetic dataset into MySQL (see mock-data-seed-and-cleanup-runbook.md).
# Usage:
#   ./load.sh --tier ci --host 127.0.0.1 --user root --password secret
#   ./load.sh --tier demo --container amz-mysql --user root --password secret
#   ./load.sh --tier ci --dry-run
set -euo pipefail

TIER=demo
DATASET=""
HOST=127.0.0.1
PORT=3306
USER=root
PASSWORD=""
CONTAINER=""
REGISTRY=1
DRY_RUN=0
ALLOW_TRUNCATE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --tier) TIER="$2"; shift 2;;
    --dataset) DATASET="$2"; shift 2;;
    --host) HOST="$2"; shift 2;;
    --port) PORT="$2"; shift 2;;
    --user) USER="$2"; shift 2;;
    --password) PASSWORD="$2"; shift 2;;
    --container) CONTAINER="$2"; shift 2;;
    --no-registry) REGISTRY=0; shift;;
    --allow-truncate) ALLOW_TRUNCATE=1; shift;;
    --dry-run) DRY_RUN=1; shift;;
    *) echo "unknown option: $1" >&2; exit 64;;
  esac
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -n "$DATASET" ] || DATASET="$HERE/out/$TIER"
[ -d "$DATASET" ] || { echo "dataset not found: $DATASET (run generate.py --tier $TIER first)" >&2; exit 66; }
PYTHON="${PYTHON:-python3}"

run_sql() {
  local db="$1" file="$2" label="$3"
  local args=(--host "$HOST" --port "$PORT" --user "$USER" --default-character-set=utf8mb4 --database "$db")
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "[dry-run] ${CONTAINER:+docker exec -i $CONTAINER }mysql ${args[*]} < $file"
    return 0
  fi
  if [ -n "$CONTAINER" ]; then
    # docker exec does not inherit the client environment: pass MYSQL_PWD explicitly
    docker exec -i -e "MYSQL_PWD=$PASSWORD" "$CONTAINER" mysql "${args[@]}" < "$file" \
      || { echo "$label failed" >&2; exit 1; }
  else
    MYSQL_PWD="$PASSWORD" mysql "${args[@]}" < "$file" || { echo "$label failed" >&2; exit 1; }
  fi
}

TABLES=$("$PYTHON" - "$DATASET" <<'PY'
import json, sys
manifest = json.load(open(sys.argv[1] + '/manifest.json'))
print(manifest['totals']['rows'])
print(manifest.get('truncate_first', False))
for t in manifest['tables']:
    print('%s\t%s\t%s\t%s' % (t['database'], t['name'], t['sql'], t['rows']))
PY
)
ROWS=$(echo "$TABLES" | sed -n '1p')
TRUNCATE_FIRST=$(echo "$TABLES" | sed -n '2p')
echo "[load] dataset=$DATASET tier=$TIER rows=$ROWS"

if [ "$REGISTRY" -eq 1 ]; then
  REG="$DATASET/registry.sql"
  if [ ! -f "$REG" ]; then
    "$PYTHON" "$HERE/purge.py" --dataset "$DATASET" --emit --registry --quiet
  fi
  echo "[load] registry: $REG"
  run_sql amz_ops "$REG" registry
fi

if [ "$TRUNCATE_FIRST" = "True" ]; then
  if [ "$ALLOW_TRUNCATE" -ne 1 ]; then
    echo "dataset generated with --truncate-first: re-run with --allow-truncate (disposable DB only)" >&2
    exit 65
  fi
  TRUNC="$DATASET/_truncate.sql"
  "$PYTHON" "$HERE/purge.py" --dataset "$DATASET" --mode truncate --allow-destructive --out "$TRUNC" --emit --quiet
  run_sql amz_ops "$TRUNC" truncate
fi

# a `while read` fed by a pipe would run in a subshell and swallow failures, so the
# manifest table list goes through a temp file and failures set a flag file.
LIST="$(mktemp)"
FAILED="$(mktemp)"
trap 'rm -f "$LIST" "$FAILED"' EXIT
echo "$TABLES" | tail -n +3 > "$LIST"
while IFS=$'\t' read -r db name sql rows; do
  [ -n "$sql" ] || continue
  echo "[load] $db.$name ($rows rows)"
  run_sql "$db" "$DATASET/$sql" "$db.$name" || echo "$db.$name" >> "$FAILED"
done < "$LIST"
if [ -s "$FAILED" ]; then
  echo "[load] FAILED tables:" >&2
  cat "$FAILED" >&2
  exit 1
fi

echo "[load] done. Verify: SELECT dataset_id, seed, tier, loaded_at, rows, is_demo FROM amz_ops.amz_synthetic_dataset_registry;"
