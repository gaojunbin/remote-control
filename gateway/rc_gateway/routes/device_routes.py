"""Device listing, pairing codes, enrollment and revocation."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse

from ..config import PAIRING_TTL_SECONDS
from ..logging import logger
from ..origins import websocket_url
from ..security import Credential, client_ip, require_user, state_of
from ..views import device_view
from .session_routes import _bounded_body

log = logger("rc_gateway.devices")
router = APIRouter()

ENROLL_BODY_MAX_BYTES = 16 * 1024
MAX_NAME_LENGTH = 64
MAX_FIELD_LENGTH = 128


@router.get("/api/devices")
async def list_devices(
    request: Request, credential: Credential = Depends(require_user)
) -> JSONResponse:
    state = state_of(request)
    records = await state.devices.list_for_user(credential.username)
    return JSONResponse(
        {
            "devices": [
                device_view(
                    record,
                    online=state.hub.device_online(record.device_id),
                    latency_ms=state.hub.latency_for(record.device_id),
                )
                for record in records
            ]
        },
        headers={"Cache-Control": "no-store"},
    )


@router.patch("/api/devices/{device_id}")
async def rename_device(
    device_id: str, request: Request, credential: Credential = Depends(require_user)
) -> JSONResponse:
    state = state_of(request)
    body = await _bounded_body(request, ENROLL_BODY_MAX_BYTES)
    name = _text(body.get("name"), MAX_NAME_LENGTH)
    if not name:
        raise HTTPException(status_code=400, detail={"code": "bad_request"})
    if not await state.devices.rename(device_id, credential.username, name):
        raise HTTPException(status_code=404, detail={"code": "not_found"})
    record = await state.devices.get(device_id)
    if record is None:
        raise HTTPException(status_code=404, detail={"code": "not_found"})
    device = device_view(
        record,
        online=state.hub.device_online(device_id),
        latency_ms=state.hub.latency_for(device_id),
    )
    await state.hub.broadcast_apps({"type": "device.updated", "device": device})
    return JSONResponse({"device": device}, headers={"Cache-Control": "no-store"})


@router.delete("/api/devices/{device_id}")
async def delete_device(
    device_id: str, request: Request, credential: Credential = Depends(require_user)
) -> JSONResponse:
    state = state_of(request)
    if not await state.devices.revoke(device_id, credential.username):
        raise HTTPException(status_code=404, detail={"code": "not_found"})
    await state.hub.disconnect_device(device_id, reason="device revoked")
    await state.hub.forget_device_sessions(device_id)
    await state.hub.broadcast_apps({"type": "device.removed", "device_id": device_id})
    log.info("device revoked", device_id=device_id)
    return JSONResponse({"ok": True}, headers={"Cache-Control": "no-store"})


@router.post("/api/devices/pairing")
async def create_pairing(
    request: Request, credential: Credential = Depends(require_user)
) -> JSONResponse:
    state = state_of(request)
    grant = await state.devices.create_pairing(credential.username, ttl=PAIRING_TTL_SECONDS)
    await state.hub.pairing_started(grant.code)
    command = f"curl -fsSL {state.config.public_origin}/install.sh | sh -s -- --pair {grant.code}"
    return JSONResponse(
        {
            "code": grant.code,
            "expires_at": grant.expires_at * 1000,
            "install": {"macos": command, "linux": command},
        },
        headers={"Cache-Control": "no-store"},
    )


@router.delete("/api/devices/pairing/{code}")
async def cancel_pairing(
    code: str, request: Request, credential: Credential = Depends(require_user)
) -> JSONResponse:
    state = state_of(request)
    removed = await state.devices.cancel_pairing(code, credential.username)
    await state.hub.pairing_cancelled(code)
    if not removed:
        raise HTTPException(status_code=404, detail={"code": "not_found"})
    return JSONResponse({"ok": True}, headers={"Cache-Control": "no-store"})


@router.post("/api/devices/enroll")
async def enroll(request: Request) -> JSONResponse:
    """Redeem a pairing code. The code is the credential, so no session is required."""
    state = state_of(request)
    address = client_ip(request, state)
    if state.enroll_limiter.limited(address):
        raise HTTPException(status_code=429, detail={"code": "too_many_requests"})
    body = await _bounded_body(request, ENROLL_BODY_MAX_BYTES)
    code = _text(body.get("code"), MAX_FIELD_LENGTH)
    name = _text(body.get("name"), MAX_NAME_LENGTH) or "device"
    if not code:
        raise HTTPException(status_code=400, detail={"code": "bad_request"})
    outcome = await state.devices.redeem(
        code,
        name=name,
        platform=_text(body.get("platform"), MAX_FIELD_LENGTH),
        hostname=_text(body.get("hostname"), MAX_FIELD_LENGTH),
        arch=_text(body.get("arch"), MAX_FIELD_LENGTH),
        client_version=_text(body.get("client_version"), MAX_FIELD_LENGTH),
        agents=_agents(body.get("agents")),
    )
    if outcome == "not_found":
        raise HTTPException(status_code=404, detail={"code": "not_found"})
    if outcome == "conflict":
        raise HTTPException(status_code=409, detail={"code": "conflict"})
    if isinstance(outcome, str):
        raise HTTPException(status_code=500, detail={"code": "internal"})

    log.info("device enrolled", device_id=outcome.device_id)
    await state.hub.pairing_enrolled(code, outcome.device_id)
    return JSONResponse(
        {
            "device_id": outcome.device_id,
            "device_token": outcome.token,
            "gateway_ws_url": websocket_url(state.config.public_origin, "/ws/device"),
        },
        headers={"Cache-Control": "no-store"},
    )


def _agents(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [item for item in value[:16] if isinstance(item, dict)]


def _text(value: Any, limit: int) -> str:
    if not isinstance(value, str):
        return ""
    cleaned = "".join(char for char in value if ord(char) >= 32 and ord(char) != 127).strip()
    return cleaned[:limit]
