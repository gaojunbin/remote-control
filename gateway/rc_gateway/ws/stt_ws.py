"""``WS /ws/stt`` — live transcription of PCM16 audio.

Binary frames carry raw little-endian 16-bit PCM at 16 kHz mono. The gateway wraps the accumulated
buffer as WAV and asks the backend for a transcript roughly every two seconds while audio keeps
arriving, skipping a partial whenever a request is still in flight, and produces the final
transcript on ``stt.stop``. Audio is never written to disk.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import time
from typing import Any

from fastapi import APIRouter, WebSocket
from starlette.websockets import WebSocketDisconnect

from ..logging import logger
from ..security import authenticate_websocket, state_of
from ..stt import PARTIAL_INTERVAL_SECONDS, SttError, Transcriber, Utterance

log = logger("rc_gateway.ws.stt")
router = APIRouter()


@router.websocket("/ws/stt")
async def stt_socket(ws: WebSocket, language: str | None = None) -> None:
    state = state_of(ws)
    if await authenticate_websocket(ws) is None:
        return
    if state.transcriber is None:
        await _fail(ws, "speech-to-text is not configured", "unsupported")
        return
    if language and language not in state.config.stt.languages:
        await _fail(ws, "unsupported language", "bad_request")
        return

    session = _Stream(ws, state.transcriber, language)
    try:
        await session.run()
    except WebSocketDisconnect:
        pass
    except Exception:
        log.exception("stt socket failed")
    finally:
        await session.close()


class _Stream:
    def __init__(self, ws: WebSocket, transcriber: Transcriber, language: str | None) -> None:
        self.ws = ws
        self.transcriber = transcriber
        self.language = language
        self.utterance = Utterance()
        self._partial: asyncio.Task[None] | None = None
        # The first partial is due one interval in, not on the first 100 ms chunk: transcribing
        # a fifth of a second costs a backend round trip and tells the user nothing.
        self._last_partial = time.monotonic()
        self._closed = False

    async def run(self) -> None:
        while True:
            message = await self.ws.receive()
            kind = message.get("type")
            if kind == "websocket.disconnect":
                return
            if (payload := message.get("bytes")) is not None:
                if not await self._append(payload):
                    return
                await self._maybe_partial()
                continue
            text = message.get("text")
            if text is None:
                continue
            command = _command(text)
            if command == "stt.stop":
                await self._finalize()
                return
            if command == "stt.cancel":
                return

    async def _append(self, payload: bytes) -> bool:
        try:
            self.utterance.append(payload)
        except SttError as exc:
            await _fail(self.ws, str(exc), exc.code)
            return False
        return True

    async def _maybe_partial(self) -> None:
        busy = self._partial is not None and not self._partial.done()
        if busy or time.monotonic() - self._last_partial < PARTIAL_INTERVAL_SECONDS:
            return
        self._last_partial = time.monotonic()
        self._partial = asyncio.create_task(self._send_partial(self.utterance.wav()))

    async def _send_partial(self, wav: bytes) -> None:
        try:
            transcript = await self.transcriber.transcribe(
                wav, filename="audio.wav", content_type="audio/wav", language=self.language
            )
        except SttError:
            return
        if not self._closed and transcript.text:
            await _send(self.ws, {"type": "stt.partial", "text": transcript.text})

    async def _finalize(self) -> None:
        await self._cancel_partial()
        if self.utterance.size == 0:
            await _send(
                self.ws,
                {"type": "stt.final", "text": "", "language": self.language or "auto"},
            )
            await _close(self.ws)
            return
        try:
            transcript = await self.transcriber.transcribe(
                self.utterance.wav(),
                filename="audio.wav",
                content_type="audio/wav",
                language=self.language,
            )
        except SttError as exc:
            await _fail(self.ws, str(exc), exc.code)
            return
        await _send(
            self.ws,
            {"type": "stt.final", "text": transcript.text, "language": transcript.language},
        )
        await _close(self.ws)

    async def _cancel_partial(self) -> None:
        if self._partial is not None and not self._partial.done():
            self._partial.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._partial
        self._partial = None

    async def close(self) -> None:
        self._closed = True
        await self._cancel_partial()


def _command(text: str) -> str:
    try:
        frame = json.loads(text)
    except ValueError:
        return ""
    return str(frame.get("type", "")) if isinstance(frame, dict) else ""


async def _send(ws: WebSocket, frame: dict[str, Any]) -> None:
    with contextlib.suppress(RuntimeError, WebSocketDisconnect):
        await ws.send_text(json.dumps(frame, ensure_ascii=False, separators=(",", ":")))


async def _fail(ws: WebSocket, message: str, code: str = "internal") -> None:
    await _send(ws, {"type": "stt.error", "message": message, "code": code})
    await _close(ws)


async def _close(ws: WebSocket) -> None:
    with contextlib.suppress(RuntimeError, WebSocketDisconnect):
        await ws.close()
