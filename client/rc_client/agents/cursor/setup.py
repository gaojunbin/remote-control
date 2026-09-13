"""`rc-client cursor setup`: one `preToolUse` entry merged into Cursor's hooks.

`~/.cursor/hooks.json` is shared. Cursor itself, and any other product that
wants to see a tool call, registers there, and on this machine one already
does. So every operation here is a merge: entries that are not ours are read,
kept in their original order and written back untouched, and `--remove` takes
out only the entry whose command is this device's own hook.

The file is often a symbolic link into a synchronised folder, so a write
follows the link and replaces the file it points at, rather than replacing the
link with a regular file of our own.
"""

from __future__ import annotations

import json
import os
import shlex
from pathlib import Path
from typing import Any

from ...channel import paths
from ...config import client_home, write_atomic
from . import runtime

HOOK_EVENT = "preToolUse"
CONFIG_VERSION = 1
# Cursor ends a hook that takes longer than this. An approval waits for a
# person, so the budget is the same day-long one the Claude question hook uses.
HOOK_TIMEOUT = 24 * 60 * 60
DEFAULT_MODE = 0o600


def hook_command() -> list[str]:
    """The `preToolUse` hook Cursor runs before each tool call."""
    return [*paths.entrypoint(), "cursor", "hook"]


def hook_line(command: list[str] | None = None) -> str:
    """The shell line Cursor runs, with the device home pinned to this install."""
    argv = command or hook_command()
    return f"RC_CLIENT_HOME={shlex.quote(str(client_home()))} {shlex.join(argv)}"


def is_ours(command: str) -> bool:
    """Our entry ends in `cursor hook`; nobody else's does."""
    try:
        parts = shlex.split(command)
    except ValueError:
        return False
    return parts[-2:] == ["cursor", "hook"]


def read_config(path: Path) -> dict[str, Any]:
    """Whatever is on disk now, or an empty configuration when there is nothing."""
    try:
        parsed = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _entries(config: dict[str, Any]) -> list[Any]:
    hooks = config.get("hooks")
    if not isinstance(hooks, dict):
        return []
    listed = hooks.get(HOOK_EVENT)
    return list(listed) if isinstance(listed, list) else []


def merge(config: dict[str, Any], command: str) -> tuple[dict[str, Any], bool]:
    """`config` with our entry present exactly once, and whether it changed.

    Everything already registered stays where it is: ours is appended last, or
    its command is refreshed in place when an older install left one behind.
    """
    entry = {"command": command, "timeout": HOOK_TIMEOUT}
    listed = _entries(config)
    kept: list[Any] = []
    replaced = False
    for existing in listed:
        if isinstance(existing, dict) and is_ours(str(existing.get("command") or "")):
            if replaced:
                continue
            replaced = True
            kept.append({**existing, **entry})
            continue
        kept.append(existing)
    if not replaced:
        kept.append(entry)
    changed = kept != listed or config.get("version") != CONFIG_VERSION
    hooks = dict(config.get("hooks") or {}) if isinstance(config.get("hooks"), dict) else {}
    hooks[HOOK_EVENT] = kept
    return {**config, "hooks": hooks, "version": CONFIG_VERSION}, changed


def unmerge(config: dict[str, Any]) -> tuple[dict[str, Any], bool]:
    """`config` without our entry, leaving every other product's alone."""
    listed = _entries(config)
    kept = [
        existing
        for existing in listed
        if not (isinstance(existing, dict) and is_ours(str(existing.get("command") or "")))
    ]
    if kept == listed:
        return config, False
    hooks = dict(config.get("hooks") or {})
    if kept:
        hooks[HOOK_EVENT] = kept
    else:
        hooks.pop(HOOK_EVENT, None)
    return {**config, "hooks": hooks}, True


def _write(path: Path, config: dict[str, Any]) -> None:
    """Replace the file the path names, following a link to its real target."""
    target = path.resolve() if path.is_symlink() else path
    try:
        mode = os.stat(target).st_mode & 0o777
    except OSError:
        mode = DEFAULT_MODE
    payload = json.dumps(config, indent=2, ensure_ascii=False) + "\n"
    write_atomic(target, payload.encode("utf-8"), mode=mode or DEFAULT_MODE)


def install(command: list[str] | None = None) -> list[str]:
    path = runtime.hooks_file()
    config, changed = merge(read_config(path), hook_line(command))
    if changed:
        _write(path, config)
    count = len(_entries(config))
    return [
        f"cursor hook  {'installed' if changed else 'already installed'} in {path}",
        f"preToolUse   {count} hook{'' if count == 1 else 's'} registered, ours last",
    ]


def remove() -> list[str]:
    path = runtime.hooks_file()
    if not path.exists():
        return [f"cursor hook  nothing to remove ({path} does not exist)"]
    config, changed = unmerge(read_config(path))
    if changed:
        _write(path, config)
    return [f"cursor hook  {'removed from' if changed else 'not present in'} {path}"]


def status() -> list[str]:
    path = runtime.hooks_file()
    binary = runtime.resolve_binary()
    entries = _entries(read_config(path))
    ours = sum(
        1
        for entry in entries
        if isinstance(entry, dict) and is_ours(str(entry.get("command") or ""))
    )
    return [
        f"cursor-agent {binary or 'not installed'}",
        f"hooks file   {path}{'' if path.exists() else ' (absent)'}",
        f"preToolUse   {len(entries)} registered, {ours} ours",
        f"socket       {runtime.socket_path()}",
    ]
