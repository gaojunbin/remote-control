"""Find the live `claude` process that owns a transcript.

Detection is process-scan first because an idle Claude TUI does not keep its
transcript file open. An incomplete scan means "unknown owner", which callers
must treat as read-only rather than as "nobody".
"""

from __future__ import annotations

import os
from dataclasses import dataclass

from ...procscan import Proc, descendants, process_cwds, scan_processes, terminate

_RESUME_FLAGS = {"--resume", "-r", "--session-id"}
_BACKGROUND_MARKERS = ("daemon", "bg-pty-host", "bg-spare", "app-server", "mcp")


@dataclass(slots=True)
class Holder:
    pid: int
    identity: tuple[int, str]
    session_id: str | None
    cwd: str | None


@dataclass(slots=True)
class HolderScan:
    holders: list[Holder]
    complete: bool

    def for_session(self, session_id: str, cwd: str | None) -> Holder | None:
        for holder in self.holders:
            if holder.session_id == session_id:
                return holder
        if cwd is None:
            return None
        target = os.path.realpath(cwd)
        matches = [
            holder
            for holder in self.holders
            if holder.session_id is None and holder.cwd and os.path.realpath(holder.cwd) == target
        ]
        return matches[0] if len(matches) == 1 else None


def _is_background(argv: list[str]) -> bool:
    """True for the helper processes Claude Code spawns, never for a TUI.

    Matching whole tokens rather than the joined command line matters: the
    device's own shim adds `--mcp-config <path>`, and a substring test would
    write off every session started through it as a background helper.
    """
    return any(token.lstrip("-").split("=", 1)[0] in _BACKGROUND_MARKERS for token in argv[1:])


def _looks_like_claude(proc: Proc) -> bool:
    argv = proc.argv
    if not argv:
        return False
    if _is_background(argv):
        return False
    for token in argv[:3]:
        base = os.path.basename(token)
        if base == "claude" or base == "claude-code":
            return True
        if base == "cli.js" and "claude" in token:
            return True
    return False


def _session_from_argv(argv: list[str]) -> str | None:
    for index, token in enumerate(argv):
        if token in _RESUME_FLAGS and index + 1 < len(argv):
            candidate = argv[index + 1]
            if not candidate.startswith("-"):
                return candidate
        for flag in _RESUME_FLAGS:
            if token.startswith(f"{flag}="):
                return token.split("=", 1)[1]
    return None


async def scan_holders(exclude_pids: set[int] | None = None) -> HolderScan:
    scan = await scan_processes()
    if not scan.complete:
        return HolderScan(holders=[], complete=False)
    # Our own agent children look exactly like a terminal CLI, so exclude the
    # whole daemon subtree rather than just this process.
    excluded = set(exclude_pids or set()) | descendants(scan.procs, os.getpid())
    candidates = [
        proc for proc in scan.procs if proc.pid not in excluded and _looks_like_claude(proc)
    ]
    cwds, complete = await process_cwds([proc.pid for proc in candidates])
    holders = [
        Holder(
            pid=proc.pid,
            identity=proc.identity,
            session_id=_session_from_argv(proc.argv),
            cwd=cwds.get(proc.pid),
        )
        for proc in candidates
    ]
    return HolderScan(holders=holders, complete=complete)


async def release_holder(pid: int, identity: tuple[int, str]) -> bool:
    """SIGTERM the exact terminal process so the session can be resumed."""
    return await terminate(pid, identity, expect=_looks_like_claude)
