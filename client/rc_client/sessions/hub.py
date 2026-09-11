"""The session hub: create, drive, queue, mirror and tear down sessions."""

from __future__ import annotations

import asyncio
import contextlib
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .. import attachments
from ..agents.base import SessionRunner
from ..agents.claude.adapter import ClaudeRunner
from ..agents.codex.adapter import CodexRunner
from ..agents.codex.daemon.service import CodexDaemonService
from ..agents.codex.models import ModelCatalog, catalog_cache
from ..errors import RcError
from ..git import create_worktree, session_git, slugify
from ..logging_setup import logger
from ..models import AgentInfo, Session, now_ms, title_from_text
from ..registry import Registry
from . import titles
from .attach import Attachment
from .channel import SessionChannel
from .shared import EXIT_SETTLE, SharedControl, SharedState

log = logger("rc_client.hub")

Publisher = Callable[[dict[str, Any]], Awaitable[None]]
MAX_TEXT_BYTES = 64 * 1024
MAX_ATTACHMENTS = 8


def _as_int(value: Any, field: str) -> int | None:
    """Coerce an app-supplied number, reporting a protocol error when it is not one."""
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError) as exc:
        raise RcError("bad_request", f"{field} must be an integer") from exc


@dataclass(slots=True)
class SessionEntry:
    session: Session
    channel: SessionChannel
    runner: SessionRunner | None = None
    queue: list[dict[str, Any]] = field(default_factory=list)
    holder_pid: int | None = None
    holder_identity: tuple[int, str] | None = None
    transcript: str | None = None
    shared: SharedState | None = None
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)


class SessionHub:
    def __init__(
        self,
        registry: Registry,
        publish: Publisher,
        device_id: str,
        agents: Callable[[], list[AgentInfo]],
    ) -> None:
        self.registry = registry
        self.publish = publish
        self.device_id = device_id
        self._agents = agents
        self.entries: dict[str, SessionEntry] = {}
        self.shared = SharedControl(self)
        # Set by the daemon once the shared Codex app-server answers a handshake
        # (amendment A11); absent means the per-session spawn path.
        self.codex_daemon: CodexDaemonService | None = None

    # ------------------------------------------------------------- accessors

    def agent_info(self, agent: str) -> AgentInfo:
        for info in self._agents():
            if info.agent == agent:
                return info
        raise RcError("agent_unavailable", f"unknown agent {agent}")

    def snapshot(self) -> list[dict[str, Any]]:
        return [entry.session.to_dict() for entry in self.entries.values()]

    def entry(self, session_id: str) -> SessionEntry:
        entry = self.entries.get(session_id)
        if entry is None:
            raise RcError("not_found", f"unknown session {session_id}")
        return entry

    def load(self) -> None:
        """Restore persisted sessions as resumable, terminal-free records."""
        for session in self.registry.load_sessions():
            if not session.session_id or not session.agent:
                continue
            session.device_id = self.device_id
            session.control = "none"
            session.state = "stopped" if session.archived else "idle"
            session.turn = None
            session.queued = 0
            self.entries[session.session_id] = SessionEntry(
                session=session,
                channel=SessionChannel(self.registry, session, self.publish),
            )

    def register_mirrored(self, session: Session, transcript: str | None = None) -> SessionEntry:
        """Adopt a session discovered from an agent transcript on disk."""
        entry = self.entries.get(session.session_id)
        if entry is not None:
            entry.transcript = transcript or entry.transcript
            return entry
        session.device_id = self.device_id
        entry = SessionEntry(
            session=session,
            channel=SessionChannel(self.registry, session, self.publish),
            transcript=transcript,
        )
        self.entries[session.session_id] = entry
        self.registry.upsert_session(session)
        return entry

    async def close(self) -> None:
        for entry in list(self.entries.values()):
            if entry.shared is not None:
                entry.shared.attachment.detach()
                entry.shared = None
            if entry.runner is not None:
                with contextlib.suppress(Exception):
                    await entry.runner.close()
            await entry.channel.close()

    # ---------------------------------------------------------------- create

    def _check_choices(self, info: AgentInfo, params: dict[str, Any]) -> None:
        """Reject settings outside what this device advertises in `AgentInfo`.

        `permission_mode` and `effort` are closed enumerations (PROTOCOL.md section 8), and an
        unknown value would only surface later as a CLI that refuses to start. `model` stays open:
        model ids are the agents' own and the detected catalogue can lag a new release.
        """
        for field_name, allowed in (
            ("permission_mode", info.permission_modes),
            ("effort", info.efforts),
        ):
            value = params.get(field_name)
            if value is None or not allowed:
                continue
            if value not in {choice.id for choice in allowed}:
                raise RcError("bad_request", f"unknown {field_name} for {info.agent}: {value}")

    async def create(self, params: dict[str, Any]) -> dict[str, Any]:
        agent = str(params.get("agent") or "")
        info = self.agent_info(agent)
        if not info.available:
            raise RcError("agent_unavailable", f"{agent} is not installed on this device")
        self._check_choices(info, params)
        cwd = str(params.get("cwd") or "")
        if not cwd or not Path(cwd).expanduser().is_dir():
            raise RcError("bad_request", f"working directory does not exist: {cwd}")
        cwd = str(Path(cwd).expanduser().resolve())

        first_message = str(params.get("first_message") or "")
        chosen = title_from_text(str(params.get("title") or ""))
        title = chosen or title_from_text(first_message)
        worktree = bool(params.get("worktree"))
        if worktree:
            cwd = await create_worktree(cwd, slugify(title or agent))

        session = Session(
            session_id=str(uuid.uuid4()),
            device_id=self.device_id,
            agent=agent,
            cwd=cwd,
            title=title,
            state="starting",
            origin="remote",
            control="remote",
            model=params.get("model") or info.default_model,
            permission_mode=params.get("permission_mode") or info.default_permission_mode,
            effort=params.get("effort") or info.default_effort,
        )
        session.git = await session_git(cwd, worktree=worktree)
        entry = SessionEntry(
            session=session, channel=SessionChannel(self.registry, session, self.publish)
        )
        self.entries[session.session_id] = entry
        entry.channel.start()
        self.registry.upsert_session(session)
        if chosen:
            # A title the user typed outranks anything the agent later produces.
            titles.pin(self.registry, session.session_id)

        try:
            entry.runner = await self._build_runner(entry, info, resume=None)
            await entry.runner.start()
        except RcError:
            self.entries.pop(session.session_id, None)
            raise
        except Exception as exc:
            self.entries.pop(session.session_id, None)
            raise RcError("agent_unavailable", f"could not start {agent}: {exc}") from exc

        await entry.channel.set_state("idle")
        if first_message:
            await self._start_turn(entry, first_message, None)
        return {"session": entry.session.to_dict()}

    async def _build_runner(
        self, entry: SessionEntry, info: AgentInfo, resume: str | None
    ) -> SessionRunner:
        session = entry.session

        async def on_turn_end() -> None:
            await self.drain_queue(entry)

        async def on_session_id(real_id: str) -> None:
            await self.rekey(entry, real_id)

        if info.agent == "claude":
            return ClaudeRunner(
                entry.channel,
                binary=info.path,
                cwd=session.cwd,
                model=session.model,
                permission_mode=session.permission_mode,
                effort=session.effort,
                resume=resume,
                session_id=session.session_id,
                on_turn_end=on_turn_end,
                on_session_id=on_session_id,
            )
        if info.agent == "codex":
            if self.codex_daemon is not None and self.codex_daemon.ready:
                return self.codex_daemon.session_for(entry, resume)
            if not info.path:
                raise RcError("agent_unavailable", "codex is not installed on this device")
            catalog: ModelCatalog = await catalog_cache.get(info.path)
            return CodexRunner(
                entry.channel,
                binary=info.path,
                cwd=session.cwd,
                catalog=catalog,
                model=session.model,
                permission_mode=session.permission_mode,
                effort=session.effort,
                thread_id=resume,
                on_turn_end=on_turn_end,
                on_session_id=on_session_id,
            )
        raise RcError("unsupported", f"no adapter for agent {info.agent}")

    async def rekey(self, entry: SessionEntry, real_id: str) -> None:
        old_id = entry.session.session_id
        if old_id == real_id:
            return
        self.registry.rekey_session(old_id, real_id)
        titles.rekey(self.registry, old_id, real_id)
        attachments.rekey(old_id, real_id)
        self.entries.pop(old_id, None)
        entry.session.session_id = real_id
        self.entries[real_id] = entry
        await self.publish({"type": "session.removed", "session_id": old_id})
        await entry.channel.publish_summary()

    # ------------------------------------------------------------------ send

    async def send(self, params: dict[str, Any]) -> dict[str, Any]:
        session_id = str(params.get("session_id") or "")
        request_id = str(params.get("id") or "")
        entry = self.entry(session_id)
        cached = self.registry.recall_request(session_id, request_id) if request_id else None
        if cached is not None:
            return cached

        text = str(params.get("text") or "")
        if len(text.encode("utf-8")) > MAX_TEXT_BYTES:
            raise RcError("too_large", "message text exceeds 64 KiB")
        attachments = params.get("attachments") or []
        if len(attachments) > MAX_ATTACHMENTS:
            raise RcError("too_large", "at most 8 attachments per message")
        mode = str(params.get("mode") or "auto")

        async with entry.lock:
            if entry.shared is not None:
                result = await self.shared.send(entry, request_id, text, attachments)
                if request_id:
                    self.registry.remember_request(session_id, request_id, result)
                return result
            if entry.session.control == "terminal":
                raise RcError("conflict", self._terminal_conflict_message(entry))
            if entry.runner is None:
                await self._resume(entry)
            runner = entry.runner
            assert runner is not None
            # Amendment A15: the send is going through, so the session is not
            # archived any more even when the message only joins the queue.
            await entry.channel.revive()

            if mode == "queue":
                # An explicit queue request always queues, even when idle: the
                # app told the user their message would wait its turn.
                result = await self._enqueue(entry, request_id, text, attachments)
            elif not runner.busy:
                await self._start_turn(entry, text, attachments, request_id)
                result = {"accepted": "sent"}
            elif mode == "interrupt":
                await runner.interrupt()
                await self._start_turn(entry, text, attachments, request_id)
                result = {"accepted": "sent"}
            elif (
                mode == "auto"
                and runner.supports_steer
                and await runner.steer(text, request_id or None)
            ):
                result = {"accepted": "steered"}
            else:
                result = await self._enqueue(entry, request_id, text, attachments)
            if mode == "queue" and not runner.busy:
                await self._drain_queue_locked(entry)

        if request_id:
            self.registry.remember_request(session_id, request_id, result)
        return result

    async def _start_turn(
        self,
        entry: SessionEntry,
        text: str,
        attachments: list[dict[str, Any]] | None,
        request_id: str = "",
    ) -> None:
        """Start a turn for a message an app sent, under that request's own id.

        Amendment A12: the app has already drawn the bubble under the id it
        chose, so the device's `user_message` has to arrive under the same one
        or the message appears twice.
        """
        runner = entry.runner
        if runner is None:
            raise RcError("agent_unavailable", "the session is not running")
        await titles.from_prompt(entry.channel, text)
        await runner.send(text, attachments, block_id=request_id or None)

    def _terminal_conflict_message(self, entry: SessionEntry) -> str:
        """Only offer "take over" when this agent can actually be taken over."""
        try:
            info = self.agent_info(entry.session.agent)
        except RcError:
            return "controlled by the terminal on this device"
        if "takeover" in info.capabilities:
            return "controlled by terminal; take over first"
        return "controlled by the terminal on this device; stop it there to continue here"

    async def _enqueue(
        self,
        entry: SessionEntry,
        request_id: str,
        text: str,
        attachments: list[dict[str, Any]],
    ) -> dict[str, Any]:
        """Hold a message until the turn boundary, keeping its attachments.

        The item's id is the app's request id where there is one, and it is
        also the id its `user_message` carries when the queue drains, so the
        bubble the app drew on sending is the one that is filled in later.
        """
        queued_id = request_id or str(uuid.uuid4())
        entry.queue.append(
            {"id": queued_id, "text": text, "ts": now_ms(), "attachments": attachments}
        )
        await entry.channel.publish_queue(self.queue_snapshot(entry))
        return {"accepted": "queued", "queued_id": queued_id}

    def queue_snapshot(self, entry: SessionEntry) -> list[dict[str, Any]]:
        """The wire form of the queue: attachments stay on the device."""
        return [{"id": item["id"], "text": item["text"], "ts": item["ts"]} for item in entry.queue]

    async def drain_queue(self, entry: SessionEntry) -> None:
        """Launch the oldest queued message once the agent goes idle."""
        async with entry.lock:
            item = self._take_queued(entry)
        if item is None:
            return
        await self._send_queued(entry, item)

    async def _drain_queue_locked(self, entry: SessionEntry) -> None:
        """Same as `drain_queue`, for callers that already hold the lock."""
        item = self._take_queued(entry)
        if item is not None:
            await self._send_queued(entry, item)

    def _take_queued(self, entry: SessionEntry) -> dict[str, Any] | None:
        if not entry.queue or entry.runner is None or entry.runner.busy:
            return None
        return entry.queue.pop(0)

    async def _send_queued(self, entry: SessionEntry, item: dict[str, Any]) -> None:
        await entry.channel.publish_queue(self.queue_snapshot(entry))
        runner = entry.runner
        if runner is None:
            return
        try:
            await runner.send(
                str(item["text"]),
                item.get("attachments") or None,
                source="queue",
                block_id=str(item["id"]),
            )
        except RcError as exc:
            await entry.channel.error(exc.message, code=exc.code)

    async def _resume(self, entry: SessionEntry) -> None:
        """Reconnect a `control: "none"` session before sending to it."""
        info = self.agent_info(entry.session.agent)
        if not info.available:
            raise RcError("agent_unavailable", f"{entry.session.agent} is not installed")
        entry.channel.start()
        runner = await self._build_runner(entry, info, resume=entry.session.session_id)
        try:
            await runner.start()
        except Exception as exc:
            raise RcError("agent_unavailable", f"could not resume the session: {exc}") from exc
        entry.runner = runner
        entry.session.control = "remote"
        await entry.channel.set_state("idle")
        if self.codex_daemon is not None and entry.session.agent == "codex":
            await self.codex_daemon.publish_control(entry)
        await entry.channel.notice("info", "resumed this session from its transcript")

    # -------------------------------------------------------------- controls

    async def stop(self, params: dict[str, Any]) -> dict[str, Any]:
        entry = self.entry(str(params.get("session_id") or ""))
        if self._is_shared(entry) and not self._agent_flag(entry, "shared_interrupt"):
            raise RcError("unsupported", "stop it in the terminal")
        if entry.runner is not None:
            await entry.runner.interrupt()
        return {}

    @staticmethod
    def _is_shared(entry: SessionEntry) -> bool:
        """A session a live CLI owns and the device is attached to (A10 4.4)."""
        return entry.shared is not None or entry.session.control == "shared"

    def _agent_flag(self, entry: SessionEntry, name: str) -> bool:
        try:
            return bool(getattr(self.agent_info(entry.session.agent), name))
        except RcError:
            return False

    async def approve(self, params: dict[str, Any]) -> dict[str, Any]:
        entry = self.entry(str(params.get("session_id") or ""))
        if entry.shared is not None:
            return await self.shared.approve(
                entry, str(params.get("request_id") or ""), str(params.get("option_id") or "")
            )
        if entry.runner is None:
            raise RcError("conflict", "the session is not running")
        ok = await entry.runner.approve(
            str(params.get("request_id") or ""),
            str(params.get("option_id") or ""),
            params.get("message"),
        )
        if not ok:
            raise RcError("not_found", "that approval is no longer pending")
        return {}

    async def answer(self, params: dict[str, Any]) -> dict[str, Any]:
        entry = self.entry(str(params.get("session_id") or ""))
        if entry.shared is not None:
            raise RcError("unsupported", "answer the question in the terminal")

        if entry.runner is None:
            raise RcError("conflict", "the session is not running")
        answers = params.get("answers")
        if not isinstance(answers, dict):
            raise RcError("bad_request", "answers must be an object")
        ok = await entry.runner.answer(str(params.get("request_id") or ""), answers)
        if not ok:
            raise RcError("not_found", "that question is no longer pending")
        return {}

    async def set_options(self, params: dict[str, Any]) -> dict[str, Any]:
        entry = self.entry(str(params.get("session_id") or ""))
        self._check_choices(self.agent_info(entry.session.agent), params)
        model = params.get("model")
        permission_mode = params.get("permission_mode")
        effort = params.get("effort")
        title = params.get("title")
        if (
            self._is_shared(entry)
            and not self._agent_flag(entry, "shared_settings")
            and any(value is not None for value in (model, permission_mode, effort))
        ):
            raise RcError("unsupported", "change it in the terminal")
        if entry.runner is not None:
            await entry.runner.apply_settings(model, permission_mode, effort)
        if title is not None:
            title = title_from_text(str(title))
            if title:
                # A title the user typed outranks anything the agent produces.
                titles.pin(self.registry, entry.session.session_id)
        await entry.channel.set_meta(
            model=model, permission_mode=permission_mode, effort=effort, title=title
        )
        return {"session": entry.session.to_dict()}

    async def history(self, params: dict[str, Any]) -> dict[str, Any]:
        session_id = str(params.get("session_id") or "")
        self.entry(session_id)
        before_seq = _as_int(params.get("before_seq"), "before_seq")
        after_seq = _as_int(params.get("after_seq"), "after_seq")
        if before_seq is not None and after_seq is not None:
            raise RcError("bad_request", "before_seq and after_seq are mutually exclusive")
        limit = _as_int(params.get("limit"), "limit") or 200
        events, has_more = self.registry.history(session_id, before_seq, limit, after_seq)
        return {"events": events, "has_more": has_more}

    async def block(self, params: dict[str, Any]) -> dict[str, Any]:
        session_id = str(params.get("session_id") or "")
        self.entry(session_id)
        event = self.registry.block(session_id, str(params.get("block_id") or ""))
        if event is None:
            raise RcError("not_found", "unknown block")
        return {"event": event}

    async def queue_remove(self, params: dict[str, Any]) -> dict[str, Any]:
        entry = self.entry(str(params.get("session_id") or ""))
        queued_id = str(params.get("queued_id") or "")
        before = len(entry.queue)
        entry.queue = [item for item in entry.queue if item["id"] != queued_id]
        if len(entry.queue) == before:
            raise RcError("not_found", "that message is not queued")
        await entry.channel.publish_queue(self.queue_snapshot(entry))
        return {}

    async def archive(self, params: dict[str, Any]) -> dict[str, Any]:
        entry = self.entry(str(params.get("session_id") or ""))
        entry.session.archived = bool(params.get("archived"))
        if entry.session.archived and entry.runner is not None:
            await entry.runner.close()
            entry.runner = None
            entry.session.control = "none"
        await entry.channel.publish_summary()
        return {"session": entry.session.to_dict()}

    async def delete(self, params: dict[str, Any]) -> dict[str, Any]:
        session_id = str(params.get("session_id") or "")
        entry = self.entry(session_id)
        if entry.shared is not None:
            entry.shared.attachment.detach()
            entry.shared = None
        if entry.runner is not None:
            await entry.runner.close()
        await entry.channel.close()
        self.entries.pop(session_id, None)
        self.registry.delete_session(session_id)
        attachments.cleanup(session_id)
        await self.publish({"type": "session.removed", "session_id": session_id})
        return {}

    async def takeover(self, params: dict[str, Any]) -> dict[str, Any]:
        from ..agents.claude.holders import release_holder

        entry = self.entry(str(params.get("session_id") or ""))
        if self._is_shared(entry):
            raise RcError("conflict", "already attached")
        info = self.agent_info(entry.session.agent)
        if "takeover" not in info.capabilities:
            raise RcError("unsupported", f"{entry.session.agent} sessions cannot be taken over")
        if entry.session.control != "terminal":
            raise RcError("conflict", "this session is not controlled by a terminal")
        if entry.session.state == "running":
            raise RcError("conflict", "the terminal session is busy; try again when it is idle")
        if entry.holder_pid is None or entry.holder_identity is None:
            raise RcError("conflict", "cannot identify the terminal process holding this session")
        released = await release_holder(entry.holder_pid, entry.holder_identity)
        if not released:
            raise RcError("conflict", "the terminal process did not release the session")
        entry.holder_pid = None
        entry.holder_identity = None
        entry.session.control = "none"
        await entry.channel.notice("info", "took over from the terminal")
        await self._resume(entry)
        return {"session": entry.session.to_dict()}

    # ------------------------------------------------------------ attachment

    async def attach_registered(self, attachment: Attachment) -> None:
        """A channel bridge claimed a terminal session (A10: `terminal → shared`)."""
        entry = self._attach_entry(attachment)
        if entry is None:
            attachment.detach()
            return
        async with entry.lock:
            await self.shared.registered(entry, attachment)

    def _attach_entry(self, attachment: Attachment) -> SessionEntry | None:
        entry = self.entries.get(attachment.session_id)
        if entry is not None:
            if entry.runner is not None:
                # This device already drives the session; a second writer would
                # interleave two conversations in one transcript.
                log.warning("refusing a channel attachment for a session we drive")
                return None
            return entry
        session = Session(
            session_id=attachment.session_id,
            device_id=self.device_id,
            agent="claude",
            cwd=attachment.cwd,
            title="",
            state="idle",
            origin="terminal",
            control="shared",
        )
        entry = self.register_mirrored(session)
        entry.channel.start()
        return entry

    async def attach_permission_request(
        self, attachment: Attachment, payload: dict[str, Any]
    ) -> None:
        entry = self.entries.get(attachment.session_id)
        if entry is None:
            return
        async with entry.lock:
            await self.shared.permission_request(entry, payload)

    async def attach_closed(self, attachment: Attachment) -> None:
        """The bridge went away: back to the terminal if the CLI is still alive."""
        entry = self.entries.get(attachment.session_id)
        if entry is None or entry.shared is None:
            return
        await asyncio.sleep(EXIT_SETTLE)
        control = await self._holder_control(entry)
        async with entry.lock:
            await self.shared.closed(entry, attachment, control)

    async def _holder_control(self, entry: SessionEntry) -> str:
        """Whether the CLI whose bridge just closed is still sitting in the terminal.

        The bridge registered the CLI's own pid, so this asks after that exact
        process. The working directory is deliberately not offered: it cannot
        tell this session's CLI from another one started beside it.
        """
        from ..agents.claude.holders import SessionRef, scan_holders

        scan = await scan_holders()
        if not scan.complete:
            return "terminal"
        session_id = entry.session.session_id
        ref = SessionRef(session_id, pid=entry.holder_pid, identity=entry.holder_identity)
        holder = scan.assign([ref]).get(session_id)
        entry.holder_pid = holder.pid if holder else None
        entry.holder_identity = holder.identity if holder else None
        return "terminal" if holder is not None else "none"
