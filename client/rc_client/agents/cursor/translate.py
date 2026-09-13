"""Turn Cursor's `stream-json` lines into protocol events.

One NDJSON object per line, one turn per process. Everything here is pure: it
reads a decoded line and returns `Emit`s, so the whole mapping is testable
without a signed-in CLI.

Two shapes are worth knowing before reading the handlers. Text arrives twice
when `--stream-partial-output` is on: once as a delta per token, and again as a
buffered replay of everything since the last flush, written just before each
tool call and once at the end of the turn. A delta carries `timestamp_ms` and
no `model_call_id`; the buffered replays carry one or the other of those the
other way round, which is how they are dropped here. And a tool call is a
tagged union, `{"<name>ToolCall": {"args": …, "result": …}}`, so the tool's
identity is the single key rather than a field.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from ...models import now_ms
from ..base import Emit

Handler = Callable[[dict[str, Any]], list[Emit]]

# An internal kind the adapter consumes rather than publishes: the chat id
# Cursor minted for this conversation, which becomes the session id.
SESSION = "cursor.session"

MAX_OUTPUT_CHARS = 200_000
TOOL_SUFFIX = "ToolCall"

_TOOL_KINDS = {
    "read": "read",
    "ls": "read",
    "readLints": "read",
    "readAgentTranscript": "read",
    "conversationHistory": "read",
    "grep": "search",
    "glob": "search",
    "semSearch": "search",
    "searchConversations": "search",
    "shell": "shell",
    "writeShellStdin": "shell",
    "edit": "edit",
    "applyAgentDiff": "edit",
    "delete": "edit",
    "write": "write",
    "webSearch": "web",
    "webFetch": "web",
    "fetch": "web",
    "mcp": "mcp",
    "getMcpTools": "mcp",
    "listMcpResources": "mcp",
    "readMcpResource": "mcp",
    "mcpAuth": "mcp",
    "task": "subagent",
    "createAgent": "subagent",
    "updateTodos": "todo",
    "readTodos": "todo",
    "createPlan": "todo",
    "createGoal": "todo",
    "updateGoal": "todo",
}
# Argument names that read as the one line an app shows for a call. Cursor does
# not document its per-tool arguments, so this is a list of likely names rather
# than a mapping, and the tool's own name is the fallback.
_TITLE_KEYS = (
    "command",
    "file_path",
    "path",
    "target_file",
    "relative_workspace_path",
    "pattern",
    "glob_pattern",
    "query",
    "search_term",
    "url",
    "description",
)
_OUTPUT_KEYS = ("output", "stdout", "text", "contents", "content", "result", "message")
_TODO_STATUS = {"in_progress": "in_progress", "completed": "completed", "pending": "pending"}


def tool_stem(tool_call: dict[str, Any]) -> str:
    """`{"readToolCall": …}` names the tool `read`; an unknown union key is its own."""
    for key in tool_call:
        return key[: -len(TOOL_SUFFIX)] if key.endswith(TOOL_SUFFIX) else key
    return "tool"


def _first_line(text: str, limit: int = 200) -> str:
    stripped = text.strip()
    return stripped.splitlines()[0][:limit] if stripped else ""


def _todo_status(value: Any) -> str:
    """Cursor spells its statuses several ways; apps know only three."""
    text = str(value or "").strip().lower().replace("-", "_")
    if text in _TODO_STATUS:
        return _TODO_STATUS[text]
    if "progress" in text:
        return "in_progress"
    if "complet" in text or "done" in text:
        return "completed"
    return "pending"


@dataclass(slots=True)
class _Stream:
    block_id: str
    text: str = ""


@dataclass(slots=True)
class _Tool:
    block_id: str
    tool: str
    tool_kind: str
    title: str
    started_at: int
    status: str = "running"
    input: dict[str, Any] = field(default_factory=dict)
    output: str = ""
    summary: str = ""


class CursorTranslator:
    """Stateful per-session mapping from `stream-json` lines to protocol events."""

    def __init__(self, cwd: str | None = None) -> None:
        self.cwd = cwd
        self.usage: dict[str, Any] = {}
        self._run = uuid.uuid4().hex[:12]
        self._stream_seq = 0
        self._open: dict[str, _Stream] = {}
        self._tools: dict[str, _Tool] = {}
        self._text_chars = 0
        self._handlers: dict[str, Handler] = {
            "system": self._on_system,
            "assistant": self._on_assistant,
            "thinking": self._on_thinking,
            "tool_call": self._on_tool_call,
            "result": self._on_result,
            "error": self._on_error,
        }

    # ------------------------------------------------------------ entry point

    def line(self, message: dict[str, Any]) -> list[Emit]:
        handler = self._handlers.get(str(message.get("type") or ""))
        return handler(message) if handler is not None else []

    def close_streams(self) -> list[Emit]:
        """Finish every open text block, which a torn-down turn must also do."""
        return [self._close(kind) for kind in list(self._open)]

    def close_tools(self, status: str = "cancelled") -> list[Emit]:
        """End every call still running, which a killed process leaves behind."""
        emits: list[Emit] = []
        for call_id, tool in list(self._tools.items()):
            tool.status = status
            emits.append(self._tool_emit(tool, ended=True))
            self._tools.pop(call_id, None)
        return emits

    # ---------------------------------------------------------------- streams

    def _chunk(self, kind: str, text: str) -> list[Emit]:
        if not text:
            return []
        stream = self._open.get(kind)
        if stream is None:
            self._stream_seq += 1
            stream = _Stream(block_id=f"{self._run}:{kind}:{self._stream_seq}")
            self._open[kind] = stream
        stream.text += text
        return [Emit(kind, {"block_id": stream.block_id, "delta": text}, delta=True)]

    def _close(self, kind: str) -> Emit:
        stream = self._open.pop(kind)
        return Emit(kind, {"block_id": stream.block_id, "text": stream.text, "done": True})

    # ------------------------------------------------------------------ lines

    def _on_system(self, message: dict[str, Any]) -> list[Emit]:
        """The `init` line names the chat; every other system line is noise here.

        Its `model` is the model's display name rather than its id, so it is not
        published as `meta`: the session keeps the id it was started with.
        """
        if str(message.get("subtype") or "") != "init":
            return []
        session_id = str(message.get("session_id") or "")
        if not session_id:
            return []
        return [Emit(SESSION, {"session_id": session_id, "cwd": str(message.get("cwd") or "")})]

    def _on_assistant(self, message: dict[str, Any]) -> list[Emit]:
        if message.get("model_call_id") or message.get("timestamp_ms") is None:
            # A buffered replay of deltas already published, not new text.
            return []
        text = _content_text(message.get("message"))
        self._text_chars += len(text)
        return self._chunk("assistant_text", text)

    def _on_thinking(self, message: dict[str, Any]) -> list[Emit]:
        subtype = str(message.get("subtype") or "")
        if subtype == "completed":
            return [self._close("thinking")] if "thinking" in self._open else []
        return self._chunk("thinking", str(message.get("text") or ""))

    # -------------------------------------------------------------- tool calls

    def _on_tool_call(self, message: dict[str, Any]) -> list[Emit]:
        call_id = str(message.get("call_id") or "")
        call = message.get("tool_call")
        if not call_id or not isinstance(call, dict):
            return []
        stem = tool_stem(call)
        body = call.get(f"{stem}{TOOL_SUFFIX}") or call.get(stem)
        body = body if isinstance(body, dict) else {}
        timestamp = int(message.get("timestamp_ms") or now_ms())
        tool = self._tools.get(call_id)
        if tool is None:
            tool = _Tool(
                block_id=call_id,
                tool=stem,
                tool_kind=_TOOL_KINDS.get(stem, "other"),
                title=stem,
                started_at=timestamp,
            )
            self._tools[call_id] = tool
        emits: list[Emit] = self.close_streams()
        self._apply(tool, body)
        ended = str(message.get("subtype") or "") == "completed"
        if ended:
            self._tools.pop(call_id, None)
        emits.append(self._tool_emit(tool, ended=ended, ended_at=timestamp))
        emits.extend(_todo_emits(stem, body))
        return emits

    def _apply(self, tool: _Tool, body: dict[str, Any]) -> None:
        args = body.get("args")
        if isinstance(args, dict):
            tool.input = dict(args)
            tool.title = _title(args, tool.tool)
        result = body.get("result")
        if not isinstance(result, dict):
            return
        outcome, value = _outcome(result)
        tool.status = "succeeded" if outcome == "success" else "failed"
        text, summary = _output(value)
        if text:
            tool.output = text[:MAX_OUTPUT_CHARS]
        if summary:
            tool.summary = summary

    @staticmethod
    def _tool_emit(tool: _Tool, *, ended: bool, ended_at: int | None = None) -> Emit:
        fields: dict[str, Any] = {
            "block_id": tool.block_id,
            "tool": tool.tool,
            "tool_kind": tool.tool_kind,
            "title": tool.title or tool.tool,
            "status": tool.status,
            "started_at": tool.started_at,
        }
        if tool.input:
            fields["input"] = tool.input
        if tool.output:
            fields["output"] = tool.output
        if tool.summary:
            fields["summary"] = tool.summary
        if ended:
            if tool.status == "running":
                tool.status = "succeeded"
                fields["status"] = tool.status
            finished = int(ended_at or now_ms())
            fields["ended_at"] = finished
            fields["duration_ms"] = max(0, finished - tool.started_at)
        return Emit("tool_call", fields)

    # -------------------------------------------------------------- turn state

    def _on_result(self, message: dict[str, Any]) -> list[Emit]:
        emits = self.close_streams()
        text = str(message.get("result") or "")
        if text and not self._text_chars:
            # The turn's text never came through as deltas; the final answer is
            # the only record of it.
            self._stream_seq += 1
            block = f"{self._run}:assistant_text:{self._stream_seq}"
            emits.append(Emit("assistant_text", {"block_id": block, "text": text, "done": True}))
        self._text_chars = 0
        self._add_usage(message.get("usage"))
        failed = bool(message.get("is_error")) or str(message.get("subtype") or "") != "success"
        fields: dict[str, Any] = {
            "stop_reason": "error" if failed else "completed",
            "duration_ms": int(message.get("duration_ms") or 0),
        }
        if self.usage:
            fields["usage"] = dict(self.usage)
        emits.append(Emit("turn_completed", fields))
        return emits

    def _add_usage(self, usage: Any) -> None:
        """Cursor counts one turn at a time; a session shows what it has spent."""
        if not isinstance(usage, dict):
            return
        counts = {
            key: int(usage.get(key) or 0)
            for key in ("inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens")
        }
        totals = {
            "input_tokens": counts["inputTokens"],
            "output_tokens": counts["outputTokens"],
            # `inputTokens` arrives net of the cache, so the total adds it back.
            "total_tokens": sum(counts.values()),
        }
        for key, value in totals.items():
            self.usage[key] = int(self.usage.get(key) or 0) + value

    def _on_error(self, message: dict[str, Any]) -> list[Emit]:
        text = str(message.get("message") or message.get("error") or "cursor error")
        return [Emit("error", {"message": text[:2000]})]


def _content_text(message: Any) -> str:
    """The text of an assistant line's content blocks."""
    if not isinstance(message, dict):
        return ""
    content = message.get("content")
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    return "".join(
        str(entry.get("text") or "")
        for entry in content
        if isinstance(entry, dict) and entry.get("type") == "text"
    )


def _title(args: dict[str, Any], fallback: str) -> str:
    for key in _TITLE_KEYS:
        value = args.get(key)
        if isinstance(value, str) and value.strip():
            return _first_line(value)
    return fallback


def _outcome(result: dict[str, Any]) -> tuple[str, Any]:
    """A tool result is a union too: `success` is the only outcome that succeeded."""
    for key, value in result.items():
        return str(key), value
    return "success", {}


def _output(value: Any) -> tuple[str, str]:
    """Readable output and a badge, from whichever shape this tool returned."""
    if isinstance(value, str):
        return value, ""
    if not isinstance(value, dict):
        return "", ""
    summary = ""
    for key in ("exit_code", "exitCode"):
        code = value.get(key)
        if isinstance(code, int):
            summary = f"exit {code}"
            break
    for key in _OUTPUT_KEYS:
        text = value.get(key)
        if isinstance(text, str) and text:
            return text, summary
    return (json.dumps(value, ensure_ascii=False) if value else ""), summary


def _todo_emits(stem: str, body: dict[str, Any]) -> list[Emit]:
    """`updateTodos` carries the list an app draws as the session's plan."""
    if stem != "updateTodos":
        return []
    args = body.get("args")
    todos = args.get("todos") if isinstance(args, dict) else None
    if not isinstance(todos, list):
        return []
    items: list[dict[str, str]] = []
    for index, entry in enumerate(todos[:128]):
        if not isinstance(entry, dict):
            continue
        items.append(
            {
                "id": str(entry.get("id") or index),
                "text": str(entry.get("content") or entry.get("text") or ""),
                "status": _todo_status(entry.get("status")),
            }
        )
    return [Emit("todos", {"items": items})] if items else []
