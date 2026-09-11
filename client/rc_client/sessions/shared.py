"""Amendment A10: driving a terminal session the device is attached to.

The one rule the whole file exists for: a message injected while a turn is
running is reframed by the CLI as untrusted external data the model is told not
to obey, so the device injects only at an idle point and holds everything else
in its own queue. Turn state comes from the transcript, which lags reality by up
to one tail interval, so an injection also marks itself in flight until its own
row appears.
"""

from __future__ import annotations

import json
import time
import uuid
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from ..agents.claude.tools import tool_kind, tool_title
from ..errors import RcError
from ..logging_setup import logger
from ..models import now_ms
from . import titles
from .attach import Attachment

if TYPE_CHECKING:  # pragma: no cover - imported for types only
    from .hub import SessionEntry, SessionHub

log = logger("rc_client.shared")

# How long an injection may stay unconfirmed before the next one is allowed.
INFLIGHT_TIMEOUT = 30.0
# How long to wait after a bridge disconnects before deciding whether the CLI
# process is still there: `/exit` closes the channel about a second early.
EXIT_SETTLE = 1.0
MAX_REMEMBERED = 32

ALLOW = "allow"
DENY = "deny"
BEHAVIORS = (ALLOW, DENY)

# Session-scoped grants are not available through the relay, so the two options
# below are the whole vocabulary of a shared approval.
APPROVAL_OPTIONS = [
    {"id": ALLOW, "label": "Allow", "style": "primary"},
    {"id": DENY, "label": "Deny", "style": "danger"},
]


@dataclass(slots=True)
class SharedApproval:
    request_id: str
    channel_id: str
    block_id: str
    tool: str
    title: str
    input: dict[str, Any]


@dataclass(slots=True)
class SharedState:
    """Everything the device tracks about one attached CLI session."""

    attachment: Attachment
    running: bool = False
    inflight: str | None = None
    inflight_at: float = 0.0
    trigger: str = "terminal"
    sent: dict[str, dict[str, Any]] = field(default_factory=dict)
    approvals: dict[str, SharedApproval] = field(default_factory=dict)
    by_channel: dict[str, str] = field(default_factory=dict)

    def remember(self, item: dict[str, Any]) -> None:
        self.sent[str(item["message_id"])] = item
        for key in list(self.sent)[:-MAX_REMEMBERED]:
            self.sent.pop(key, None)

    def mark_inflight(self, message_id: str) -> None:
        self.inflight = message_id
        self.inflight_at = time.monotonic()

    @property
    def waiting(self) -> bool:
        """True while an injection has not yet shown up in the transcript."""
        if self.inflight is None:
            return False
        return (time.monotonic() - self.inflight_at) < INFLIGHT_TIMEOUT


def pending_item(text: str, request_id: str) -> dict[str, Any]:
    """One held message: a queue entry that also carries its bubble's identity.

    The bubble is the app's request id (amendment A12), which is also the id
    the queue reports, so a message drawn on sending and shown again as queued
    and once more as delivered is one block throughout. `message_id` stays the
    channel's own: it names the injection, not the bubble.
    """
    message_id = str(uuid.uuid4())
    block_id = request_id or str(uuid.uuid4())
    return {
        "id": block_id,
        "text": text,
        "ts": now_ms(),
        "attachments": [],
        "message_id": message_id,
        "block_id": block_id,
        "retried": False,
    }


def approval_title(tool: str, preview: str, description: str, cwd: str) -> str:
    """Name the call from the relay's preview, falling back to its description.

    The preview is usually the tool input as JSON, which the ordinary tool
    titles understand; when it is plain text it is already the best summary
    there is.
    """
    try:
        parsed = json.loads(preview)
    except (json.JSONDecodeError, TypeError):
        parsed = None
    if isinstance(parsed, dict):
        return tool_title(tool, parsed, cwd)
    for candidate in (preview, description):
        line = candidate.strip().splitlines()[0].strip() if candidate.strip() else ""
        if line:
            return line[:200]
    return tool


class SharedControl:
    """Implements A10 section 6.3 for sessions with a live channel attachment."""

    def __init__(self, hub: SessionHub) -> None:
        self.hub = hub

    # ------------------------------------------------------------- lifecycle

    async def registered(self, entry: SessionEntry, attachment: Attachment) -> None:
        previous = entry.shared
        if previous is not None and previous.attachment is not attachment:
            previous.attachment.detach()
        entry.shared = SharedState(attachment=attachment)
        entry.holder_pid = attachment.pid or None
        entry.holder_identity = None
        entry.session.control = "shared"
        await entry.channel.emit("meta", control="shared")
        await self._settle(entry)
        await entry.channel.publish_summary()

    async def closed(self, entry: SessionEntry, attachment: Attachment, control: str) -> None:
        """Detach and hand the session back to the terminal, or to nobody."""
        state = entry.shared
        if state is None or state.attachment is not attachment:
            return
        entry.shared = None
        for approval in state.approvals.values():
            await self._emit_approval(entry, approval, status="expired")
        state.approvals.clear()
        state.by_channel.clear()
        entry.session.control = control  # type: ignore[assignment]
        await entry.channel.emit("meta", control=control)
        if entry.session.turn is not None:
            await self._end_turn(entry, "stopped")
        await entry.channel.set_state("readonly" if control == "terminal" else "idle")
        if entry.queue:
            # The held messages survive the detachment and go out through the
            # ordinary resume path the next time the session is sent to.
            await entry.channel.publish_queue(self.hub.queue_snapshot(entry))
        await entry.channel.publish_summary()

    # ------------------------------------------------------------ transcript

    async def tick(self, entry: SessionEntry, running: bool) -> None:
        """Called after every transcript read: the only source of turn state."""
        state = entry.shared
        if state is None:
            return
        if running and not state.running and not state.waiting:
            state.trigger = "terminal"
        state.running = running
        await self._settle(entry)
        if not running:
            await self.drain(entry)

    async def _settle(self, entry: SessionEntry) -> None:
        """Reconcile turn, state and approvals with what the transcript says.

        An injection counts as busy before its row appears, so the apps see the
        turn start at once instead of at the next transcript read.
        """
        state = entry.shared
        if state is None:
            return
        busy = state.running or state.waiting
        if busy and entry.session.turn is None:
            await entry.channel.begin_turn(state.trigger)
        if state.approvals:
            await entry.channel.set_state("needs_approval")
        elif busy:
            await entry.channel.set_state("running")
        else:
            await self._end_turn(entry, "completed")

    async def _end_turn(self, entry: SessionEntry, stop_reason: str) -> None:
        turn = entry.session.turn
        if turn is None:
            await entry.channel.set_state("idle")
            return
        started = int(turn.get("started_at") or now_ms())
        await entry.channel.end_turn(stop_reason, max(0, now_ms() - started))

    async def echo_delivered(self, entry: SessionEntry, message_id: str) -> None:
        """The injected row reached the transcript: the bubble is already correct."""
        state = entry.shared
        if state is not None and state.inflight == message_id:
            state.inflight = None

    async def echo_absorbed(self, entry: SessionEntry, message_id: str) -> None:
        """The CLI took the injection as mid-turn data; re-send it at the next idle."""
        state = entry.shared
        if state is None:
            return
        if state.inflight == message_id:
            state.inflight = None
        item = state.sent.pop(message_id, None)
        if item is None:
            return
        await self._emit_message(entry, item, "absorbed")
        if item.get("retried"):
            await entry.channel.notice(
                "warn", "the terminal treated this message as data; send it again if it was missed"
            )
            return
        item["retried"] = True
        entry.queue.insert(0, item)
        await entry.channel.publish_queue(self.hub.queue_snapshot(entry))

    async def tool_finished(self, entry: SessionEntry, tool: str, failed: bool) -> None:
        """A tool ran or was refused, so its prompt was answered in the terminal."""
        state = entry.shared
        if state is None:
            return
        for request_id, approval in list(state.approvals.items()):
            if approval.tool != tool:
                continue
            self._forget(state, request_id)
            await self._resolve(entry, approval, DENY if failed else ALLOW, "terminal")
            return

    # ----------------------------------------------------------------- input

    async def send(
        self,
        entry: SessionEntry,
        request_id: str,
        text: str,
        attachments: list[dict[str, Any]],
    ) -> dict[str, Any]:
        state = entry.shared
        if state is None:
            raise RcError("conflict", "the session is no longer attached")
        if attachments:
            raise RcError("unsupported", "attachments cannot be delivered to a terminal session")
        await titles.from_prompt(entry.channel, text)
        item = pending_item(text, request_id)
        if not state.running and not state.waiting and await self._inject(entry, item):
            return {"accepted": "sent"}
        entry.queue.append(item)
        await self._emit_message(entry, item, "pending")
        await entry.channel.publish_queue(self.hub.queue_snapshot(entry))
        return {"accepted": "queued", "queued_id": str(item["id"])}

    async def drain(self, entry: SessionEntry) -> None:
        """Release the oldest held message once the transcript says the turn ended."""
        state = entry.shared
        if state is None or state.running or state.waiting or not entry.queue:
            return
        state.inflight = None
        item = entry.queue.pop(0)
        if not await self._inject(entry, item):
            entry.queue.insert(0, item)
            return
        await entry.channel.publish_queue(self.hub.queue_snapshot(entry))

    async def _inject(self, entry: SessionEntry, item: dict[str, Any]) -> bool:
        state = entry.shared
        if state is None:
            return False
        message_id = str(item["message_id"])
        if not await state.attachment.inject(message_id, str(item["text"])):
            return False
        state.mark_inflight(message_id)
        state.trigger = "remote"
        state.remember(item)
        await self._emit_message(entry, item, "delivered")
        await self._settle(entry)
        return True

    async def _emit_message(self, entry: SessionEntry, item: dict[str, Any], delivery: str) -> None:
        await entry.channel.emit(
            "user_message",
            block_id=str(item["block_id"]),
            text=str(item["text"]),
            source="remote",
            delivery=delivery,
        )

    # ------------------------------------------------------------- approvals

    async def permission_request(self, entry: SessionEntry, payload: dict[str, Any]) -> None:
        state = entry.shared
        if state is None:
            return
        channel_id = str(payload.get("request_id") or "")
        if not channel_id or channel_id in state.by_channel:
            return
        tool = str(payload.get("tool_name") or "Tool")
        description = str(payload.get("description") or "")
        preview = str(payload.get("input_preview") or "")
        approval = SharedApproval(
            request_id=str(uuid.uuid4()),
            channel_id=channel_id,
            block_id=str(uuid.uuid4()),
            tool=tool,
            title=approval_title(tool, preview, description, entry.session.cwd),
            input={"tool_name": tool, "description": description, "input_preview": preview},
        )
        state.approvals[approval.request_id] = approval
        state.by_channel[channel_id] = approval.request_id
        await self._emit_approval(entry, approval, status="pending")
        await self._settle(entry)

    async def approve(self, entry: SessionEntry, request_id: str, option_id: str) -> dict[str, Any]:
        state = entry.shared
        if state is None:
            raise RcError("conflict", "the session is no longer attached")
        if option_id not in BEHAVIORS:
            raise RcError("bad_request", "a shared session accepts only allow or deny")
        approval = state.approvals.get(request_id)
        if approval is None:
            # Answered in the terminal, or from another app first. Claude Code
            # drops unknown ids silently, so this is a no-op either way.
            return {}
        self._forget(state, request_id)
        await state.attachment.relay_permission(approval.channel_id, option_id)
        await self._resolve(entry, approval, option_id, "remote")
        return {}

    @staticmethod
    def _forget(state: SharedState, request_id: str) -> None:
        approval = state.approvals.pop(request_id, None)
        if approval is not None:
            state.by_channel.pop(approval.channel_id, None)

    async def _resolve(
        self, entry: SessionEntry, approval: SharedApproval, option_id: str, by: str
    ) -> None:
        await self._emit_approval(
            entry, approval, status="resolved", decision={"option_id": option_id, "by": by}
        )
        await self._settle(entry)

    async def _emit_approval(
        self,
        entry: SessionEntry,
        approval: SharedApproval,
        *,
        status: str,
        decision: dict[str, str] | None = None,
    ) -> None:
        fields: dict[str, Any] = {
            "block_id": approval.block_id,
            "request_id": approval.request_id,
            "tool": approval.tool,
            "tool_kind": tool_kind(approval.tool),
            "title": approval.title,
            "input": dict(approval.input),
            "options": [dict(option) for option in APPROVAL_OPTIONS],
            "status": status,
        }
        if decision is not None:
            fields["decision"] = decision
        await entry.channel.emit("approval", **fields)
