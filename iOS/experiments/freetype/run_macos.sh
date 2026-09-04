#!/bin/bash
# Optional standalone experiment. Does not build, modify, or package the app.
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
exec python3 "$script_dir/run_probe.py" --run "$@"
