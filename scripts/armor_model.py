#!/usr/bin/env python3
"""OpenRouter-shaped stand-in for one request: a full set of diamond armor.

The plugin calls this the way it calls OpenRouter. The planner model name is
treated as the big model and the desk and advice names as the small one. Each
call is appended to scripts/armor-model/calls.jsonl before it is answered, so
a run still has the list if the plugin log is lost.

It never orders strip mining. Diamond armor is one get_items for all 24
diamonds, so the helper packs for one trip down and stays there, then one
get_items per piece. The body decides how to reach the ore.
"""

from __future__ import annotations

import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "armor-model"
LOG = ROOT / "calls.jsonl"
PIECES = ("diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots")
DIAMONDS = 24

PLAN = {
    "steps": [
        f"Get all {DIAMONDS} diamonds in one trip down, through caves. Do not strip mine.",
        "Craft one diamond helmet, one diamond chestplate, one diamond leggings, and one diamond boots.",
    ],
    "done_when": [{"kind": "have", "item": piece, "count": 1} for piece in PIECES],
}


def piece_done(briefing: str, piece: str) -> bool:
    for line in briefing.splitlines():
        if line.startswith("get_items") and f'"item":"{piece}"' in line and "-> ok " in line:
            return True
    return False


def next_piece(briefing: str) -> str | None:
    for piece in PIECES:
        if not piece_done(briefing, piece):
            return piece
    return None


def choose(body: dict) -> dict:
    """An answer file: either a tool call or a JSON content object."""
    user = body["messages"][-1]["content"]
    if "tools" not in body:
        schema = body.get("response_format", {}).get("json_schema", {}).get("name", "")
        if schema == "verdict":
            return {"content": {
                "possible": True,
                "reason": "Diamond armor is crafted from diamonds, and diamonds are mined near y=-59.",
                "ways": ["cave", "cavern"],
                "biomes": [],
                "visit": [],
                "blocks": ["diamond_ore", "deepslate_diamond_ore"],
            }}
        if "diamond" not in user.splitlines()[0]:
            return {"content": {"ways": [], "biomes": [], "visit": [], "note": ""}}
        return {"content": {
            "ways": ["cave", "cavern"],
            "biomes": [],
            "visit": [],
            "note": "Stay near y=-59 and keep walking the caves.",
        }}
    names = [tool["function"]["name"] for tool in body["tools"]]
    if "start_mission" in names:
        said = user
        for line in user.splitlines():
            if " said: " in line:
                said = line.split(" said: ", 1)[1]
                break
        return {"tool": "start_mission", "arguments": {"request": said}}
    if "get_items" not in names:
        return {"tool": "write_plan", "arguments": PLAN}
    piece = next_piece(user)
    if piece is None:
        return {"tool": "finish", "arguments": {"summary": "Holding a full set of diamond armor."}}
    if piece == PIECES[0] and not piece_done(user, "diamond"):
        return {"tool": "get_items", "arguments": {"item": "diamond", "count": DIAMONDS}}
    return {"tool": "get_items", "arguments": {"item": piece, "count": 1}}


class Handler(BaseHTTPRequestHandler):

    def do_POST(self) -> None:
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        model = (body.get("models") or ["live"])[0]
        answer = choose(body)
        user = body["messages"][-1]["content"]
        size = "big" if model == "big" else "small"
        what = answer.get("tool") or "json"
        record = {"size": size, "model": model, "what": what, "arguments": answer.get("arguments") or answer.get("content")}
        ROOT.mkdir(parents=True, exist_ok=True)
        with LOG.open("a") as log:
            log.write(json.dumps(record) + "\n")
        print(f"model fire: {size} {model} {what}", flush=True)
        if "tool" in answer:
            message = {"role": "assistant", "content": None, "tool_calls": [{
                "id": "call-1", "type": "function",
                "function": {"name": answer["tool"], "arguments": json.dumps(answer.get("arguments") or {})},
            }]}
        else:
            message = {"role": "assistant", "content": json.dumps(answer["content"])}
        payload = {
            "model": model,
            "choices": [{"message": message}],
            "usage": {"prompt_tokens": max(1, len(user) // 4), "completion_tokens": 40, "cost": 0.0},
        }
        data = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format: str, *args: object) -> None:
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    ROOT.mkdir(parents=True, exist_ok=True)
    print(f"armor model listening on {port}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
