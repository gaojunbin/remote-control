"""Which Codex TUIs are alive on this machine, and in which directory.

The shared daemon announces nothing when a terminal TUI exits and offers no way
to ask who is attached to a thread, so the TUI process itself is the only
evidence there is. A directory is not an identity, though: the daemon never
unloads a thread, so a directory a person works in accumulates one loaded
thread per `codex` they have ever run there, and a single live TUI must not
speak for all of them. The scan therefore counts the TUIs per directory, and
the service hands out that many claims.

Scans fail closed, exactly like the Claude holder scan: an incomplete answer
means "unknown", never "nobody".
"""

from __future__ import annotations

import os
from collections import Counter
from dataclasses import dataclass

from ....procscan import Proc, descendants, process_cwds, scan_processes

# Whole argv tokens that make a `codex` process something other than a TUI. They
# are matched anywhere in the command line because the helpers that Codex and
# the ChatGPT app spawn put `-c key=value` overrides before their subcommand.
NOT_A_TUI = frozenset({"app-server", "exec", "mcp", "mcp-server", "proto", "daemon"})
# A TUI launched with any of these runs its own embedded app-server and never
# joins the shared daemon, so it is never the terminal of a daemon thread. The
# bypass flag belongs here for the same reason the config overrides do: it
# changes the sandbox policy the server is built with, and such a TUI is
# observably holding its own thread-writer lock on a thread the daemon has
# never loaded.
EMBEDDED = frozenset(
    {"-c", "--config", "--enable", "--disable", "--dangerously-bypass-approvals-and-sandbox"}
)


@dataclass(slots=True)
class TerminalScan:
    """How many Codex TUIs are running in each working directory."""

    cwds: dict[str, int]
    complete: bool

    def count(self, cwd: str) -> int:
        """How many terminals this directory can speak for."""
        if not cwd:
            return 0
        return self.cwds.get(os.path.realpath(cwd), 0)

    def holds(self, cwd: str) -> bool:
        return self.count(cwd) > 0


def looks_like_a_tui(proc: Proc) -> bool:
    """Whether this process is a bare `codex` TUI that the daemon could own."""
    argv = proc.argv
    if not argv or not proc.has_tty:
        return False
    if os.path.basename(argv[0]) != "codex":
        return False
    disqualifying = NOT_A_TUI | EMBEDDED
    return not any(token.split("=", 1)[0] in disqualifying for token in argv[1:])


async def scan_terminals() -> TerminalScan:
    """Every directory a live Codex TUI is running in, this device's own excluded."""
    scan = await scan_processes()
    if not scan.complete:
        return TerminalScan(cwds={}, complete=False)
    ours = descendants(scan.procs, os.getpid())
    candidates = [proc for proc in scan.procs if proc.pid not in ours and looks_like_a_tui(proc)]
    if not candidates:
        return TerminalScan(cwds={}, complete=True)
    cwds, complete = await process_cwds([proc.pid for proc in candidates])
    found = Counter(os.path.realpath(cwd) for cwd in cwds.values() if cwd)
    return TerminalScan(cwds=dict(found), complete=complete)
