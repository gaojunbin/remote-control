"""The ``realtime`` speech backend: a live session over the OpenAI Realtime protocol."""

from __future__ import annotations

import asyncio
import base64
import io
import json
import threading
import wave
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient
from websockets.asyncio.server import ServerConnection, serve

from rc_gateway import stt_realtime
from rc_gateway.app import build_state, create_app
from rc_gateway.config import ConfigError, SttConfig, load_config
from rc_gateway.stt import SttError
from rc_gateway.stt_realtime import LiveTranscription, RealtimeTranscriber, join_segments

from .conftest import FakePolisher, FakeWebPushSender, make_config

SILENCE = b"\x00\x00" * 1600  # 100 ms of 16 kHz mono PCM16


class FakeRealtimeServer:
    """A vendor that speaks the documented Alibaba dialect, run on a loop of its own.

    Every ``input_audio_buffer.append`` is answered with a partial naming the bytes heard so
    far; ``input_audio_buffer.commit`` or ``session.finish`` completes the sentence and finishes
    the session. With ``fail`` it answers the first frame with an ``error`` event instead.
    """

    def __init__(self, *, fail: bool = False) -> None:
        self.fail = fail
        self.requests: list[dict[str, Any]] = []
        self.headers: dict[str, str] = {}
        self.path = ""
        self.port = 0
        self._ready = threading.Event()
        self._stop: asyncio.Event | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread = threading.Thread(target=self._run, daemon=True)

    @property
    def url(self) -> str:
        return f"ws://127.0.0.1:{self.port}/api-ws/v1/realtime?model=qwen3-asr-flash-realtime"

    def start(self) -> None:
        self._thread.start()
        assert self._ready.wait(5), "the fake vendor did not start"

    def stop(self) -> None:
        if self._loop is not None and self._stop is not None:
            self._loop.call_soon_threadsafe(self._stop.set)
        self._thread.join(5)

    def _run(self) -> None:
        asyncio.run(self._serve())

    async def _serve(self) -> None:
        self._loop = asyncio.get_running_loop()
        self._stop = asyncio.Event()
        async with serve(self._handle, "127.0.0.1", 0) as server:
            self.port = server.sockets[0].getsockname()[1]
            self._ready.set()
            await self._stop.wait()

    async def _handle(self, connection: ServerConnection) -> None:
        request = connection.request
        assert request is not None
        self.headers = {key.lower(): value for key, value in request.headers.items()}
        self.path = request.path
        heard = 0
        await connection.send(json.dumps({"type": "session.created", "session": {"id": "s1"}}))
        async for raw in connection:
            event = json.loads(raw)
            self.requests.append(event)
            kind = event["type"]
            if kind == "session.update":
                await connection.send(json.dumps({"type": "session.updated"}))
            elif kind == "input_audio_buffer.append":
                if self.fail:
                    await connection.send(
                        json.dumps({"type": "error", "error": {"message": "quota exhausted"}})
                    )
                    continue
                heard += len(base64.b64decode(event["audio"]))
                await connection.send(
                    json.dumps(
                        {
                            "type": "conversation.item.input_audio_transcription.text",
                            "text": f"heard {heard}",
                            "stash": " bytes",
                        }
                    )
                )
            elif kind == "input_audio_buffer.commit":
                await connection.send(
                    json.dumps(
                        {
                            "type": "conversation.item.input_audio_transcription.completed",
                            "transcript": f"final {heard} bytes.",
                        }
                    )
                )
            elif kind == "session.finish":
                await connection.send(json.dumps({"type": "session.finished"}))


@pytest.fixture
def vendor() -> Iterator[FakeRealtimeServer]:
    server = FakeRealtimeServer()
    server.start()
    yield server
    server.stop()


@pytest.fixture
def failing_vendor() -> Iterator[FakeRealtimeServer]:
    server = FakeRealtimeServer(fail=True)
    server.start()
    yield server
    server.stop()


def realtime_config(url: str) -> SttConfig:
    return SttConfig(
        provider="realtime",
        base_url="",
        api_key="sk-live",
        model="qwen3-asr-flash-realtime",
        languages=("auto", "zh", "en"),
        realtime_url=url,
    )


@pytest.fixture
def realtime_client(tmp_path: Path, vendor: FakeRealtimeServer) -> Iterator[TestClient]:
    config = make_config(tmp_path, stt=realtime_config(vendor.url))
    state = build_state(
        config,
        transcriber=RealtimeTranscriber(config.stt),
        polisher=FakePolisher(),
        web_sender=FakeWebPushSender(),
    )
    with TestClient(create_app(state)) as client:
        yield client


# ------------------------------------------------------------------- the session


async def test_a_live_session_streams_partials_and_returns_the_final(
    vendor: FakeRealtimeServer,
) -> None:
    partials: list[str] = []

    async def on_partial(text: str) -> None:
        partials.append(text)

    session = LiveTranscription(vendor.url, "sk-live", "zh", on_partial)
    await session.open()
    try:
        await session.append(SILENCE)
        await session.append(SILENCE)
        text = await session.finish()
    finally:
        await session.close()

    assert text == f"final {2 * len(SILENCE)} bytes."
    # Partials carry the tentative tail too, and the final replaces them.
    assert partials[0] == f"heard {len(SILENCE)} bytes"
    assert partials[-1] == text
    assert vendor.headers["authorization"] == "Bearer sk-live"
    assert vendor.headers["openai-beta"] == "realtime=v1"
    assert vendor.path.endswith("?model=qwen3-asr-flash-realtime")
    update = vendor.requests[0]
    assert update["type"] == "session.update"
    assert update["session"]["input_audio_format"] == "pcm"
    assert update["session"]["sample_rate"] == 16000
    assert update["session"]["turn_detection"]["type"] == "server_vad"
    assert update["session"]["input_audio_transcription"] == {"language": "zh"}
    assert [event["type"] for event in vendor.requests[-2:]] == [
        "input_audio_buffer.commit",
        "session.finish",
    ]


async def test_auto_leaves_the_language_to_the_vendor(vendor: FakeRealtimeServer) -> None:
    async def ignore(_: str) -> None:
        return None

    session = LiveTranscription(vendor.url, "", "auto", ignore)
    await session.open()
    try:
        await session.append(SILENCE)
        await session.finish()
    finally:
        await session.close()
    assert "input_audio_transcription" not in vendor.requests[0]["session"]
    assert "authorization" not in vendor.headers


async def test_a_vendor_error_surfaces_as_an_stt_error(failing_vendor: FakeRealtimeServer) -> None:
    async def ignore(_: str) -> None:
        return None

    session = LiveTranscription(failing_vendor.url, "sk", None, ignore)
    await session.open()
    try:
        await session.append(SILENCE)
        with pytest.raises(SttError, match="quota exhausted"):
            for _ in range(50):
                await asyncio.sleep(0.02)
                await session.append(SILENCE)
    finally:
        await session.close()


async def test_an_unreachable_vendor_is_an_stt_error() -> None:
    async def ignore(_: str) -> None:
        return None

    session = LiveTranscription("ws://127.0.0.1:9/realtime", "sk", None, ignore)
    with pytest.raises(SttError, match="unreachable"):
        await session.open()


def test_segments_join_without_spaces_between_chinese_and_with_them_between_english() -> None:
    assert join_segments(["今天天气很好。", "我们去公园吧"]) == "今天天气很好。我们去公园吧"
    assert join_segments(["Hello there.", "How are you"]) == "Hello there. How are you"
    assert join_segments(["打开 VS Code", "然后运行测试"]) == "打开 VS Code然后运行测试"
    assert join_segments(["", "  ", "one"]) == "one"


# ---------------------------------------------------------------- through the app


def test_the_live_socket_streams_words_and_ends_on_stop(
    realtime_client: TestClient, auth: dict[str, str], vendor: FakeRealtimeServer
) -> None:
    with realtime_client.websocket_connect("/ws/stt?language=zh", headers=auth) as socket:
        socket.send_bytes(SILENCE)
        first = socket.receive_json()
        socket.send_bytes(SILENCE)
        second = socket.receive_json()
        socket.send_json({"type": "stt.stop"})
        frames = [socket.receive_json()]
        while frames[-1]["type"] != "stt.final":
            frames.append(socket.receive_json())
    assert first == {"type": "stt.partial", "text": f"heard {len(SILENCE)} bytes"}
    assert second == {"type": "stt.partial", "text": f"heard {2 * len(SILENCE)} bytes"}
    assert frames[-1] == {
        "type": "stt.final",
        "text": f"final {2 * len(SILENCE)} bytes.",
        "language": "zh",
    }


def test_a_stop_before_any_audio_is_an_empty_final(
    realtime_client: TestClient, auth: dict[str, str]
) -> None:
    with realtime_client.websocket_connect("/ws/stt", headers=auth) as socket:
        socket.send_json({"type": "stt.stop"})
        final = socket.receive_json()
    assert final == {"type": "stt.final", "text": "", "language": "auto"}


def test_the_live_socket_reports_the_vendor_refusing(
    tmp_path: Path, failing_vendor: FakeRealtimeServer, auth: dict[str, str]
) -> None:
    config = make_config(tmp_path, stt=realtime_config(failing_vendor.url))
    state = build_state(
        config,
        transcriber=RealtimeTranscriber(config.stt),
        polisher=FakePolisher(),
        web_sender=FakeWebPushSender(),
    )
    with (
        TestClient(create_app(state)) as client,
        client.websocket_connect("/ws/stt", headers=auth) as socket,
    ):
        frame: dict[str, Any] = {}
        for _ in range(50):
            socket.send_bytes(SILENCE)
            try:
                frame = socket.receive_json()
            except Exception:
                break
            if frame.get("type") == "stt.error":
                break
    assert frame["type"] == "stt.error"
    assert "quota exhausted" in frame["message"]


def test_a_recorded_wav_is_played_into_a_live_session(
    realtime_client: TestClient, auth: dict[str, str]
) -> None:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as sink:
        sink.setnchannels(1)
        sink.setsampwidth(2)
        sink.setframerate(16000)
        sink.writeframes(SILENCE * 3)
    response = realtime_client.post(
        "/api/stt/transcribe",
        files={"audio": ("clip.wav", buffer.getvalue(), "audio/wav")},
        data={"language": "en"},
        headers=auth,
    )
    assert response.status_code == 200, response.text
    assert response.json() == {"text": f"final {3 * len(SILENCE)} bytes.", "language": "en"}


def test_a_compressed_upload_is_refused_by_the_live_backend(
    realtime_client: TestClient, auth: dict[str, str]
) -> None:
    response = realtime_client.post(
        "/api/stt/transcribe",
        files={"audio": ("clip.m4a", b"\x00\x00\x00\x18ftypM4A ", "audio/mp4")},
        headers=auth,
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "bad_request"


def test_a_wav_at_another_rate_is_refused() -> None:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as sink:
        sink.setnchannels(1)
        sink.setsampwidth(2)
        sink.setframerate(44100)
        sink.writeframes(SILENCE)
    with pytest.raises(SttError, match="16 kHz"):
        stt_realtime.pcm_from_upload(buffer.getvalue(), "audio/wav")


# ------------------------------------------------------------------- the config


def test_the_realtime_provider_needs_a_socket_url(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("DATA_DIR", str(tmp_path))
    monkeypatch.setenv("PUBLIC_ORIGIN", "https://rc.example.com")
    monkeypatch.setenv("RC_PASSWORD", "hunter2hunter2")
    monkeypatch.delenv("WEB_PUSH_CONTACT", raising=False)
    monkeypatch.setenv("STT_PROVIDER", "realtime")
    monkeypatch.delenv("STT_REALTIME_URL", raising=False)
    with pytest.raises(ConfigError, match="STT_REALTIME_URL"):
        load_config(load_env_file=False)


def test_the_realtime_provider_carries_the_model_in_its_url(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("DATA_DIR", str(tmp_path))
    monkeypatch.setenv("PUBLIC_ORIGIN", "https://rc.example.com")
    monkeypatch.setenv("RC_PASSWORD", "hunter2hunter2")
    monkeypatch.delenv("WEB_PUSH_CONTACT", raising=False)
    monkeypatch.setenv("STT_PROVIDER", "realtime")
    monkeypatch.setenv("STT_MODEL", "qwen3-asr-flash-realtime")
    monkeypatch.setenv(
        "STT_REALTIME_URL", "wss://ws.example.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime"
    )
    config = load_config(load_env_file=False)
    assert config.stt.enabled
    assert config.stt.realtime_url.endswith("/api-ws/v1/realtime?model=qwen3-asr-flash-realtime")
    monkeypatch.setenv("STT_REALTIME_URL", "wss://ws.example/api-ws/v1/realtime?model=other")
    assert load_config(load_env_file=False).stt.realtime_url.endswith("?model=other")
