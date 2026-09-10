"""Mirror Codex threads started in a terminal by tailing their rollout files.

A rollout's first record is `session_meta`, which carries the thread id and the
working directory. Live rows are appended, so growth is tracked by `st_size`.
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass, field
from typing import Any

from ...tailing import FileTail
from ..base import Emit
from .runtime import SESSIONS_DIR
from .translate import CodexTranslator, _plan_items

MAX_ROLLOUTS = 50
MAX_AGE_DAYS = 14


@dataclass(slots=True)
class RolloutInfo:
    thread_id: str
    path: str
    cwd: str
    size: int
    mtime: float


def _session_meta(path: str) -> dict[str, Any] | None:
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            first = handle.readline()
    except OSError:
        return None
    try:
        row = json.loads(first)
    except json.JSONDecodeError:
        return None
    if not isinstance(row, dict) or row.get("type") != "session_meta":
        return None
    payload = row.get("payload")
    return payload if isinstance(payload, dict) else None


def discover(limit: int = MAX_ROLLOUTS, max_age_days: int = MAX_AGE_DAYS) -> list[RolloutInfo]:
    """The most recent rollouts, bounded the same way as Claude transcripts."""
    root = SESSIONS_DIR
    if not root.is_dir():
        return []
    cutoff = time.time() - max_age_days * 86400
    candidates: list[tuple[float, str]] = []
    for entry in root.glob("*/*/*/rollout-*.jsonl"):
        try:
            stat = entry.stat()
        except OSError:
            continue
        if stat.st_mtime < cutoff or stat.st_size == 0:
            continue
        candidates.append((stat.st_mtime, str(entry)))
    candidates.sort(reverse=True)
    found: list[RolloutInfo] = []
    for mtime, path in candidates[:limit]:
        meta = _session_meta(path)
        if meta is None:
            continue
        thread_id = str(meta.get("session_id") or meta.get("id") or "")
        if not thread_id:
            continue
        found.append(
            RolloutInfo(
                thread_id=thread_id,
                path=path,
                cwd=str(meta.get("cwd") or ""),
                size=os.path.getsize(path),
                mtime=mtime,
            )
        )
    return found


@dataclass(slots=True)
class RolloutTailer:
    path: str
    cwd: str
    running: bool = False
    translator: CodexTranslator = field(default_factory=CodexTranslator)
    tail: FileTail = field(init=False)

    def __post_init__(self) -> None:
        self.tail = FileTail(path=self.path)

    @property
    def offset(self) -> int:
        return self.tail.offset

    @offset.setter
    def offset(self, value: int) -> None:
        self.tail.offset = value

    def seek_to_end(self) -> None:
        self.tail.seek_to_end()

    def read_new(self) -> list[dict[str, Any]]:
        return self.tail.read_new()

    def translate(self, row: dict[str, Any]) -> list[Emit]:
        if row.get("type") != "event_msg":
            return []
        payload = row.get("payload")
        if not isinstance(payload, dict):
            return []
        kind = payload.get("type")
        if kind == "task_started":
            self.running = True
            return []
        if kind in {"task_complete", "turn_aborted", "task_failed"}:
            self.running = False
            return []
        if kind == "item_completed":
            item = payload.get("item")
            return self.translator.item(item, True) if isinstance(item, dict) else []
        if kind == "item_started":
            item = payload.get("item")
            return self.translator.item(item, False) if isinstance(item, dict) else []
        if kind == "plan_update":
            return [Emit("todos", {"items": _plan_items(payload.get("plan"))})]
        return []
