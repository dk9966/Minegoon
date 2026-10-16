#!/usr/bin/env bash
# Runs the long-task suite, or the quick skills suite. See docs/05-long-tasks.md.
#
#   [REPEATS=n] scripts/long-tasks.sh [iteration] [long|skills|moves] [task,task,...]
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 -u "$root/scripts/long_tasks.py" "$@"
