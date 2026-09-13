"""Drive one pi session over its RPC mode on a private `pi --mode rpc` child.

pi has no permission system: every tool it decides to run, it runs. A session
this device drives therefore has pi's own full permissions, which is why
`AgentInfo.permission_modes` is empty (4.2) and `session.set` refuses a
permission mode rather than pretending to apply one.
"""

from __future__ import annotations

import asyncio
import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from ...child_env import sanitized_child_env
from ...errors import RcError
from ...logging_setup import logger
from ...models import UNSET, SpeedSetting, now_ms
from ...sessions.channel import SessionChannel
from ..base import Emit
from . import catalog as catalogue
from .rpc import DRAIN_TIMEOUT, PiProcess
from .translate import QUEUE, PiTranslator

log = logger("rc_client.pi")

# A running tool's output is republished at most this often, so a noisy build
# cannot flood the link with replacement events.
OUTPUT_THROTTLE = 0.5

TurnEndCallback = Callable[[], Awaitable[None]]
SessionIdCallback = Callable[[str], Awaitable[None]]


@dataclass(slots=True, frozen=True)
class _Steer:
    """A message sent into a running turn, and the block it is owed under."""

    text: str
    block_id: str


class PiRunner:
    agent = "pi"

    def __init__(
        self,
        channel: SessionChannel,
        *,
        binary: str,
        cwd: str,
        session_id: str,
        model: str | None = None,
        effort: str | None = None,
        on_turn_end: TurnEndCallback | None = None,
        on_session_id: SessionIdCallback | None = None,
    ) -> None:
        self.channel = channel
        self._binary = binary
        self._cwd = cwd
        self._session_id = session_id
        self._model = model
        self._effort = effort
        self._on_turn_end = on_turn_end
        self._on_session_id = on_session_id
        self._process: PiProcess | None = None
        self._translator = PiTranslator()
        self._context_window: int | None = None
        self._streaming = False
        self._interrupting = False
        self._turn_started_at = 0
        self._turn_done = asyncio.Event()
        self._turn_done.set()
        self._steers: list[_Steer] = []
        self._last_output_flush: dict[str, float] = {}

    # ------------------------------------------------------------- lifecycle

    async def start(self) -> None:
        process = PiProcess(
            self._binary,
            cwd=self._cwd,
            args=self._spawn_args(),
            env=sanitized_child_env(),
            on_event=self._on_event,
            on_closed=self._on_closed,
        )
        await process.start()
        self._process = process
        await self._adopt_state(await process.command("get_state"))

    def _spawn_args(self) -> list[str]:
        """`--session-id` creates the session when it does not exist yet.

        `--no-approve` keeps the device from trusting a project's own pi
        settings, resources and extensions: a remote session must not start
        running code that happens to be checked into the working directory.
        """
        args = ["--session-id", self._session_id, "--no-approve"]
        if self._model:
            args.extend(["--model", self._model])
        if self._effort:
            args.extend(["--thinking", self._effort])
        return args

    async def _adopt_state(self, state: dict[str, Any]) -> None:
        """Report what pi actually runs with, which A17 says apps show."""
        model = state.get("model") if isinstance(state.get("model"), dict) else None
        self._context_window = catalogue.context_window(model)
        fields: dict[str, Any] = {}
        running = catalogue.model_id(model)
        if running:
            self._model = running
            fields["model"] = running
        level = state.get("thinkingLevel")
        if isinstance(level, str) and level:
            self._effort = level
            fields["effort"] = level
        if fields:
            await self.channel.set_meta(**fields)
        session_id = str(state.get("sessionId") or "")
        if session_id and session_id != self._session_id:
            self._session_id = session_id
            if self._on_session_id:
                await self._on_session_id(session_id)

    async def close(self) -> None:
        process = self._process
        self._process = None
        if process is not None:
            await process.close()

    # ------------------------------------------------------------- streaming

    async def _on_event(self, event: dict[str, Any]) -> None:
        completion: dict[str, Any] | None = None
        for emit in self._translator.event(event):
            if emit.kind == "turn_completed":
                completion = dict(emit.fields)
                continue
            if emit.kind == QUEUE:
                await self._apply_queue(emit.fields)
                continue
            await self._apply(emit)
        if completion is not None:
            await self._finish_turn(completion)

    async def _on_closed(self) -> None:
        """pi left while a turn was running: end it rather than stream forever."""
        if self.channel.session.turn is None:
            return
        for emit in self._translator.close_streams():
            await self._apply(emit)
        await self.channel.error("the pi process exited before the turn completed")
        await self._finish_turn({"stop_reason": "error"})

    async def _apply(self, emit: Emit) -> None:
        if emit.delta:
            fields = dict(emit.fields)
            block_id = str(fields.pop("block_id"))
            delta = str(fields.pop("delta", ""))
            await self.channel.emit_delta(emit.kind, block_id, delta, **fields)
            return
        if emit.kind == "tool_call" and emit.fields.get("status") == "running":
            block_id = str(emit.fields.get("block_id") or "")
            now = time.monotonic()
            if emit.fields.get("output") and now - self._last_output_flush.get(block_id, 0.0) < (
                OUTPUT_THROTTLE
            ):
                return
            self._last_output_flush[block_id] = now
        await self.channel.emit(emit.kind, **emit.fields)

    async def _apply_queue(self, fields: dict[str, Any]) -> None:
        """Publish the bubble of every steered message pi has now taken (A14).

        pi reports its whole steering queue whenever it changes, so a message
        that has left the queue is one the agent read, and that is where its
        `user_message` belongs. Nothing is taken while an interrupt is clearing
        the queue; those messages were never read and are published at the end
        of the turn instead.
        """
        if self._interrupting:
            return
        queued = list(fields.get("steering") or [])
        for steer in list(self._steers):
            if steer.text in queued:
                queued.remove(steer.text)
                continue
            self._steers.remove(steer)
            await self._publish_steer(steer)

    async def _publish_steer(self, steer: _Steer) -> None:
        await self.channel.emit(
            "user_message", block_id=steer.block_id, text=steer.text, source="remote"
        )

    async def _publish_unread(self, stop_reason: str) -> None:
        """Show the steered messages this turn ended without ever reading."""
        unread = list(self._steers)
        self._steers.clear()
        for steer in unread:
            await self._publish_steer(steer)
            if stop_reason == "interrupted":
                await self.channel.notice(
                    "warn", "the agent was stopped before it read your message"
                )

    async def _finish_turn(self, completion: dict[str, Any]) -> None:
        if self.channel.session.turn is None:
            return
        reason = "interrupted" if self._interrupting else str(completion.get("stop_reason"))
        self._interrupting = False
        self._streaming = False
        self._last_output_flush.clear()
        await self._publish_unread(reason)
        duration = max(0, now_ms() - self._turn_started_at)
        await self.channel.end_turn(reason, duration, await self._usage())
        self._turn_done.set()
        if self._on_turn_end is not None:
            await self._on_turn_end()

    async def _usage(self) -> dict[str, Any] | None:
        """pi counts the whole session itself, cost and context window included."""
        process = self._process
        if process is None:
            return None
        try:
            stats = await process.command("get_session_stats")
        except RcError:
            return None
        tokens = stats.get("tokens")
        if not isinstance(tokens, dict):
            return None
        usage: dict[str, Any] = {
            "input_tokens": int(tokens.get("input") or 0),
            "output_tokens": int(tokens.get("output") or 0),
            "total_tokens": int(tokens.get("total") or 0),
        }
        cost = stats.get("cost")
        if isinstance(cost, int | float) and cost > 0:
            usage["cost_usd"] = round(float(cost), 8)
        context = stats.get("contextUsage")
        context = context if isinstance(context, dict) else {}
        used = context.get("tokens")
        if isinstance(used, int):
            usage["context_used"] = used
        window = context.get("contextWindow")
        window = window if isinstance(window, int) else self._context_window
        if window:
            usage["context_window"] = window
        return usage

    # --------------------------------------------------------------- driving

    @property
    def busy(self) -> bool:
        return self._streaming

    @property
    def supports_steer(self) -> bool:
        """A prompt sent mid-turn is read at the agent's next step, not queued."""
        return True

    async def send(
        self,
        text: str,
        attachments: list[dict[str, Any]] | None = None,
        source: str = "remote",
        block_id: str | None = None,
    ) -> None:
        process = self._require_process()
        if attachments:
            # pi's `prompt` takes images, but this device does not advertise
            # `attachments` for it; the hub refuses before this is reached.
            raise RcError("unsupported", "pi sessions cannot carry attachments")
        await self.channel.emit(
            "user_message",
            block_id=block_id or f"user:{uuid.uuid4()}",
            text=text,
            source=source,
        )
        await self._prompt(process, text)
        self._streaming = True
        self._turn_started_at = now_ms()
        self._turn_done.clear()
        await self.channel.begin_turn(source)

    async def _prompt(self, process: PiProcess, text: str, **extra: Any) -> None:
        try:
            await process.command("prompt", message=text, **extra)
        except RcError as exc:
            await self.channel.error(exc.message[:2000])
            raise RcError("agent_unavailable", exc.message) from exc

    async def steer(self, text: str, block_id: str | None = None) -> bool:
        process = self._process
        if process is None or not self._streaming:
            return False
        try:
            await process.command("prompt", message=text, streamingBehavior="steer")
        except RcError:
            return False
        # Amendment A14: the bubble waits for pi to take the message off its
        # steering queue, which is the step that reads it.
        self._steers.append(_Steer(text, block_id or f"user:{uuid.uuid4()}"))
        return True

    async def interrupt(self) -> bool:
        process = self._process
        if process is None or not self._streaming:
            return False
        self._interrupting = True
        await self.channel.set_state("running", "interrupting")
        try:
            # Stopping means stopping: `abort` alone resumes with whatever is
            # still queued, so the queue goes first (pi's own documented order).
            await process.command("clear_queue")
            await process.command("abort", timeout=DRAIN_TIMEOUT)
        except RcError:
            log.warning("pi refused the abort; waiting for the turn to settle")
        try:
            await asyncio.wait_for(self._turn_done.wait(), timeout=DRAIN_TIMEOUT)
        except TimeoutError:
            log.warning("pi did not settle after abort; ending the turn")
            await self.channel.notice("warn", "the agent did not stop in time")
            await self._finish_turn({"stop_reason": "interrupted"})
        return True

    async def apply_settings(
        self,
        model: str | None,
        permission_mode: str | None,
        effort: str | None,
        speed: SpeedSetting = UNSET,
    ) -> None:
        if permission_mode is not None:
            raise RcError("unsupported", "pi has no permission modes")
        if speed is not UNSET and speed is not None:
            raise RcError("unsupported", "pi has no speed tiers")
        process = self._require_process()
        if model is not None:
            provider, identifier = catalogue.split_model(model)
            await process.command("set_model", provider=provider, modelId=identifier)
            self._model = model
            await self.channel.set_meta(model=model)
        if effort is not None:
            await process.command("set_thinking_level", level=effort)
            self._effort = effort
            await self.channel.set_meta(effort=effort)

    async def approve(self, request_id: str, option_id: str, message: str | None) -> bool:
        """pi never asks: it has no permission system, so nothing is ever waiting."""
        return False

    async def answer(self, request_id: str, answers: dict[str, Any]) -> bool:
        return False

    def _require_process(self) -> PiProcess:
        process = self._process
        if process is None:
            raise RcError("agent_unavailable", "the pi session is not connected")
        return process
