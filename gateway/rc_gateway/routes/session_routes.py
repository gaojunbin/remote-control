"""Login, logout, session introspection, health and client configuration."""

from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.responses import JSONResponse

from ..auth import (
    ADMIN_USERNAME,
    SESSION_COOKIE_NAME,
    authenticate_login,
    make_session_token,
    session_token_claims,
)
from ..config import SESSION_TTL_SECONDS
from ..frames import PROTOCOL_VERSION
from ..logging import logger
from ..security import Credential, client_ip, reject_foreign_origin, require_user, state_of
from ..state import VERSION

log = logger("rc_gateway.auth")
router = APIRouter()

LOGIN_BODY_MAX_BYTES = 4096


@router.get("/api/health")
async def health(request: Request) -> JSONResponse:
    state = state_of(request)
    return JSONResponse(
        {
            "ok": True,
            "version": VERSION,
            "protocol": PROTOCOL_VERSION,
            "auth": {"mode": "password"},
            "devices_online": len(state.hub.online_device_ids()),
        },
        headers={"Cache-Control": "no-store"},
    )


@router.post("/api/login")
async def login(request: Request) -> Response:
    state = state_of(request)
    reject_foreign_origin(request)
    address = client_ip(request, state)
    if state.login_limiter.limited(address):
        log.warning("login rate limited")
        raise HTTPException(status_code=429, detail={"code": "too_many_requests"})

    body = await _bounded_body(request, LOGIN_BODY_MAX_BYTES)
    username = str(body.get("username") or ADMIN_USERNAME)
    password = body.get("password")
    if not isinstance(password, str):
        raise HTTPException(status_code=400, detail={"code": "bad_request"})
    resolved = authenticate_login(username, password, state.config.password)
    if resolved is None:
        raise HTTPException(status_code=401, detail={"code": "unauthorized"})

    token, expires_at = make_session_token(
        state.config.secret, SESSION_TTL_SECONDS, username=resolved
    )
    claims = session_token_claims(token, state.config.secret)
    if claims is None:
        raise HTTPException(status_code=500, detail={"code": "internal"})
    await state.sessions.register(claims)
    log.info("login succeeded", username=resolved)
    response = JSONResponse(
        {"ok": True, "token": token, "exp": expires_at, "user": {"username": resolved}},
        headers={"Cache-Control": "no-store"},
    )
    response.set_cookie(
        SESSION_COOKIE_NAME,
        token,
        max_age=SESSION_TTL_SECONDS,
        httponly=True,
        samesite="strict",
        secure=state.config.secure_cookie,
        path="/",
    )
    return response


@router.post("/api/logout")
async def logout(request: Request, credential: Credential = Depends(require_user)) -> Response:
    state = state_of(request)
    await state.sessions.revoke(credential.claims.jti)
    await state.push_store.revoke_session(credential.claims.jti)
    response = JSONResponse({"ok": True}, headers={"Cache-Control": "no-store"})
    response.delete_cookie(SESSION_COOKIE_NAME, path="/")
    return response


@router.get("/api/session")
async def session(credential: Credential = Depends(require_user)) -> JSONResponse:
    return JSONResponse(
        {
            "ok": True,
            "user": {"username": credential.username},
            "exp": credential.claims.expires_at,
        },
        headers={"Cache-Control": "no-store"},
    )


@router.get("/api/config")
async def config(request: Request, _: Credential = Depends(require_user)) -> JSONResponse:
    return JSONResponse(state_of(request).config_view(), headers={"Cache-Control": "no-store"})


@router.get("/api/sessions")
async def sessions(
    request: Request,
    _: Credential = Depends(require_user),
    device_id: str | None = None,
    archived: bool | None = None,
) -> JSONResponse:
    state = state_of(request)
    return JSONResponse(
        {"sessions": await state.index.list_sessions(device_id=device_id, archived=archived)},
        headers={"Cache-Control": "no-store"},
    )


async def _bounded_body(request: Request, limit: int) -> dict[str, Any]:
    raw = b""
    async for chunk in request.stream():
        raw += chunk
        if len(raw) > limit:
            raise HTTPException(status_code=413, detail={"code": "too_large"})
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail={"code": "bad_request"}) from exc
    if not isinstance(parsed, dict):
        raise HTTPException(status_code=400, detail={"code": "bad_request"})
    return parsed
