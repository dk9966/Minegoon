#!/usr/bin/env bash
# Runs the helper through repeated practice trips on the local Docker server
# and collects one result line per trip.
#
#   scripts/practice.sh [runs] [count] [item]     e.g. scripts/practice.sh 5 3 iron_ingot
#
# FRESH=1 deletes the practice world first; the fixed seed regenerates the same one.
# TIMEOUT sets the seconds allowed per trip (default 1200).
set -euo pipefail

runs="${1:-3}"
count="${2:-3}"
item="${3:-iron_ingot}"
timeout="${TIMEOUT:-1200}"

root="$(cd "$(dirname "$0")/.." && pwd)"
data="$root/infra/data"
plugin_dir="$data/plugins/Minegoon"
results="$plugin_dir/results.jsonl"
compose=(docker compose -f "$root/infra/docker-compose.yml")

rcon() {
  docker exec minegoon-practice rcon-cli "$@"
}

lines() {
  [[ -f "$results" ]] && wc -l < "$results" | tr -d ' ' || echo 0
}

echo "Building the plugin"
(cd "$root/server" && ./gradlew build -q)

if [[ "${FRESH:-0}" == "1" ]]; then
  echo "Removing the practice world"
  "${compose[@]}" down
  rm -rf "$data"/world*
fi

mkdir -p "$plugin_dir"
cp "$root"/server/build/libs/minegoon-*.jar "$data/plugins/minegoon.jar"
[[ -f "$plugin_dir/config.yml" ]] || cp "$root/infra/lab-config.yml" "$plugin_dir/config.yml"

echo "Starting the practice server"
"${compose[@]}" up -d
"${compose[@]}" restart practice >/dev/null
until rcon minegoon status >/dev/null 2>&1; do
  sleep 5
done

run_id="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$root/scripts/results"
out="$root/scripts/results/$run_id.jsonl"

for ((run = 1; run <= runs; run++)); do
  echo "Trip $run of $runs: get $count $item"
  rcon minegoon reset >/dev/null
  rcon time set day >/dev/null
  before="$(lines)"
  rcon minegoon get "$count" "$item" >/dev/null
  waited=0
  while [[ "$(lines)" -le "$before" && "$waited" -lt "$timeout" ]]; do
    sleep 10
    waited=$((waited + 10))
  done
  if [[ "$(lines)" -le "$before" ]]; then
    echo "  timed out, stopping the task"
    rcon minegoon stop >/dev/null
    sleep 2
  fi
  tail -n 1 "$results" | tee -a "$out"
done

echo "Results saved to $out"
