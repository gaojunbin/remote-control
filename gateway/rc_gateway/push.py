"""Notification triggers and delivery.

The gateway pushes on four observed session transitions and never on the content of a turn: the
payload carries identifiers and one generic sentence, so a notification on a lock screen tells the
user which device wants attention and nothing about the code being written. A transition is
delivered to the registrations of the account that owns the device and to nobody else (A24).
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import time
from collections.abc import Awaitable, Callable
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Protocol

from .apns import ApnsProvider, ApnsResponse
from .hub import SessionTransition
from .logging import logger
from .push_store import PushStore, WebPushSubscription

log = logger("rc_gateway.push")

KIND_NEEDS_APPROVAL = "needs_approval"
KIND_NEEDS_INPUT = "needs_input"
KIND_TURN_COMPLETED = "turn_completed"
KIND_ERROR = "error"

ACTIVE_STATES = frozenset({"running", "needs_approval", "needs_input"})
_TEXTS = {
    KIND_NEEDS_APPROVAL: "approval needed",
    KIND_NEEDS_INPUT: "waiting for your answer",
    KIND_TURN_COMPLETED: "turn finished",
    KIND_ERROR: "session error",
}
RETRY_BACKOFF_SECONDS = (30.0, 120.0, 600.0)
MAX_ATTEMPTS = 4
WORKER_INTERVAL_SECONDS = 15.0
#: `pywebpush` is a blocking `requests` call whose own default never applies: its signature
#: defaults `timeout=None` and passes the key through, so `requests` waits until the OS gives up.
#: Ten seconds, the same as the APNs client.
WEB_PUSH_TIMEOUT_SECONDS = 10.0
#: Push delivery gets its own threads. The default executor is shared by every blocking call in
#: the gateway — 55 of them across the stores — and it holds `min(32, cpu_count + 4)` threads, six
#: on a 2-vCPU VPS. A browser vendor that black-holes connections must not be able to take the
#: pool that logins, device lookups and session reads are queued on.
PUSH_THREADS = 4

DeviceNameLookup = Callable[[str], Awaitable[str]]


class WebPushSender(Protocol):
    async def __call__(self, subscription: WebPushSubscription, payload: str) -> int | None: ...


def transition_kind(previous_state: str, state: str) -> str | None:
    """Map a session state change to a notification kind, or ``None`` for a silent change."""
    if state == previous_state:
        return None
    if state == "needs_approval":
        return KIND_NEEDS_APPROVAL
    if state == "needs_input":
        return KIND_NEEDS_INPUT
    if state == "error":
        return KIND_ERROR
    if state == "idle" and previous_state in ACTIVE_STATES:
        return KIND_TURN_COMPLETED
    return None


def build_payload(kind: str, session: dict[str, Any], device_name: str) -> dict[str, Any]:
    """Protocol §2 payload: identifiers plus one generic sentence, never turn content."""
    return {
        "rc": {
            "v": 1,
            "kind": kind,
            "device_id": str(session.get("device_id") or ""),
            "session_id": str(session.get("session_id") or ""),
            "device_name": device_name,
            "title": f"{device_name}: {_TEXTS.get(kind, 'update')}",
        }
    }


class PushService:
    def __init__(
        self,
        store: PushStore,
        *,
        device_name: DeviceNameLookup,
        vapid_private_key: str = "",
        vapid_contact: str = "",
        web_sender: WebPushSender | None = None,
        apns: ApnsProvider | None = None,
    ) -> None:
        self.store = store
        self.apns = apns
        self._device_name = device_name
        self._vapid_private_key = vapid_private_key
        self._vapid_contact = vapid_contact
        self._web_sender = web_sender or self._send_with_pywebpush
        self._worker: asyncio.Task[None] | None = None
        self._threads: ThreadPoolExecutor | None = None

    @property
    def web_enabled(self) -> bool:
        return bool(self._vapid_private_key and self._vapid_contact)

    @property
    def apns_enabled(self) -> bool:
        return self.apns is not None

    async def start(self) -> None:
        if self.apns is not None and self._worker is None:
            self._worker = asyncio.create_task(self._retry_loop())

    async def stop(self) -> None:
        if self._worker is not None:
            self._worker.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._worker
            self._worker = None
        if self._threads is not None:
            # Not waited on: a vendor that is not answering would hold shutdown for its whole
            # timeout, and the delivery is journalled or lost either way.
            self._threads.shutdown(wait=False, cancel_futures=True)
            self._threads = None
        if self.apns is not None:
            await self.apns.close()

    async def on_session_transition(self, transition: SessionTransition) -> None:
        kind = transition_kind(transition.previous_state, transition.state)
        if kind is None:
            return
        if transition.has_active_subscriber:
            log.debug("push suppressed: an app is watching this session", kind=kind)
            return
        if not transition.owner:
            log.debug("push skipped: the device has no owner", kind=kind)
            return
        device_id = str(transition.session.get("device_id") or "")
        await self.notify(
            kind, transition.session, await self._device_name(device_id), transition.owner
        )

    async def notify(
        self, kind: str, session: dict[str, Any], device_name: str, owner: str
    ) -> None:
        payload = build_payload(kind, session, device_name)
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        await asyncio.gather(
            self._deliver_web(body, owner),
            self._enqueue_apns(payload, owner),
            return_exceptions=True,
        )

    # ---- web push ----

    async def _deliver_web(self, body: str, owner: str) -> None:
        if not self.web_enabled:
            return
        subscriptions = await self.store.list_web(owner)
        results = await asyncio.gather(
            *(self._deliver_one_web(item, body) for item in subscriptions),
            return_exceptions=True,
        )
        failures = sum(isinstance(item, Exception) for item in results)
        if failures:
            log.warning("web push delivery failed", failures=failures, total=len(results))

    async def _deliver_one_web(self, subscription: WebPushSubscription, body: str) -> None:
        status = await self._web_sender(subscription, body)
        if status in {404, 410}:
            await self.store.remove_web(subscription.endpoint, subscription.username)

    async def _send_with_pywebpush(
        self, subscription: WebPushSubscription, payload: str
    ) -> int | None:
        def send() -> int | None:
            from pywebpush import WebPushException, webpush

            try:
                response = webpush(
                    subscription_info=subscription.browser_payload(),
                    data=payload,
                    vapid_private_key=self._vapid_private_key,
                    vapid_claims={"sub": self._vapid_contact},
                    ttl=300,
                    timeout=WEB_PUSH_TIMEOUT_SECONDS,
                )
                raw = getattr(response, "status_code", None)
                return raw if isinstance(raw, int) else None
            except WebPushException as exc:
                failed = getattr(exc, "response", None)
                status = getattr(failed, "status_code", None)
                if isinstance(status, int) and status in {404, 410}:
                    return status
                raise

        return await asyncio.get_running_loop().run_in_executor(self._executor(), send)

    def _executor(self) -> ThreadPoolExecutor:
        """Push's own threads, created on first delivery so an idle gateway holds none."""
        if self._threads is None:
            self._threads = ThreadPoolExecutor(
                max_workers=PUSH_THREADS, thread_name_prefix="rc-push"
            )
        return self._threads

    # ---- apns ----

    async def _enqueue_apns(self, payload: dict[str, Any], owner: str) -> None:
        if self.apns is None:
            return
        registrations = await self.store.list_apns(owner)
        if not registrations:
            return
        body = json.dumps(_apns_payload(payload), ensure_ascii=False, separators=(",", ":"))
        for registration in registrations:
            await self.store.enqueue(registration.device_token, registration.environment, body)
        await self.flush_apns()

    async def flush_apns(self) -> None:
        """Send every due journal entry once, rescheduling or dropping by the APNs answer."""
        if self.apns is None:
            return
        for delivery in await self.store.claim_due():
            try:
                response = await self.apns.send(
                    delivery.device_token,
                    delivery.payload.encode("utf-8"),
                    environment=delivery.environment,
                )
            except ValueError:
                await self.store.finish(delivery.delivery_id)
                await self.store.remove_apns(delivery.device_token)
                continue
            await self._settle(delivery.delivery_id, delivery, response)

    async def _settle(self, delivery_id: int, delivery: Any, response: ApnsResponse) -> None:
        if response.delivered:
            await self.store.finish(delivery_id)
            return
        if response.permanent_failure:
            log.info("apns registration dropped", reason=response.reason)
            await self.store.finish(delivery_id)
            await self.store.remove_apns(delivery.device_token)
            return
        attempts = delivery.attempts + 1
        if attempts >= MAX_ATTEMPTS:
            log.warning("apns delivery abandoned", attempts=attempts, status=response.status)
            await self.store.finish(delivery_id)
            return
        backoff = RETRY_BACKOFF_SECONDS[min(attempts - 1, len(RETRY_BACKOFF_SECONDS) - 1)]
        if response.retry_after is not None:
            backoff = max(backoff, response.retry_after)
        await self.store.retry_at(delivery_id, time.time() + backoff)

    async def _retry_loop(self) -> None:
        while True:
            try:
                await asyncio.sleep(WORKER_INTERVAL_SECONDS)
                await self.flush_apns()
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("apns retry pass failed")


def _apns_payload(payload: dict[str, Any]) -> dict[str, Any]:
    rc = payload.get("rc", {})
    title = rc.get("title", "") if isinstance(rc, dict) else ""
    return {
        "aps": {
            "alert": {"title": "Remote Control", "body": title},
            "sound": "default",
            "interruption-level": "active",
        },
        **payload,
    }
