"""The device's view of the shared Codex daemon: one connection, many sessions.

The daemon is the source of truth for every Codex thread on this machine, so
when it answers a handshake this replaces rollout mirroring outright: history
comes from `thread/list`, live threads from `thread/loaded/list`, and the block
timeline from the notification stream.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import os
from collections.abc import Awaitable, Callable
from typing import TYPE_CHECKING, Any

from ....errors import RcError
from ....logging_setup import logger
from ....models import Session
from ..models import ModelCatalog, catalog_cache
from . import approvals, threads
from .rpc import DaemonClient
from .session import CodexDaemonSession
from .transport import socket_exists

if TYPE_CHECKING:  # pragma: no cover - imported for types only
    from ....sessions.hub import SessionEntry, SessionHub

log = logger("rc_client.codex.daemon.service")

ModeCallback = Callable[[], Awaitable[None]]

HISTORY_LIMIT = 100
THREAD_CONFIG_ENV = "RC_CODEX_THREAD_CONFIG"


def thread_config() -> dict[str, Any] | None:
    """A per-thread `config` override for threads this device starts.

    Codex applies it to the thread wherever it is later resumed, including in a
    terminal, which is the only way to change a thread's configuration without
    editing the user's own files. Empty unless the device is told otherwise.
    """
    raw = os.environ.get(THREAD_CONFIG_ENV, "").strip()
    if not raw:
        return None
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        log.warning("ignoring an unreadable thread config override", env=THREAD_CONFIG_ENV)
        return None
    return parsed if isinstance(parsed, dict) else None


class CodexDaemonService:
    """Owns the daemon connection and maps its threads onto hub sessions."""

    def __init__(
        self, hub: SessionHub, version: str, on_mode_change: ModeCallback | None = None
    ) -> None:
        self.hub = hub
        self._version = version
        self._on_mode_change = on_mode_change
        self._client: DaemonClient | None = None
        self._known: set[str] = set()
        # Threads the daemon says are loaded. A brand-new one is loaded before
        # its first turn is persisted, and `thread/resume` fails until then, so
        # "loaded" and "we have a runner" are two different facts.
        self._loaded: set[str] = set()
        self._attaching: set[str] = set()
        self._catalog = ModelCatalog()
        self._config = thread_config()
        self._lock = asyncio.Lock()

    # ------------------------------------------------------------- lifecycle

    @property
    def ready(self) -> bool:
        return self._client is not None and self._client.connected

    @property
    def client(self) -> DaemonClient:
        client = self._client
        if client is None:
            raise RcError("agent_unavailable", "the codex daemon is not connected")
        return client

    def knows(self, thread_id: str) -> bool:
        """Whether this thread is the daemon's, so the rollout mirror leaves it alone."""
        return thread_id in self._known

    async def start(self, binary: str | None) -> bool:
        """Try the daemon once. False means the device stays on the fallback path."""
        if not socket_exists():
            return False
        if binary:
            self._catalog = await catalog_cache.get(binary)
        client = DaemonClient(
            self._version,
            on_notification=self._notification,
            on_request=self._request,
            on_connected=self._reconnected,
        )
        try:
            await client.start()
        except Exception as exc:
            log.info("the codex daemon socket is present but did not answer", error=str(exc)[:200])
            with contextlib.suppress(Exception):
                await client.close()
            return False
        self._client = client
        await self.refresh()
        return True

    async def stop(self) -> None:
        client, self._client = self._client, None
        if client is not None:
            await client.close()

    async def tick(self, binary: str | None) -> None:
        """Keep the mode and the thread index current, on the mirror's scan interval.

        A daemon bootstrapped after the device started switches the mode without
        a restart; sessions already discovered on the fallback path keep the
        runner they have until they are next resumed.
        """
        if self.ready:
            await self.refresh()
            return
        if self._client is not None:
            # The client is reconnecting on its own backoff.
            return
        if await self.start(binary) and self._on_mode_change is not None:
            await self._on_mode_change()

    # -------------------------------------------------------------- indexing

    async def refresh(self) -> None:
        """Reconcile the session list with the daemon's history and live threads."""
        if not self.ready:
            return
        async with self._lock:
            history = await self._safe_request(
                "thread/list", {"limit": HISTORY_LIMIT, "sortDirection": "desc"}
            )
            loaded = await self._safe_request("thread/loaded/list", {})
            live = {
                str(item) for item in (loaded.get("data") or []) if isinstance(item, str) and item
            }
            self._loaded = live
            page = threads.summaries(history.get("data"))
            for summary in page:
                self._known.add(summary.thread_id)
                await self._adopt(summary, loaded=summary.thread_id in live)
            for thread_id in live - self._known:
                await self._adopt_by_id(thread_id)
            await self._forget_deleted(page, live)

    async def _forget_deleted(self, page: list[threads.ThreadSummary], live: set[str]) -> None:
        """Drop sessions for threads deleted in Codex, as the mirror does for transcripts.

        `thread/list` is one page, so a thread missing from it may only have
        fallen off the end. Only a thread newer than the oldest entry on the page
        would have been on it, which is what makes its absence proof of deletion.
        """
        if not page:
            return
        seen = {summary.thread_id for summary in page}
        cutoff = min(summary.updated_at for summary in page)
        for thread_id in sorted(self._known - seen - live):
            entry = self.hub.entries.get(thread_id)
            if entry is None:
                self._known.discard(thread_id)
                continue
            if entry.runner is not None or entry.session.updated_at < cutoff:
                continue
            self._known.discard(thread_id)
            self._loaded.discard(thread_id)
            log.info("forgetting a codex thread deleted in the agent")
            await self.hub.delete({"session_id": thread_id})

    async def _safe_request(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        try:
            return await self.client.request(method, params)
        except RcError as exc:
            log.warning("codex daemon request failed", method=method, error=exc.message[:200])
            return {}

    async def _adopt_by_id(self, thread_id: str) -> None:
        result = await self._safe_request("thread/read", {"threadId": thread_id})
        thread = result.get("thread") if isinstance(result.get("thread"), dict) else result
        if not isinstance(thread, dict) or threads.is_ephemeral(thread):
            return
        summary = threads.ThreadSummary.parse(thread)
        if summary is None:
            return
        self._known.add(summary.thread_id)
        await self._adopt(summary, loaded=True)

    async def _adopt(self, summary: threads.ThreadSummary, *, loaded: bool) -> None:
        entry = self.hub.entries.get(summary.thread_id)
        if entry is None:
            entry = self.hub.register_mirrored(
                Session(
                    session_id=summary.thread_id,
                    device_id=self.hub.device_id,
                    agent="codex",
                    cwd=summary.cwd,
                    title=summary.title,
                    state="idle",
                    origin="terminal",
                    control="none",
                    model=summary.model,
                    effort=summary.effort,
                    created_at=summary.created_at,
                    updated_at=summary.updated_at,
                )
            )
            entry.channel.start()
        if not entry.session.title and summary.title:
            entry.session.title = summary.title
        if loaded:
            self._loaded.add(summary.thread_id)
            await self._attach(entry)
        else:
            self._loaded.discard(summary.thread_id)
        await self.publish_control(entry)

    async def _attach(self, entry: SessionEntry) -> None:
        """Resume a loaded thread so its stream reaches the apps.

        A thread the terminal has just created is loaded but has no rollout
        until its first turn is persisted, and `thread/resume` refuses it until
        then; the session is already `shared` and the resume is retried when the
        next notification or scan says the thread is still there.
        """
        thread_id = entry.session.session_id
        if entry.runner is not None or entry.session.archived or thread_id in self._attaching:
            return
        self._attaching.add(thread_id)
        try:
            runner = self.session_for(entry, resume=thread_id)
            await runner.start()
        except RcError as exc:
            log.warning("could not attach to a codex thread", error=exc.message[:120])
            return
        finally:
            self._attaching.discard(thread_id)
        entry.runner = runner
        if runner.subscribed:
            await runner.publish_settings()

    def session_for(self, entry: SessionEntry, resume: str | None) -> CodexDaemonSession:
        """Build the runner for one session; the hub owns when it is started."""
        session = entry.session

        async def on_turn_end() -> None:
            await self.hub.drain_queue(entry)

        async def on_terminal_seen() -> None:
            await self.publish_control(entry)

        async def on_thread_id(thread_id: str) -> None:
            self._known.add(thread_id)
            self._loaded.add(thread_id)
            await self.hub.rekey(entry, thread_id)

        return CodexDaemonSession(
            entry.channel,
            self.client,
            cwd=session.cwd,
            catalog=self._catalog,
            model=session.model,
            permission_mode=session.permission_mode,
            effort=session.effort,
            thread_id=resume,
            thread_config=self._config,
            created_here=session.origin == "remote",
            on_turn_end=on_turn_end,
            on_terminal_seen=on_terminal_seen,
            on_thread_id=on_thread_id,
        )

    async def publish_control(self, entry: SessionEntry) -> None:
        """Apply the A11 table and announce a change the way every other one travels."""
        runner = entry.runner if isinstance(entry.runner, CodexDaemonSession) else None
        created_here = entry.session.origin == "remote" or (
            runner is not None and runner.created_here
        )
        origin, control = threads.resolve(
            created_here,
            loaded=entry.session.session_id in self._loaded,
            terminal_seen=runner is not None and runner.terminal_seen,
        )
        changed = entry.session.origin != origin or entry.session.control != control
        entry.session.origin = origin  # type: ignore[assignment]
        entry.session.control = control  # type: ignore[assignment]
        if changed:
            await entry.channel.emit("meta", control=control)
            if entry.session.state == "readonly":
                await entry.channel.set_state("idle")
            await entry.channel.publish_summary()

    # ---------------------------------------------------------- dispatching

    def _session(self, thread_id: str) -> CodexDaemonSession | None:
        entry = self.hub.entries.get(thread_id)
        if entry is None or not isinstance(entry.runner, CodexDaemonSession):
            return None
        return entry.runner

    async def _notification(self, method: str, params: dict[str, Any]) -> None:
        if method == "remoteControl/status/changed":
            log.info("codex remote control status", status=str(params.get("status") or ""))
            return
        if method == "thread/started":
            await self._thread_started(params)
            return
        thread_id = str(params.get("threadId") or "")
        if not thread_id:
            return
        if method == "thread/closed":
            await self._thread_closed(thread_id)
            return
        if method == "serverRequest/resolved":
            session = self._session(thread_id)
            if session is not None:
                await session.resolved_elsewhere(params.get("requestId"))
            return
        session = self._session(thread_id)
        if session is None:
            await self._retry_attach(thread_id)
            return
        await session.notification(method, params)

    async def _retry_attach(self, thread_id: str) -> None:
        """A loaded thread we have no runner for just spoke; take one now."""
        entry = self.hub.entries.get(thread_id)
        if entry is None or thread_id not in self._loaded:
            return
        await self._attach(entry)
        await self.publish_control(entry)

    async def _thread_started(self, params: dict[str, Any]) -> None:
        thread = params.get("thread")
        if not isinstance(thread, dict) or threads.is_ephemeral(thread):
            return
        summary = threads.ThreadSummary.parse(thread)
        if summary is None or self._session(summary.thread_id) is not None:
            return
        async with self._lock:
            self._known.add(summary.thread_id)
            self._loaded.add(summary.thread_id)
            await self._adopt(summary, loaded=True)

    async def _thread_closed(self, thread_id: str) -> None:
        self._loaded.discard(thread_id)
        entry = self.hub.entries.get(thread_id)
        if entry is None:
            return
        if isinstance(entry.runner, CodexDaemonSession):
            await entry.runner.close()
            entry.runner = None
        await self.publish_control(entry)

    async def _request(self, request_id: Any, method: str, params: dict[str, Any]) -> Any:
        thread_id = str(params.get("threadId") or "")
        session = self._session(thread_id)
        if session is None:
            if method in approvals.APPROVAL_METHODS:
                # Nobody is watching this thread here; let the terminal answer.
                raise RcError("conflict", "no attached session for this thread")
            raise RcError("unsupported", f"unhandled codex request {method}")
        return await session.server_request(request_id, method, params)

    async def _reconnected(self) -> None:
        """Re-resume everything we were following and backfill what we missed."""
        for entry in list(self.hub.entries.values()):
            runner = entry.runner
            if not isinstance(runner, CodexDaemonSession):
                continue
            try:
                await runner.resubscribe()
            except RcError as exc:
                log.warning("could not re-resume a codex thread", error=exc.message[:200])
        await self.refresh()
