"""The newline-delimited JSON protocol between a channel bridge and the daemon.

One JSON object per line, UTF-8, no embedded newlines. Bridges are short-lived
and untrusted only in the sense that a stale one may still be connected, so the
reader caps the line length instead of growing without bound.
"""

from __future__ import annotations

import json
from typing import Any

MAX_LINE_BYTES = 256 * 1024

# bridge -> daemon
REGISTER = "register"
PERMISSION_REQUEST = "permission_request"
CLOSED = "closed"
# daemon -> bridge
INJECT = "inject"
PERMISSION = "permission"
REGISTERED = "registered"


def encode(message: dict[str, Any]) -> bytes:
    """One frame: compact JSON followed by a newline."""
    return (json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def decode(line: bytes) -> dict[str, Any] | None:
    """Parse one frame, returning None for blank or malformed lines."""
    text = line.strip()
    if not text:
        return None
    try:
        parsed = json.loads(text)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    return parsed if isinstance(parsed, dict) else None


def register(session_id: str, cwd: str, pid: int, claude_version: str | None) -> dict[str, Any]:
    return {
        "type": REGISTER,
        "session_id": session_id,
        "cwd": cwd,
        "pid": pid,
        "claude_version": claude_version,
    }


def permission_request(payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": PERMISSION_REQUEST,
        "request_id": str(payload.get("request_id") or ""),
        "tool_name": str(payload.get("tool_name") or ""),
        "description": str(payload.get("description") or ""),
        "input_preview": str(payload.get("input_preview") or ""),
    }


def closed() -> dict[str, Any]:
    return {"type": CLOSED}


def inject(message_id: str, text: str) -> dict[str, Any]:
    return {"type": INJECT, "message_id": message_id, "text": text}


def permission(request_id: str, behavior: str) -> dict[str, Any]:
    return {"type": PERMISSION, "request_id": request_id, "behavior": behavior}
