"""The caller's own preferences (PROTOCOL 3.2, A35).

Only the caller's account is readable or writable: there is no path here that names a username, so
one person's switches cannot be read or flipped by another. A change is published the moment it is
stored — ``preferences.updated`` to the account's app sockets, ``preferences`` to its devices — so
a device acts on the new value without being asked and a second app never shows a stale switch.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse

from ..logging import logger
from ..preference_store import FIELDS, Preferences
from ..security import Credential, require_user, state_of
from .session_routes import _bounded_body

log = logger("rc_gateway.preferences")
router = APIRouter()

#: A body of booleans. Far above anything the wire defines and far below a payload worth spooling.
BODY_MAX_BYTES = 4096


@router.get("/api/preferences")
async def read_preferences(
    request: Request, credential: Credential = Depends(require_user)
) -> JSONResponse:
    state = state_of(request)
    return _response(await state.preference_store.get(credential.username))


@router.patch("/api/preferences")
async def patch_preferences(
    request: Request, credential: Credential = Depends(require_user)
) -> JSONResponse:
    state = state_of(request)
    body = await _bounded_body(request, BODY_MAX_BYTES)
    values = _changes(body)
    before = await state.preference_store.get(credential.username)
    after = await state.preference_store.patch(credential.username, values)
    if after != before:
        log.info("preferences changed", username=credential.username)
        await state.publish_preferences(credential.username, after)
    return _response(after)


def _changes(body: dict[str, Any]) -> dict[str, bool]:
    """The switches this body sets. Absent means unchanged; a non-boolean is a bad request.

    A field the gateway does not know is ignored rather than refused, as the protocol's preamble
    requires of every component reading a frame from a newer peer.
    """
    values: dict[str, bool] = {}
    for name in FIELDS:
        if name not in body:
            continue
        value = body[name]
        if not isinstance(value, bool):
            raise HTTPException(status_code=400, detail={"code": "bad_request"})
        values[name] = value
    return values


def _response(preferences: Preferences) -> JSONResponse:
    return JSONResponse({"preferences": preferences.view()}, headers={"Cache-Control": "no-store"})
