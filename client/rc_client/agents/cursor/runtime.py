"""Locate the Cursor agent CLI, read what it will tell us, and name its files.

Two probes, both cheap and both allowed to fail: `--version` works on any
install, while `--list-models` needs a signed-in CLI and answers with an
authentication error otherwise. Detection never treats either failure as fatal,
because an agent the device cannot talk to is still an agent it can describe.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import shutil
from pathlib import Path

from ...channel import paths as channel_paths

VERSION_TIMEOUT = 5.0
MODELS_TIMEOUT = 10.0
MAX_PROBE_BYTES = 64 * 1024
SOCKET_NAME = "cursor-hook.sock"


def home() -> Path:
    """Cursor's own state directory. Read on every call so a test can move `$HOME`."""
    return Path.home() / ".cursor"


def hooks_file() -> Path:
    """The user-level hook configuration. Other products register here too."""
    return home() / "hooks.json"


def socket_path() -> Path:
    """The socket the `preToolUse` hook dials, beside the Claude channel's.

    The channel already solves the 104-byte `sun_path` limit by falling back to
    a short per-user directory, so borrowing its directory keeps both sockets
    reachable without repeating the calculation.
    """
    return channel_paths.socket_path().with_name(SOCKET_NAME)


def candidate_paths() -> list[str]:
    """Resolution order: explicit override, PATH, then the installer's own target.

    `shutil.which` only ever finds executables, so a shell alias or function
    named `cursor-agent` cannot be picked up here.
    """
    explicit = os.environ.get("RC_CURSOR_BIN", "").strip()
    candidates: list[str] = []
    if explicit:
        candidates.append(os.path.expanduser(explicit))
    found = shutil.which("cursor-agent")
    if found:
        candidates.append(found)
    candidates.append(str(Path.home() / ".local/bin/cursor-agent"))
    return candidates


def resolve_binary() -> str | None:
    seen: set[str] = set()
    for candidate in candidate_paths():
        path = os.path.abspath(os.path.expanduser(candidate))
        real = os.path.realpath(path)
        if real in seen:
            continue
        seen.add(real)
        if os.path.isfile(path) and os.access(path, os.X_OK):
            return path
    return None


async def run_probe(path: str, *args: str, timeout: float) -> tuple[int, str] | None:
    """One short-lived read-only command, or None when it will not run at all."""
    try:
        process = await asyncio.create_subprocess_exec(
            path,
            *args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except OSError:
        return None
    try:
        out, err = await asyncio.wait_for(process.communicate(), timeout=timeout)
    except TimeoutError:
        # The child may have exited between the timeout and the signal.
        with contextlib.suppress(ProcessLookupError):
            process.kill()
            await process.wait()
        return None
    text = (out[:MAX_PROBE_BYTES] + err[:MAX_PROBE_BYTES]).decode("utf-8", "replace")
    return process.returncode or 0, text


def parse_version(text: str) -> str | None:
    """`cursor-agent --version` prints a calendar version on a line of its own."""
    for line in text.splitlines():
        token = line.strip()
        if token and " " not in token and len(token) <= 64:
            return token
    return None


async def probe_version(path: str) -> str | None:
    answer = await run_probe(path, "--version", timeout=VERSION_TIMEOUT)
    if answer is None:
        return None
    code, text = answer
    return parse_version(text) if code == 0 else None


async def probe_models(path: str) -> str | None:
    """`--list-models`, which exits non-zero when the CLI is not signed in."""
    answer = await run_probe(path, "--list-models", timeout=MODELS_TIMEOUT)
    if answer is None:
        return None
    code, text = answer
    return text if code == 0 else None
