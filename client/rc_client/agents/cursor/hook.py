"""The `preToolUse` hook Cursor runs, and the one thing it prints.

Cursor spawns this process before every tool call, hands it the call on stdin
and reads a JSON object back on stdout: `{"permission": "allow"}` runs the tool,
`"deny"` refuses it, and printing nothing leaves Cursor's own rules in charge.
That last case is the common one — the hook belongs to the whole machine, so
most of the calls it sees are from a person's own `cursor-agent`, and the
daemon answers only for the conversations it drives itself.

Two rules shape the module, both borrowed from the Claude hook beside it.
Nothing but a decision is ever printed on stdout, because Cursor reads that
stream as the hook's answer. And a non-zero exit puts our stderr in front of
someone in the middle of their own work, for a feature they did not ask about:
every failure here is silent and the exit code is always 0.
"""

from __future__ import annotations

import contextlib
import json
import re
import signal
import socket
import sys
from typing import IO, Any

from ...channel import wire
from . import broker, runtime

MAX_STDIN_BYTES = 256 * 1024
CONNECT_TIMEOUT = 2.0
CONVERSATION_ID = re.compile(r"[A-Za-z0-9_-]{1,120}\Z")


def read_payload(stream: IO[bytes] | None = None) -> dict[str, Any] | None:
    """The single JSON object Cursor writes on stdin, capped."""
    source = stream if stream is not None else sys.stdin.buffer
    try:
        raw = source.read(MAX_STDIN_BYTES)
    except (OSError, ValueError):
        return None
    try:
        parsed = json.loads(raw)
    except (TypeError, ValueError):
        return None
    return parsed if isinstance(parsed, dict) else None


def request(payload: dict[str, Any]) -> dict[str, Any] | None:
    """What this call looks like to the daemon, or None when it says too little."""
    conversation_id = str(payload.get("conversation_id") or "")
    if not CONVERSATION_ID.match(conversation_id):
        return None
    tool_input = payload.get("tool_input")
    return {
        "type": broker.PRE_TOOL_USE,
        "conversation_id": conversation_id,
        "tool_name": str(payload.get("tool_name") or ""),
        "tool_input": tool_input if isinstance(tool_input, dict) else {},
        "tool_use_id": str(payload.get("tool_use_id") or ""),
        "cwd": str(payload.get("cwd") or ""),
    }


def ask(message: dict[str, Any]) -> str | None:
    """Raise the call with the daemon and wait for the one reply it sends.

    The wait has no deadline of its own: the tool call is held open at the other
    end for as long as Cursor lets this process live, and the daemon hanging up
    reads as "nobody remote answered".
    """
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(CONNECT_TIMEOUT)
        client.connect(str(runtime.socket_path()))
        client.sendall(wire.encode(message))
        client.settimeout(None)
        reply = _reply(client)
    if reply is None:
        return None
    permission = reply.get("permission")
    return permission if permission in broker.DECISIONS else None


def _reply(client: socket.socket) -> dict[str, Any] | None:
    """The first line the daemon sends, or None when it sends none."""
    buffer = bytearray()
    while len(buffer) <= wire.MAX_LINE_BYTES:
        chunk = client.recv(4096)
        if not chunk:
            return None
        buffer.extend(chunk)
        head, newline, _ = bytes(buffer).partition(b"\n")
        if newline:
            return wire.decode(head)
    return None


def relay() -> None:
    """Ask the daemon about this call and print whatever an app decided."""
    payload = read_payload()
    if payload is None:
        return
    message = request(payload)
    if message is None:
        return
    permission = ask(message)
    if permission is not None:
        sys.stdout.write(json.dumps({"permission": permission}))


def _quit(_signum: int, _frame: Any) -> None:
    """A hook Cursor gave up on says nothing, like every other failure here."""
    raise SystemExit(0)


def main() -> int:
    """Always 0: a failure here would interrupt somebody's own session."""
    with contextlib.suppress(Exception):
        signal.signal(signal.SIGTERM, _quit)
        relay()
    return 0
