"""Transcription over HTTP and the live PCM socket."""

from __future__ import annotations

import struct
from pathlib import Path
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from rc_gateway.app import build_state, create_app
from rc_gateway.config import SttConfig
from rc_gateway.stt import OpenAiTranscriber, SttError, Utterance, wav_from_pcm16

from .conftest import FakeTranscriber, make_config

SILENCE = struct.pack("<h", 0) * 1600  # 100 ms at 16 kHz


def test_wav_header_describes_16k_mono_pcm() -> None:
    wav = wav_from_pcm16(SILENCE)
    assert wav[:4] == b"RIFF" and wav[8:12] == b"WAVE"
    channels, sample_rate = struct.unpack("<H", wav[22:24])[0], struct.unpack("<I", wav[24:28])[0]
    assert channels == 1
    assert sample_rate == 16000
    assert struct.unpack("<H", wav[34:36])[0] == 16
    assert wav[44:] == SILENCE


def test_utterance_enforces_its_bounds() -> None:
    utterance = Utterance(max_seconds=1, max_bytes=1_000_000)
    with pytest.raises(SttError) as seconds:
        for _ in range(20):
            utterance.append(SILENCE)
    assert seconds.value.code == "too_large"

    small = Utterance(max_seconds=120, max_bytes=1000)
    with pytest.raises(SttError) as size:
        small.append(b"\x00" * 2000)
    assert size.value.code == "too_large"


def test_transcribe_uploads_the_file(
    client: TestClient, auth: dict[str, str], transcriber: FakeTranscriber
) -> None:
    response = client.post(
        "/api/stt/transcribe",
        files={"audio": ("note.wav", wav_from_pcm16(SILENCE), "audio/wav")},
        data={"language": "en"},
        headers=auth,
    )
    assert response.status_code == 200
    assert response.json() == {"text": "hello world", "language": "en"}
    assert transcriber.calls[0]["language"] == "en"
    assert transcriber.calls[0]["filename"] == "note.wav"


def test_transcribe_reports_a_backend_failure(
    client: TestClient, auth: dict[str, str], transcriber: FakeTranscriber
) -> None:
    transcriber.fail = "backend down"
    response = client.post(
        "/api/stt/transcribe",
        files={"audio": ("note.wav", wav_from_pcm16(SILENCE), "audio/wav")},
        headers=auth,
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "internal"


def test_streaming_socket_produces_a_final_transcript(
    client: TestClient, auth: dict[str, str], transcriber: FakeTranscriber
) -> None:
    with client.websocket_connect("/ws/stt?language=en", headers=auth) as socket:
        socket.send_bytes(SILENCE)
        socket.send_bytes(SILENCE)
        socket.send_json({"type": "stt.stop"})
        final = socket.receive_json()
    assert final == {"type": "stt.final", "text": "hello world", "language": "en"}
    assert transcriber.calls[-1]["bytes"] == 44 + 2 * len(SILENCE)
    assert transcriber.calls[-1]["content_type"] == "audio/wav"


def test_partials_arrive_while_audio_keeps_coming(
    client: TestClient, auth: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("rc_gateway.ws.stt_ws.PARTIAL_INTERVAL_SECONDS", 0.0)
    with client.websocket_connect("/ws/stt?language=en", headers=auth) as socket:
        socket.send_bytes(SILENCE)
        partial = socket.receive_json()
        socket.send_json({"type": "stt.stop"})
        final = socket.receive_json()
    assert partial == {"type": "stt.partial", "text": "hello world"}
    assert final["type"] == "stt.final"


def test_streaming_socket_reports_backend_errors(
    client: TestClient, auth: dict[str, str], transcriber: FakeTranscriber
) -> None:
    transcriber.fail = "backend down"
    with client.websocket_connect("/ws/stt", headers=auth) as socket:
        socket.send_bytes(SILENCE)
        socket.send_json({"type": "stt.stop"})
        error = socket.receive_json()
    assert error["type"] == "stt.error"
    assert error["message"]


def test_cancel_produces_nothing(client: TestClient, auth: dict[str, str]) -> None:
    with client.websocket_connect("/ws/stt", headers=auth) as socket:
        socket.send_bytes(SILENCE)
        socket.send_json({"type": "stt.cancel"})


def test_stt_disabled_returns_unsupported(tmp_path: Path) -> None:
    config = make_config(
        tmp_path,
        stt=SttConfig(
            provider="none",
            base_url="",
            api_key="",
            model="whisper-1",
            languages=("auto", "zh", "en"),
        ),
    )
    with TestClient(create_app(build_state(config))) as offline:
        token = offline.post(
            "/api/login",
            json={"password": config.password},
            headers={"Origin": config.public_origin},
        ).json()["token"]
        headers = {"Authorization": f"Bearer {token}"}
        assert offline.get("/api/config", headers=headers).json()["stt"]["enabled"] is False
        response = offline.post(
            "/api/stt/transcribe",
            files={"audio": ("note.wav", b"RIFF", "audio/wav")},
            headers=headers,
        )
        assert response.status_code == 503
        assert response.json()["error"]["code"] == "unsupported"
        with offline.websocket_connect("/ws/stt", headers=headers) as socket:
            error = socket.receive_json()
        assert error == {
            "type": "stt.error",
            "message": "speech-to-text is not configured",
            "code": "unsupported",
        }


@pytest.mark.asyncio
async def test_openai_transcriber_posts_multipart() -> None:
    seen: dict[str, str] = {}
    body_seen: dict[str, bytes] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        body_seen["body"] = request.content
        return httpx.Response(200, json={"text": "  transcribed  ", "language": "zh"})

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport) as http:
        transcriber = OpenAiTranscriber(
            SttConfig(
                provider="openai",
                base_url="https://stt.example/v1",
                api_key="secret",
                model="whisper-1",
                languages=("auto",),
            ),
            client=http,
        )
        result = await transcriber.transcribe(
            wav_from_pcm16(SILENCE), filename="a.wav", content_type="audio/wav", language="zh"
        )
    assert result.text == "transcribed"
    assert result.language == "zh"
    assert seen["url"] == "https://stt.example/v1/audio/transcriptions"
    body = body_seen["body"]
    assert b'name="file"' in body
    assert b'name="model"' in body
    assert b"whisper-1" in body
    assert b'name="language"' in body


@pytest.mark.asyncio
async def test_auto_language_is_not_forwarded() -> None:
    seen: dict[str, bytes] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = request.content
        return httpx.Response(200, json={"text": "ok"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        transcriber = OpenAiTranscriber(
            SttConfig(
                provider="openai",
                base_url="https://stt.example/v1",
                api_key="",
                model="whisper-1",
                languages=("auto",),
            ),
            client=http,
        )
        result = await transcriber.transcribe(
            b"RIFFdata", filename="a.wav", content_type="audio/wav", language="auto"
        )
    assert result.language == "auto"
    assert b'name="language"' not in seen["body"]


@pytest.mark.asyncio
async def test_backend_errors_become_stt_errors() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, json={"error": "boom"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        transcriber = OpenAiTranscriber(
            SttConfig(
                provider="openai",
                base_url="https://stt.example/v1",
                api_key="",
                model="whisper-1",
                languages=("auto",),
            ),
            client=http,
        )
        with pytest.raises(SttError):
            await transcriber.transcribe(
                b"RIFFdata", filename="a.wav", content_type="audio/wav", language=None
            )


def test_an_unauthenticated_upload_is_refused_before_the_body_is_read(
    client: TestClient, transcriber: FakeTranscriber
) -> None:
    """The guard runs ahead of routing, so no anonymous caller can spool a file to disk."""
    response = client.post(
        "/api/stt/transcribe",
        files={"audio": ("note.wav", b"RIFF" + b"\0" * 4096, "audio/wav")},
    )
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "unauthorized"
    assert transcriber.calls == []


def test_an_oversized_upload_is_refused_by_content_length(
    client: TestClient, auth: dict[str, str], transcriber: FakeTranscriber
) -> None:
    from rc_gateway.routes.stt_routes import MAX_UPLOAD_BYTES

    response = client.post(
        "/api/stt/transcribe",
        content=b"x" * 16,
        headers={
            **auth,
            "Content-Type": "multipart/form-data; boundary=x",
            "Content-Length": str(MAX_UPLOAD_BYTES + 1),
        },
    )
    assert response.status_code == 413
    assert response.json()["error"]["code"] == "too_large"
    assert transcriber.calls == []


def test_an_unadvertised_language_is_refused(
    client: TestClient, auth: dict[str, str], transcriber: FakeTranscriber
) -> None:
    response = client.post(
        "/api/stt/transcribe",
        files={"audio": ("note.wav", wav_from_pcm16(SILENCE), "audio/wav")},
        data={"language": "kl"},
        headers=auth,
    )
    assert response.status_code == 400
    assert transcriber.calls == []
    with client.websocket_connect("/ws/stt?language=kl", headers=auth) as socket:
        error = socket.receive_json()
    assert error["type"] == "stt.error"
    assert error["code"] == "bad_request"


def test_a_missing_audio_part_is_a_bad_request(client: TestClient, auth: dict[str, str]) -> None:
    response = client.post("/api/stt/transcribe", data={"language": "en"}, headers=auth)
    assert response.status_code == 400


@pytest.mark.asyncio
async def test_the_guard_stops_a_chunked_body_at_the_cap() -> None:
    """Without a Content-Length the cap has to hold while the body streams."""
    from rc_gateway.uploads import _counted

    chunks = [
        {"type": "http.request", "body": b"a" * 40, "more_body": True},
        {"type": "http.request", "body": b"b" * 40, "more_body": True},
        {"type": "http.request", "body": b"c" * 40, "more_body": False},
    ]

    async def receive() -> Any:
        return chunks.pop(0)

    counted = _counted(receive, 60)
    assert (await counted())["body"] == b"a" * 40
    stopped = await counted()
    assert stopped["body"] == b""
    assert stopped["more_body"] is False
