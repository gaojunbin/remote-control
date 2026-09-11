"""``WS /ws/device`` — the socket a client daemon dials out to.

The device authenticates with its bearer token at the upgrade, before any frame is read. The
``device_id`` comes from the token lookup and never from the hello, so a device cannot claim to be
another one. The first frame must be a ``hello``; a second connection for the same device replaces
the first, which is closed with 4001 (amendment A4: 4401 for a bad credential, 1008 for a protocol
violation, 4001 only for replacement).
"""

from __future__ import annotations

import asyncio
import json
import time
from typing import Any

from fastapi import APIRouter, WebSocket
from starlette.websockets import WebSocketDisconnect

from ..auth import bearer_token
from ..connections import DeviceConnection
from ..frames import (
    CLOSE_PROTOCOL_ERROR,
    CLOSE_UNAUTHORIZED,
    DELTA_FLUSH_MS,
    MAX_EVENT_BYTES,
    frame_type,
)
from ..logging import logger
from ..security import accept_for_close, client_ip, state_of
from ..state import GatewayState

log = logger("rc_gateway.ws.device")
router = APIRouter()

HELLO_TIMEOUT_SECONDS = 15.0
BAD_HELLO_CLOSE_REASON = "first frame must be a hello"


@router.websocket("/ws/device")
async def device_socket(ws: WebSocket) -> None:
    state = state_of(ws)
    token = bearer_token(ws.headers.get("authorization"))
    record = await state.devices.device_for_token(token) if token else None
    if record is None:
        # Nothing above this line does work a caller can aim at: the token lookup is the
        # credential check itself, and everything else about the refusal is in memory.
        if _report_rejected(ws, state, "invalid token" if token else "no credential"):
            return
        # Accept first so the close code reaches the client: a refused handshake is an HTTP 403
        # and a device could not tell a revoked token from an unreachable gateway.
        if await accept_for_close(ws):
            await ws.close(code=CLOSE_UNAUTHORIZED, reason="unauthorized")
        return

    await ws.accept()
    connection = DeviceConnection(ws, record.device_id)
    connection.start()
    attached = False
    try:
        # The slot is claimed only once the hello parses: a client stuck in a reconnect loop
        # would otherwise close its own working connection with 4001 on every attempt.
        hello = await asyncio.wait_for(_receive(ws), timeout=HELLO_TIMEOUT_SECONDS)
        if hello is None or frame_type(hello) != "hello":
            await connection.stop(code=CLOSE_PROTOCOL_ERROR, reason=BAD_HELLO_CLOSE_REASON)
            return
        connection.note_frame()
        await state.hub.attach_device(connection)
        attached = True
        if not await state.hub.handle_device_hello(connection, hello):
            await connection.stop(code=CLOSE_PROTOCOL_ERROR, reason="unsupported protocol version")
            return
        await connection.send(
            {
                "type": "hello_ack",
                "device_id": record.device_id,
                "server_time": int(time.time() * 1000),
                "config": {
                    "delta_flush_ms": DELTA_FLUSH_MS,
                    "max_event_bytes": MAX_EVENT_BYTES,
                },
            }
        )
        while True:
            frame = await _receive(ws)
            if frame is None:
                continue
            connection.note_frame()
            await state.hub.handle_device_frame(connection, frame)
    except (TimeoutError, WebSocketDisconnect):
        pass
    except Exception:
        log.exception("device socket failed", device_id=record.device_id)
    finally:
        if attached:
            await state.hub.detach_device(connection)
        await connection.stop()


def _report_rejected(ws: WebSocket, state: GatewayState, reason: str) -> bool:
    """Warn about a refused upgrade. Returns True when the handshake should not be completed.

    A daemon whose machine was wiped retries forever and used to be refused silently, so nothing
    said where the attempts came from. One line per address per minute carries the count instead,
    and an address far past any sane retry backoff is refused before the handshake rather than
    accepted only to be closed. The credential is never part of the line.
    """
    address = client_ip(ws, state)
    outcome = state.device_rejects.record(address)
    if outcome.report is not None:
        log.warning(
            "device upgrade rejected",
            reason=reason,
            address=address,
            device_id=_claimed_device_id(ws) or "unknown",
            attempts=outcome.report,
        )
    return outcome.flooding


def _claimed_device_id(ws: WebSocket) -> str:
    """What the caller said it was. Unverified and only a hint for whoever reads the line."""
    claimed = ws.headers.get("x-device-id") or ws.query_params.get("device_id") or ""
    return "".join(char for char in claimed if char.isalnum() or char in "-_")[:64]


async def _receive(ws: WebSocket) -> dict[str, Any] | None:
    """Read one JSON object; malformed text is ignored rather than fatal."""
    raw = await ws.receive_text()
    try:
        frame = json.loads(raw)
    except ValueError:
        log.warning("malformed frame ignored", bytes=len(raw))
        return None
    return frame if isinstance(frame, dict) else None
