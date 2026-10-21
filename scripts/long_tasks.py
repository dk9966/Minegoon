#!/usr/bin/env python3
"""Run the long-task suite, or the quick skills, moves or agent suite, on one Paper server.

    long_tasks.py [iteration] [long|skills|moves|agent] [task,...]

See docs/05-long-tasks.md. Three trips run at once, each on its own copy of the
fresh world, inside one Java process. The world is put back before every trip.
A running trip's blocks cannot be shared; the server itself can.
"""

from __future__ import annotations

import atexit
import gzip
import hashlib
import json
import math
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LAB = ROOT / "infra" / "lab"
TEMPLATE = LAB / "template"
# Written once the template world is saved. A template from older code, which killed its animals, fails the check.
TEMPLATE_READY = "seed minegoon, animals kept\n"
COMPOSE = ROOT / "infra" / "docker-compose.lab.yml"
MAIN_COMPOSE = ROOT / "infra" / "docker-compose.yml"
RESULTS = ROOT / "scripts" / "results" / "long-tasks"
HELPER = "Builder"
REPEATS = int(os.environ.get("REPEATS", "3"))
# What a trip's behavior depends on, for telling runs apart.
CODE = ("server", "scripts")
CONTAINER = "minegoon-lab"
LANES = ("run_a", "run_b", "run_c")
# Applied only when the suite server starts. The template boot leaves these unset
# and gets the compose defaults: a normal world, nether on, 2G heap.
SUITE_ENV = {
    "LAB_MEMORY": os.environ.get("LAB_MEMORY", "2560M"),
    "LAB_CAP": os.environ.get("LAB_CAP", "4g"),
    "LEVEL_TYPE": "minecraft:flat",
    "ALLOW_NETHER": "false",
}
ANSI = re.compile(r"\x1b\[[0-9;]*m")
WORLD_NAMES = ("world", "world_nether", "world_the_end")



@dataclass(frozen=True)
class Task:
    """Get `count` of `item`, or with `spar` set, fight that many of that monster.
    `kit` is handed over after the reset, as item:count. An `impossible` task
    passes when the helper stops it with a reason instead of trying forever.
    With `said` set, the request is said to the helper by name instead, and
    `check` reads the trip's record: it names what went wrong, or is empty."""
    id: str
    count: int
    item: str
    timeout: int
    kit: tuple[str, ...] = ()
    spar: str | None = None
    course: str | None = None
    impossible: bool = False
    said: str | None = None
    check: Callable[[dict], str] | None = None


# Time limits are a little over twice a good run. A trip still going then is lost.
LONG = (
    Task("stone-pick", 1, "stone_pickaxe", 8 * 60),
    Task("iron", 3, "iron_ingot", 12 * 60),
    Task("bucket", 1, "bucket", 12 * 60),
    Task("shield", 1, "shield", 12 * 60),
    Task("torches", 32, "torch", 12 * 60),
    Task("water", 1, "water_bucket", 15 * 60),
    Task("beef", 4, "cooked_beef", 15 * 60),
    Task("bread", 3, "bread", 18 * 60),
    Task("bed", 1, "white_bed", 15 * 60),
    Task("string", 4, "string", 15 * 60),
    Task("diamonds", 3, "diamond", 20 * 60),
)

# One skill each, from a head start, so a failure points at one piece.
DEEP_KIT = ("iron_pickaxe:1", "stone_axe:1", "shield:1", "cooked_beef:12", "cobblestone:64")
SKILLS = (
    Task("stone", 16, "cobblestone", 3 * 60, ("wooden_pickaxe:1",)),
    Task("coal", 8, "coal", 5 * 60, ("stone_pickaxe:1",)),
    Task("iron", 3, "raw_iron", 8 * 60, ("stone_pickaxe:1",)),
    Task("cow", 3, "beef", 5 * 60),
    Task("sheep", 2, "white_wool", 5 * 60),
    Task("water", 1, "water_bucket", 5 * 60, ("bucket:1",)),
    Task("diamonds", 1, "diamond", 15 * 60, DEEP_KIT),
    Task("spar-zombie", 1, "zombie", 3 * 60, ("stone_sword:1", "shield:1"), spar="zombie"),
    Task("spar-skeleton", 1, "skeleton", 3 * 60, ("stone_sword:1", "shield:1"), spar="skeleton"),
    Task("spar-spider", 1, "spider", 3 * 60, ("stone_sword:1", "shield:1"), spar="spider"),
    Task("spar-creeper", 1, "creeper", 3 * 60, ("stone_sword:1", "shield:1"), spar="creeper"),
    Task("spar-skeleton-bow", 1, "skeleton", 3 * 60, ("stone_sword:1", "shield:1", "bow:1", "arrow:32"),
         spar="skeleton"),
    # Asking the model first: no survival way at all, and a way the knowledge file does not cover.
    Task("bedrock", 1, "bedrock", 2 * 60, impossible=True),
    Task("amethyst", 1, "amethyst_shard", 8 * 60, DEEP_KIT),
)
# Getting about: each course is carved around wherever the helper stands, then timed.
MOVES = (
    Task("trek", 1, "trek", 4 * 60, course="trek"),
    Task("climb", 1, "climb", 2 * 60, ("cobblestone:32",), course="climb"),
    Task("pit", 1, "pit", 2 * 60, ("stone_pickaxe:1",), course="pit"),
    Task("ravine", 1, "ravine", 2 * 60, ("cobblestone:32",), course="ravine"),
    Task("gap", 1, "gap", 2 * 60, course="gap"),
    Task("swim", 1, "swim", 2 * 60, course="swim"),
    Task("breath", 1, "breath", 2 * 60, course="breath"),
)


def ended(record: dict, outcome: str) -> str:
    return "" if record["outcome"] == outcome else f"ended {record['outcome']}"


def ordered(record: dict, word: str) -> str:
    """Empty when some get_items call's arguments mention `word`, such as a way or a biome."""
    if any(word in call["args"] for call in record["calls"] if call["tool"] == "get_items"):
        return ""
    return f"no get_items with {word}"


def dug_for_it(record: dict) -> str:
    return ended(record, "done") or ordered(record, '"dig"')


def searched_plains(record: dict) -> str:
    return ended(record, "done") or ordered(record, '"plains"')


def came_back(record: dict) -> str:
    tools = [call["tool"] for call in record["calls"]]
    gets = [index for index, tool in enumerate(tools) if tool == "get_items"]
    shape = "" if len(gets) >= 2 and "come_to" in tools[gets[-1]:] else "wanted two get_items, then come_to"
    return ended(record, "done") or shape


def told_inventory(record: dict) -> str:
    if record["mission"]:
        return "started a mission"
    return ended(record, "answered") or (
        "" if [turn["tool"] for turn in record["desk"]] == ["inventory"] else "wanted one inventory turn")


def refused(record: dict) -> str:
    if record["mission"]:
        return "started a mission"
    if record["outcome"] not in ("answered", "refused", "blocked") or not record["reason"]:
        return f"ended {record['outcome']} without saying why"
    return ""


# Said to the helper by name, so a model reads them. The plain gets after them
# are the typed commands for the same work, to check the fast path did not change.
# The two-item trip and the question have no typed command of their own.
AGENT = (
    Task("say-strip-mine", 1, "diamond", 20 * 60, DEEP_KIT, said="strip mine for 1 diamond", check=dug_for_it),
    Task("say-wool", 2, "white_wool", 8 * 60, said="get 2 white wool from the plains", check=searched_plains),
    Task("say-logs-stone", 4, "log", 10 * 60, said="get 4 logs and 4 cobblestone, then come back to me",
         check=came_back),
    Task("say-carrying", 0, "inventory", 60, said="what are you carrying?", check=told_inventory),
    Task("say-bedrock", 1, "bedrock", 2 * 60, said="get me bedrock", check=refused),
    Task("diamonds", 1, "diamond", 15 * 60, DEEP_KIT),
    Task("sheep", 2, "white_wool", 5 * 60),
    Task("bedrock", 1, "bedrock", 2 * 60, impossible=True),
)
SUITES = {"long": LONG, "skills": SKILLS + MOVES, "moves": MOVES, "agent": AGENT}
# Every lane copies one world, so they share its spawn. The helper is moved
# there before each trip: a fresh world does not move the player back.
SPAWN = (-32, -48)
# Skills start from a different place each repeat: spawn, then two spots far off.
SPOTS = (SPAWN, (480, -320), (-560, 640))


@dataclass
class Lane:
    name: str
    helper: str
    data: Path
    jobs: list[tuple[Task, int, tuple[int, int] | None]]


PRINT = threading.Lock()
# name, data dir, extra compose env. Stopped on the way out, including after a crash.
STARTED: list[tuple[str, Path, dict[str, str] | None]] = []


def say(message: str) -> None:
    with PRINT:
        print(message, flush=True)


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True).stdout.strip()


def git_rev() -> str:
    """The commit, plus a short hash of uncommitted code so two dirty runs can be told apart."""
    head = git("rev-parse", "--short", "HEAD") or "unknown"
    changes = git("diff", "HEAD", "--", *CODE)
    untracked = git("ls-files", "--others", "--exclude-standard", "--", *CODE).splitlines()
    if not changes and not untracked:
        return head
    digest = hashlib.sha1(changes.encode())
    for name in untracked:
        digest.update((ROOT / name).read_bytes())
    return f"{head}+{digest.hexdigest()[:8]}"


def take_lock() -> None:
    LAB.mkdir(parents=True, exist_ok=True)
    lock = LAB / "running"
    if lock.exists():
        try:
            pid = int(lock.read_text().strip() or "0")
        except ValueError:
            pid = 0
        if pid and pid_alive(pid):
            raise SystemExit(f"A suite is already running (pid {pid}).")
    lock.write_text(str(os.getpid()))


def pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    return True


def release_lock() -> None:
    lock = LAB / "running"
    if lock.exists() and lock.read_text().strip() == str(os.getpid()):
        lock.unlink()


def helper_name(lane: str) -> str:
    return f"{HELPER}-{lane.rsplit('_', 1)[-1]}"


def compose(name: str, data: Path, *args: str, check: bool = True,
            extra: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["LAB_NAME"] = name
    env["LAB_DATA"] = str(data)
    if extra:
        env.update(extra)
    result = subprocess.run(
        ["docker", "compose", "-p", name, "-f", str(COMPOSE), *args],
        env=env, text=True, capture_output=True,
    )
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError(f"{name} compose {' '.join(args)} failed: {detail}")
    return result


def stop_started() -> None:
    for name, data, extra in STARTED:
        compose(name, data, "stop", check=False, extra=extra)


def rcon(name: str, *args: str, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            ["docker", "exec", name, "rcon-cli", "--", *args],
            text=True, capture_output=True, timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(args, 1, "", "rcon timed out")


def running(name: str) -> bool:
    result = subprocess.run(
        ["docker", "inspect", "-f", "{{.State.Running}}", name],
        capture_output=True, text=True,
    )
    return result.stdout.strip() == "true"


def clean(text: str) -> str:
    return ANSI.sub("", text).strip()


def plugin_jar() -> Path:
    jars = [
        path for path in (ROOT / "server" / "build" / "libs").glob("minegoon-*.jar")
        if "sources" not in path.name and "javadoc" not in path.name
    ]
    if not jars:
        raise SystemExit("The build produced no plugin jar.")
    return max(jars, key=lambda path: path.stat().st_mtime)


def copy_data(src: Path, dest: Path) -> None:
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(src, dest, ignore=shutil.ignore_patterns("logs", "crash-reports", "*.log"))


def wipe_world(data: Path) -> None:
    for name in WORLD_NAMES:
        path = data / name
        if path.exists():
            shutil.rmtree(path)


def wipe_plugin_state(data: Path) -> None:
    folder = data / "plugins" / "Minegoon"
    folder.mkdir(parents=True, exist_ok=True)
    for path in folder.glob("minegoon.db*"):
        path.unlink()
    results = folder / "results.jsonl"
    if results.exists():
        results.unlink()


def install_jar(data: Path, jar: Path) -> None:
    plugins = data / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    shutil.copy(jar, plugins / "minegoon.jar")


def state_dir(data: Path, lane: str | None = None) -> Path:
    folder = data / "plugins" / "Minegoon"
    return folder / lane if lane else folder


def copy_lane(src: Path, dest: Path) -> None:
    """One trip's world: the saved seed, without the nether, the end, or the old player."""
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(src, dest)
    for relative in (
        "session.lock",
        "level.dat_old",
        "players",
        "dimensions/minecraft/the_nether",
        "dimensions/minecraft/the_end",
    ):
        path = dest / relative
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists():
            path.unlink()
    for tickets in dest.rglob("chunk_tickets.dat"):
        tickets.unlink()
    rename_level(dest / "level.dat", dest.name)


def rename_level(path: Path, name: str) -> None:
    with gzip.open(path, "rb") as handle:
        data = handle.read()
    key = b"LevelName"
    at = data.find(key)
    if at < 0:
        raise RuntimeError(f"{path} has no LevelName")
    length_at = at + len(key)
    old_len = int.from_bytes(data[length_at:length_at + 2], "big")
    value_at = length_at + 2
    encoded = name.encode()
    data = data[:length_at] + len(encoded).to_bytes(2, "big") + encoded + data[value_at + old_len:]
    with gzip.open(path, "wb") as handle:
        handle.write(data)


def write_lanes(path: Path) -> None:
    kept: list[str] = []
    skipping = False
    for line in path.read_text().splitlines():
        if not skipping and line.startswith("lanes:"):
            skipping = True
            continue
        if skipping:
            if line.startswith("  -") or not line.strip():
                if not line.strip():
                    skipping = False
                    kept.append(line)
                continue
            skipping = False
        kept.append(line)
    body = "\n".join(kept).rstrip() + "\nlanes:\n"
    body += "".join(f"  - {lane}\n" for lane in LANES)
    path.write_text(body)


def disable_end(path: Path) -> None:
    if path.exists():
        path.write_text(path.read_text().replace("allow-end: true", "allow-end: false", 1))


def result_count(data: Path, lane: str | None = None) -> int:
    path = state_dir(data, lane) / "results.jsonl"
    if not path.exists():
        return 0
    return sum(1 for line in path.read_text().splitlines() if line.strip())


def new_results(data: Path, lane: str | None, before: int) -> list[dict]:
    """The lines after the first `before`, up to one the server is still writing."""
    path = state_dir(data, lane) / "results.jsonl"
    if not path.exists():
        return []
    lines = []
    for line in [line for line in path.read_text().splitlines() if line.strip()][before:]:
        try:
            lines.append(json.loads(clean(line)))
        except json.JSONDecodeError:
            break
    return lines


def conversation_end(lines: list[dict]) -> dict | None:
    """What ends a spoken request: its mission, the task a plain get started, or a desk turn that started neither."""
    for line in lines:
        if "mission" in line or ("task" in line and "mission_id" not in line):
            return line
        turn = line.get("desk")
        if turn and not (turn["ok"] and turn["tool"] in ("start_mission", "get")):
            return line
    return None


def log_size(data: Path) -> int:
    path = data / "logs" / "latest.log"
    return path.stat().st_size if path.exists() else 0


def new_chat(data: Path, offset: int, helper: str) -> list[str]:
    path = data / "logs" / "latest.log"
    if not path.exists():
        return []
    with path.open("rb") as handle:
        handle.seek(offset)
        text = handle.read().decode("utf-8", "replace")
    return [clean(line) for line in text.splitlines() if helper in line]


def read_db(data: Path, lane: str | None, read: Callable[[sqlite3.Connection], object]) -> object:
    """Reads a copy of the job log, so the server's writes never wait on the harness."""
    folder = state_dir(data, lane)
    source = folder / "minegoon.db"
    if not source.exists():
        return None
    copied = folder / "minegoon.read.db"
    shutil.copy(source, copied)
    try:
        with sqlite3.connect(copied) as connection:
            return read(connection)
    finally:
        if copied.exists():
            copied.unlink()


def read_behavior(data: Path, lane: str | None = None) -> tuple[dict | None, list[dict]]:
    def read(connection: sqlite3.Connection) -> tuple[dict | None, list[dict]]:
        task = connection.execute(
            "SELECT id, goal, count, status, reason, deaths, model_calls FROM tasks ORDER BY id DESC LIMIT 1"
        ).fetchone()
        if task is None:
            return None, []
        keys = ("id", "goal", "count", "status", "reason", "deaths", "model_calls")
        events = [
            {"kind": kind, "detail": detail}
            for kind, detail in connection.execute(
                "SELECT kind, detail FROM events WHERE task_id = ? ORDER BY id", (task[0],)
            )
        ]
        return dict(zip(keys, task)), events

    return read_db(data, lane, read) or (None, [])


def events_since(data: Path, lane: str | None, started: float) -> list[dict]:
    """Every event from the trip's start: the desk and mission turns, and the tasks they ran."""
    rows = read_db(data, lane, lambda connection: connection.execute(
        "SELECT kind, detail FROM events WHERE at >= ? ORDER BY id", (int(started * 1000),)
    ).fetchall())
    return [{"kind": kind, "detail": detail} for kind, detail in rows or []]


def parse_numbers(text: str) -> list[float]:
    return [float(match) for match in re.findall(r"-?\d+(?:\.\d+)?", text)]


def sample(helper: str, started: float) -> dict:
    pos = rcon(CONTAINER, "data", "get", "entity", helper, "Pos")
    health = rcon(CONTAINER, "data", "get", "entity", helper, "Health")
    food = rcon(CONTAINER, "data", "get", "entity", helper, "foodLevel")
    tps = rcon(CONTAINER, "tps")
    numbers = parse_numbers(pos.stdout)
    block = [int(number) for number in numbers[:3]] if len(numbers) >= 3 else None
    health_numbers = parse_numbers(health.stdout)
    food_numbers = parse_numbers(food.stdout)
    return {
        "at": round(time.time() - started),
        "block": block,
        "health": health_numbers[0] if health_numbers else None,
        "food": food_numbers[0] if food_numbers else None,
        "tps": clean(tps.stdout).replace("\n", " "),
    }


def position(helper: str) -> tuple[int, int, int]:
    numbers = parse_numbers(rcon(CONTAINER, "data", "get", "entity", helper, "Pos").stdout)
    if len(numbers) < 3:
        raise RuntimeError(f"could not read where {helper} is")
    return math.floor(numbers[0]), math.floor(numbers[1]), math.floor(numbers[2])


def inventory(helper: str) -> dict[str, int]:
    """What the helper is holding, item name to count. One stack at a time: the
    server cuts a long answer short."""
    held: dict[str, int] = {}
    for index in range(41):
        text = clean(rcon(CONTAINER, "data", "get", "entity", helper, f"Inventory[{index}]").stdout)
        item = re.search(r'id: "minecraft:(\w+)"', text)
        if not item:
            break
        count = re.search(r"count: (\d+)", text)
        held[item.group(1)] = held.get(item.group(1), 0) + int(count.group(1) if count else 1)
    return held


def stood_still(samples: list[dict]) -> str:
    run = 1
    worst = 1
    for previous, current in zip(samples, samples[1:]):
        if previous["block"] and previous["block"] == current["block"]:
            run += 1
            worst = max(worst, run)
        else:
            run = 1
    if worst >= 4:
        return f"stood on one block for about {worst * 30}s"
    return ""


def wait_ready(name: str, timeout: int, *status: str, helper: str = HELPER) -> None:
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        if running(name):
            result = rcon(name, *status)
            last = clean(result.stdout or result.stderr)
            if result.returncode == 0 and helper in last:
                return
        time.sleep(5)
    raise RuntimeError(f"{name} did not come up. Last status: {last.splitlines()[0] if last else 'none'}")


def wait_lanes(timeout: int) -> None:
    pending = set(LANES)
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline and pending:
        if running(CONTAINER):
            for lane in list(pending):
                result = rcon(CONTAINER, "minegoon", "lane", lane, "status")
                last = clean(result.stdout or result.stderr)
                if result.returncode == 0 and helper_name(lane) in last:
                    pending.discard(lane)
            if not pending:
                return
        time.sleep(5)
    raise RuntimeError(
        f"{CONTAINER} did not come up. Still waiting on {', '.join(sorted(pending)) or 'the server'}. "
        f"Last status: {last.splitlines()[0] if last else 'none'}"
    )


def start_server(name: str, data: Path, extra: dict[str, str] | None = None, recreate: bool = False) -> None:
    exists = subprocess.run(["docker", "inspect", name], capture_output=True)
    if exists.returncode != 0 or recreate:
        compose(name, data, "up", "-d", extra=extra)
    else:
        compose(name, data, "start", extra=extra)


def stop_server(name: str, data: Path, extra: dict[str, str] | None = None) -> None:
    compose(name, data, "stop", extra=extra)


def prepare_template(name: str) -> None:
    reset = rcon(name, "minegoon", "reset")
    if reset.returncode != 0:
        raise RuntimeError(clean(reset.stdout or reset.stderr) or "reset failed")
    rcon(name, "time", "set", "day")
    rcon(name, "weather", "clear")


def prepare_lane(lane: Lane) -> None:
    args = ["minegoon", "lane", lane.name, "reset", "clear"]
    for tries_left in (1, 0):
        reset = rcon(CONTAINER, *args)
        text = clean(reset.stdout or reset.stderr)
        if reset.returncode == 0 and "reset to spawn" in text:
            return
        if tries_left:
            say(f"[{lane.helper.removeprefix(HELPER + '-')}] reset failed ({text or 'no reply'}), once more")
            time.sleep(5)
    raise RuntimeError(text or "reset failed")


def hand_kit(lane: Lane, task: Task) -> None:
    for entry in task.kit:
        item, count = entry.split(":")
        given = rcon(CONTAINER, "give", lane.helper, item, count)
        if given.returncode != 0:
            raise RuntimeError(f"could not give {entry}: {clean(given.stdout or given.stderr)}")


def move_to(lane: Lane, spot: tuple[int, int]) -> None:
    x, z = spot
    moved = rcon(CONTAINER, "execute", "in", f"minecraft:{lane.name}", "run", "spreadplayers",
                 str(x), str(z), "0", "24", "false", lane.helper)
    text = clean(moved.stdout or moved.stderr)
    if moved.returncode != 0 or "Spread" not in text:
        raise RuntimeError(text or "spreadplayers failed")
    # Respawn where the trip starts, as a player's bed would be, not at world spawn far off.
    in_lane(lane, "execute", "as", lane.helper, "at", "@s", "run", "spawnpoint", "@s", "~", "~", "~")
    time.sleep(3)


def in_lane(lane: Lane, *command: str) -> str:
    done = rcon(CONTAINER, "execute", "in", f"minecraft:{lane.name}", "run", *command)
    text = clean(done.stdout or done.stderr)
    if done.returncode != 0:
        raise RuntimeError(text or f"{command[0]} failed")
    return text


def fill(lane: Lane, x1: int, y1: int, z1: int, x2: int, y2: int, z2: int, block: str) -> None:
    text = in_lane(lane, "fill", *map(str, (x1, y1, z1, x2, y2, z2)), block)
    if "filled" not in text.lower():
        raise RuntimeError(text or "fill failed")


def ground(lane: Lane, x: int, y: int, z: int) -> int:
    """The height to stand at in this column: on top of the first block under
    the feet that is not part of a tree or a plant."""
    for floor in range(y - 1, y - 48, -1):
        cell = (str(x), str(floor), str(z))
        test = rcon(CONTAINER, "execute", "in", f"minecraft:{lane.name}",
                    "unless", "block", *cell, "#minecraft:leaves",
                    "unless", "block", *cell, "#minecraft:logs",
                    "unless", "block", *cell, "#minecraft:replaceable")
        if "passed" in clean(test.stdout).lower():
            return floor + 1
    raise RuntimeError(f"no ground under {x} {y} {z}")


def carve(lane: Lane, course: str) -> list[str]:
    """Builds the course around the helper. Returns where to go, as goto arguments."""
    x, y, z = position(lane.helper)
    if course == "trek":
        return [str(x + 300), str(z)]
    # Spawn can be a treetop. The built courses go in the ground under it.
    y = ground(lane, x, y, z)
    fill(lane, x, y, z, x, y + 1, z, "air")
    in_lane(lane, "tp", lane.helper, f"{x + 0.5}", str(y), f"{z + 0.5}")
    if course == "ravine":
        fill(lane, x + 3, y - 15, z - 20, x + 7, y + 6, z + 20, "air")
        return [str(x + 10), str(z)]
    if course == "pit":
        fill(lane, x - 1, y - 7, z - 1, x + 1, y - 7, z + 1, "stone")
        fill(lane, x - 1, y - 6, z - 1, x + 1, y + 6, z + 1, "air")
        in_lane(lane, "tp", lane.helper, f"{x + 0.5}", str(y - 6), f"{z + 0.5}")
        return [str(x + 8), str(z)]
    # A flat, open yard for the built courses.
    fill(lane, x - 2, y, z - 10, x + 14, y + 8, z + 10, "air")
    fill(lane, x - 2, y - 1, z - 10, x + 14, y - 1, z + 10, "stone")
    if course == "climb":
        fill(lane, x + 4, y, z - 10, x + 12, y + 5, z + 10, "stone")
        return [str(x + 8), str(y + 6), str(z)]
    if course == "gap":
        fill(lane, x + 3, y - 4, z - 10, x + 4, y - 4, z + 10, "stone")
        fill(lane, x + 3, y - 3, z - 10, x + 4, y - 1, z + 10, "air")
        return [str(x + 8), str(y), str(z)]
    if course == "swim":
        # A lake: deep water whose surface is a block below the banks, so getting out means climbing.
        fill(lane, x + 14, y, z - 10, x + 26, y + 8, z + 10, "air")
        fill(lane, x + 2, y - 5, z - 11, x + 26, y - 1, z + 11, "stone")
        fill(lane, x + 3, y - 4, z - 10, x + 22, y - 1, z + 10, "water")
        return [str(x + 25), str(y), str(z)]
    if course == "breath":
        # Starts on the floor of a pool under a lid it cannot dig; the only air is a hole six blocks off.
        fill(lane, x + 14, y, z - 10, x + 18, y + 8, z + 10, "air")
        fill(lane, x + 2, y - 7, z - 4, x + 18, y - 2, z + 4, "stone")
        fill(lane, x + 2, y - 1, z - 4, x + 18, y - 1, z + 4, "obsidian")
        fill(lane, x + 3, y - 6, z - 3, x + 15, y - 2, z + 3, "water")
        fill(lane, x + 11, y - 1, z - 1, x + 12, y - 1, z + 1, "water")
        in_lane(lane, "tp", lane.helper, f"{x + 5.5}", str(y - 6), f"{z + 0.5}")
        return [str(x + 16), str(y), str(z)]
    raise RuntimeError(f"no course named {course}")


def put_world_back(lane: Lane) -> None:
    unloaded = rcon(CONTAINER, "minegoon", "lane", lane.name, "unload")
    text = clean(unloaded.stdout or unloaded.stderr)
    if unloaded.returncode != 0 or "Unloaded " not in text:
        raise RuntimeError(text or "unload failed")
    time.sleep(1)
    # Paper moves a loaded world into the main world's dimensions folder and
    # will not move it there again, so the moved copy goes too.
    migrated = lane.data / "world" / "dimensions" / "minecraft" / lane.name
    if migrated.exists():
        shutil.rmtree(migrated)
    copy_lane(TEMPLATE / "world", lane.data / lane.name)
    # Two lanes reloading at once can get an empty answer back; the load itself is safe to repeat.
    text = ""
    for _ in range(3):
        loaded = rcon(CONTAINER, "minegoon", "lane", lane.name, "load")
        text = clean(loaded.stdout or loaded.stderr)
        if loaded.returncode == 0 and "Loaded " in text:
            return
        time.sleep(2)
    raise RuntimeError(text or "load failed")


def stop_practice() -> bool:
    """Stops the usual practice server. Returns whether it was running."""
    running = subprocess.run(
        ["docker", "compose", "-f", str(MAIN_COMPOSE), "ps", "-q", "--status", "running"],
        cwd=ROOT, capture_output=True, text=True, check=False,
    ).stdout.strip()
    if not running:
        return False
    say("Stopping the usual practice server so the suite fits in Docker")
    subprocess.run(["docker", "compose", "-f", str(MAIN_COMPOSE), "stop"], cwd=ROOT, check=False)
    return True


def start_practice() -> None:
    say("Starting the usual practice server again")
    subprocess.run(["docker", "compose", "-f", str(MAIN_COMPOSE), "start"], cwd=ROOT, check=False)


# What the helper learned last run, kept across the reset: lessons for every
# lane, and the structures each lane has seen in its copy of the world.
CARRIED = ("lessons.json", "*/structures.json")


def carry_over(data: Path) -> dict[str, bytes]:
    folder = data / "plugins" / "Minegoon"
    return {str(path.relative_to(folder)): path.read_bytes()
            for pattern in CARRIED for path in folder.glob(pattern)}


def restore(data: Path, carried: dict[str, bytes]) -> None:
    folder = data / "plugins" / "Minegoon"
    for relative, content in carried.items():
        path = folder / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)


def ensure_template(jar: Path) -> None:
    """Boot a fresh seed once and keep that world."""
    ready = TEMPLATE / ".ready"
    if ready.exists() and ready.read_text() == TEMPLATE_READY and (TEMPLATE / "world" / "level.dat").exists():
        say("Reusing the saved world")
        return

    say("Making a fresh world from seed minegoon")
    if TEMPLATE.exists():
        shutil.rmtree(TEMPLATE)
    copy_data(ROOT / "infra" / "data", TEMPLATE)
    wipe_world(TEMPLATE)
    wipe_plugin_state(TEMPLATE)
    install_jar(TEMPLATE, jar)
    config = TEMPLATE / "plugins" / "Minegoon" / "config.yml"
    if not config.exists() or "lab: true" not in config.read_text():
        raise SystemExit("The practice config is missing lab: true, so reset would not work.")

    name = "minegoon-lab-template"
    STARTED.append((name, TEMPLATE, None))
    start_server(name, TEMPLATE)
    wait_ready(name, 300, "minegoon", "status")
    prepare_template(name)
    rcon(name, "save-all", "flush")
    time.sleep(2)
    stop_server(name, TEMPLATE)
    ready.write_text(TEMPLATE_READY)
    say("Fresh world saved")


def jdk_home() -> Path:
    """Paper's Gradle plugin needs Java 25. On a Mac that JDK lives under
    ~/.local/jdks. A Gradle toolchain download (Linux included) lives under
    ~/.gradle/jdks and is just as good."""
    homes = sorted(Path.home().joinpath(".local/jdks").glob("jdk-*/Contents/Home/bin/java"))
    homes += sorted(Path.home().joinpath(".gradle/jdks").glob("*/bin/java"))
    if not homes:
        raise SystemExit("No JDK 25 in ~/.local/jdks or ~/.gradle/jdks, and the Java on PATH is too old to build.")
    return homes[-1].resolve().parents[1]


def build() -> Path:
    home = jdk_home()
    # A Mac JDK's home directory is named Home; the version is two levels up.
    # A toolchain JDK's home directory is already the version folder.
    label = home.parent.parent.name if home.name == "Home" else home.name
    say(f"Building the plugin with {label}")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(home)
    subprocess.run(
        ["./gradlew", "build", "-q", "--no-daemon"], cwd=ROOT / "server", env=env, check=True
    )
    return plugin_jar()


def output_dir(suite: str, iteration: int) -> Path:
    root = RESULTS if suite == "long" else RESULTS.with_name(suite)
    root.mkdir(parents=True, exist_ok=True)
    dest = root / f"iter-{iteration}"
    if dest.exists():
        dest.rename(root / f"iter-{iteration}-old-{time.strftime('%H%M%S')}")
    dest.mkdir()
    return dest


def assign(lanes: list[Lane], tasks: tuple[Task, ...], spread: bool) -> None:
    jobs = [
        (task, repeat, SPOTS[repeat - 1] if spread else SPAWN)
        for task in tasks for repeat in range(1, REPEATS + 1)
    ]
    for index, job in enumerate(jobs):
        lanes[index % len(lanes)].jobs.append(job)


def write_trip(out: Path, record: dict) -> None:
    stem = f"{record['task']}-r{record['repeat']}"
    (out / f"{stem}.json").write_text(json.dumps(record, indent=2) + "\n")
    talk = [
        f"said: \"{record['said']}\"",
        f"turns {record['turns']}, planner calls {record['strong_calls']}, cost ${record['cost']:.4f}",
        f"problem: {record['problem'] or '-'}",
        "calls:",
        *(f"  {call['tool']} {call['args']} -> {'ok' if call['ok'] else 'failed'}" for call in record["calls"]),
    ] if record.get("said") else []
    lines = [
        f"{record['task']} repeat {record['repeat']} on {record['worker']}",
        *talk,
        f"outcome: {record['outcome']} in {record['seconds']}s, deaths {record['deaths']}, "
        f"model calls {record['model_calls']}",
        f"reason: {record['reason'] or '-'}",
        f"stood still: {record['stood_still'] or 'no'}",
        f"holding: {', '.join(f'{count} {item}' for item, count in record.get('inventory', {}).items()) or '-'}",
        "",
        "chat:",
    ]
    chat = record["chat"][-40:]
    lines.extend(f"  {line}" for line in chat) if chat else lines.append("  (nothing)")
    lines.extend(["", "events:"])
    events = record["events"]
    lines.extend(f"  {event['kind']}: {event['detail']}" for event in events) if events else lines.append("  (none)")
    lines.extend(["", "where it was:"])
    for point in record["samples"]:
        lines.append(
            f"  t+{point['at']}s {point['block']} health {point['health']} food {point['food']} {point['tps']}"
        )
    (out / f"{stem}.txt").write_text("\n".join(lines) + "\n")


def hint(record: dict) -> str:
    if record["outcome"] == "done" and not record["stood_still"]:
        return ""
    failed = [event["detail"] for event in record["events"] if event["kind"] == "failed"]
    pieces = []
    if record["stood_still"]:
        pieces.append(record["stood_still"])
    if failed:
        pieces.append(failed[-1])
    elif record["reason"]:
        pieces.append(record["reason"])
    return " ".join("; ".join(pieces).split())[:180]


def run_one(lane: Lane, task: Task, repeat: int, spot: tuple[int, int], out: Path, revision: str) -> dict:
    mark = lane.helper.removeprefix(HELPER + "-")
    verb = "course" if task.course else "spar" if task.spar else "ask" if task.said else "get"
    asked = f'"{task.said}"' if task.said else f"{task.count} {task.item}"
    say(f"[{mark}] {task.id} r{repeat}: {verb} {asked} from {spot}")
    prepare_lane(lane)
    move_to(lane, spot)
    hand_kit(lane, task)
    target = carve(lane, task.course) if task.course else []
    before = result_count(lane.data, lane.name)
    offset = log_size(lane.data)
    started = time.time()
    samples = [sample(lane.helper, started)]
    if task.course:
        command = ["goto", *target]
    elif task.spar:
        command = ["spar", task.spar, str(task.count)]
    elif task.said:
        command = ["ask", f"{lane.helper}, {task.said}"]
    else:
        command = ["get", str(task.count), task.item]
    launch = rcon(CONTAINER, "minegoon", "lane", lane.name, *command)
    if task.said and "OpenRouter key" in launch.stdout:
        raise RuntimeError("the agent suite needs openrouter.api-key in the lab's config.yml")
    if launch.returncode != 0:
        say(f"[{mark}] {verb} was refused: {clean(launch.stdout or launch.stderr)}")
    next_sample = started + 30
    outcome = ""
    lines: list[dict] = []
    while True:
        lines = new_results(lane.data, lane.name, before)
        if conversation_end(lines) if task.said else lines:
            outcome = "finished"
            break
        if not running(CONTAINER):
            outcome = "crash"
            break
        now = time.time()
        if now - started >= task.timeout:
            outcome = "timeout"
            rcon(CONTAINER, "minegoon", "lane", lane.name, "stop")
            time.sleep(2)
            break
        if any("Say resume" in line for line in new_chat(lane.data, offset, lane.helper)):
            outcome = "paused"
            break
        if now >= next_sample:
            point = sample(lane.helper, started)
            samples.append(point)
            say(f"[{mark}] {task.id} r{repeat} t+{point['at']}s at {point['block']} "
                f"health {point['health']}")
            next_sample = now + 30
        time.sleep(5)

    try:
        lines = new_results(lane.data, lane.name, before)
    except OSError:
        pass
    result = lines[-1] if lines else {}
    if task.course:
        return trek_record(lane, task, repeat, spot, out, revision, result.get("trek"), outcome, samples, offset)
    if task.spar:
        return spar_record(lane, task, repeat, spot, out, revision, result.get("spar"), outcome, samples, offset)
    if task.said:
        return talk_record(lane, task, repeat, spot, out, revision, lines, outcome, samples, offset, started)
    try:
        task_row, events = read_behavior(lane.data, lane.name)
    except (OSError, sqlite3.Error) as error:
        task_row, events = None, [{"kind": "note", "detail": f"could not read the job log: {error}"}]
    status = str(result.get("status") or (task_row or {}).get("status") or outcome)
    if outcome == "timeout":
        status = "timeout"
    if outcome == "crash":
        status = "crash"
    record = {
        "iteration_rev": revision,
        "task": task.id,
        "item": task.item,
        "count": task.count,
        "repeat": repeat,
        "spot": spot,
        "worker": lane.name,
        "outcome": status.lower(),
        "seconds": result.get("seconds", round(time.time() - started)),
        "deaths": result.get("deaths", 0),
        "model_calls": result.get("model_calls", 0),
        "guard_catches": result.get("guard_catches", 0),
        "reason": result.get("reason") or (task_row or {}).get("reason"),
        "stood_still": stood_still(samples),
        "inventory": inventory(lane.helper),
        "chat": new_chat(lane.data, offset, lane.helper),
        "events": events,
        "samples": samples,
        "success": status.lower() == ("blocked" if task.impossible else "done"),
    }
    write_trip(out, record)
    note = hint(record)
    say(f"[{mark}] {task.id} r{repeat}: {record['outcome']} in {record['seconds']}s"
        + (f" ({note})" if note else ""))
    return record


def spar_record(lane: Lane, task: Task, repeat: int, spot: tuple[int, int] | None, out: Path, revision: str,
                fight: dict | None, outcome: str, samples: list[dict], offset: int) -> dict:
    fight = fight or {}
    if fight.get("won"):
        status = "won"
    elif fight.get("died"):
        status = "died"
    elif fight.get("fled"):
        status = "fled"
    else:
        status = "unfinished" if outcome == "finished" else outcome
    record = {
        "iteration_rev": revision,
        "task": task.id,
        "item": task.item,
        "count": task.count,
        "repeat": repeat,
        "spot": spot,
        "worker": lane.name,
        "outcome": status,
        "seconds": round(fight.get("ticks", 0) / 20),
        "deaths": 1 if fight.get("died") else 0,
        "model_calls": 0,
        "guard_catches": 0,
        "reason": None,
        "fight": fight,
        "stood_still": "",
        "inventory": inventory(lane.helper),
        "chat": new_chat(lane.data, offset, lane.helper),
        "events": [],
        "samples": samples,
        "success": status == "won",
    }
    write_trip(out, record)
    mark = lane.helper.removeprefix(HELPER + "-")
    say(f"[{mark}] {task.id} r{repeat}: {status} ({fight_note(record)})")
    return record


def trek_record(lane: Lane, task: Task, repeat: int, spot: tuple[int, int] | None, out: Path, revision: str,
                trek: dict | None, outcome: str, samples: list[dict], offset: int) -> dict:
    trek = trek or {}
    if trek.get("died"):
        status = "died"
    else:
        status = ("reached" if trek.get("reached") else "failed") if trek else outcome
    seconds = round(trek.get("ticks", 0) / 20, 1)
    record = {
        "iteration_rev": revision,
        "task": task.id,
        "item": task.item,
        "count": task.count,
        "repeat": repeat,
        "spot": spot,
        "worker": lane.name,
        "outcome": status,
        "seconds": seconds,
        "deaths": 1 if status == "died" else 0,
        "model_calls": 0,
        "guard_catches": 0,
        "reason": {"reached": None, "died": f"died on the way to {trek.get('to')}"}.get(
            status, f"ended at {trek.get('end')} going to {trek.get('to')}" if trek else f"no result ({outcome})"),
        "trek": trek,
        "speed": round(trek.get("blocks", 0) / seconds, 2) if seconds else 0,
        "stood_still": "",
        "inventory": inventory(lane.helper),
        "chat": new_chat(lane.data, offset, lane.helper),
        "events": [],
        "samples": samples,
        "success": status == "reached",
    }
    write_trip(out, record)
    mark = lane.helper.removeprefix(HELPER + "-")
    say(f"[{mark}] {task.id} r{repeat}: {status} in {seconds}s ({trek_note(record)})")
    return record


def talk_record(lane: Lane, task: Task, repeat: int, spot: tuple[int, int], out: Path, revision: str,
                lines: list[dict], outcome: str, samples: list[dict], offset: int, started: float) -> dict:
    end = conversation_end(lines) or {}
    mission = next((line for line in lines if "mission" in line), None)
    desk = [line["desk"] for line in lines if "desk" in line]
    if outcome != "finished":
        status, reason = outcome, None
    elif "desk" in end:
        status = "answered" if end["desk"]["ok"] else "refused"
        reason = end["desk"]["said"]
    else:
        status, reason = end["status"], end.get("reason")
    try:
        events = events_since(lane.data, lane.name, started)
    except (OSError, sqlite3.Error) as error:
        events = [{"kind": "note", "detail": f"could not read the job log: {error}"}]
    tasks = [line for line in lines if "task" in line]
    record = {
        "iteration_rev": revision,
        "task": task.id,
        "said": task.said,
        "item": task.item,
        "count": task.count,
        "repeat": repeat,
        "spot": spot,
        "worker": lane.name,
        "outcome": status,
        "seconds": end.get("seconds", round(time.time() - started)),
        "deaths": sum(line.get("deaths", 0) for line in tasks),
        "model_calls": (mission or {}).get("model_calls", 0) + len(desk),
        "turns": (mission or {}).get("turns", 0),
        "strong_calls": (mission or {}).get("strong_calls", 0),
        "cost": round((mission or {}).get("cost", 0) + sum(turn["cost"] for turn in desk), 5),
        "guard_catches": sum(line.get("guard_catches", 0) for line in tasks),
        "reason": reason,
        "mission": mission,
        "desk": desk,
        "calls": (mission or {}).get("calls", []),
        "stood_still": stood_still(samples),
        "inventory": inventory(lane.helper),
        "chat": new_chat(lane.data, offset, lane.helper),
        "events": events,
        "samples": samples,
    }
    record["problem"] = task.check(record) if task.check else ""
    record["success"] = not record["problem"]
    write_trip(out, record)
    mark = lane.helper.removeprefix(HELPER + "-")
    say(f"[{mark}] {task.id} r{repeat}: {status} in {record['seconds']}s ({talk_note(record)})")
    return record


def talk_note(record: dict) -> str:
    if "said" not in record:
        return hint(record)
    note = f"{record['turns']} turns, {record['strong_calls']} planner, ${record['cost']:.3f}"
    return note if record["success"] else f"{note}, {record['problem']}"


def trek_note(record: dict) -> str:
    note = f"{record.get('speed', 0)} blocks/s"
    return note if record["success"] else f"{note}, {record['reason']}"


def fight_note(record: dict) -> str:
    fight = record.get("fight") or {}
    if not fight:
        return ""
    return (f"{fight['hits']}/{fight['swings']} hits, {fight['crits']} crits, {fight.get('shots', 0)} arrows, "
            f"dealt {fight['dealt']:.0f} took {fight['taken']:.0f}")


def work(lane: Lane, out: Path, revision: str, records: list[dict], lock: threading.Lock) -> None:
    mark = lane.helper.removeprefix(HELPER + "-")
    for index, (task, repeat, spot) in enumerate(lane.jobs):
        try:
            if index:
                say(f"[{mark}] putting the fresh world back")
                put_world_back(lane)
            record = run_one(lane, task, repeat, spot, out, revision)
        except Exception as error:
            say(f"[{mark}] {task.id} r{repeat} crashed the harness: {error}")
            record = {
                "task": task.id, "repeat": repeat, "worker": lane.name, "outcome": "crash",
                "seconds": 0, "deaths": 0, "model_calls": 0, "reason": str(error),
                "stood_still": "", "success": False, "events": [], "chat": [], "samples": [],
                "item": task.item, "count": task.count, "guard_catches": 0,
            }
            write_trip(out, record)
            with lock:
                records.append(record)
            break
        with lock:
            records.append(record)


def summarize(out: Path, records: list[dict], tasks: tuple[Task, ...]) -> None:
    lines = ["| Task | Done | Times (s) | Deaths | Notes |", "|---|---|---|---|---|"]
    for task in tasks:
        rows = [record for record in records if record["task"] == task.id]
        done = sum(1 for record in rows if record["success"])
        times = ", ".join(str(record["seconds"]) for record in rows) or "-"
        deaths = ", ".join(str(record["deaths"]) for record in rows) or "-"
        note = trek_note if task.course else fight_note if task.spar else talk_note if task.said else hint
        notes = "; ".join(note(record) for record in rows if note(record)) or "-"
        lines.append(f"| {task.id} | {done}/{len(rows)} | {times} | {deaths} | {notes} |")
    text = "\n".join(lines) + "\n"
    (out / "summary.md").write_text(text)
    say("\n" + text)


def main() -> None:
    iteration = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    suite = sys.argv[2] if len(sys.argv) > 2 else "long"
    if suite not in SUITES:
        raise SystemExit(f"Unknown suite {suite}. Pick one of: {', '.join(SUITES)}.")
    only = set(sys.argv[3].split(",")) if len(sys.argv) > 3 else None
    tasks = tuple(task for task in SUITES[suite] if only is None or task.id in only)
    if not tasks:
        raise SystemExit(f"No task in {suite} is named {', '.join(sorted(only or ()))}.")
    take_lock()
    atexit.register(release_lock)
    revision = git_rev()
    jar = build()
    # Exit hooks run last first: the suite's servers stop before the practice one starts.
    if stop_practice():
        atexit.register(start_practice)
    atexit.register(stop_started)
    for name in ("minegoon-lab-a", "minegoon-lab-b", "minegoon-lab-c", CONTAINER, "minegoon-lab-template"):
        subprocess.run(["docker", "stop", name], capture_output=True)
    ensure_template(jar)
    out = output_dir(suite, iteration)
    data = LAB / CONTAINER
    say(f"Preparing {CONTAINER}")
    carried = carry_over(data) if data.exists() else {}
    copy_data(TEMPLATE, data)
    wipe_world(data)
    for lane_name in LANES:
        copy_lane(TEMPLATE / "world", data / lane_name)
    wipe_plugin_state(data)
    restore(data, carried)
    install_jar(data, jar)
    write_lanes(data / "plugins" / "Minegoon" / "config.yml")
    disable_end(data / "bukkit.yml")
    (data / ".ready").unlink(missing_ok=True)
    STARTED.append((CONTAINER, data, SUITE_ENV))
    start_server(CONTAINER, data, SUITE_ENV, recreate=True)
    wait_lanes(300)
    lanes = [Lane(name, helper_name(name), data, []) for name in LANES]
    assign(lanes, tasks, spread=suite in ("skills", "moves", "agent"))
    records: list[dict] = []
    lock = threading.Lock()
    threads = [
        threading.Thread(
            target=work, args=(lane, out, revision, records, lock), daemon=False
        )
        for lane in lanes
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    summarize(out, records, tasks)
    say(f"Timelines are in {out}")


if __name__ == "__main__":
    main()
