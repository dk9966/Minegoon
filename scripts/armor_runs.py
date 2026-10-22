#!/usr/bin/env python3
"""Three tries at a full set of diamond armor, on the practice server.

Each try starts at spawn in the morning with empty hands: the helper works out
what the trip takes and gets it itself. The sentence goes through the front
desk, so the big planner and the small desk both get to speak. Model fires are
the model_fire lines in the plugin's results.jsonl. Every poll also records
where the helper is, its health, and the mobs near it, so a run's route can be
read back afterwards.

    python3 scripts/armor_runs.py

TIMEOUT is the seconds allowed for one try (default 3600). TRIES is how many
tries to run (default 3). The goal is a finish inside 40 minutes.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "infra" / "data"
RESULTS = DATA / "plugins" / "Minegoon" / "results.jsonl"
OUT = ROOT / "scripts" / "results" / "armor"
TIMEOUT = int(os.environ.get("TIMEOUT", "3600"))
TRIES = int(os.environ.get("TRIES", "3"))
POLL = 10
PIECES = ("diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots")
ASK = "get a full set of diamond armor"
HELPER = "Builder"
STATUS_LINE = re.compile(r"Health (\d+)/20, food (\d+)/20, at (-?\d+) (-?\d+) (-?\d+), ([^.|]*)")


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


def point(started: float) -> dict | None:
    """Where the helper is now, from the status line: position, health, food, mobs near."""
    try:
        status = rcon("minegoon", "status").replace("\n", " | ")
    except (RuntimeError, subprocess.TimeoutExpired):
        return None
    found = STATUS_LINE.search(status)
    if not found:
        return {"t": int(time.time() - started), "status": status[:240]}
    health, food, x, y, z, mobs = found.groups()
    return {"t": int(time.time() - started), "x": int(x), "y": int(y), "z": int(z),
            "health": int(health), "food": int(food), "mobs": mobs.strip(), "status": status[:240]}


def run_once(number: int) -> dict:
    say(f"--- try {number}: reset, empty hands, ask ---")
    rcon("minegoon", "reset")
    rcon("time", "set", "day")
    before = len(lines())
    started = time.time()
    rcon("minegoon", "ask", ASK)
    fires: list[dict] = []
    track: list[dict] = []
    mission = None
    deadline = started + TIMEOUT
    while time.time() < deadline:
        time.sleep(POLL)
        fresh = lines()[before:]
        fires = [row["model_fire"] for row in fresh if "model_fire" in row]
        missions = [row for row in fresh if row.get("request") == ASK or row.get("mission")]
        ended = [row for row in missions if "status" in row and "model_fire" not in row and "mission" in row]
        if ended:
            mission = ended[-1]
            break
        sample = point(started)
        if sample is not None:
            track.append(sample)
        if sample is not None and sample["t"] % 60 < POLL:
            say(f"  t={sample['t']}s fires={len(fires)} {sample['status']}")
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
    tasks = [row for row in fresh if "task" in row and "goal" in row]
    record = {
        "try": number,
        "seconds": int(time.time() - started),
        "mission": mission,
        "tasks": tasks,
        "deaths": sum(row.get("deaths", 0) for row in tasks),
        "track": track,
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
    records = [run_once(number) for number in range(1, TRIES + 1)]
    path = OUT / f"{stamp}.json"
    path.write_text(json.dumps(records, indent=2))
    say(f"wrote {path}")


if __name__ == "__main__":
    main()
