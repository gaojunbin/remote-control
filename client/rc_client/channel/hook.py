"""`rc-client hook session-start`: which session a terminal is in, now.

Claude Code runs this from the `SessionStart` hook the shim installs, on
startup and again on `/resume`, `/clear` and a compaction. It opens its own
connection to the daemon socket, writes one `session_start` frame and leaves;
the connection is not an attachment and the daemon answers nothing.

Two rules shape the whole module. **Anything this process prints on stdout is
appended to the model's context**, so it prints nothing there, ever. And a
non-zero exit puts our stderr in front of the person, in the middle of their
own work, for a feature they did not ask about: every failure here is silent
and the exit code is always 0.
"""

from __future__ import annotations

import contextlib
import json
import os
import re
import socket
import subprocess
import sys
import time
from collections.abc import Callable
from typing import IO, Any

from . import paths, wire

MAX_STDIN_BYTES = 64 * 1024
SESSION_ID = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")

# One `ps` per ancestor, with a budget for the walk as a whole: Claude Code
# holds the session open while the hook runs.
PS_TIMEOUT = 1.0
ANCESTRY_BUDGET = 2.0
MAX_ANCESTRY_LEVELS = 6
SOCKET_TIMEOUT = 1.0

# (ppid, command line) of one process, or None when it cannot be read.
PsRunner = Callable[[int], tuple[int, str] | None]


def _ps(pid: int) -> tuple[int, str] | None:
    try:
        result = subprocess.run(
            ["ps", "-o", "ppid=,command=", "-p", str(pid)],
            capture_output=True,
            text=True,
            timeout=PS_TIMEOUT,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    head, _, command = result.stdout.strip().partition(" ")
    try:
        return int(head), command.strip()
    except ValueError:
        return None


def looks_like_claude(command: str) -> bool:
    """The same test the holder scan uses: the CLI itself, not one of its helpers."""
    for token in command.split()[:3]:
        base = os.path.basename(token)
        if base in {"claude", "claude-code"}:
            return True
        if base == "cli.js" and "claude" in token:
            return True
    return False


def claude_pid(runner: PsRunner | None = None) -> int:
    """The Claude Code process this hook belongs to.

    Today the hook's `/bin/sh` is a direct child of the CLI, so the parent is
    already the answer; walking up a few levels costs one `ps` each and keeps
    the answer right if a wrapper is ever inserted between the two.
    """
    ps = runner or _ps
    deadline = time.monotonic() + ANCESTRY_BUDGET
    parent = os.getppid()
    pid = parent
    for _ in range(MAX_ANCESTRY_LEVELS):
        if pid <= 1 or time.monotonic() >= deadline:
            break
        row = ps(pid)
        if row is None:
            break
        ppid, command = row
        if looks_like_claude(command):
            return pid
        pid = ppid
    return parent


def read_payload(stream: IO[bytes] | None = None) -> dict[str, Any] | None:
    """The single JSON object Claude Code writes on stdin, capped."""
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


def frame(payload: dict[str, Any], pid: int) -> dict[str, Any] | None:
    """The `session_start` frame for this payload, or None when it says nothing usable."""
    session_id = str(payload.get("session_id") or "")
    if not SESSION_ID.match(session_id):
        return None
    source = str(payload.get("source") or "")
    return wire.session_start(
        session_id=session_id,
        cwd=str(payload.get("cwd") or ""),
        pid=pid,
        source=source if source in wire.SESSION_START_SOURCES else "startup",
        transcript_path=str(payload.get("transcript_path") or ""),
    )


def send(message: dict[str, Any]) -> None:
    """One frame to the daemon, then close. Nothing comes back."""
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(SOCKET_TIMEOUT)
        client.connect(str(paths.socket_path()))
        client.sendall(wire.encode(message))


def report() -> None:
    """Tell the daemon where this terminal is now, if the payload says."""
    payload = read_payload()
    if payload is None:
        return
    message = frame(payload, claude_pid())
    if message is not None:
        send(message)


def main() -> int:
    """Always 0: a failure here would interrupt the person's own session."""
    with contextlib.suppress(Exception):
        report()
    return 0
