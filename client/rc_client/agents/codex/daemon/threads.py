"""Reading the daemon's thread index, and the A11 section 4.4 origin/control table."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from ....models import MAX_TITLE, now_ms

# Every real turn spawns a short-lived thread that only generates a title; those
# would otherwise flicker into the session list on every message.
EPHEMERAL = "ephemeral"


def is_ephemeral(thread: dict[str, Any]) -> bool:
    return bool(thread.get(EPHEMERAL))


def is_active(status: Any) -> bool:
    """Whether a `ThreadStatus` says a turn is in progress."""
    return isinstance(status, dict) and status.get("type") == "active"


def resolve(
    created_here: bool,
    loaded: bool,
    terminal_seen: bool,
    terminal_live: bool = True,
    local_turn: bool = False,
) -> tuple[str, str]:
    """`(origin, control)` for one Codex thread on the daemon.

    An unloaded thread is `none` and the next `session.send` resumes it. A
    loaded thread is `shared` while a terminal has it, which the daemon never
    reports: `terminal_live` is the process scan's answer, and it defaults to
    "still there" so an unknown answer never hands the session away. Once the
    terminal is gone the thread stays ours to drive — `remote` while a turn
    this device started is running, `none` when it is idle — and `origin` never
    changes.
    """
    origin = "remote" if created_here else "terminal"
    if not loaded:
        return origin, "none"
    if (terminal_seen or not created_here) and terminal_live:
        return origin, "shared"
    if created_here or local_turn:
        return origin, "remote"
    return origin, "none"


@dataclass(slots=True)
class ThreadSummary:
    """The fields of a daemon thread a session record is built from."""

    thread_id: str
    cwd: str
    title: str
    # The thread's own name, which Codex generates from the conversation. Empty
    # when it has none yet, and then `title` falls back to the first prompt.
    name: str
    model: str | None
    effort: str | None
    created_at: int
    updated_at: int
    active: bool

    @classmethod
    def parse(cls, thread: dict[str, Any]) -> ThreadSummary | None:
        thread_id = str(thread.get("id") or thread.get("sessionId") or "")
        if not thread_id:
            return None
        name = str(thread.get("name") or "").strip()
        preview = str(thread.get("preview") or "").strip()
        title = (name or preview.splitlines()[0] if preview or name else "")[:MAX_TITLE]
        created = int(thread.get("createdAt") or 0) or now_ms()
        updated = int(thread.get("updatedAt") or 0) or created
        model = thread.get("model")
        effort = thread.get("reasoningEffort")
        return cls(
            thread_id=thread_id,
            cwd=str(thread.get("cwd") or ""),
            title=title,
            name=name[:MAX_TITLE],
            model=model if isinstance(model, str) and model else None,
            effort=effort if isinstance(effort, str) and effort else None,
            created_at=created,
            updated_at=updated,
            active=is_active(thread.get("status")),
        )


def summaries(threads: Any) -> list[ThreadSummary]:
    """Parse a `thread/list` page, dropping ephemeral and unreadable entries."""
    found: list[ThreadSummary] = []
    for entry in threads if isinstance(threads, list) else []:
        if not isinstance(entry, dict) or is_ephemeral(entry):
            continue
        summary = ThreadSummary.parse(entry)
        if summary is not None:
            found.append(summary)
    return found
