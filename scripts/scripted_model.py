#!/usr/bin/env python3
"""A stand-in for OpenRouter that plays the models from a fixed script.

    scripted_model.py [port]

For running the agent suite with no key: it checks that chat, the front desk,
missions, tools and results all work in a real game, not what a real model
would choose. Point the lab's config at it:

    openrouter:
      api-key: stand-in
      url: http://172.17.0.1:8765/api/v1/chat/completions

A front desk turn is picked from words in the sentence. A mission writes the
plan scripted for its request, then makes that plan's calls in order, then
finishes. A failed call makes it give up with that call's receipt, so a
failure shows in the result instead of being worked around. Requests for a
JSON answer (the advice calls) get a 503, as if the model were down, so the
helper searches with what it knows. Every request and reply is appended to
scripted-model.jsonl next to this file.
"""

from __future__ import annotations

import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

LOG = Path(__file__).with_name("scripted-model.jsonl")


def have(item: str, count: int) -> dict:
    return {"kind": "have", "item": item, "count": count}


# Each request: words it contains, the plan's steps and done_when, and its calls.
# Stone comes before logs: the pickaxe for stone is made from logs.
MISSIONS = (
    (("strip mine", "diamond"), ["Strip mine for 1 diamond"], [have("diamond", 1)], [
        ("get_items", {"item": "diamond", "count": 1, "search": {"ways": ["dig"]}}),
    ]),
    (("wool", "plains"), ["Get 2 white wool from sheep in the plains"], [have("white_wool", 2)], [
        ("get_items", {"item": "white_wool", "count": 2, "search": {"biomes": ["plains"]}}),
    ]),
    (("logs", "cobblestone"), ["Get 4 cobblestone", "Get 4 logs", "Come back"],
     [have("cobblestone", 4), have("oak_log", 4), {"kind": "near", "within": 4}], [
        ("get_items", {"item": "cobblestone", "count": 4}),
        ("get_items", {"item": "oak_log", "count": 4}),
        ("come_to", {}),
    ]),
)


def desk(text: str) -> tuple[str, dict]:
    said = re.sub(r"^\S+,\s*", "", text).strip()
    lowered = said.lower()
    if "carrying" in lowered or "holding" in lowered:
        return "inventory", {}
    if "bedrock" in lowered:
        return "answer", {"text": "I can't get bedrock: it can't be broken or crafted in survival."}
    return "start_mission", {"request": said}


def mission(briefing: str) -> tuple[str, dict]:
    request = re.search(r'^Request from [^:]*: "(.*)"$', briefing, re.M)
    lowered = request.group(1).lower() if request else ""
    script = next((entry for entry in MISSIONS if all(word in lowered for word in entry[0])), None)
    if script is None:
        return "give_up", {"reason": f"the stand-in model has no script for {lowered!r}"}
    _, steps, done_when, calls = script
    if "Plan: none yet" in briefing:
        return "write_plan", {"steps": steps, "done_when": done_when}
    log = briefing.split("So far:\n", 1)[1].split("\nNow ", 1)[0].splitlines() if "So far:" in briefing else []
    made = [line for line in log if not line.startswith("note: ") and not line.startswith("write_plan ")]
    if made and " -> failed " in made[-1]:
        return "give_up", {"reason": made[-1].split(" -> ", 1)[1]}
    if len(made) < len(calls):
        return calls[len(made)]
    return "finish", {"summary": "Did every step of the plan."}


class Handler(BaseHTTPRequestHandler):

    def do_POST(self) -> None:
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if "tools" not in body:
            self.reply(503, {"error": {"message": "the stand-in only makes tool calls"}}, body)
            return
        names = {tool["function"]["name"] for tool in body["tools"]}
        user = body["messages"][-1]["content"]
        if "start_mission" in names:
            name, args = desk(user.split(" said: ", 1)[1].splitlines()[0])
        else:
            name, args = mission(user)
        model = body.get("models", [body.get("model", "stand-in")])[0]
        self.reply(200, {
            "id": "stand-in",
            "model": model,
            "choices": [{"message": {"role": "assistant", "content": None, "tool_calls": [{
                "id": "call-1", "type": "function",
                "function": {"name": name, "arguments": json.dumps(args)},
            }]}}],
            "usage": {"prompt_tokens": len(user) // 4, "completion_tokens": 20, "cost": 0.0},
        }, body)

    def reply(self, status: int, answer: dict, body: dict) -> None:
        data = json.dumps(answer).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        with LOG.open("a") as log:
            log.write(json.dumps({"briefing": body["messages"][-1]["content"], "status": status,
                                  "answer": answer.get("choices", [{}])[0].get("message", answer)}) + "\n")

    def log_message(self, format: str, *args: object) -> None:
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
