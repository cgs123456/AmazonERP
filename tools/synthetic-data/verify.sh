#!/usr/bin/env sh
# Verify the synthetic dataset toolchain (spec 7.9).
# Usage: ./verify.sh [--tier ci] [--orders 200] [--dataset out/ci] [--skip-determinism]
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PYTHON=${PYTHON:-python3}
exec "$PYTHON" "$here/verify.py" "$@"