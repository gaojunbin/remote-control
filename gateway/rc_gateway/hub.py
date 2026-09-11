"""The routing core: one device slot per device, request forwarding, replay fan-out.

Adapted in structure from cc-remote's ``cc_remote/relay/pairing.py`` (MIT, see
THIRD_PARTY_NOTICES.md). What is kept: one connection slot per device with replacement, a send lock
that linearises forwarding against slot changes, and the "drop the whole slow connection" rule.
What is new here: request/reply correlation with gateway-generated ``device_offline`` and
``timeout`` replies, the per-session replay buffer with per-connection cursors, and the session
index.
"""

from __future__ import annotations

import asyncio
import contextlib
import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any

from .connections import AppConnection, DeviceConnection, SlowClientError, encode
from .devices import DeviceStore, normalize_pairing_code
from .frames import (
    CLOSE_DEVICE_REPLACED,
    CLOSE_FORBIDDEN,
    CLOSE_SLOW_CLIENT,
    CLOSE_UNAUTHORIZED,
    DEVICE_PUSH_TYPES,
    ERROR_BAD_REQUEST,
    ERROR_DEVICE_OFFLINE,
    ERROR_NOT_FOUND,
    ERROR_TIMEOUT,
    ERROR_TOO_LARGE,
    FORWARDED_BY_DEVICE,
    FORWARDED_TYPES,
    MAX_ATTACHMENT_BYTES,
    MAX_ATTACHMENTS,
    MAX_EVENT_BYTES,
    PING_INTERVAL_SECONDS,
    PROTOCOL_VERSION,
    REQUEST_TIMEOUT_SECONDS,
    SILENT_TIMEOUT_SECONDS,
    Frame,
    error_reply,
    frame_type,
    int_field,
    object_field,
    ok_reply,
    request_id,
    text_field,
)
from .index import IndexedSession, SessionIndex
from .logging import logger
from .replay import ReplayBuffer
from .views import device_view, has_available_agent

log = logger("rc_gateway.hub")

DEVICE_REPLACED_CLOSE_REASON = "replaced by a newer connection"
HEARTBEAT_TICK_SECONDS = 5.0
MAX_TRACKED_PAIRINGS = 64
#: Pairing progress is tracked only as long as the code can still be redeemed. Without this a
#: device with no agent installed never reaches the `agents` step and its entry would linger,
#: absorbing the `online` step of a later re-enrollment.
PAIRING_TRACK_SECONDS = 600.0
#: Amendment A9. A gateway-originated request carries this opaque id in `from`, so the device
#: echoes it like any other and the reply routes back here instead of to an app connection.
GATEWAY_ORIGIN_ID = "gateway"
BACKFILL_LIMIT = 1000
#: A device that was offline for a long time can be far ahead; stop after this many pages rather
#: than looping forever against a peer that keeps reporting `has_more`.
MAX_BACKFILL_PAGES = 64
ACTIVE_APP_WINDOW_SECONDS = 60.0
#: How long a shutdown waits for notifications that are already on their way out.
TRANSITION_DRAIN_SECONDS = 5.0
#: Amendment A13. A dropped device link is not an offline device: mobile NAT, a suspended laptop
#: and a daemon restart all look identical for a few seconds, and reporting each one as offline
#: made devices flap. `online` stays true for this long, and a reconnect inside it is invisible.
OFFLINE_GRACE_SECONDS = 20.0
#: Close codes that mean the device is not coming back, so `online` flips at once (A13, §2.5).
IMMEDIATE_OFFLINE_CLOSES = frozenset({CLOSE_UNAUTHORIZED, CLOSE_FORBIDDEN})

#: Replay buffers hold up to 4 MiB each, so the map itself has to be bounded. Evicting the coldest
#: buffer only costs the next subscriber a `resync: true`, which the protocol already handles.
MAX_REPLAY_BUFFERS = 512

#: Session states that mean a turn is in progress (``needs_*`` are sub-states of running, §3).
ACTIVE_STATES = frozenset({"running", "needs_approval", "needs_input"})

TransitionHook = Callable[[str, str, dict[str, Any], bool], Awaitable[None]]


@dataclass
class _Pending:
    device_id: str
    connection_id: str
    timer: asyncio.Task[None] | None = None


@dataclass
class _Grace:
    """A device whose link dropped, still reported online while it might come back (A13)."""

    #: Set when the grace resolves, whichever way: a replacement connection arrived, or the
    #: period ran out. A request parked on it re-reads the device slot and learns which.
    resolved: asyncio.Event
    timer: asyncio.Task[None] | None = None


@dataclass
class _Pairing:
    code: str
    created_at: float
    device_id: str = ""
    steps: set[str] = field(default_factory=set)


class Hub:
    def __init__(
        self,
        index: SessionIndex,
        device_store: DeviceStore,
        *,
        on_session_transition: TransitionHook | None = None,
        request_timeout: float = REQUEST_TIMEOUT_SECONDS,
        offline_grace: float = OFFLINE_GRACE_SECONDS,
    ) -> None:
        self.index = index
        self.device_store = device_store
        self.request_timeout = request_timeout
        self.offline_grace = offline_grace
        self._on_session_transition = on_session_transition
        self._devices: dict[str, DeviceConnection] = {}
        self._apps: dict[str, AppConnection] = {}
        self._buffers: dict[str, ReplayBuffer] = {}
        self._owners: dict[str, str] = {}
        self._queues: dict[str, Frame] = {}
        self._pending: dict[tuple[str, str], _Pending] = {}
        self._gateway_pending: dict[str, asyncio.Future[Frame]] = {}
        self._backfills: set[asyncio.Task[None]] = set()
        self._transitions: set[asyncio.Task[None]] = set()
        self._grace: dict[str, _Grace] = {}
        self._pairings: dict[str, _Pairing] = {}
        self._lock = asyncio.Lock()
        self._send_lock = asyncio.Lock()
        self._heartbeat: asyncio.Task[None] | None = None

    # ---- lifecycle ----

    async def start(self) -> None:
        if self._heartbeat is None:
            self._heartbeat = asyncio.create_task(self._heartbeat_loop())

    async def stop(self) -> None:
        if self._heartbeat is not None:
            self._heartbeat.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._heartbeat
            self._heartbeat = None
        for task in list(self._backfills):
            task.cancel()
        self._backfills.clear()
        for device_id in list(self._grace):
            self._end_grace(device_id)
        await self._drain_transitions()
        for waiting in self._gateway_pending.values():
            waiting.cancel()
        self._gateway_pending.clear()
        for pending in list(self._pending.values()):
            if pending.timer is not None:
                pending.timer.cancel()
        self._pending.clear()
        for connection in [*self._devices.values(), *self._apps.values()]:
            await connection.stop(code=1001, reason="gateway shutting down")
        self._devices.clear()
        self._apps.clear()

    async def _drain_transitions(self) -> None:
        """Let the pushes already in flight finish, rather than dropping a notification.

        A vendor that has stopped answering must not hold the shutdown open, so whatever is still
        running when the grace period ends is cancelled.
        """
        pending = list(self._transitions)
        if pending:
            _, unfinished = await asyncio.wait(pending, timeout=TRANSITION_DRAIN_SECONDS)
            for task in unfinished:
                task.cancel()
        self._transitions.clear()

    def device_online(self, device_id: str) -> bool:
        """True while the device holds its slot, and through the A13 grace period after it drops."""
        return device_id in self._devices or device_id in self._grace

    def online_device_ids(self) -> list[str]:
        return sorted(self._devices)

    def latency_for(self, device_id: str) -> int | None:
        connection = self._devices.get(device_id)
        return connection.latency_ms if connection is not None else None

    def agents_for(self, device_id: str) -> list[dict[str, Any]]:
        connection = self._devices.get(device_id)
        return list(connection.agents) if connection is not None else []

    @property
    def app_count(self) -> int:
        return len(self._apps)

    # ---- device side ----

    async def attach_device(self, connection: DeviceConnection) -> None:
        """Claim the device's single slot, closing any previous connection."""
        async with self._send_lock, self._lock:
            previous = self._devices.get(connection.device_id)
            self._devices[connection.device_id] = connection
        # A13: a replacement inside the grace period ends it without a word to the apps, which
        # never saw the device leave, and releases whatever requests were parked waiting for it.
        reconnected = self._end_grace(connection.device_id)
        if previous is not None and previous is not connection:
            await previous.stop(code=CLOSE_DEVICE_REPLACED, reason=DEVICE_REPLACED_CLOSE_REASON)
        log.info("device connected", device_id=connection.device_id, within_grace=reconnected)

    async def detach_device(self, connection: DeviceConnection) -> None:
        async with self._lock:
            if self._devices.get(connection.device_id) is not connection:
                return
            del self._devices[connection.device_id]
        code = connection.close_code
        log.info("device disconnected", device_id=connection.device_id, close_code=code)
        if code in IMMEDIATE_OFFLINE_CLOSES:
            await self._report_offline(connection.device_id)
            return
        self._begin_grace(connection.device_id)

    def _begin_grace(self, device_id: str) -> None:
        """Hold a dropped device online for the grace period (A13)."""
        self._end_grace(device_id)
        grace = _Grace(resolved=asyncio.Event())
        grace.timer = asyncio.create_task(self._expire_grace(device_id, grace))
        self._grace[device_id] = grace

    def _end_grace(self, device_id: str) -> bool:
        """Drop a device's grace without reporting anything. True when one was running."""
        grace = self._grace.pop(device_id, None)
        if grace is None:
            return False
        if grace.timer is not None:
            grace.timer.cancel()
        grace.resolved.set()
        return True

    async def _expire_grace(self, device_id: str, grace: _Grace) -> None:
        try:
            await asyncio.sleep(self.offline_grace)
        except asyncio.CancelledError:
            return
        if self._grace.get(device_id) is not grace:
            return
        del self._grace[device_id]
        # Released before the broadcast, so a parked request is answered without also waiting for
        # every app socket to take the frame.
        grace.resolved.set()
        log.info("device offline: no reconnect within the grace period", device_id=device_id)
        await self._report_offline(device_id)

    async def _report_offline(self, device_id: str) -> None:
        await self._fail_pending_for_device(device_id, ERROR_DEVICE_OFFLINE)
        record = await self.device_store.get(device_id)
        if record is not None:
            await self.broadcast_apps(
                {
                    "type": "device.updated",
                    "device": device_view(record, online=False, latency_ms=None),
                }
            )

    async def _await_device(self, device_id: str) -> None:
        """Hold a request while its device is inside the grace period (A13, §2.5).

        A link that dropped is not an offline device, so a message sent in the seconds around a
        reconnect waits for the replacement connection rather than being refused. The wait is
        bounded by the period itself: when it ends without one, the caller re-reads the slot,
        finds it empty and answers ``device_offline`` as before.
        """
        if device_id in self._devices:
            return
        grace = self._grace.get(device_id)
        if grace is None:
            return
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(grace.resolved.wait(), timeout=self.offline_grace)

    async def disconnect_device(self, device_id: str, *, reason: str) -> bool:
        """Close a device's socket because its credential is gone (A4: 4401, do not retry)."""
        # An explicit removal is not a transient drop: end any grace so nothing later reports
        # this device as merely offline, and release requests parked on it (A13).
        self._end_grace(device_id)
        async with self._lock:
            connection = self._devices.get(device_id)
        if connection is None:
            return False
        await connection.stop(code=CLOSE_UNAUTHORIZED, reason=reason)
        return True

    async def handle_device_hello(self, connection: DeviceConnection, frame: Frame) -> bool:
        """Apply a device hello. Returns False when the frame is unusable."""
        if int_field(frame, "protocol") != PROTOCOL_VERSION:
            return False
        connection.hello = frame
        connection.agents = _agent_list(frame.get("agents"))
        await self.device_store.describe(
            connection.device_id,
            agents=connection.agents,
            name=text_field(frame, "name"),
            platform=text_field(frame, "platform"),
            hostname=text_field(frame, "hostname"),
            arch=text_field(frame, "arch"),
            client_version=text_field(frame, "client_version"),
        )
        await self.device_store.touch(connection.device_id)
        sessions = frame.get("sessions")
        if isinstance(sessions, list):
            for summary in sessions:
                if isinstance(summary, dict):
                    await self._store_session(connection.device_id, summary)
        await self._announce_device(connection)
        self._start_backfill(connection, sessions if isinstance(sessions, list) else [])
        await self._advance_pairing(connection.device_id, "online")
        if has_available_agent(connection.agents):
            await self._advance_pairing(connection.device_id, "agents")
        return True

    # ---- backfill after a link outage (A9) ----

    def _start_backfill(self, connection: DeviceConnection, summaries: list[Any]) -> None:
        """Ask the device for whatever the replay buffer missed while the link was down.

        Only a session whose buffer still holds something is worth backfilling: an empty buffer
        already answers `session.subscribe` with `resync`, and the app pages history itself.
        """
        plan: list[tuple[str, int]] = []
        for summary in summaries:
            if not isinstance(summary, dict):
                continue
            session_id = text_field(summary, "session_id")
            device_last = int_field(summary, "last_seq")
            buffer = self._buffers.get(session_id)
            if not session_id or device_last is None or buffer is None or len(buffer) == 0:
                continue
            if device_last > buffer.last_seq:
                plan.append((session_id, buffer.last_seq))
        if not plan:
            return
        task = asyncio.create_task(self._run_backfill(connection, plan))
        self._backfills.add(task)
        task.add_done_callback(self._backfills.discard)

    async def _run_backfill(
        self, connection: DeviceConnection, plan: list[tuple[str, int]]
    ) -> None:
        for session_id, tail in plan:
            try:
                await self._backfill_session(connection, session_id, tail)
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("backfill failed", device_id=connection.device_id)

    async def _backfill_session(
        self, connection: DeviceConnection, session_id: str, tail: int
    ) -> None:
        after = tail
        for _ in range(MAX_BACKFILL_PAGES):
            reply = await self._ask_device(
                connection,
                {
                    "type": "session.history",
                    "session_id": session_id,
                    "after_seq": after,
                    "limit": BACKFILL_LIMIT,
                },
            )
            if reply is None or not reply.get("ok"):
                return
            result = object_field(reply, "result") or {}
            events = result.get("events")
            if not isinstance(events, list) or not events:
                return
            applied = 0
            for event in events:
                if not isinstance(event, dict):
                    continue
                seq = int_field(event, "seq")
                if seq is None or seq <= after:
                    continue
                frame = {"type": "session.event", "session_id": session_id, "event": event}
                if await self._publish_event(connection, session_id, event, frame):
                    applied += 1
                    after = max(after, seq)
            log.info(
                "backfilled events after a device reconnect",
                device_id=connection.device_id,
                events=applied,
                through_seq=after,
            )
            if not result.get("has_more") or applied == 0:
                return

    async def _ask_device(self, connection: DeviceConnection, request: Frame) -> Frame | None:
        """Send a gateway-originated request and wait for the device's reply."""
        identifier = uuid.uuid4().hex
        waiter: asyncio.Future[Frame] = asyncio.get_running_loop().create_future()
        self._gateway_pending[identifier] = waiter
        frame = {
            **request,
            "id": identifier,
            "from": GATEWAY_ORIGIN_ID,
            "device_id": connection.device_id,
        }
        try:
            async with self._send_lock:
                await connection.send(frame)
            return await asyncio.wait_for(waiter, timeout=self.request_timeout)
        except (SlowClientError, ConnectionError):
            log.warning("backfill request could not be sent", device_id=connection.device_id)
            return None
        except (TimeoutError, asyncio.CancelledError):
            log.warning("backfill request timed out", device_id=connection.device_id)
            return None
        finally:
            self._gateway_pending.pop(identifier, None)

    async def handle_device_frame(self, connection: DeviceConnection, frame: Frame) -> None:
        kind = frame_type(frame)
        if kind == "pong":
            connection.note_pong()
            return
        if kind == "reply":
            await self._route_reply(connection, frame)
            return
        if kind not in DEVICE_PUSH_TYPES:
            log.debug("unknown device frame dropped", device_id=connection.device_id, kind=kind)
            return
        if kind == "session.event":
            await self._handle_session_event(connection, frame)
        elif kind == "session.updated":
            summary = object_field(frame, "session")
            if summary is not None:
                await self._store_session(connection.device_id, summary)
        elif kind == "session.removed":
            session_id = text_field(frame, "session_id")
            if session_id:
                await self._forget_session(connection.device_id, session_id)
        elif kind == "agents.updated":
            connection.agents = _agent_list(frame.get("agents"))
            await self.device_store.describe(connection.device_id, agents=connection.agents)
            await self._announce_device(connection)
            if has_available_agent(connection.agents):
                await self._advance_pairing(connection.device_id, "agents")

    # ---- app side ----

    async def attach_app(self, connection: AppConnection) -> None:
        async with self._lock:
            self._apps[connection.id] = connection

    async def detach_app(self, connection: AppConnection) -> None:
        async with self._lock:
            self._apps.pop(connection.id, None)
        for key in [key for key in self._pending if key[0] == connection.id]:
            pending = self._pending.pop(key, None)
            if pending is not None and pending.timer is not None:
                pending.timer.cancel()

    async def app_hello_payload(self, username: str, gateway_version: str, stt: Frame) -> Frame:
        records = await self.device_store.list_for_user(username)
        devices = [
            device_view(
                record,
                online=self.device_online(record.device_id),
                latency_ms=self.latency_for(record.device_id),
            )
            for record in records
        ]
        return {
            "type": "hello",
            "protocol": PROTOCOL_VERSION,
            "gateway_version": gateway_version,
            "user": {"username": username},
            "devices": devices,
            "sessions": await self.index.list_sessions(),
            "stt": stt,
            "server_time": _now_ms(),
        }

    async def handle_app_frame(self, connection: AppConnection, frame: Frame) -> None:
        kind = frame_type(frame)
        if kind == "pong":
            connection.note_pong()
            return
        connection.note_activity()
        if kind == "session.subscribe":
            await self._handle_subscribe(connection, frame)
            return
        if kind == "session.unsubscribe":
            connection.unsubscribe(text_field(frame, "session_id"))
            return
        if kind in FORWARDED_TYPES:
            await self._forward(connection, frame)
            return
        identifier = request_id(frame)
        if identifier:
            await self._send_app(
                connection, error_reply(identifier, ERROR_BAD_REQUEST, f"unsupported type {kind!r}")
            )
        else:
            log.debug("unknown app frame dropped", kind=kind)

    async def broadcast_apps(self, frame: Frame) -> None:
        async with self._lock:
            connections = list(self._apps.values())
        for connection in connections:
            await self._send_app(connection, frame)

    # ---- pairing progress ----

    async def pairing_started(self, code: str) -> None:
        key = normalize_pairing_code(code)
        async with self._lock:
            self._expire_pairings()
            if len(self._pairings) >= MAX_TRACKED_PAIRINGS:
                oldest = min(self._pairings.values(), key=lambda item: item.created_at)
                self._pairings.pop(normalize_pairing_code(oldest.code), None)
            self._pairings[key] = _Pairing(code=code, created_at=time.monotonic())
        await self.broadcast_apps({"type": "pairing.progress", "code": code, "step": "waiting"})

    async def pairing_cancelled(self, code: str) -> None:
        async with self._lock:
            self._pairings.pop(normalize_pairing_code(code), None)

    async def pairing_enrolled(self, code: str, device_id: str) -> None:
        """A device redeemed ``code``; report progress under the code as it was minted."""
        key = normalize_pairing_code(code)
        async with self._lock:
            pairing = self._pairings.get(key)
            if pairing is None:
                pairing = _Pairing(code=code, created_at=time.monotonic())
                self._pairings[key] = pairing
            pairing.device_id = device_id
            pairing.steps.add("enrolled")
            display = pairing.code
        await self._emit_pairing(display, "enrolled", device_id)

    def _expire_pairings(self) -> None:
        cutoff = time.monotonic() - PAIRING_TRACK_SECONDS
        for key in [k for k, item in self._pairings.items() if item.created_at < cutoff]:
            del self._pairings[key]

    async def _advance_pairing(self, device_id: str, step: str) -> None:
        async with self._lock:
            self._expire_pairings()
            pairing = next(
                (item for item in self._pairings.values() if item.device_id == device_id), None
            )
            if pairing is None or step in pairing.steps:
                return
            pairing.steps.add(step)
            display = pairing.code
            if step == "agents":
                self._pairings.pop(normalize_pairing_code(display), None)
        await self._emit_pairing(display, step, device_id)

    async def _emit_pairing(self, code: str, step: str, device_id: str) -> None:
        frame: Frame = {"type": "pairing.progress", "code": code, "step": step}
        record = await self.device_store.get(device_id)
        if record is not None:
            frame["device"] = device_view(
                record,
                online=self.device_online(device_id),
                latency_ms=self.latency_for(device_id),
            )
        await self.broadcast_apps(frame)

    # ---- forwarding ----

    async def _forward(self, connection: AppConnection, frame: Frame) -> None:
        identifier = request_id(frame)
        if not identifier:
            log.debug("forwardable frame without id dropped", kind=frame_type(frame))
            return
        kind = frame_type(frame)
        oversized = _attachments_over_limit(frame)
        if oversized is not None:
            await self._send_app(connection, error_reply(identifier, ERROR_TOO_LARGE, oversized))
            return
        outgoing = dict(frame)
        if kind in FORWARDED_BY_DEVICE:
            device_id = text_field(frame, "device_id")
            if not device_id:
                await self._send_app(
                    connection, error_reply(identifier, ERROR_BAD_REQUEST, "device_id is required")
                )
                return
        else:
            session_id = text_field(frame, "session_id")
            # Only the owning device is needed to route, and that is in memory: reading the whole
            # summary back from SQLite would put a disk read in front of every message an app sends.
            owner = await self._owner_of(session_id) if session_id else None
            if owner is None:
                await self._send_app(
                    connection, error_reply(identifier, ERROR_NOT_FOUND, "unknown session")
                )
                return
            device_id = owner
            outgoing["device_id"] = device_id
        outgoing["from"] = connection.id

        # Outside the send lock: a device inside its grace period is waited for, and holding the
        # lock across that would stall every other device's forwarding for the whole period.
        await self._await_device(device_id)
        async with self._send_lock:
            async with self._lock:
                device = self._devices.get(device_id)
                still_connected = self._apps.get(connection.id) is connection
            if not still_connected:
                return
            if device is None:
                await self._send_app(
                    connection,
                    error_reply(identifier, ERROR_DEVICE_OFFLINE, "device is not connected"),
                )
                return
            try:
                await device.send(outgoing)
            except (SlowClientError, ConnectionError) as exc:
                log.warning("forward to device failed", device_id=device_id, error=str(exc))
                await self._drop_device(device)
                await self._send_app(
                    connection,
                    error_reply(identifier, ERROR_DEVICE_OFFLINE, "device link broken"),
                )
                return
        self._track_pending(connection, identifier, device_id)

    def _track_pending(self, connection: AppConnection, identifier: str, device_id: str) -> None:
        key = (connection.id, identifier)
        existing = self._pending.get(key)
        if existing is not None and existing.timer is not None:
            existing.timer.cancel()
        pending = _Pending(device_id=device_id, connection_id=connection.id)
        self._pending[key] = pending
        pending.timer = asyncio.create_task(self._expire_pending(key, identifier))

    async def _expire_pending(self, key: tuple[str, str], identifier: str) -> None:
        try:
            await asyncio.sleep(self.request_timeout)
        except asyncio.CancelledError:
            return
        if self._pending.pop(key, None) is None:
            return
        async with self._lock:
            connection = self._apps.get(key[0])
        if connection is not None:
            await self._send_app(
                connection, error_reply(identifier, ERROR_TIMEOUT, "device did not reply in time")
            )

    async def _route_reply(self, device: DeviceConnection, frame: Frame) -> None:
        identifier = request_id(frame)
        target = text_field(frame, "from")
        if not identifier or not target:
            return
        if target == GATEWAY_ORIGIN_ID:
            waiting = self._gateway_pending.pop(identifier, None)
            if waiting is not None and not waiting.done():
                waiting.set_result(frame)
            return
        key = (target, identifier)
        pending = self._pending.get(key)
        if pending is None:
            log.debug("late reply dropped", device_id=device.device_id)
            return
        if pending.device_id != device.device_id:
            # A device answering a request forwarded elsewhere would forge results for a session
            # it does not own. The entry is inspected before it is claimed, so a forged reply
            # cannot consume the request either: the real device can still answer, and the
            # timeout still fires if it never does.
            log.warning("reply from the wrong device dropped", device_id=device.device_id)
            return
        del self._pending[key]
        if pending.timer is not None:
            pending.timer.cancel()
        async with self._lock:
            connection = self._apps.get(target)
        if connection is None:
            return
        reply = {key_: value for key_, value in frame.items() if key_ != "from"}
        await self._send_app(connection, reply)

    async def _fail_pending_for_device(self, device_id: str, code: str) -> None:
        for key in [key for key, item in self._pending.items() if item.device_id == device_id]:
            pending = self._pending.pop(key, None)
            if pending is None:
                continue
            if pending.timer is not None:
                pending.timer.cancel()
            async with self._lock:
                connection = self._apps.get(key[0])
            if connection is not None:
                await self._send_app(
                    connection, error_reply(key[1], code, "device is not connected")
                )

    # ---- subscriptions and events ----

    async def _handle_subscribe(self, connection: AppConnection, frame: Frame) -> None:
        identifier = request_id(frame)
        session_id = text_field(frame, "session_id")
        if not identifier or not session_id:
            if identifier:
                await self._send_app(
                    connection, error_reply(identifier, ERROR_BAD_REQUEST, "session_id is required")
                )
            return
        indexed = await self.index.get(session_id)
        if indexed is None:
            await self._send_app(
                connection, error_reply(identifier, ERROR_NOT_FOUND, "unknown session")
            )
            return
        since_seq = int_field(frame, "since_seq")
        buffer = self._buffers.get(session_id)
        if buffer is None:
            events: list[Frame] = []
            resync = since_seq is not None and since_seq < indexed.last_seq
        else:
            events, resync = buffer.replay_from(since_seq)
        cursor = max(since_seq or 0, 0)
        if events:
            cursor = int_field(events[-1], "seq") or cursor
        if resync:
            cursor = indexed.last_seq
        connection.subscribe(session_id, cursor)
        result: dict[str, Any] = {
            "session": indexed.summary,
            "events": events,
            "resync": resync,
        }
        queued = self._queues.get(session_id)
        if queued is not None:
            result["queue"] = {"pending": queued.get("pending") or []}
        await self._send_app(connection, ok_reply(identifier, result))

    async def _claim_session(self, device_id: str, session_id: str) -> bool:
        """True when `device_id` owns `session_id`, claiming it the first time it is seen.

        The binding comes from the device token, never from a frame, and is never rebound: a
        device that announces another machine's session is refused, so it cannot hijack its
        routing, forge its timeline or delete it.
        """
        owner = await self._owner_of(session_id)
        if owner is None:
            self._owners[session_id] = device_id
            return True
        if owner == device_id:
            return True
        log.warning(
            "device frame for a session it does not own",
            device_id=device_id,
            owner=owner,
        )
        return False

    async def _owner_of(self, session_id: str) -> str | None:
        """The device that owns a session, from memory whenever this process has seen it.

        Every session the gateway has already routed a frame for is in ``_owners``, so the common
        case answers without a disk read; only one first seen by an earlier process needs one.
        """
        owner = self._owners.get(session_id)
        if owner is not None:
            return owner
        owner = await self.index.owner(session_id)
        if owner is not None:
            self._owners[session_id] = owner
        return owner

    def _buffer_for(self, session_id: str) -> ReplayBuffer:
        buffer = self._buffers.get(session_id)
        if buffer is not None:
            self._buffers[session_id] = self._buffers.pop(session_id)
            return buffer
        while len(self._buffers) >= MAX_REPLAY_BUFFERS:
            coldest, _ = next(iter(self._buffers.items()))
            self._buffers.pop(coldest, None)
            log.info("replay buffer evicted", sessions=len(self._buffers))
        buffer = ReplayBuffer()
        self._buffers[session_id] = buffer
        return buffer

    async def _handle_session_event(self, device: DeviceConnection, frame: Frame) -> None:
        session_id = text_field(frame, "session_id")
        event = object_field(frame, "event")
        if not session_id or event is None:
            return
        if not await self._claim_session(device.device_id, session_id):
            return
        await self._publish_event(device, session_id, event, frame)

    async def _publish_event(
        self, device: DeviceConnection, session_id: str, event: Frame, frame: Frame
    ) -> bool:
        """Buffer one event and fan it out. Returns False for a dropped or duplicate event."""
        seq = int_field(event, "seq")
        if seq is None:
            return False
        size = len(encode(frame).encode("utf-8"))
        if size > MAX_EVENT_BYTES:
            log.warning(
                "oversized event dropped",
                device_id=device.device_id,
                kind=text_field(event, "kind"),
                bytes=size,
            )
            return False
        buffer = self._buffer_for(session_id)
        if not buffer.append(event, size):
            return False
        if text_field(event, "kind") == "queue":
            # A6: `queue` describes current state rather than timeline history, so the newest
            # snapshot is kept for `session.subscribe` instead of being replayed from the buffer.
            self._queues[session_id] = event
        # Recorded before the fan-out and without touching the disk, so no app can read a summary
        # that predates an event it has already been sent, and no event waits on SQLite.
        self.index.record_seq(session_id, seq)
        # A5: apps address a session by (device_id, session_id) and a pushed event has to carry
        # the device identity the gateway derived from the token, not one the frame claimed.
        outgoing = {**frame, "device_id": device.device_id}
        async with self._lock:
            subscribers = [item for item in self._apps.values() if item.subscribed(session_id)]
        for subscriber in subscribers:
            subscriber.subscribe(session_id, seq)
            await self._send_app(subscriber, outgoing)
        return True

    # ---- session index ----

    async def _store_session(self, device_id: str, summary: Frame) -> None:
        stored = dict(summary)
        stored["device_id"] = device_id
        session_id = text_field(stored, "session_id")
        if not session_id or not await self._claim_session(device_id, session_id):
            return
        previous = await self.index.get(session_id)
        indexed = await self.index.upsert(stored)
        if indexed is None:
            log.warning("session summary refused", device_id=device_id)
            return
        await self.broadcast_apps({"type": "session.updated", "session": indexed.summary})
        # A session the gateway has never seen has no transition to report: an index that was
        # just created (or a device reconnecting after a wipe) must not produce a push storm.
        if self._on_session_transition is None or previous is None:
            return
        if previous.state == indexed.state:
            return
        self._notify_transition(previous.state, indexed)

    def _notify_transition(self, previous_state: str, indexed: IndexedSession) -> None:
        """Run the push hook off the device read loop.

        A push is an outbound HTTP call to a browser vendor or to APNs. Awaiting it here would
        hold every later frame from the same device behind it, because that loop reads and
        dispatches one frame at a time. Whether an app is watching is decided now, at the
        transition, so the delivery running later cannot change the outcome.
        """
        hook = self._on_session_transition
        if hook is None:
            return
        task = asyncio.create_task(
            self._run_transition(
                hook,
                previous_state,
                indexed.state,
                indexed.summary,
                self._has_active_subscriber(indexed.session_id),
            )
        )
        self._transitions.add(task)
        task.add_done_callback(self._transitions.discard)

    async def _run_transition(
        self, hook: TransitionHook, previous_state: str, state: str, summary: Frame, active: bool
    ) -> None:
        try:
            await hook(previous_state, state, summary, active)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception(
                "session transition hook failed", session_id=text_field(summary, "session_id")
            )

    async def _forget_session(self, device_id: str, session_id: str) -> None:
        if not await self._claim_session(device_id, session_id):
            return
        await self.index.remove(session_id)
        self._buffers.pop(session_id, None)
        self._queues.pop(session_id, None)
        self._owners.pop(session_id, None)
        async with self._lock:
            for connection in self._apps.values():
                connection.unsubscribe(session_id)
        await self.broadcast_apps(
            {"type": "session.removed", "session_id": session_id, "device_id": device_id}
        )

    async def forget_device_sessions(self, device_id: str) -> None:
        """Drop every session of a deleted device from the index and from the apps."""
        for session_id in await self.index.remove_for_device(device_id):
            self._buffers.pop(session_id, None)
            self._queues.pop(session_id, None)
            self._owners.pop(session_id, None)
            async with self._lock:
                for connection in self._apps.values():
                    connection.unsubscribe(session_id)
            await self.broadcast_apps(
                {"type": "session.removed", "session_id": session_id, "device_id": device_id}
            )

    def _has_active_subscriber(self, session_id: str) -> bool:
        """True when an app is watching this session and its user did something recently.

        Heartbeat traffic deliberately does not count as activity: a phone with a backgrounded
        tab still answers ``ping``, and suppressing its notification would defeat the feature.
        """
        return any(
            connection.subscribed(session_id) and connection.idle_for() <= ACTIVE_APP_WINDOW_SECONDS
            for connection in self._apps.values()
        )

    # ---- helpers ----

    async def _announce_device(self, connection: DeviceConnection) -> None:
        record = await self.device_store.get(connection.device_id)
        if record is None:
            return
        await self.broadcast_apps(
            {
                "type": "device.updated",
                "device": device_view(
                    record, online=True, latency_ms=connection.latency_ms, last_seen=_now_ms()
                ),
            }
        )

    async def _send_app(self, connection: AppConnection, frame: Frame) -> None:
        try:
            await connection.send(frame)
        except SlowClientError:
            log.warning("app dropped: send queue exhausted", connection=connection.id)
            await self._drop_app(connection)
        except ConnectionError:
            await self._drop_app(connection)

    async def _drop_app(self, connection: AppConnection) -> None:
        async with self._lock:
            if self._apps.get(connection.id) is connection:
                del self._apps[connection.id]
        await connection.stop(code=CLOSE_SLOW_CLIENT, reason="slow client")

    async def _drop_device(self, connection: DeviceConnection) -> None:
        async with self._lock:
            if self._devices.get(connection.device_id) is connection:
                del self._devices[connection.device_id]
        await connection.stop(code=CLOSE_SLOW_CLIENT, reason="slow client")

    async def _heartbeat_loop(self) -> None:
        while True:
            try:
                await asyncio.sleep(HEARTBEAT_TICK_SECONDS)
                await self.heartbeat_tick()
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("heartbeat tick failed")

    async def heartbeat_tick(self) -> None:
        """Ping idle connections and close the ones that stopped answering."""
        now = time.monotonic()
        async with self._lock:
            connections = [*self._devices.values(), *self._apps.values()]
        for connection in connections:
            if connection.silent_for() > SILENT_TIMEOUT_SECONDS:
                log.info("closing silent connection", connection=connection.id)
                await connection.stop(code=1001, reason="no frames received")
                continue
            if now - connection.last_ping_at >= PING_INTERVAL_SECONDS:
                connection.note_ping_sent()
                try:
                    await connection.send({"type": "ping"})
                except (SlowClientError, ConnectionError):
                    await connection.stop(code=CLOSE_SLOW_CLIENT, reason="slow client")


def _attachments_over_limit(frame: Frame) -> str | None:
    """Enforce the §5 attachment bounds, so a device never has to defend itself against them."""
    attachments = frame.get("attachments")
    if not isinstance(attachments, list):
        return None
    if len(attachments) > MAX_ATTACHMENTS:
        return f"at most {MAX_ATTACHMENTS} attachments per message"
    for item in attachments:
        if not isinstance(item, dict):
            continue
        encoded = item.get("data_base64")
        # 4 base64 characters carry 3 bytes; compare without decoding megabytes to count them.
        if isinstance(encoded, str) and len(encoded) // 4 * 3 > MAX_ATTACHMENT_BYTES:
            return f"attachments are limited to {MAX_ATTACHMENT_BYTES // (1024 * 1024)} MiB each"
    return None


def _agent_list(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]


def _now_ms() -> int:
    return int(time.time() * 1000)
