"""``WS /ws/app`` — the socket the web UI and the iOS app hold open.

The gateway sends ``hello`` immediately so a cold app renders without an extra round trip, then
pushes device and session updates plus the events of every session this connection subscribed to.
A session revoked by ``POST /api/logout`` closes its sockets at once rather than at token expiry.
"""

from __future__ import annotations

import asyncio
import json
from typing import Any

from fastapi import APIRouter, WebSocket
from starlette.websockets import WebSocketDisconnect

from ..connections import AppConnection
from ..frames import CLOSE_UNAUTHORIZED
from ..logging import logger
from ..security import authenticate_websocket, state_of
from ..state import VERSION

log = logger("rc_gateway.ws.app")
router = APIRouter()


@router.websocket("/ws/app")
async def app_socket(ws: WebSocket) -> None:
    state = state_of(ws)
    credential = await authenticate_websocket(ws)
    if credential is None:
        return
    connection = AppConnection(ws, credential.username)
    connection.start()
    await state.hub.attach_app(connection)
    watchdog = asyncio.create_task(_watch_revocation(ws, connection, state, credential))
    try:
        await connection.send(
            await state.hub.app_hello_payload(credential.username, VERSION, state.stt_view())
        )
        while True:
            raw = await ws.receive_text()
            connection.note_frame()
            frame = _parse(raw)
            if frame is None:
                continue
            await state.hub.handle_app_frame(connection, frame)
    except WebSocketDisconnect:
        pass
    except Exception:
        log.exception("app socket failed", connection=connection.id)
    finally:
        watchdog.cancel()
        await state.hub.detach_app(connection)
        await connection.stop()


async def _watch_revocation(
    ws: WebSocket, connection: AppConnection, state: Any, credential: Any
) -> None:
    """Close the socket the moment its session is signed out."""
    event = await state.sessions.revoked_event(credential.claims)
    if event is None:
        # The jti disappeared between authentication and this first await, which is exactly what
        # a sign-out racing the handshake looks like. Closing is the only safe reading.
        log.info("closing socket for a session revoked mid-handshake", connection=connection.id)
        await connection.stop(code=CLOSE_UNAUTHORIZED, reason="session revoked")
        return
    try:
        await event.wait()
    except asyncio.CancelledError:
        return
    log.info("closing socket for a revoked session", connection=connection.id)
    await connection.stop(code=CLOSE_UNAUTHORIZED, reason="session revoked")


def _parse(raw: str) -> dict[str, Any] | None:
    try:
        frame = json.loads(raw)
    except ValueError:
        log.warning("malformed frame ignored", bytes=len(raw))
        return None
    return frame if isinstance(frame, dict) else None
