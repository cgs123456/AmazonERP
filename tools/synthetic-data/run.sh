#!/usr/bin/env sh
# Generate the deterministic synthetic AmazonERP dataset (spec 7.9).
# Usage: ./run.sh [--tier ci] [--orders 1000] [--reset] [--out DIR] [--include-conditional]
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PYTHON=${PYTHON:-python3}
exec "$PYTHON" "$here/generate.py" "$@"