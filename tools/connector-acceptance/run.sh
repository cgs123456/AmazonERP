#!/usr/bin/env sh
# AmazonERP connector acceptance runner (runbook section 3; P0-52c).
# Usage:
#   ./run.sh --selftest
#   ./run.sh --service-url http://127.0.0.1:8096 --connector spapi --shop-id 1001 \
#            --marketplace-id ATVPDKIKX0DER --operations orders,reports --out-dir ./acceptance-out
# Exit codes of a REAL run: 0 = every criterion met, 1 = ran but has gaps, 2 = precondition failed.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PYTHON=${PYTHON:-python3}
exec "$PYTHON" "$here/acceptance_runner.py" "$@"