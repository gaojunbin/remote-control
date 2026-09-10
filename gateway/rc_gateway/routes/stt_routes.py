"""One-shot transcription of an uploaded audio file.

`BoundedUploads` authenticates and bounds this path ahead of routing. The handler declares no body
parameters as a second line of defence: FastAPI parses a declared form *before* it solves
dependencies, so with a body parameter the route's own `Depends(require_user)` would run only after
the upload had been read. With none, the dependency runs first and the form is read explicitly.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from starlette.datastructures import UploadFile

from ..logging import logger
from ..security import Credential, require_user, state_of
from ..stt import SttError

log = logger("rc_gateway.stt")
router = APIRouter()

#: The path `BoundedUploads` guards; keep the two in step.
UPLOAD_PATH = "/api/stt/transcribe"
MAX_UPLOAD_BYTES = 25 * 1024 * 1024
_CONTENT_TYPES = {
    "wav": "audio/wav",
    "webm": "audio/webm",
    "m4a": "audio/mp4",
    "mp3": "audio/mpeg",
}


@router.post(UPLOAD_PATH)
async def transcribe(request: Request, _: Credential = Depends(require_user)) -> JSONResponse:
    state = state_of(request)
    if state.transcriber is None:
        raise HTTPException(status_code=503, detail={"code": "unsupported"})
    form = await request.form()
    try:
        # Starlette's parser yields its own UploadFile, which fastapi.UploadFile subclasses;
        # check against the base or a genuine upload reads as a plain form field.
        audio = form.get("audio")
        if not isinstance(audio, UploadFile):
            raise HTTPException(status_code=400, detail={"code": "bad_request"})
        language = resolve_language(state.config.stt.languages, form.get("language"))
        payload = await audio.read(MAX_UPLOAD_BYTES + 1)
        if len(payload) > MAX_UPLOAD_BYTES:
            raise HTTPException(status_code=413, detail={"code": "too_large"})
        if not payload:
            raise HTTPException(status_code=400, detail={"code": "bad_request"})
        filename = audio.filename or "audio.wav"
        content_type = audio.content_type or _guess_type(filename)
    finally:
        await form.close()
    try:
        transcript = await state.transcriber.transcribe(
            payload, filename=filename, content_type=content_type, language=language
        )
    except SttError as exc:
        status = 400 if exc.code == "bad_request" else 502
        raise HTTPException(status_code=status, detail={"code": exc.code}) from exc
    return JSONResponse(
        {"text": transcript.text, "language": transcript.language},
        headers={"Cache-Control": "no-store"},
    )


def resolve_language(allowed: tuple[str, ...], requested: object) -> str | None:
    """Accept only a language the gateway advertises in ``/api/config``.

    The value is forwarded to the speech backend, so it is never taken on trust: an unbounded or
    unexpected string would reach a third-party API as-is.
    """
    if requested is None or requested == "":
        return None
    if not isinstance(requested, str) or requested not in allowed:
        raise HTTPException(status_code=400, detail={"code": "bad_request"})
    return requested


def _guess_type(filename: str) -> str:
    suffix = filename.rsplit(".", 1)[-1].lower()
    return _CONTENT_TYPES.get(suffix, "application/octet-stream")
