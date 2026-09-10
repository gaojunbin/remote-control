"""Locate the `codex` executable and read its version."""

from __future__ import annotations

import asyncio
import glob
import os
import re
import shutil
from pathlib import Path

VERSION_TIMEOUT = 3.0
_VERSION_RE = re.compile(r"(?<!\d)(\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?)")

CODEX_HOME = Path(os.environ.get("CODEX_HOME") or (Path.home() / ".codex"))
SESSIONS_DIR = CODEX_HOME / "sessions"


def candidate_paths() -> list[str]:
    explicit = os.environ.get("RC_CODEX_BIN", "").strip()
    candidates: list[str] = []
    if explicit:
        candidates.append(os.path.expanduser(explicit))
    found = shutil.which("codex")
    if found:
        candidates.append(found)
    home = Path.home()
    releases = str(home / ".codex/packages/standalone/releases/*/bin/codex")
    candidates.extend(sorted(glob.glob(releases)))
    candidates.extend(
        str(path)
        for path in (
            home / ".local/bin/codex",
            Path("/opt/homebrew/bin/codex"),
            Path("/usr/local/bin/codex"),
            Path("/usr/bin/codex"),
        )
    )
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


async def probe_version(path: str) -> str | None:
    try:
        process = await asyncio.create_subprocess_exec(
            path,
            "--version",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except OSError:
        return None
    try:
        out, err = await asyncio.wait_for(process.communicate(), timeout=VERSION_TIMEOUT)
    except TimeoutError:
        process.kill()
        await process.wait()
        return None
    match = _VERSION_RE.search(out.decode("utf-8", "replace") + err.decode("utf-8", "replace"))
    return match.group(1) if match else None
