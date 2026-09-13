"""Mirror Grok sessions a person started in a terminal.

Grok keeps one directory per session under `~/.grok/sessions/<encoded cwd>/<id>/`
and appends every ACP update to `updates.jsonl`, whether the session is driven
over ACP or by a person at the TUI. That log is the mirror: no attachment, no
configuration change, and `_meta.eventId` is a monotonic `<sessionId>-<n>`
counter, so a reader that remembers the last `n` it applied can resume exactly.
"""

from __future__ import annotations

import json
import os
import time
import urllib.parse
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ...tailing import FileTail
from ..base import Emit
from . import runtime
from .translate import GrokTranslator, session_update

MAX_SESSIONS = 50
MAX_AGE_DAYS = 14
UPDATES = "updates.jsonl"
SUMMARY = "summary.json"


@dataclass(slots=True)
class GrokSessionInfo:
    session_id: str
    path: str
    cwd: str
    title: str = ""
    model: str | None = None
    effort: str | None = None
    size: int = 0
    mtime: float = 0.0

    @property
    def settings(self) -> dict[str, str]:
        """What the terminal chose, for the `meta` amendment A17 asks for."""
        fields: dict[str, str] = {}
        if self.model:
            fields["model"] = self.model
        if self.effort:
            fields["effort"] = self.effort
        return fields


def event_index(row: dict[str, Any]) -> int:
    """The `n` of an `<sessionId>-<n>` event id, or 0 when there is none."""
    params = row.get("params")
    meta = params.get("_meta") if isinstance(params, dict) else None
    event_id = meta.get("eventId") if isinstance(meta, dict) else None
    if not isinstance(event_id, str) or "-" not in event_id:
        return 0
    tail = event_id.rsplit("-", 1)[1]
    return int(tail) if tail.isdigit() else 0


def _summary(directory: Path) -> dict[str, Any]:
    try:
        data = json.loads((directory / SUMMARY).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def _cwd_of(directory: Path, summary: dict[str, Any]) -> str:
    info = summary.get("info")
    if isinstance(info, dict):
        cwd = info.get("cwd")
        if isinstance(cwd, str) and cwd:
            return cwd
    # The parent directory is the percent-encoded working directory.
    return urllib.parse.unquote(directory.parent.name)


def _title_of(summary: dict[str, Any]) -> str:
    for key in ("generated_title", "session_summary", "last_turn_summary"):
        value = summary.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def discover(limit: int = MAX_SESSIONS, max_age_days: int = MAX_AGE_DAYS) -> list[GrokSessionInfo]:
    """The most recent terminal sessions this device may mirror."""
    root = runtime.sessions_dir()
    if not root.is_dir():
        return []
    cutoff = time.time() - max_age_days * 86400
    candidates: list[tuple[float, Path]] = []
    for entry in root.glob(f"*/*/{UPDATES}"):
        try:
            stat = entry.stat()
        except OSError:
            continue
        if stat.st_mtime < cutoff or stat.st_size == 0:
            continue
        candidates.append((stat.st_mtime, entry))
    candidates.sort(key=lambda item: item[0], reverse=True)
    found: list[GrokSessionInfo] = []
    for mtime, path in candidates[:limit]:
        directory = path.parent
        summary = _summary(directory)
        effort = summary.get("reasoning_effort")
        model = summary.get("current_model_id")
        found.append(
            GrokSessionInfo(
                session_id=directory.name,
                path=str(path),
                cwd=_cwd_of(directory, summary),
                title=_title_of(summary),
                model=model if isinstance(model, str) and model else None,
                effort=effort if isinstance(effort, str) and effort else None,
                size=os.path.getsize(path),
                mtime=mtime,
            )
        )
    return found


def active_ids() -> tuple[set[str], bool]:
    """The sessions Grok's cross-process registry lists, and whether it answered.

    `~/.grok/active_sessions.json` is written only when `[cli] session_registry`
    is on, so a device that cannot read it falls back to asking the operating
    system who holds the log open.
    """
    try:
        data = json.loads(runtime.active_sessions_file().read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return set(), False
    entries: list[Any]
    if isinstance(data, dict):
        entries = list(data.keys())
    elif isinstance(data, list):
        entries = data
    else:
        return set(), False
    ids: set[str] = set()
    for entry in entries:
        if isinstance(entry, str):
            ids.add(entry)
        elif isinstance(entry, dict):
            for key in ("session_id", "sessionId", "id"):
                value = entry.get(key)
                if isinstance(value, str) and value:
                    ids.add(value)
                    break
    return ids, True


@dataclass(slots=True)
class GrokTailer:
    """Reads new updates for one session and turns them into events."""

    path: str
    cwd: str
    # The last `eventId` index a previous run applied: rows at or below it are
    # skipped, which is what lets a tail resume without re-reading the file.
    resume_from: int = 0
    # The highest index seen so far, remembered for the next start. It is not
    # used to filter, because Grok writes an event id slightly out of order: an
    # `agent_message_chunk` lands after the hook rows that follow it.
    cursor: int = 0
    running: bool = False
    translator: GrokTranslator = field(default_factory=GrokTranslator)
    tail: FileTail = field(init=False)

    def __post_init__(self) -> None:
        self.tail = FileTail(path=self.path)

    @property
    def offset(self) -> int:
        return self.tail.offset

    @offset.setter
    def offset(self, value: int) -> None:
        self.tail.offset = value

    @property
    def busy(self) -> bool:
        """Whether a turn is in progress, as the rows read so far leave it."""
        return self.running

    def read_new(self) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        for row in self.tail.read_new():
            index = event_index(row)
            if index and index <= self.resume_from:
                continue
            self.cursor = max(self.cursor, index)
            rows.append(row)
        return rows

    def translate(self, row: dict[str, Any]) -> list[Emit]:
        method = str(row.get("method") or "")
        params = row.get("params")
        if not isinstance(params, dict):
            return []
        found = session_update(method, params)
        if found is None:
            return []
        kind, _ = found
        if kind == "turn_completed":
            self.running = False
        elif kind == "user_message_chunk" or self._of_a_prompt(params):
            # Every row a turn produces names the prompt it belongs to, so a
            # mirror that attaches mid-turn still reports the session as running.
            self.running = True
        return self.translator.notification(method, params)

    @staticmethod
    def _of_a_prompt(params: dict[str, Any]) -> bool:
        meta = params.get("_meta")
        return isinstance(meta, dict) and bool(meta.get("promptId"))
