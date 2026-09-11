"""Test fixtures: a complete gateway on a temporary directory with injected senders."""

from __future__ import annotations

import json
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from rc_gateway.app import build_state, create_app
from rc_gateway.config import ApnsConfig, Config, SttConfig
from rc_gateway.connections import AppConnection, Connection, DeviceConnection
from rc_gateway.devices import DeviceStore
from rc_gateway.frames import REQUEST_TIMEOUT_SECONDS
from rc_gateway.hub import OFFLINE_GRACE_SECONDS, Hub, TransitionHook
from rc_gateway.index import SessionIndex
from rc_gateway.push_store import WebPushSubscription
from rc_gateway.state import GatewayState
from rc_gateway.stt import SttError, Transcript

ORIGIN = "http://testserver"
PASSWORD = "correct-horse-battery-staple"
FIXTURE_DIR = Path(__file__).resolve().parents[2] / "protocol" / "fixtures"


class FakeTranscriber:
    """Returns a canned transcript and records what it was asked to transcribe."""

    def __init__(self, text: str = "hello world", language: str = "en") -> None:
        self.text = text
        self.language = language
        self.calls: list[dict[str, Any]] = []
        self.fail: str | None = None

    async def transcribe(
        self, audio: bytes, *, filename: str, content_type: str, language: str | None
    ) -> Transcript:
        self.calls.append(
            {
                "bytes": len(audio),
                "filename": filename,
                "content_type": content_type,
                "language": language,
            }
        )
        if self.fail is not None:
            raise SttError(self.fail)
        return Transcript(text=self.text, language=self.language)


class FakeWebPushSender:
    def __init__(self) -> None:
        self.sent: list[tuple[str, dict[str, Any]]] = []
        self.status: int | None = 201

    async def __call__(self, subscription: WebPushSubscription, payload: str) -> int | None:
        self.sent.append((subscription.endpoint, json.loads(payload)))
        return self.status


def make_config(tmp_path: Path, **overrides: Any) -> Config:
    values: dict[str, Any] = {
        "public_origin": ORIGIN,
        "password": PASSWORD,
        "secret": "test-secret-value-that-is-long-enough",
        "data_dir": tmp_path,
        "host": "127.0.0.1",
        "port": 8787,
        "log_level": "warning",
        "web_dist_dir": tmp_path / "web-dist",
        "client_install_script": tmp_path / "install.sh",
        "client_dist_dir": tmp_path / "client-dist",
        "web_push_contact": "mailto:admin@example.com",
        "stt": SttConfig(
            provider="openai",
            base_url="http://stt.invalid/v1",
            api_key="k",
            model="whisper-1",
            languages=("auto", "zh", "en"),
        ),
        "apns": ApnsConfig(team_id="", key_id="", key_path="", topic="", environment="production"),
        "vapid_private_pem": tmp_path / "vapid_private.pem",
        "vapid_public_key": "BJ" + "A" * 84,
    }
    values.update(overrides)
    return Config(**values)


@pytest.fixture
def transcriber() -> FakeTranscriber:
    return FakeTranscriber()


@pytest.fixture
def web_sender() -> FakeWebPushSender:
    return FakeWebPushSender()


@pytest.fixture
def state(
    tmp_path: Path, transcriber: FakeTranscriber, web_sender: FakeWebPushSender
) -> GatewayState:
    built = build_state(make_config(tmp_path), transcriber=transcriber, web_sender=web_sender)
    built.hub = Hub(
        built.index,
        built.devices,
        on_session_transition=built.push.on_session_transition,
        request_timeout=0.4,
        # Short enough to drive the A13 grace period in a test, and well inside the request
        # timeout above so a parked request is still answered `device_offline` rather than
        # `timeout` when the period ends without a reconnect.
        offline_grace=0.2,
    )
    return built


@pytest.fixture
def client(state: GatewayState) -> Iterator[TestClient]:
    with TestClient(create_app(state)) as test_client:
        yield test_client


@pytest.fixture
def token(client: TestClient) -> str:
    response = client.post("/api/login", json={"password": PASSWORD}, headers={"Origin": ORIGIN})
    assert response.status_code == 200, response.text
    value: str = response.json()["token"]
    return value


@pytest.fixture
def auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def enroll_device(
    client: TestClient, auth: dict[str, str], name: str = "mac-studio"
) -> dict[str, Any]:
    """Mint a pairing code and redeem it, returning the enrollment response plus the code."""
    minted = client.post("/api/devices/pairing", headers=auth)
    assert minted.status_code == 200, minted.text
    code = minted.json()["code"]
    enrolled = client.post(
        "/api/devices/enroll",
        json={
            "code": code,
            "name": name,
            "platform": "macos",
            "hostname": "studio.local",
            "arch": "arm64",
            "client_version": "0.1.0",
        },
    )
    assert enrolled.status_code == 200, enrolled.text
    payload: dict[str, Any] = enrolled.json()
    payload["code"] = code
    return payload


def device_hello(**overrides: Any) -> dict[str, Any]:
    frame: dict[str, Any] = {
        "type": "hello",
        "protocol": 1,
        "client_version": "0.1.0",
        "name": "mac-studio",
        "platform": "macos",
        "hostname": "studio.local",
        "arch": "arm64",
        "agents": [
            {
                "agent": "claude",
                "available": True,
                "version": "2.1.266",
                "path": "/usr/local/bin/claude",
                "models": [{"id": "claude-sonnet-4-5", "label": "Sonnet 4.5"}],
                "default_model": "claude-sonnet-4-5",
                "permission_modes": [{"id": "default", "label": "Ask before edits"}],
                "default_permission_mode": "default",
                "efforts": [],
                "default_effort": None,
                "capabilities": ["interrupt", "queue", "history"],
            }
        ],
        "sessions": [],
    }
    frame.update(overrides)
    return frame


def session_summary(session_id: str, device_id: str, **overrides: Any) -> dict[str, Any]:
    summary: dict[str, Any] = {
        "session_id": session_id,
        "device_id": device_id,
        "agent": "claude",
        "title": "Fix flaky auth test",
        "cwd": "/Users/me/dev/gateway",
        "git": None,
        "state": "idle",
        "state_detail": None,
        "origin": "remote",
        "control": "remote",
        "model": "claude-sonnet-4-5",
        "permission_mode": "default",
        "effort": None,
        "created_at": 1710000000000,
        "updated_at": 1710000000000,
        "last_seq": 0,
        "archived": False,
        "turn": None,
        "todos": None,
        "usage": None,
        "queued": 0,
    }
    summary.update(overrides)
    return summary


def close_code_for(client: TestClient, path: str, **kwargs: Any) -> int:
    """Connect and return the close code the gateway sends.

    Amendment A4 requires the handshake to complete so the code is observable, so the rejection
    shows up on the first receive rather than as a failed upgrade.
    """
    from starlette.websockets import WebSocketDisconnect

    with (
        pytest.raises(WebSocketDisconnect) as caught,
        client.websocket_connect(path, **kwargs) as socket,
    ):
        for _ in range(5):
            socket.receive_json()
    return int(caught.value.code)


def collect_until(ws: Any, kind: str, limit: int = 40) -> list[dict[str, Any]]:
    """Read frames up to and including the first of ``kind``, returning all of them."""
    seen: list[dict[str, Any]] = []
    for _ in range(limit):
        frame = dict(ws.receive_json())
        seen.append(frame)
        if frame.get("type") == kind:
            return seen
    raise AssertionError(f"{kind!r} never arrived; saw {[f.get('type') for f in seen]}")


def drain_until(ws: Any, kind: str, limit: int = 40) -> dict[str, Any]:
    """Read frames until one of ``kind`` arrives; fail loudly instead of hanging forever."""
    seen: list[str] = []
    for _ in range(limit):
        frame = ws.receive_json()
        seen.append(str(frame.get("type")))
        if frame.get("type") == kind:
            return dict(frame)
    raise AssertionError(f"{kind!r} never arrived; saw {seen}")


class FakeSocket:
    """A WebSocket stand-in for hub tests that run no server."""

    async def send_text(self, raw: str) -> None:
        return None

    async def close(self, code: int = 1000, reason: str = "") -> None:
        return None


@dataclass
class HubRig:
    """A hub with a real store behind it, one enrolled device and one app."""

    hub: Hub
    index: SessionIndex
    devices: DeviceStore
    device: DeviceConnection
    app: AppConnection
    device_id: str


async def hub_rig(
    tmp_path: Path,
    *,
    on_session_transition: TransitionHook | None = None,
    request_timeout: float = REQUEST_TIMEOUT_SECONDS,
    offline_grace: float = OFFLINE_GRACE_SECONDS,
) -> HubRig:
    """Build a hub whose connections never drain their queues.

    Leaving the writer tasks unstarted keeps what the gateway produced in the queues, so a test
    reads exactly the frames the hub emitted, in order, without racing a sender.
    """
    index = SessionIndex(tmp_path / "sessions.sqlite3")
    devices = DeviceStore(tmp_path / "devices.sqlite3")
    grant = await devices.create_pairing("admin")
    enrolled = await devices.redeem(
        grant.code,
        name="mac-studio",
        platform="macos",
        hostname="studio.local",
        arch="arm64",
        client_version="0.1.0",
    )
    assert not isinstance(enrolled, str), enrolled
    hub = Hub(
        index,
        devices,
        on_session_transition=on_session_transition,
        request_timeout=request_timeout,
        offline_grace=offline_grace,
    )
    device = fake_device(enrolled.device_id)
    app = fake_app()
    await hub.attach_device(device)
    await hub.attach_app(app)
    return HubRig(
        hub=hub,
        index=index,
        devices=devices,
        device=device,
        app=app,
        device_id=enrolled.device_id,
    )


def fake_device(device_id: str) -> DeviceConnection:
    return DeviceConnection(FakeSocket(), device_id)  # type: ignore[arg-type]


def fake_app(username: str = "admin") -> AppConnection:
    return AppConnection(FakeSocket(), username)  # type: ignore[arg-type]


def frames_of(connection: Connection) -> list[dict[str, Any]]:
    """Everything queued for a connection, drained."""
    out: list[dict[str, Any]] = []
    while not connection.queue.empty():
        raw, _ = connection.queue.get_nowait()
        out.append(json.loads(raw))
    return out
