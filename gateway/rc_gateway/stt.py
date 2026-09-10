"""Speech-to-text through any OpenAI-compatible ``/audio/transcriptions`` endpoint.

The gateway never stores audio: a request's bytes live in memory for the length of one HTTP call.
Streaming transcription accumulates raw PCM and re-transcribes the whole utterance every couple of
seconds, which is what a whisper-style backend supports without a realtime protocol.
"""

from __future__ import annotations

import io
import struct
from dataclasses import dataclass
from typing import Protocol

import httpx

from .config import SttConfig
from .logging import logger

log = logger("rc_gateway.stt")

SAMPLE_RATE = 16000
CHANNELS = 1
BYTES_PER_SAMPLE = 2
MAX_UTTERANCE_SECONDS = 120
MAX_UTTERANCE_BYTES = 4 * 1024 * 1024
PARTIAL_INTERVAL_SECONDS = 2.0
REQUEST_TIMEOUT_SECONDS = 20.0


class SttError(RuntimeError):
    def __init__(self, message: str, code: str = "internal") -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class Transcript:
    text: str
    language: str


class Transcriber(Protocol):
    async def transcribe(
        self, audio: bytes, *, filename: str, content_type: str, language: str | None
    ) -> Transcript: ...


class OpenAiTranscriber:
    """Posts multipart audio to ``{base_url}/audio/transcriptions``."""

    def __init__(self, config: SttConfig, client: httpx.AsyncClient | None = None) -> None:
        self.config = config
        self._client = client
        self._owns_client = client is None

    async def transcribe(
        self, audio: bytes, *, filename: str, content_type: str, language: str | None
    ) -> Transcript:
        if not audio:
            raise SttError("no audio received", "bad_request")
        if self._client is None:
            self._client = httpx.AsyncClient(timeout=REQUEST_TIMEOUT_SECONDS, trust_env=False)
        data = {"model": self.config.model}
        if language and language != "auto":
            data["language"] = language
        headers = {"Authorization": f"Bearer {self.config.api_key}"} if self.config.api_key else {}
        try:
            response = await self._client.post(
                f"{self.config.base_url}/audio/transcriptions",
                files={"file": (filename, audio, content_type)},
                data=data,
                headers=headers,
                timeout=REQUEST_TIMEOUT_SECONDS,
            )
        except httpx.HTTPError as exc:
            log.warning("stt backend unreachable", error=type(exc).__name__)
            raise SttError("speech-to-text backend is unreachable") from exc
        if response.status_code >= 400:
            log.warning("stt backend rejected the request", status=response.status_code)
            raise SttError(f"speech-to-text backend returned {response.status_code}")
        try:
            payload = response.json()
        except ValueError as exc:
            raise SttError("speech-to-text backend returned a non-JSON body") from exc
        text = payload.get("text") if isinstance(payload, dict) else None
        detected = payload.get("language") if isinstance(payload, dict) else None
        return Transcript(
            text=text.strip() if isinstance(text, str) else "",
            language=detected if isinstance(detected, str) and detected else (language or "auto"),
        )

    async def close(self) -> None:
        if self._client is not None and self._owns_client:
            await self._client.aclose()
            self._client = None


def wav_from_pcm16(
    pcm: bytes, *, sample_rate: int = SAMPLE_RATE, channels: int = CHANNELS
) -> bytes:
    """Wrap raw little-endian 16-bit PCM in a RIFF/WAVE container."""
    byte_rate = sample_rate * channels * BYTES_PER_SAMPLE
    block_align = channels * BYTES_PER_SAMPLE
    buffer = io.BytesIO()
    buffer.write(b"RIFF")
    buffer.write(struct.pack("<I", 36 + len(pcm)))
    buffer.write(b"WAVEfmt ")
    buffer.write(struct.pack("<IHHIIHH", 16, 1, channels, sample_rate, byte_rate, block_align, 16))
    buffer.write(b"data")
    buffer.write(struct.pack("<I", len(pcm)))
    buffer.write(pcm)
    return buffer.getvalue()


def pcm_duration_seconds(pcm: bytes) -> float:
    return len(pcm) / (SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)


class Utterance:
    """Accumulates PCM frames under the protocol's 120 s / 4 MiB limits."""

    def __init__(
        self,
        *,
        max_seconds: int = MAX_UTTERANCE_SECONDS,
        max_bytes: int = MAX_UTTERANCE_BYTES,
    ) -> None:
        self.max_seconds = max_seconds
        self.max_bytes = max_bytes
        self._chunks: list[bytes] = []
        self._size = 0

    @property
    def size(self) -> int:
        return self._size

    @property
    def duration(self) -> float:
        return self._size / (SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)

    def append(self, chunk: bytes) -> None:
        if self._size + len(chunk) > self.max_bytes:
            raise SttError("utterance exceeds the 4 MiB limit", "too_large")
        self._chunks.append(chunk)
        self._size += len(chunk)
        if self.duration > self.max_seconds:
            raise SttError("utterance exceeds the 120 s limit", "too_large")

    def wav(self) -> bytes:
        return wav_from_pcm16(b"".join(self._chunks))
