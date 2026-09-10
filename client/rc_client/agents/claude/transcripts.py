"""Mirror Claude sessions started in a terminal by tailing their transcripts.

Growth is detected by `st_size`, never `st_mtime`: `claude --resume` touches
mtime without appending a byte, which would otherwise look like live activity.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ...diffs import from_tool_input
from ...models import now_ms
from ...tailing import FileTail
from ..base import Emit
from .runtime import PROJECTS_DIR
from .tools import todos_from_input, tool_kind, tool_title

MAX_TRANSCRIPTS = 50
MAX_AGE_DAYS = 14
_SKIP_PREFIXES = ("<command-name>", "<local-command-", "<command-message>", "<command-args>")
_SKIP_TEXTS = {"No response requested.", "(no content)"}


@dataclass(slots=True)
class TranscriptInfo:
    session_id: str
    path: str
    cwd: str
    size: int
    mtime: float


def _first_row(path: Path) -> dict[str, Any] | None:
    try:
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(row, dict) and row.get("cwd"):
                    return row
    except OSError:
        return None
    return None


def discover(
    limit: int = MAX_TRANSCRIPTS, max_age_days: int = MAX_AGE_DAYS
) -> list[TranscriptInfo]:
    """The most recent top-level transcripts; subagent files live one level deeper.

    Both bounds matter on a developer machine: without them a freshly enrolled
    device would publish every session the user has ever run.
    """
    root = PROJECTS_DIR
    if not root.is_dir():
        return []
    cutoff = time.time() - max_age_days * 86400
    candidates: list[tuple[float, Path]] = []
    for project in root.iterdir():
        if not project.is_dir():
            continue
        for entry in project.glob("*.jsonl"):
            try:
                stat = entry.stat()
            except OSError:
                continue
            if stat.st_mtime < cutoff or stat.st_size == 0:
                continue
            candidates.append((stat.st_mtime, entry))
    candidates.sort(reverse=True)
    found: list[TranscriptInfo] = []
    for mtime, path in candidates[:limit]:
        row = _first_row(path)
        if row is None:
            continue
        found.append(
            TranscriptInfo(
                session_id=str(row.get("sessionId") or path.stem),
                path=str(path),
                cwd=str(row.get("cwd") or ""),
                size=path.stat().st_size,
                mtime=mtime,
            )
        )
    return found


def _text_of(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = [
            str(item.get("text") or "")
            for item in content
            if isinstance(item, dict) and item.get("type") == "text"
        ]
        return "\n".join(part for part in parts if part)
    return ""


def _is_meta(row: dict[str, Any], text: str) -> bool:
    if row.get("isMeta"):
        return True
    stripped = text.strip()
    if stripped in _SKIP_TEXTS:
        return True
    return any(stripped.startswith(prefix) for prefix in _SKIP_PREFIXES)


@dataclass(slots=True)
class TranscriptTailer:
    """Incremental JSONL reader that converts new rows into session events."""

    path: str
    cwd: str
    tools: dict[str, dict[str, Any]] = field(default_factory=dict)
    awaiting_reply: bool = False
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
        row_type = row.get("type")
        if row_type == "user":
            return self._user(row)
        if row_type == "assistant":
            return self._assistant(row)
        return []

    def _user(self, row: dict[str, Any]) -> list[Emit]:
        message = row.get("message") or {}
        content = message.get("content")
        if isinstance(content, str):
            if _is_meta(row, content) or not content.strip():
                return []
            self.awaiting_reply = True
            return [
                Emit(
                    "user_message",
                    {
                        "block_id": str(row.get("uuid") or f"user:{now_ms()}"),
                        "text": content,
                        "source": "terminal",
                    },
                )
            ]
        emits: list[Emit] = []
        text_parts: list[str] = []
        for block in content or []:
            if not isinstance(block, dict):
                continue
            if block.get("type") == "tool_result":
                emits.extend(self._tool_result(block, row))
            elif block.get("type") == "text":
                text_parts.append(str(block.get("text") or ""))
        text = "\n".join(part for part in text_parts if part)
        if text and not _is_meta(row, text):
            self.awaiting_reply = True
            emits.append(
                Emit(
                    "user_message",
                    {
                        "block_id": str(row.get("uuid") or f"user:{now_ms()}"),
                        "text": text,
                        "source": "terminal",
                    },
                )
            )
        return emits

    def _assistant(self, row: dict[str, Any]) -> list[Emit]:
        message = row.get("message") or {}
        message_id = str(message.get("id") or row.get("uuid") or "msg")
        emits: list[Emit] = []
        has_tool = False
        for index, block in enumerate(message.get("content") or []):
            if not isinstance(block, dict):
                continue
            block_type = block.get("type")
            if block_type == "text":
                text = str(block.get("text") or "")
                if text:
                    emits.append(
                        Emit(
                            "assistant_text",
                            {"block_id": f"{message_id}:{index}", "text": text, "done": True},
                        )
                    )
            elif block_type == "thinking":
                text = str(block.get("thinking") or "")
                if text:
                    emits.append(
                        Emit(
                            "thinking",
                            {"block_id": f"{message_id}:{index}", "text": text, "done": True},
                        )
                    )
            elif block_type == "tool_use":
                has_tool = True
                emits.extend(self._tool_use(block))
        if not has_tool and emits:
            self.awaiting_reply = False
        return emits

    def _tool_use(self, block: dict[str, Any]) -> list[Emit]:
        name = str(block.get("name") or "Tool")
        raw_input = block.get("input")
        tool_input: dict[str, Any] = raw_input if isinstance(raw_input, dict) else {}
        block_id = str(block.get("id") or "")
        if not block_id:
            return []
        started_at = now_ms()
        self.tools[block_id] = {"tool": name, "input": tool_input, "started_at": started_at}
        if name == "TodoWrite":
            items = todos_from_input(tool_input)
            return [Emit("todos", {"items": items})] if items else []
        return [
            Emit(
                "tool_call",
                {
                    "block_id": block_id,
                    "tool": name,
                    "tool_kind": tool_kind(name),
                    "title": tool_title(name, tool_input, self.cwd),
                    "status": "running",
                    "input": tool_input,
                    "started_at": started_at,
                },
            )
        ]

    def _tool_result(self, block: dict[str, Any], row: dict[str, Any]) -> list[Emit]:
        block_id = str(block.get("tool_use_id") or "")
        if not block_id:
            return []
        record = self.tools.get(block_id, {})
        name = str(record.get("tool") or "Tool")
        if name == "TodoWrite":
            return []
        raw_input = record.get("input")
        tool_input: dict[str, Any] = raw_input if isinstance(raw_input, dict) else {}
        started_at = int(record.get("started_at") or now_ms())
        ended_at = now_ms()
        failed = bool(block.get("is_error"))
        fields: dict[str, Any] = {
            "block_id": block_id,
            "tool": name,
            "tool_kind": tool_kind(name),
            "title": tool_title(name, tool_input, self.cwd),
            "status": "failed" if failed else "succeeded",
            "input": tool_input,
            "output": _result_output(block, row),
            "started_at": started_at,
            "ended_at": ended_at,
            "duration_ms": max(0, ended_at - started_at),
        }
        if not failed:
            diff = from_tool_input(name, tool_input)
            if diff:
                fields["diff"] = diff
        return [Emit("tool_call", fields)]


def _result_output(block: dict[str, Any], row: dict[str, Any]) -> str:
    content = block.get("content")
    text = _text_of(content)
    if text:
        return text
    if isinstance(content, str):
        return content
    result = row.get("toolUseResult")
    if isinstance(result, dict):
        for key in ("stdout", "output", "content", "text"):
            value = result.get(key)
            if isinstance(value, str) and value:
                return value
    return ""
