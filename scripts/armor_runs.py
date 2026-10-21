#!/usr/bin/env python3
"""Three tries at a full set of diamond armor, on the practice server.

Each try starts at spawn in the morning with a diamond-trip kit (an iron
pickaxe, stone pickaxes for the staircase, food, blocks). The sentence goes
through the front desk, so the big planner and the small desk both get to
speak. Model fires are the model_fire lines in the plugin's results.jsonl.

    python3 scripts/armor_runs.py

TIMEOUT is the seconds allowed for one try (default 5400).
"""

from __future__ import annotations

import json
import os
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "infra" / "data"
RESULTS = DATA / "plugins" / "Minegoon" / "results.jsonl"
OUT = ROOT / "scripts" / "results" / "armor"
TIMEOUT = int(os.environ.get("TIMEOUT", "5400"))
PIECES = ("diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots")
KIT = (
    ("iron_pickaxe", 1),
    ("stone_pickaxe", 4),
    ("stone_axe", 1),
    ("shield", 1),
    ("cooked_beef", 32),
    ("cobblestone", 64),
    ("stick", 16),
    ("crafting_table", 1),
)
ASK = "get a full set of diamond armor"


def say(message: str) -> None:
    print(message, flush=True)


def rcon(*args: str, timeout: int = 30) -> str:
    done = subprocess.run(
        ["sudo", "docker", "exec", "minegoon-practice", "rcon-cli", *args],
        text=True, capture_output=True, timeout=timeout,
    )
    text = (done.stdout or done.stderr).strip()
    if done.returncode != 0:
        raise RuntimeError(text or "rcon failed")
    return text


def lines() -> list[dict]:
    if not RESULTS.exists():
        return []
    parsed = []
    for line in RESULTS.read_text(errors="replace").splitlines():
        line = line.strip()
        if line:
            parsed.append(json.loads(line))
    return parsed


def wait_ready() -> None:
    deadline = time.time() + 300
    while time.time() < deadline:
        try:
            rcon("minegoon", "status")
            return
        except (RuntimeError, subprocess.TimeoutExpired):
            time.sleep(5)
    raise RuntimeError("practice server did not answer")


def run_once(number: int) -> dict:
    say(f"--- try {number}: reset, kit, ask ---")
    rcon("minegoon", "reset")
    rcon("time", "set", "day")
    for item, count in KIT:
        rcon("give", "Builder", item, str(count))
    before = len(lines())
    started = time.time()
    rcon("minegoon", "ask", ASK)
    fires: list[dict] = []
    mission = None
    deadline = started + TIMEOUT
    while time.time() < deadline:
        time.sleep(20)
        fresh = lines()[before:]
        fires = [row["model_fire"] for row in fresh if "model_fire" in row]
        missions = [row for row in fresh if row.get("request") == ASK or row.get("mission")]
        ended = [row for row in missions if "status" in row and "model_fire" not in row and "mission" in row]
        if ended:
            mission = ended[-1]
            break
        elapsed = int(time.time() - started)
        if elapsed % 60 < 20:
            try:
                status = rcon("minegoon", "status").replace("\n", " | ")
            except RuntimeError as error:
                status = str(error)
            say(f"  t={elapsed}s fires={len(fires)} {status[:240]}")
    else:
        say("  timed out, stopping")
        try:
            rcon("minegoon", "stop")
        except RuntimeError as error:
            say(f"  stop failed: {error}")
        time.sleep(2)
    fresh = lines()[before:]
    fires = [row["model_fire"] for row in fresh if "model_fire" in row]
    ended = [row for row in fresh if "mission" in row and "status" in row]
    mission = ended[-1] if ended else mission
    try:
        inventory = rcon("minegoon", "inventory")
    except RuntimeError as error:
        inventory = str(error)
    record = {
        "try": number,
        "seconds": int(time.time() - started),
        "mission": mission,
        "model_fires": fires,
        "big": sum(1 for fire in fires if fire["size"] == "big"),
        "small": sum(1 for fire in fires if fire["size"] == "small"),
        "inventory": inventory,
        "pieces": {piece: piece in inventory for piece in PIECES},
    }
    say(f"try {number}: big={record['big']} small={record['small']} "
        f"status={None if mission is None else mission.get('status')} in {record['seconds']}s")
    for fire in fires:
        say(f"  {fire['size']} {fire['role']} what={fire['what']} asked={fire['asked']}")
    return record


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    records = [run_once(number) for number in (1, 2, 3)]
    path = OUT / f"{stamp}.json"
    path.write_text(json.dumps(records, indent=2))
    say(f"wrote {path}")


if __name__ == "__main__":
    main()
