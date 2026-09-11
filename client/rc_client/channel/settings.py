"""The `--settings` file the shim hands to Claude Code.

It carries exactly one thing: a `SessionStart` hook that tells the daemon which
session the terminal is in now. The environment a CLI starts with is the
environment its MCP servers keep for as long as they live, so the channel bridge
reports the session id Claude Code picked at startup and nothing else; `/resume`
and `/clear` move the conversation without the bridge ever hearing about it. The
hook fires on each of those, which is what keeps the attachment pointed at the
session the person is actually typing into.

Written by both `rc-client shim install` and the daemon at startup, like the MCP
config beside it, so the file matches whichever of the two ran most recently.
"""

from __future__ import annotations

import json
import shlex
from pathlib import Path
from typing import Any

from ..config import client_home, write_atomic
from . import paths

# Claude Code blocks on the hook, so the budget is small: the hook only resolves
# a pid and writes one line to a local socket.
HOOK_TIMEOUT = 5


def hook_line(command: list[str] | None = None) -> str:
    """The shell line Claude Code runs, with the device home pinned to this install."""
    argv = command or paths.hook_command()
    home = shlex.quote(str(client_home()))
    return f"RC_CLIENT_HOME={home} {shlex.join(argv)}"


def settings(command: list[str] | None = None) -> dict[str, Any]:
    """One `SessionStart` hook with no matcher, so every source fires."""
    return {
        "hooks": {
            "SessionStart": [
                {
                    "hooks": [
                        {"type": "command", "command": hook_line(command), "timeout": HOOK_TIMEOUT}
                    ]
                }
            ]
        }
    }


def write_settings(command: list[str] | None = None) -> Path:
    target = paths.settings_path()
    payload = json.dumps(settings(command), indent=2) + "\n"
    write_atomic(target, payload.encode("utf-8"), mode=0o600)
    return target
