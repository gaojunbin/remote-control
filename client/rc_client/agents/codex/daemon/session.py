"""One Codex thread on the shared daemon, driven as a session runner.

Everything the terminal does on this thread reaches us as ordinary
notifications, and everything we do reaches the terminal, so the only
difference from the private app-server adapter is that we neither own nor
outlive the process on the other end.
"""

from __future__ import annotations

import asyncio
import contextlib
import time
import uuid
from collections.abc import Awaitable, Callable
from typing import Any

from ....attachments import Attachment, describe, materialise, wire_attachments
from ....errors import RcError
from ....logging_setup import logger
from ....models import UNSET, SpeedSetting, now_ms
from ....sessions.channel import SessionChannel
from ..echoes import Echo, EchoLog
from ..models import ModelCatalog, tier_id
from ..translate import CodexTranslator, item_type, normalise, text_of
from .dialogs import DialogDesk
from .rpc import DaemonClient

log = logger("rc_client.codex.daemon.session")

APPROVAL_TIMEOUT = 300.0
OUTPUT_THROTTLE = 0.2
DRAIN_TIMEOUT = 15.0
BACKFILL_ITEMS = 200
BACKFILL_PAGE = 100
# Notifications that mean something has moved on this thread, so a resume the
# daemon refused before may be accepted now.
TURN_BOUNDARIES = frozenset({"turn/started", "turn/completed", "thread/status/changed"})

ControlCallback = Callable[[], Awaitable[None]]
TurnEndCallback = Callable[[], Awaitable[None]]


class CodexDaemonSession:
    """A session runner backed by one thread inside the shared daemon."""

    agent = "codex"

    def __init__(
        self,
        channel: SessionChannel,
        client: DaemonClient,
        *,
        cwd: str,
        catalog: ModelCatalog,
        model: str | None = None,
        permission_mode: str | None = None,
        effort: str | None = None,
        speed: str | None = None,
        thread_id: str | None = None,
        thread_config: dict[str, Any] | None = None,
        created_here: bool | None = None,
        on_turn_end: TurnEndCallback | None = None,
        on_control_change: ControlCallback | None = None,
        on_thread_id: Callable[[str], Awaitable[None]] | None = None,
    ) -> None:
        self.channel = channel
        self._client = client
        self._cwd = cwd
        self._catalog = catalog
        self._model = model
        self._permission_mode = permission_mode or "on-request"
        self._effort = effort
        self._speed = speed
        self._thread_id = thread_id
        self._thread_config = thread_config
        self._on_turn_end = on_turn_end
        self._on_control_change = on_control_change
        self._on_thread_id = on_thread_id
        self._translator = CodexTranslator(cwd=cwd, mirror_user_messages=True)
        self._turn_id: str | None = None
        self._turn_started_at = 0
        self._interrupting = False
        self._turn_epoch = 0
        self._turn_done = asyncio.Event()
        self._turn_done.set()
        self._dialogs = DialogDesk(
            channel,
            cwd=cwd,
            busy=lambda: self.busy,
            pending_diff=self._translator.pending_diff,
        )
        self._last_output_flush: dict[str, float] = {}
        self._echoes = EchoLog()
        self._last_item_id: str | None = None
        self._subscribed = False
        # Whether a resume has already been refused for this thread. A thread
        # with no rollout refuses every time, and asking again on each message
        # and each notification puts a failed round trip in front of them.
        self._resume_refused = False
        self._terminal_seen = False
        self._terminal_holds = False
        self._terminal_spoke = False
        self._local_turn = False
        self._created_here = thread_id is None if created_here is None else created_here

    # ------------------------------------------------------------- accessors

    @property
    def thread_id(self) -> str | None:
        return self._thread_id

    @property
    def terminal_seen(self) -> bool:
        """Whether a terminal has ever been seen in this thread. Never unsaid."""
        return self._terminal_seen

    @property
    def terminal_holds(self) -> bool:
        """Whether a terminal is in this thread now, as far as anyone can tell."""
        return self._terminal_holds

    @property
    def local_turn(self) -> bool:
        """Whether the turn running right now is one this device started."""
        return self._turn_id is not None and self._local_turn

    @property
    def created_here(self) -> bool:
        return self._created_here

    @property
    def busy(self) -> bool:
        return self._turn_id is not None

    @property
    def supports_steer(self) -> bool:
        return True

    def claim_terminal(self) -> None:
        """Record a terminal in this thread from evidence no scan can give.

        A thread another client has just opened or resumed belongs to whoever
        opened it, and the process scan may not see that TUI at all: it can be
        younger than the scan, or running in a directory of its own.
        """
        self._terminal_seen = True
        self._terminal_holds = True
        self._terminal_spoke = True

    def terminal_present(self, present: bool) -> bool:
        """Fold one process scan into what we believe about the terminal.

        Evidence gathered since the last scan outranks the scan: the TUI that
        typed, opened or resumed this thread may be standing in a directory
        where no process the scan can attribute to it is to be found. Answers
        with what the thread now believes, which is what the caller shares out
        between the threads competing for the same terminal.
        """
        if self._terminal_spoke:
            self._terminal_spoke = False
            self._terminal_holds = True
        else:
            self._terminal_holds = present
        return self._terminal_holds

    async def _control_changed(self) -> None:
        if self._on_control_change is not None:
            await self._on_control_change()

    # ------------------------------------------------------------- lifecycle

    @property
    def subscribed(self) -> bool:
        return self._subscribed

    async def start(self) -> None:
        if self._thread_id is None:
            await self._start_thread()
        else:
            await self.resubscribe()

    async def _start_thread(self) -> None:
        params: dict[str, Any] = {"cwd": self._cwd, "approvalPolicy": self._permission_mode}
        if self._model:
            params["model"] = self._model
        if self._thread_config:
            params["config"] = self._thread_config
        wanted_speed = self._speed
        result = await self._client.request("thread/start", params)
        thread = result.get("thread") or {}
        thread_id = str(thread.get("id") or "")
        if not thread_id:
            raise RcError("agent_unavailable", "the codex daemon did not return a thread id")
        self._thread_id = thread_id
        self._subscribed = True
        self._adopt_settings(result)
        if wanted_speed is not None and wanted_speed != self._speed:
            # `thread/start` accepts `serviceTier` and ignores it: the tier is a
            # thread setting, so a new session asks for it once the thread is up.
            self._speed = wanted_speed
            await self._client.request(
                "thread/settings/update", {"threadId": thread_id, "serviceTier": wanted_speed}
            )
        # A thread whose tier came from the user's own Codex configuration is
        # already faster than the session says it is.
        await self.channel.set_meta(speed=self._speed)
        if self._on_thread_id is not None:
            await self._on_thread_id(thread_id)

    async def try_resume(self) -> bool:
        """Resume, unless we already know the daemon will refuse.

        The refusal is remembered until something could have changed it — a
        turn boundary on this thread, or a turn this device has just started —
        so an unresumable thread costs one failed request per turn rather than
        one per message and one per notification.
        """
        if self._subscribed:
            return True
        if self._resume_refused:
            return False
        return await self.resubscribe()

    def resume_again(self) -> None:
        """Something happened on this thread; a refused resume is worth retrying."""
        self._resume_refused = False

    async def resubscribe(self) -> bool:
        """Resume the thread and catch up on anything we missed while away.

        A thread the terminal created moments ago has no rollout until its first
        turn starts, and the daemon refuses to resume it until then. That is not
        a failure: the thread is loaded, `turn/start` already works on it, and
        the subscription is taken the moment the rollout exists.
        """
        thread_id = self._thread_id
        if thread_id is None:
            return False
        params: dict[str, Any] = {"threadId": thread_id, "excludeTurns": True}
        if self._thread_config and self._created_here:
            # Never rewrite the configuration of a thread somebody else started:
            # a `config` on resume applies to the thread, not to our view of it.
            params["config"] = self._thread_config
        try:
            result = await self._client.request("thread/resume", params)
        except RcError as exc:
            self._subscribed = False
            self._resume_refused = True
            log.info("codex thread not resumable yet", error=exc.message[:120])
            return False
        self._subscribed = True
        self._resume_refused = False
        self._adopt_settings(result)
        await self.backfill()
        return True

    def _adopt_settings(self, result: dict[str, Any]) -> None:
        model = result.get("model")
        if isinstance(model, str) and model:
            self._model = model
        effort = result.get("reasoningEffort")
        if isinstance(effort, str) and effort:
            self._effort = effort
        policy = result.get("approvalPolicy")
        if isinstance(policy, str) and policy:
            self._permission_mode = policy
        if "serviceTier" in result:
            # Null is the standard speed, so the key's absence is the only thing
            # that leaves the tier alone (amendment A21).
            self._speed = tier_id(result.get("serviceTier"))

    async def publish_settings(self) -> None:
        """Publish what the thread reports about itself. The tier may be null (A21)."""
        fields: dict[str, Any] = {"speed": self._speed}
        for key, value in (
            ("model", self._model),
            ("permission_mode", self._permission_mode),
            ("effort", self._effort),
        ):
            if value is not None:
                fields[key] = value
        await self.channel.set_meta(**fields)

    async def close(self) -> None:
        """Let go of the thread without unloading it: the terminal may still be there."""
        self._dialogs.cancel_all()
        thread_id = self._thread_id
        if thread_id is not None and self._subscribed and self._client.connected:
            with contextlib.suppress(RcError):
                await self._client.request("thread/unsubscribe", {"threadId": thread_id})
        self._subscribed = False

    # -------------------------------------------------------------- backfill

    async def backfill(self) -> None:
        """Replay the items appended since the last one we published."""
        thread_id = self._thread_id
        if thread_id is None:
            return
        collected: list[dict[str, Any]] = []
        cursor: str | None = None
        stop = self._last_item_id
        found = False
        while len(collected) < BACKFILL_ITEMS and not found:
            params: dict[str, Any] = {
                "threadId": thread_id,
                "limit": BACKFILL_PAGE,
                "sortDirection": "desc",
            }
            if cursor:
                params["cursor"] = cursor
            try:
                page = await self._client.request("thread/items/list", params)
            except RcError as exc:
                log.warning("codex history backfill failed", error=exc.message[:200])
                return
            items = [item for item in (page.get("data") or []) if isinstance(item, dict)]
            for item in items:
                if stop is not None and str(item.get("id") or "") == stop:
                    found = True
                    break
                collected.append(item)
            cursor = page.get("nextCursor") if isinstance(page.get("nextCursor"), str) else None
            if not cursor or not items:
                break
        if stop is None and not collected:
            return
        for item in reversed(collected):
            await self._publish_item(item, completed=True, timestamp=None)

    # ---------------------------------------------------------- notifications

    async def notification(self, method: str, params: dict[str, Any]) -> None:
        if method in TURN_BOUNDARIES:
            # A thread that was too young to resume has a rollout once a turn
            # has run in it, so this is where a refusal is worth retrying.
            self.resume_again()
        if not self._subscribed:
            # The thread may have just become resumable; take the subscription
            # before handling anything, so the backfill and the live stream
            # line up.
            await self.try_resume()
        if method == "turn/started":
            await self._turn_started(params)
            return
        if method == "turn/completed":
            await self._turn_completed(params)
            return
        if method in {"item/started", "item/completed"}:
            completed = method == "item/completed"
            item = params.get("item")
            if isinstance(item, dict):
                stamp = params.get("completedAtMs" if completed else "startedAtMs")
                await self._publish_item(item, completed, stamp)
            return
        if method == "thread/settings/updated":
            await self._settings_updated(params)
            return
        if method == "thread/status/changed":
            await self._status_changed(params)
            return
        for emit in self._translator.notification(method, params):
            await self._apply(emit)

    async def _turn_started(self, params: dict[str, Any]) -> None:
        turn = params.get("turn") or {}
        turn_id = str(turn.get("id") or "")
        if not turn_id or self._turn_id == turn_id:
            return
        self._translator.turn_id = turn_id
        self._turn_id = turn_id
        self._turn_started_at = now_ms()
        self._turn_done.clear()
        await self.channel.begin_turn("terminal" if not self._echoes else "remote")

    async def _turn_completed(self, params: dict[str, Any]) -> None:
        completion: dict[str, Any] = {}
        for emit in self._translator.notification("turn/completed", params):
            if emit.kind == "turn_completed":
                completion = dict(emit.fields)
                continue
            await self._apply(emit)
        duration = int(completion.get("duration_ms") or 0) or max(
            0, now_ms() - self._turn_started_at
        )
        reported = str(completion.get("stop_reason") or "completed")
        stop_reason = "interrupted" if self._interrupting else reported
        self._interrupting = False
        await self._publish_unread(stop_reason)
        self._turn_id = None
        self._local_turn = False
        self._turn_epoch += 1
        self._turn_done.set()
        self._last_output_flush.clear()
        usage = dict(completion.get("usage") or self._translator.usage)
        await self.channel.end_turn(stop_reason, duration, usage or None)
        if self._on_turn_end is not None:
            await self._on_turn_end()
        await self._control_changed()

    async def _settings_updated(self, params: dict[str, Any]) -> None:
        settings = params.get("threadSettings")
        if not isinstance(settings, dict):
            return
        model = settings.get("model")
        effort = settings.get("effort")
        policy = settings.get("approvalPolicy")
        self._model = model if isinstance(model, str) and model else self._model
        self._effort = effort if isinstance(effort, str) and effort else self._effort
        if isinstance(policy, str) and policy:
            self._permission_mode = policy
        if "serviceTier" in settings:
            self._speed = tier_id(settings.get("serviceTier"))
        await self.publish_settings()

    async def _status_changed(self, params: dict[str, Any]) -> None:
        """Catch a turn that was already running when we resumed the thread."""
        status = params.get("status")
        active = isinstance(status, dict) and status.get("type") == "active"
        if active and self._turn_id is None:
            self._turn_id = f"unknown:{uuid.uuid4()}"
            self._local_turn = False
            self._turn_started_at = now_ms()
            self._turn_done.clear()
            await self.channel.begin_turn("terminal")
        elif not active and self._turn_id is not None and self._turn_id.startswith("unknown:"):
            await self._turn_completed({"turn": {"status": "completed"}})

    async def _publish_item(
        self, raw: dict[str, Any], completed: bool, timestamp: int | None
    ) -> None:
        item = normalise(raw)
        item_id = str(item.get("id") or "")
        if item_type(item) == "userMessage" and not await self._accept_user_message(item):
            return
        for emit in self._translator.item(raw, completed, timestamp):
            await self._apply(emit)
        if completed and item_id:
            self._last_item_id = item_id

    async def _accept_user_message(self, item: dict[str, Any]) -> bool:
        """Decide whether a `userMessage` item is news, and who typed it.

        The daemon echoes the prompt that started a turn on both `item/started`
        and `item/completed`, so recognising it once is not enough: the item id
        of an echo we matched stays known, and every later sighting of it — the
        second event, a backfill, a reconnect — is dropped. What is left after
        that is somebody else's message, and a `clientId` that is not one the
        daemon has stamped on an echo of ours names the terminal typing it,
        which is the only signal the daemon offers that a TUI is there.
        """
        item_id = str(item.get("id") or "")
        if self._echoes.recognised(item_id):
            return False
        client_id = str(item.get("clientId") or "")
        claimed = self._echoes.claim(item_id, text_of(item.get("content")), client_id)
        if claimed is not None:
            # A steered message is published here rather than where it was sent,
            # so it lands after the output Codex produced before reading it.
            await self._publish_steer(claimed)
            return False
        if client_id and not self._echoes.owns(client_id):
            changed = not self._terminal_holds
            self.claim_terminal()
            if changed:
                await self._control_changed()
        return True

    async def _publish_steer(self, echo: Echo) -> None:
        """Publish the `user_message` a steer has been holding back."""
        if echo.block_id is None:
            return
        fields: dict[str, Any] = {
            "block_id": echo.block_id,
            "text": echo.text,
            "source": "remote",
        }
        if self.channel.session.control == "shared":
            fields["delivery"] = "delivered"
        await self.channel.emit("user_message", **fields)

    async def _publish_unread(self, stop_reason: str) -> None:
        """Show the steered messages this turn ended without ever reading.

        Amendment A14 puts a steered bubble where the agent took the message,
        which is nowhere at all when the turn stops first, so the turn's end is
        the last place it can be shown — and an interrupted turn says so.
        """
        for echo in self._echoes.unclaimed():
            await self._publish_steer(echo)
            if stop_reason == "interrupted":
                await self.channel.notice(
                    "warn", "the agent was stopped before it read your message"
                )

    async def _apply(self, emit: Any) -> None:
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
            return
        if emit.kind == "tool_call" and emit.fields.get("status") == "running":
            block_id = str(emit.fields.get("block_id") or "")
            now = time.monotonic()
            last = self._last_output_flush.get(block_id, 0.0)
            if emit.fields.get("output") and now - last < OUTPUT_THROTTLE:
                return
            self._last_output_flush[block_id] = now
        await self.channel.emit(emit.kind, **emit.fields)

    # --------------------------------------------------------------- driving

    def _inputs(self, text: str, written: list[Attachment]) -> list[dict[str, Any]]:
        """Images travel as inputs; anything else is named in the prompt."""
        images = [item for item in written if item.mime.startswith("image/")]
        others = [item for item in written if not item.mime.startswith("image/")]
        prompt = describe(text, others)
        inputs: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
        inputs.extend({"type": "localImage", "path": item.path} for item in images)
        return inputs

    async def send(
        self,
        text: str,
        attachments: list[dict[str, Any]] | None = None,
        source: str = "remote",
        block_id: str | None = None,
    ) -> None:
        """Publish the message, then start the turn that answers it."""
        thread_id = self._thread_id
        if thread_id is None:
            raise RcError("agent_unavailable", "the codex thread is not connected")
        written = materialise(self.channel.session.session_id, attachments) if attachments else []
        inputs = self._inputs(text, written)
        fields: dict[str, Any] = {
            "block_id": block_id or f"user:{uuid.uuid4()}",
            "text": text,
            "source": source,
        }
        if written:
            fields["attachments"] = wire_attachments(written)
        if self.channel.session.control == "shared":
            fields["delivery"] = "delivered"
        # The message goes out before any request does: everything below is a
        # round trip to the daemon, and the apps have drawn this bubble already.
        await self.channel.emit("user_message", **fields)
        subscribed = await self.try_resume()
        evicted = self._echoes.remember(str(inputs[0]["text"]))
        if evicted is not None:
            await self._publish_steer(evicted)
        self._local_turn = True
        self._turn_started_at = now_ms()
        params: dict[str, Any] = {
            "threadId": thread_id,
            "input": inputs,
            "approvalPolicy": self._permission_mode,
        }
        if self._model:
            params["model"] = self._model
        effort = self._catalog.clamp_effort(self._model, self._effort)
        if effort:
            params["effort"] = effort
        epoch = self._turn_epoch
        self._turn_done.clear()
        try:
            result = await self._client.request("turn/start", params)
        except RcError:
            self._local_turn = False
            raise
        if not subscribed:
            # The rollout exists once a turn has started, so this is the first
            # moment a thread created in the terminal can be subscribed to.
            # When the resume above worked there is nothing left to do here.
            self.resume_again()
            await self.try_resume()
        turn = result.get("turn") or {}
        if epoch == self._turn_epoch:
            turn_id = str(turn.get("id") or "")
            if not turn_id or self._turn_id != turn_id:
                self._turn_id = turn_id or str(uuid.uuid4())
                await self.channel.begin_turn(source)
        # A thread with no terminal is `remote` while this turn runs.
        await self._control_changed()

    async def steer(self, text: str, block_id: str | None = None) -> bool:
        thread_id = self._thread_id
        turn_id = self._turn_id
        if thread_id is None or turn_id is None or turn_id.startswith("unknown:"):
            return False
        try:
            await self._client.request(
                "turn/steer",
                {
                    "threadId": thread_id,
                    "expectedTurnId": turn_id,
                    "input": [{"type": "text", "text": text}],
                },
            )
        except RcError:
            return False
        # Amendment A14: the bubble waits for Codex's echo of this prompt, which
        # arrives at the step that reads it. The apps hold their optimistic row
        # under the same block id until then.
        evicted = self._echoes.remember(text, block_id or f"user:{uuid.uuid4()}")
        if evicted is not None:
            await self._publish_steer(evicted)
        return True

    async def interrupt(self) -> bool:
        thread_id = self._thread_id
        turn_id = self._turn_id
        if thread_id is None or turn_id is None:
            return False
        self._interrupting = True
        await self.channel.set_state("running", "interrupting")
        self._dialogs.deny_all()
        if not turn_id.startswith("unknown:"):
            try:
                await self._client.request(
                    "turn/interrupt", {"threadId": thread_id, "turnId": turn_id}
                )
            except RcError:
                log.warning("codex interrupt was rejected; waiting for the turn to settle")
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(self._turn_done.wait(), timeout=DRAIN_TIMEOUT)
        return True

    async def apply_settings(
        self,
        model: str | None,
        permission_mode: str | None,
        effort: str | None,
        speed: SpeedSetting = UNSET,
    ) -> None:
        thread_id = self._thread_id
        params: dict[str, Any] = {"threadId": thread_id} if thread_id else {}
        if model is not None:
            self._model = model
            params["model"] = model
        if permission_mode is not None:
            self._permission_mode = permission_mode
            params["approvalPolicy"] = permission_mode
        if effort is not None:
            self._effort = effort
            clamped = self._catalog.clamp_effort(self._model, effort)
            if clamped:
                params["effort"] = clamped
        if speed is not UNSET:
            # Null is what takes the thread back to the standard tier; the
            # daemon then reports the tier as `default`, which reads as null.
            self._speed = speed
            params["serviceTier"] = speed
        if thread_id is None or len(params) < 2:
            return
        await self._client.request("thread/settings/update", params)

    # ------------------------------------------------------- prompts to user

    async def server_request(self, request_id: Any, method: str, params: dict[str, Any]) -> Any:
        return await self._dialogs.request(request_id, method, params)

    async def resolved_elsewhere(self, request_id: Any) -> None:
        await self._dialogs.resolved_elsewhere(request_id)

    async def approve(self, request_id: str, option_id: str, message: str | None) -> bool:
        return await self._dialogs.approve(request_id, option_id, message)

    async def answer(self, request_id: str, answers: dict[str, Any]) -> bool:
        return await self._dialogs.answer(request_id, answers)
