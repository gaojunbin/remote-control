"""Mirror Claude sessions started in a terminal by tailing their transcripts.

Growth is detected by `st_size`, never `st_mtime`: `claude --resume` touches
mtime without appending a byte, which would otherwise look like live activity.
"""

from __future__ import annotations

import json
import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ...diffs import from_tool_input
from ...models import now_ms
from ...tailing import FileTail
from ..base import Emit
from .questions import QUESTION_TOOL
from .runtime import PROJECTS_DIR
from .tools import todos_from_input, tool_kind, tool_title

MAX_TRANSCRIPTS = 50
MAX_AGE_DAYS = 14
_SKIP_PREFIXES = ("<command-name>", "<local-command-", "<command-message>", "<command-args>")
_SKIP_TEXTS = {"No response requested.", "(no content)"}

# Amendment A10: a message this device injected through the channel comes back
# wrapped in a <channel> tag whose attributes repeat the `meta` we sent, so the
# id we chose is the correlation key. Neither shape becomes a second bubble.
CHANNEL_SERVER = "rc"
_MESSAGE_ID_RE = re.compile(r'message_id="([^"]{1,64})"')
CHANNEL_DELIVERED = "channel_delivered"
CHANNEL_ABSORBED = "channel_absorbed"

# Amendment A20: the result row of an `AskUserQuestion` is how the device hears
# that the person answered the CLI's own dialog. `toolUseResult` carries what
# they chose, under the question's own prompt.
QUESTION_ANSWERED = "question_answered"

# Claude Code names a session after its own reading of the conversation and
# writes the result into the transcript as a row of its own. It may do so more
# than once — the title is generated shortly after the first turn, and accepting
# a plan replaces it — and the last one is the current title. `/rename` in the
# terminal writes the other shape, which is the user's own title and outranks
# every generated one. Both are internal to Claude Code and may change, so an
# unknown or malformed row is read as "not a title" rather than as an error.
AI_TITLE_ROW = "ai-title"
CUSTOM_TITLE_ROW = "custom-title"
TITLE = "session_title"
_TITLE_ROWS = {AI_TITLE_ROW: ("aiTitle", False), CUSTOM_TITLE_ROW: ("customTitle", True)}
_SESSION_ID = re.compile(r"[A-Za-z0-9_-]{1,80}")

# Amendment A17: a session the terminal holds takes no settings from an app, so
# the device reads what the terminal chose out of the transcript and publishes
# it as `meta`. Claude Code writes the model as an attachment at session start,
# after a resume and on every `/model`; the permission mode as a row of its own
# once per turn; and the effort on each assistant message. All three are
# internal to Claude Code, so an unknown or malformed row reads as "no
# settings" rather than as an error, exactly as the title rows do.
SESSION_SETTINGS = "session_settings"
PERMISSION_MODE_ROW = "permission-mode"
MODEL_ATTACHMENT = "model"
# One bounded pass over a transcript is enough to find the values in force; the
# cap keeps a runaway file from holding the thread it runs on.
SETTINGS_SCAN_BYTES = 64 * 1024 * 1024


def channel_message_id(text: str, server: str = CHANNEL_SERVER) -> str | None:
    """The `message_id` of a channel tag this device wrote, if that is what it is."""
    head = text[:400]
    if not head.lstrip().startswith(f'<channel source="{server}"'):
        return None
    match = _MESSAGE_ID_RE.search(head)
    return match.group(1) if match else None


def _channel_origin(row: dict[str, Any], server: str = CHANNEL_SERVER) -> bool:
    origin = row.get("origin")
    if not isinstance(origin, dict):
        return False
    return origin.get("kind") == "channel" and origin.get("server") == server


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


def find_transcript(session_id: str) -> Path | None:
    """The transcript of one session, in whichever project directory Claude filed it.

    A session this device drives is never mirrored, so its path is not known
    from a scan; the id is unique across projects, which makes a glob enough.
    """
    if not _SESSION_ID.fullmatch(session_id):
        return None
    for path in PROJECTS_DIR.glob(f"*/{session_id}.jsonl"):
        return path
    return None


@dataclass(slots=True, frozen=True)
class Title:
    """A title read from a transcript, and whether the user chose it."""

    text: str
    by_user: bool


def read_title(row: dict[str, Any]) -> Title | None:
    """The title a transcript row carries, or `None` when it carries none."""
    shape = _TITLE_ROWS.get(str(row.get("type") or ""))
    if shape is None:
        return None
    field_name, by_user = shape
    value = row.get(field_name)
    text = value.strip() if isinstance(value, str) else ""
    return Title(text=text, by_user=by_user) if text else None


@dataclass(slots=True)
class TitleTail:
    """Watch a transcript for nothing but the titles it carries.

    Sessions this device drives get their events from the SDK, which does not
    carry the title, and mirroring their transcript would publish every message
    twice. Reading only the title rows costs one incremental read per tick.
    """

    path: str
    tail: FileTail = field(init=False)

    def __post_init__(self) -> None:
        self.tail = FileTail(path=self.path)

    def read_new(self) -> list[Title]:
        """Every title among the rows appended since the last read, in order.

        All of them, not just the last: a generated title that lands after a
        rename must not win, and applying them in order is what decides that.
        """
        found = [read_title(row) for row in self.tail.read_new()]
        return [title for title in found if title is not None]


def _setting(name: str, value: Any) -> dict[str, str]:
    text = value.strip() if isinstance(value, str) else ""
    return {name: text} if text else {}


def _model_setting(row: dict[str, Any]) -> dict[str, str]:
    attachment = row.get("attachment")
    if not isinstance(attachment, dict) or attachment.get("type") != MODEL_ATTACHMENT:
        return {}
    identity = attachment.get("identity")
    if not isinstance(identity, dict):
        return {}
    # The id is kept verbatim, suffix and all: `claude-opus-5[1m]` is a model an
    # app must be able to show even though no agent list carries it.
    return _setting("model", identity.get("modelId"))


def read_settings(row: dict[str, Any]) -> dict[str, str]:
    """The session settings a transcript row carries, empty when it carries none."""
    row_type = row.get("type")
    if row_type == "attachment":
        return _model_setting(row)
    if row_type == PERMISSION_MODE_ROW:
        return _setting("permission_mode", row.get("permissionMode"))
    if row_type == "assistant":
        # `perTurnEffort` is a different field on other rows and is not this one.
        return _setting("effort", row.get("effort"))
    return {}


def latest_settings(path: str | Path, limit: int = SETTINGS_SCAN_BYTES) -> dict[str, str]:
    """The settings in force at the end of a transcript, in one streaming pass.

    A mirror starts reading at a stored offset or near the end of the file, and
    the model is recorded only when it changes, so the value in force usually
    lies far behind that point. Reading the whole file once, when the session is
    adopted, is what lets an app show the right values from the first frame.
    """
    found: dict[str, str] = {}
    read = 0
    try:
        with open(path, "rb") as handle:
            for line in handle:
                read += len(line)
                if read > limit:
                    break
                if not line.strip():
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(row, dict):
                    found.update(read_settings(row))
    except OSError:
        return found
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

    @property
    def busy(self) -> bool:
        """Whether a turn is in progress, as the rows read so far leave it."""
        return self.awaiting_reply

    def seek_to_end(self) -> None:
        self.tail.seek_to_end()

    def read_new(self) -> list[dict[str, Any]]:
        return self.tail.read_new()

    def translate(self, row: dict[str, Any]) -> list[Emit]:
        settings = read_settings(row)
        emits: list[Emit] = [Emit(SESSION_SETTINGS, dict(settings))] if settings else []
        return emits + self._content(row)

    def _content(self, row: dict[str, Any]) -> list[Emit]:
        row_type = row.get("type")
        if row_type in _TITLE_ROWS:
            title = read_title(row)
            if title is None:
                return []
            return [Emit(TITLE, {"title": title.text, "by_user": title.by_user})]
        if row_type == "user":
            return self._channel_echo(row) or self._user(row)
        if row_type == "assistant":
            return self._assistant(row)
        if row_type == "attachment":
            return self._attachment(row)
        return []

    def _channel_echo(self, row: dict[str, Any]) -> list[Emit]:
        """An injected message reappearing as a user row: a turn just started."""
        if not _channel_origin(row):
            return []
        message = row.get("message") or {}
        message_id = channel_message_id(_text_of(message.get("content")))
        if message_id is None:
            return []
        self.awaiting_reply = True
        return [Emit(CHANNEL_DELIVERED, {"message_id": message_id})]

    def _attachment(self, row: dict[str, Any]) -> list[Emit]:
        """A `queued_command` attachment means the CLI absorbed our injection."""
        attachment = row.get("attachment")
        if not isinstance(attachment, dict) or attachment.get("type") != "queued_command":
            return []
        if not _channel_origin(attachment):
            return []
        message_id = channel_message_id(str(attachment.get("prompt") or ""))
        if message_id is None:
            return []
        return [Emit(CHANNEL_ABSORBED, {"message_id": message_id})]

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
        emits = [Emit("tool_call", fields)]
        if name == QUESTION_TOOL:
            emits.append(Emit(QUESTION_ANSWERED, {"answers": _question_answers(row)}))
        return emits


def _question_answers(row: dict[str, Any]) -> dict[str, Any]:
    """What an `AskUserQuestion` result says was chosen, keyed by the prompt."""
    result = row.get("toolUseResult")
    if not isinstance(result, dict):
        return {}
    answers = result.get("answers")
    return answers if isinstance(answers, dict) else {}


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
