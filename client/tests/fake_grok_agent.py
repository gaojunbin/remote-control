"""A fake `agent agent stdio` peer: real ACP framing, recorded replies.

The runner spawns this through a shim exactly as it spawns Grok, so the
subprocess, the JSON-RPC framing and the reader task are all exercised. What it
answers comes from `tests/fixtures/grok/`, recorded from the real agent.

`RC_FAKE_GROK_DIR` names a scratch directory: `mode` picks the scenario and
every message the peer receives is appended to `requests.jsonl`.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "grok"
SESSION_ID = "01a09b4f-1b25-7e92-b96b-356d292ff2d0"


def _handshake(name: str) -> dict[str, Any]:
    data: dict[str, Any] = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    return data


def _notifications(name: str) -> list[dict[str, Any]]:
    rows = []
    for line in (FIXTURES / name).read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


class FakePeer:
    def __init__(self) -> None:
        self.directory = Path(os.environ["RC_FAKE_GROK_DIR"])
        self.mode = (self.directory / "mode").read_text(encoding="utf-8").strip()
        self.log = (self.directory / "requests.jsonl").open("a", encoding="utf-8")
        self.cancelled = False
        self.prompt_id: Any = None

    # ------------------------------------------------------------------ wire

    def send(self, payload: dict[str, Any]) -> None:
        sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
        sys.stdout.flush()

    def result(self, message_id: Any, result: dict[str, Any]) -> None:
        self.send({"jsonrpc": "2.0", "id": message_id, "result": result})

    def notify(self, method: str, params: dict[str, Any]) -> None:
        self.send({"jsonrpc": "2.0", "method": method, "params": params})

    def record(self, message: dict[str, Any]) -> None:
        self.log.write(json.dumps(message, ensure_ascii=False) + "\n")
        self.log.flush()

    # ------------------------------------------------------------- scenarios

    def run(self) -> None:
        for line in sys.stdin:
            if not line.strip():
                continue
            message = json.loads(line)
            self.record(message)
            self.dispatch(message)

    def dispatch(self, message: dict[str, Any]) -> None:
        method = str(message.get("method") or "")
        message_id = message.get("id")
        if method == "session/cancel":
            self.cancel()
            return
        if not method:
            # The client answered a permission request, so the turn can finish.
            self.finish_prompt()
            return
        if message_id is None:
            return
        handshake = _handshake("handshake.json")
        if method == "initialize":
            self.result(message_id, handshake["initialize"])
        elif method == "session/new":
            self.result(message_id, handshake["session/new"])
            self.advertise_commands()
        elif method == "session/load":
            self.load(message_id)
            self.advertise_commands()
        elif method == "session/set_mode":
            self.result(message_id, {})
        elif method == "session/set_config_option":
            self.set_option(message_id, message.get("params") or {})
        elif method == "session/prompt":
            self.prompt(message_id)
        else:
            self.send(
                {
                    "jsonrpc": "2.0",
                    "id": message_id,
                    "error": {"code": -32601, "message": "Method not found"},
                }
            )

    def advertise_commands(self) -> None:
        """Grok pushes its whole command list unasked once a session is open."""
        self.send(_handshake("available-commands.json"))

    def load(self, message_id: Any) -> None:
        resumed = _handshake("resume-handshake.json")
        for row in _notifications("resume.jsonl"):
            meta = (row.get("params") or {}).get("_meta") or {}
            if meta.get("isReplay"):
                self.send(row)
        self.result(message_id, resumed["session/load"])

    def set_option(self, message_id: Any, params: dict[str, Any]) -> None:
        options: list[dict[str, Any]] = [
            {"id": "model", "name": "Model", "category": "model", "type": "select"},
            {
                "id": "reasoning_effort",
                "name": "Reasoning Effort",
                "category": "thought_level",
                "type": "select",
            },
        ]
        for option in options:
            current = params.get("value") if option["id"] == params.get("configId") else None
            option["currentValue"] = current or ("grok-4.6" if option["id"] == "model" else "high")
        self.result(message_id, {"configOptions": options})

    def prompt(self, message_id: Any) -> None:
        self.prompt_id = message_id
        if self.mode == "approval":
            self.ask_permission()
            return
        if self.mode == "cancel":
            for row in _notifications("turn.jsonl")[:4]:
                self.send(row)
            return
        self.finish_prompt()

    def finish_prompt(self) -> None:
        if self.prompt_id is None:
            return
        # A slash command runs inside Grok itself: one message, no tools, no usage.
        source = "command.jsonl" if self.mode == "command" else "turn.jsonl"
        for row in _notifications(source):
            self.send(row)
        self.result(self.prompt_id, {"stopReason": "end_turn"})
        self.prompt_id = None

    def ask_permission(self) -> None:
        self.send(
            {
                "jsonrpc": "2.0",
                "id": 9001,
                "method": "session/request_permission",
                "params": {
                    "sessionId": SESSION_ID,
                    "toolCall": {
                        "toolCallId": "call-1",
                        "title": "run_terminal_command",
                        "kind": "execute",
                        "rawInput": {"command": "ls -la"},
                    },
                    "options": [
                        {"optionId": "allow-once", "name": "Allow once", "kind": "allow_once"},
                        {
                            "optionId": "allow-always",
                            "name": "Always allow",
                            "kind": "allow_always",
                        },
                        {"optionId": "reject-once", "name": "Reject", "kind": "reject_once"},
                    ],
                },
            }
        )

    def cancel(self) -> None:
        self.cancelled = True
        self.notify(
            "_x.ai/session_notification",
            {
                "sessionId": SESSION_ID,
                "update": {
                    "sessionUpdate": "turn_completed",
                    "stop_reason": "cancelled",
                    "elapsed_ms": 120,
                    "usage": {"inputTokens": 10, "outputTokens": 2, "totalTokens": 12},
                },
                "_meta": {"eventId": f"{SESSION_ID}-99"},
            },
        )
        if self.prompt_id is not None:
            self.result(self.prompt_id, {"stopReason": "cancelled"})
            self.prompt_id = None


def main() -> int:
    peer = FakePeer()
    try:
        peer.run()
    except (BrokenPipeError, KeyboardInterrupt):
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
