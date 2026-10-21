#!/usr/bin/env python3
"""An OpenRouter-shaped endpoint that waits for a chosen tool call.

    scripts/live_model.py [port]

The lab points openrouter.url at this process. Each call is written to
scripts/live-model/pending/<id>.json. The process waits until
scripts/live-model/answers/<id>.json appears, then answers the game.

An answer is either a tool call or a JSON object:

    {"tool": "write_plan", "arguments": {"steps": ["..."], "done_when": []}}
    {"content": {"possible": false, "reason": "...", "ways": [], "biomes": [], "visit": [], "blocks": []}}

This is how a model that is not behind OpenRouter can still drive a lab pass.
The pending files are the briefings. Nothing here chooses a tool on its own.
"""

from __future__ import annotations

import json
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "live-model"
PENDING = ROOT / "pending"
ANSWERS = ROOT / "answers"
LOG = ROOT / "calls.jsonl"
WAIT_SECONDS = 170


def brief(body: dict) -> str:
    message = body["messages"][-1]["content"]
    if "tools" in body:
        names = ", ".join(tool["function"]["name"] for tool in body["tools"])
        return f"tools [{names}]\n{message}"
    schema = body.get("response_format", {}).get("json_schema", {}).get("name", "json")
    return f"json {schema}\n{message}"


class Handler(BaseHTTPRequestHandler):

    def do_POST(self) -> None:
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        call_id = uuid.uuid4().hex[:12]
        PENDING.mkdir(parents=True, exist_ok=True)
        ANSWERS.mkdir(parents=True, exist_ok=True)
        (PENDING / f"{call_id}.json").write_text(json.dumps({
            "id": call_id,
            "model": (body.get("models") or [body.get("model", "")])[0],
            "kind": "tool" if "tools" in body else "json",
            "tools": [tool["function"]["name"] for tool in body.get("tools", [])],
            "system": body["messages"][0]["content"],
            "user": body["messages"][-1]["content"],
        }, indent=2))
        print(f"waiting {call_id}: {brief(body).splitlines()[0]}", flush=True)
        answer_path = ANSWERS / f"{call_id}.json"
        deadline = time.time() + WAIT_SECONDS
        while time.time() < deadline and not answer_path.exists():
            time.sleep(0.2)
        if not answer_path.exists():
            self.reply(504, {"error": {"message": f"no answer for {call_id}"}}, body, call_id)
            return
        answer = json.loads(answer_path.read_text())
        model = (body.get("models") or ["live"])[0]
        user = body["messages"][-1]["content"]
        if "tool" in answer:
            message = {"role": "assistant", "content": None, "tool_calls": [{
                "id": "call-1", "type": "function",
                "function": {"name": answer["tool"], "arguments": json.dumps(answer.get("arguments") or {})},
            }]}
        else:
            message = {"role": "assistant", "content": json.dumps(answer["content"])}
        self.reply(200, {
            "id": call_id,
            "model": model,
            "choices": [{"message": message}],
            "usage": {
                "prompt_tokens": len(user) // 4,
                "completion_tokens": 40,
                "cost": 0.0,
            },
        }, body, call_id)

    def reply(self, status: int, payload: dict, body: dict, call_id: str) -> None:
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        with LOG.open("a") as log:
            log.write(json.dumps({
                "id": call_id,
                "status": status,
                "user": body["messages"][-1]["content"],
                "answer": payload.get("choices", [{}])[0].get("message", payload),
            }) + "\n")
        print(f"answered {call_id} {status}", flush=True)

    def log_message(self, format: str, *args: object) -> None:
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    PENDING.mkdir(parents=True, exist_ok=True)
    ANSWERS.mkdir(parents=True, exist_ok=True)
    print(f"listening on {port}, answers go in {ANSWERS}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
