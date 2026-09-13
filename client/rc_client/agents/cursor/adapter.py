"""Drive one Cursor session as a `cursor-agent -p` process per turn.

Cursor has no server mode and no daemon: a turn is one short-lived process that
prints NDJSON and exits, and the conversation lives on Cursor's own servers
under a chat id. So the runner spawns a process per prompt, learns the chat id
from the first line of the first turn, and resumes that chat on every turn
after it. Interrupting a turn is killing the process.

Approvals are the one thing that cannot be done on the stream, which carries no
request and accepts no answer. They arrive instead through Cursor's `preToolUse`
hook, which dials the broker and blocks; the runner registers this session's
chat id there for as long as it is alive, so the hook stays silent for every
other Cursor session on the machine.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import signal
import uuid
from collections.abc import Awaitable, Callable
from typing import Any

from ...child_env import sanitized_child_env
from ...errors import RcError
from ...logging_setup import logger
from ...models import UNSET, SpeedSetting, now_ms
from ...sessions.channel import SessionChannel
from ..base import Emit
from . import broker, runtime
from .translate import SESSION, CursorTranslator

log = logger("rc_client.cursor")

# `--mode` has no "normal" value; omitting every flag is normal mode.
MODE_FLAGS: dict[str, list[str]] = {
    "default": [],
    "force": ["--force"],
    "plan": ["--mode", "plan"],
    "ask": ["--mode", "ask"],
}
APPROVAL_OPTIONS = [
    {"id": broker.ALLOW, "label": "Allow", "style": "primary"},
    {"id": broker.DENY, "label": "Deny", "style": "danger"},
]
MAX_LINE_BYTES = 4 * 1024 * 1024
MAX_STDERR_BYTES = 2000
TERMINATE_GRACE = 5.0

TurnEndCallback = Callable[[], Awaitable[None]]
SessionIdCallback = Callable[[str], Awaitable[None]]


class CursorRunner:
    agent = "cursor"

    def __init__(
        self,
        channel: SessionChannel,
        *,
        binary: str,
        cwd: str,
        model: str | None = None,
        permission_mode: str | None = None,
        resume: str | None = None,
        on_turn_end: TurnEndCallback | None = None,
        on_session_id: SessionIdCallback | None = None,
    ) -> None:
        self.channel = channel
        self._binary = binary
        self._cwd = cwd
        self._model = model
        self._permission_mode = permission_mode or "default"
        self._chat_id = resume
        self._on_turn_end = on_turn_end
        self._on_session_id = on_session_id
        self._translator = CursorTranslator(cwd=cwd)
        self._process: asyncio.subprocess.Process | None = None
        self._turn: asyncio.Task[None] | None = None
        self._turn_started_at = 0
        self._active = False
        self._generation = 0
        self._interrupting = False
        self._registered: str | None = None
        self._pending: dict[str, asyncio.Future[dict[str, Any]]] = {}

    # ------------------------------------------------------------- lifecycle

    async def start(self) -> None:
        """Nothing runs between turns; a resumed session can already take hooks."""
        if self._chat_id:
            await self._register(self._chat_id)

    async def close(self) -> None:
        for future in self._pending.values():
            if not future.done():
                future.cancel()
        self._pending.clear()
        if self._turn is not None:
            self._turn.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._turn
            self._turn = None
        await self._kill()
        if self._registered is not None:
            await broker.unregister(self._registered)
            self._registered = None

    async def _register(self, chat_id: str) -> None:
        if self._registered == chat_id:
            return
        if self._registered is not None:
            await broker.unregister(self._registered)
        await broker.register(runtime.socket_path(), chat_id, self._decide)
        self._registered = chat_id

    # --------------------------------------------------------------- driving

    @property
    def busy(self) -> bool:
        """A turn is in progress, which ends before its task does.

        The turn is over the moment the process reports its result, and the hub
        sends the next queued message from `on_turn_end` right then, while the
        task is still winding the child down. So readiness is this flag rather
        than the state of the task.
        """
        return self._active

    @property
    def supports_steer(self) -> bool:
        """A turn is a process that has already been given its prompt."""
        return False

    async def steer(self, text: str, block_id: str | None = None) -> bool:
        return False

    def spawn_args(self, text: str) -> list[str]:
        """The whole command line for one turn, prompt last behind a `--`."""
        args = ["-p", "--output-format", "stream-json", "--stream-partial-output"]
        if self._chat_id:
            args.extend(["--resume", self._chat_id])
        # `auto` is Cursor's own "let the server choose", which is what passing
        # no model at all means, and the only id valid on every install.
        if self._model and self._model != "auto":
            args.extend(["--model", self._model])
        args.extend(MODE_FLAGS.get(self._permission_mode, []))
        args.extend(["--", text])
        return args

    async def send(
        self,
        text: str,
        attachments: list[dict[str, Any]] | None = None,
        source: str = "remote",
        block_id: str | None = None,
    ) -> None:
        if self.busy:
            raise RcError("conflict", "the cursor session is already running a turn")
        if attachments:
            # Print mode takes a prompt and nothing else; the hub refuses first.
            raise RcError("unsupported", "cursor sessions cannot carry attachments")
        await self.channel.emit(
            "user_message",
            block_id=block_id or f"user:{uuid.uuid4()}",
            text=text,
            source=source,
        )
        self._turn_started_at = now_ms()
        self._generation += 1
        self._active = True
        turn = self._generation
        await self.channel.begin_turn(source)
        self._turn = asyncio.create_task(self._run_turn(text, turn), name="cursor-turn")

    async def _run_turn(self, text: str, generation: int) -> None:
        try:
            process = await asyncio.create_subprocess_exec(
                self._binary,
                *self.spawn_args(text),
                cwd=self._cwd,
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=sanitized_child_env(),
                limit=MAX_LINE_BYTES,
            )
        except OSError as exc:
            await self._fail_turn(str(exc), generation)
            return
        self._process = process
        # stderr has to be drained as well as stdout: a child whose error pipe
        # fills up stops writing anything at all.
        complaint = asyncio.create_task(_drain(process.stderr), name="cursor-stderr")
        try:
            await self._read(process)
            await process.wait()
        except asyncio.CancelledError:
            complaint.cancel()
            raise
        except Exception as exc:
            complaint.cancel()
            await self._fail_turn(str(exc), generation)
            return
        await self._settle(process.returncode or 0, await complaint, generation)

    async def _read(self, process: asyncio.subprocess.Process) -> None:
        """One JSON object per line, until the process closes its stdout."""
        stream = process.stdout
        if stream is None:
            return
        while True:
            try:
                line = await stream.readline()
            except (ValueError, asyncio.LimitOverrunError):
                # A single line longer than the reader's buffer; skip it rather
                # than end a turn that is otherwise healthy.
                continue
            if not line:
                return
            message = _decode(line)
            if message is None:
                continue
            for emit in self._translator.line(message):
                await self._apply(emit)

    async def _apply(self, emit: Emit) -> None:
        if emit.kind == SESSION:
            await self._adopt(str(emit.fields.get("session_id") or ""))
            return
        if emit.delta:
            fields = dict(emit.fields)
            block_id = str(fields.pop("block_id"))
            delta = str(fields.pop("delta", ""))
            await self.channel.emit_delta(emit.kind, block_id, delta, **fields)
            return
        if emit.kind == "todos":
            await self.channel.publish_todos(list(emit.fields.get("items") or []))
            return
        if emit.kind == "turn_completed":
            await self._finish_turn(dict(emit.fields))
            return
        await self.channel.emit(emit.kind, **emit.fields)

    async def _adopt(self, chat_id: str) -> None:
        """The chat Cursor minted is the session id every later turn resumes."""
        if not chat_id or chat_id == self._chat_id:
            await self._register(chat_id or self._chat_id or "")
            return
        self._chat_id = chat_id
        await self._register(chat_id)
        if self._on_session_id:
            await self._on_session_id(chat_id)

    async def _settle(self, returncode: int, complaint: str, generation: int) -> None:
        """Close whatever the process left open when it exits mid-turn."""
        if not self._current(generation):
            return
        for emit in self._translator.close_streams():
            await self._apply(emit)
        for emit in self._translator.close_tools("cancelled" if self._interrupting else "failed"):
            await self._apply(emit)
        if returncode != 0 and not self._interrupting:
            detail = f": {complaint}" if complaint else ""
            await self.channel.error(f"cursor-agent exited with status {returncode}{detail}")
        await self._finish_turn({"stop_reason": "error", "duration_ms": 0})

    async def _fail_turn(self, message: str, generation: int) -> None:
        if not self._current(generation):
            return
        for emit in self._translator.close_streams():
            await self._apply(emit)
        await self.channel.error(message[:2000])
        await self._finish_turn({"stop_reason": "error", "duration_ms": 0})

    def _current(self, generation: int) -> bool:
        """Whether this task still owns the turn it was started for."""
        return self._active and generation == self._generation

    async def _finish_turn(self, completion: dict[str, Any]) -> None:
        if not self._active:
            return
        # Free the session before the callback: the hub sends the next
        # queued message from inside it.
        self._active = False
        duration = int(completion.get("duration_ms") or 0) or max(
            0, now_ms() - self._turn_started_at
        )
        reason = "interrupted" if self._interrupting else str(completion.get("stop_reason"))
        self._interrupting = False
        usage = completion.get("usage")
        await self.channel.end_turn(
            reason, duration, dict(usage) if isinstance(usage, dict) else None
        )
        if self._on_turn_end is not None:
            await self._on_turn_end()

    # ------------------------------------------------------------- interrupt

    async def interrupt(self) -> bool:
        if not self._active:
            return False
        self._interrupting = True
        await self.channel.set_state("running", "interrupting")
        for request_id, future in list(self._pending.items()):
            if not future.done():
                future.set_result({"cancelled": True})
            self._pending.pop(request_id, None)
        await self._kill()
        return True

    async def _kill(self) -> None:
        """Cursor has no cancel: a turn ends when its process does.

        The reference to the child outlives its turn deliberately. Closing the
        session cancels the turn task, and a task cancelled between the spawn
        and the exit would otherwise leave nothing pointing at a live process.
        """
        process = self._process
        if process is None or process.returncode is not None:
            return
        with contextlib.suppress(ProcessLookupError):
            process.send_signal(signal.SIGTERM)
        try:
            await asyncio.wait_for(asyncio.shield(process.wait()), timeout=TERMINATE_GRACE)
        except TimeoutError:
            log.warning("cursor-agent ignored SIGTERM; killing it")
            with contextlib.suppress(ProcessLookupError):
                process.kill()
            with contextlib.suppress(Exception):
                await process.wait()

    # -------------------------------------------------------------- settings

    async def apply_settings(
        self,
        model: str | None,
        permission_mode: str | None,
        effort: str | None,
        speed: SpeedSetting = UNSET,
    ) -> None:
        """Both settings are process options, so they take effect on the next turn."""
        if effort is not None:
            raise RcError("unsupported", "cursor has no effort levels")
        if speed is not UNSET and speed is not None:
            raise RcError("unsupported", "cursor has no speed tiers")
        fields: dict[str, Any] = {}
        if model is not None:
            self._model = model
            fields["model"] = model
        if permission_mode is not None:
            if permission_mode not in MODE_FLAGS:
                raise RcError("bad_request", f"unknown permission mode: {permission_mode}")
            self._permission_mode = permission_mode
            fields["permission_mode"] = permission_mode
        if fields:
            await self.channel.set_meta(**fields)

    # ------------------------------------------------------- prompts to user

    async def _decide(self, payload: dict[str, Any]) -> str | None:
        """Hold one tool call as an `approval` block until somebody answers it."""
        request_id = str(uuid.uuid4())
        tool = str(payload.get("tool_name") or "Tool")
        tool_input = payload.get("tool_input")
        tool_input = tool_input if isinstance(tool_input, dict) else {}
        base: dict[str, Any] = {
            "block_id": f"approval:{request_id}",
            "request_id": request_id,
            "tool": tool,
            "tool_kind": "other",
            "title": _approval_title(tool, tool_input),
            "options": list(APPROVAL_OPTIONS),
        }
        if tool_input:
            base["input"] = tool_input
        await self.channel.emit("approval", status="pending", **base)
        await self.channel.set_state("needs_approval")
        decision = await self._wait_for(request_id)
        option_id = str(decision.get("option_id") or "") if decision else ""
        if option_id not in {option["id"] for option in APPROVAL_OPTIONS}:
            # An id we never advertised must never be read as consent.
            decision = None
            option_id = ""
        await self.channel.emit(
            "approval",
            status="resolved" if decision else "expired",
            **({"decision": {"option_id": option_id, "by": "remote"}} if decision else {}),
            **base,
        )
        await self.channel.set_state("running")
        return option_id or None

    async def _wait_for(self, request_id: str) -> dict[str, Any] | None:
        future: asyncio.Future[dict[str, Any]] = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        try:
            answer = await future
        except asyncio.CancelledError:
            if future.cancelled():
                return None
            raise
        finally:
            self._pending.pop(request_id, None)
        return None if answer.get("cancelled") else answer

    async def approve(self, request_id: str, option_id: str, message: str | None) -> bool:
        future = self._pending.get(request_id)
        if future is None or future.done():
            return False
        future.set_result({"option_id": option_id, "message": message})
        return True

    async def answer(self, request_id: str, answers: dict[str, Any]) -> bool:
        """Cursor answers its own questions in the stream; nothing waits here."""
        return False


async def _drain(stream: asyncio.StreamReader | None) -> str:
    """Read the child's stderr to the end, keeping the tail worth reporting."""
    if stream is None:
        return ""
    collected = bytearray()
    while True:
        try:
            chunk = await stream.read(4096)
        except (ValueError, OSError):
            break
        if not chunk:
            break
        collected.extend(chunk)
        if len(collected) > MAX_STDERR_BYTES:
            del collected[:-MAX_STDERR_BYTES]
    return collected.decode("utf-8", "replace").strip()[-MAX_STDERR_BYTES:]


def _decode(line: bytes) -> dict[str, Any] | None:
    try:
        parsed = json.loads(line)
    except (UnicodeDecodeError, ValueError):
        return None
    return parsed if isinstance(parsed, dict) else None


def _approval_title(tool: str, tool_input: dict[str, Any]) -> str:
    for key in ("command", "file_path", "path", "target_file", "url", "query"):
        value = tool_input.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip().splitlines()[0][:200]
    return tool or "run a tool"
