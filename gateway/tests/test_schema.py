"""Validate real gateway output against `protocol/schema/*.json`.

The other tests compare key sets, which cannot catch a value in the wrong shape. These validate
what the gateway actually emits against the normative schema every other component checks against,
so a `device_id` that is not a UUID, or a timestamp in seconds, fails here rather than in the iOS
verification run.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from rc_gateway.state import GatewayState

from .conftest import (
    device_hello,
    drain_until,
    enroll_device,
    session_summary,
    write_wheel,
)

jsonschema = pytest.importorskip("jsonschema")
SCHEMA_DIR = Path(__file__).resolve().parents[2] / "protocol" / "schema"
pytestmark = pytest.mark.skipif(not SCHEMA_DIR.is_dir(), reason="protocol/schema is not present")

SESSION_ID = "dddd1111-2222-4333-8444-555555555555"


def _registry() -> Any:
    from referencing import Registry, Resource

    registry = Registry()
    for path in SCHEMA_DIR.glob("*.json"):
        document = json.loads(path.read_text(encoding="utf-8"))
        registry = registry.with_resource(document["$id"], Resource.from_contents(document))
    return registry


def check(frame: Any, schema_file: str, definition: str) -> None:
    """Validate one frame against `<schema_file>#/$defs/<definition>`."""
    document = json.loads((SCHEMA_DIR / schema_file).read_text(encoding="utf-8"))
    validator = jsonschema.Draft202012Validator(
        {"$ref": f"{document['$id']}#/$defs/{definition}"}, registry=_registry()
    )
    errors = sorted(validator.iter_errors(frame), key=lambda item: list(item.path))
    assert not errors, "\n".join(f"{list(e.path)}: {e.message}" for e in errors[:5])


def test_enroll_response_matches_the_schema(client: TestClient, auth: dict[str, str]) -> None:
    """Finding 7: `device_id` is typed as a UUID everywhere it appears."""
    code = client.post("/api/devices/pairing", headers=auth).json()["code"]
    response = client.post(
        "/api/devices/enroll",
        json={
            "code": code,
            "name": "mac-studio-office",
            "platform": "macos",
            "hostname": "mac-studio.local",
            "arch": "arm64",
            "client_version": "0.1.0",
        },
    )
    check(response.json(), "http.json", "EnrollResponse")


def test_health_and_pairing_responses_match_the_schema(
    client: TestClient, auth: dict[str, str]
) -> None:
    check(client.get("/api/health").json(), "http.json", "HealthResponse")
    check(client.post("/api/devices/pairing", headers=auth).json(), "http.json", "PairingResponse")


def test_device_list_matches_the_schema(client: TestClient, auth: dict[str, str]) -> None:
    enrolled = enroll_device(client, auth)
    with client.websocket_connect(
        "/ws/device", headers={"Authorization": f"Bearer {enrolled['device_token']}"}
    ) as device:
        device.send_json(device_hello(client_build="a" * 64))
        device.receive_json()
    check(client.get("/api/devices", headers=auth).json(), "http.json", "DeviceListResponse")


def test_config_and_pairing_request_bodies_match_the_schema(
    tmp_path: Path, state: GatewayState, client: TestClient, auth: dict[str, str]
) -> None:
    """A22 and A23: the served wheel, the claim token, the poll and the claim."""
    write_wheel(tmp_path)
    state.pairing_requests.poll_timeout = 0.05
    check(client.get("/api/config", headers=auth).json(), "http.json", "ConfigResponse")

    minted = client.post("/api/pairing/requests").json()
    check(minted, "http.json", "PairingRequestResponse")
    token = minted["token"]
    check(
        client.get(f"/api/pairing/requests/{token}").json(),
        "http.json",
        "PairingRequestStatusResponse",
    )
    claimed = client.post(f"/api/pairing/requests/{token}/claim", headers=auth).json()
    check(claimed, "http.json", "PairingClaimResponse")
    check(
        client.get(f"/api/pairing/requests/{token}").json(),
        "http.json",
        "PairingRequestStatusResponse",
    )


def test_hello_ack_and_app_hello_match_the_schema(client: TestClient, auth: dict[str, str]) -> None:
    enrolled = enroll_device(client, auth)
    headers = {"Authorization": f"Bearer {enrolled['device_token']}"}
    with client.websocket_connect("/ws/device", headers=headers) as device:
        device.send_json(
            device_hello(sessions=[session_summary(SESSION_ID, enrolled["device_id"])])
        )
        ack = device.receive_json()
        check(ack, "device_frames.json", "HelloAck")
        with client.websocket_connect("/ws/app", headers=auth) as app:
            hello = drain_until(app, "hello")
    check(hello, "app_frames.json", "Hello")


def test_pushed_app_frames_match_the_schema(client: TestClient, auth: dict[str, str]) -> None:
    enrolled = enroll_device(client, auth)
    headers = {"Authorization": f"Bearer {enrolled['device_token']}"}
    with client.websocket_connect("/ws/app", headers=auth) as app:
        drain_until(app, "hello")
        with client.websocket_connect("/ws/device", headers=headers) as device:
            device.send_json(device_hello())
            device.receive_json()
            check(drain_until(app, "device.updated"), "app_frames.json", "DeviceUpdated")
            device.send_json(
                {
                    "type": "session.updated",
                    "session": session_summary(SESSION_ID, enrolled["device_id"]),
                }
            )
            check(drain_until(app, "session.updated"), "app_frames.json", "SessionUpdated")
            app.send_json({"type": "session.subscribe", "id": "s1", "session_id": SESSION_ID})
            drain_until(app, "reply")
            device.send_json(
                {
                    "type": "session.event",
                    "session_id": SESSION_ID,
                    "event": {"seq": 1, "ts": 1, "kind": "notice", "level": "info", "text": "x"},
                }
            )
            check(drain_until(app, "session.event"), "app_frames.json", "SessionEvent")
            device.send_json({"type": "session.removed", "session_id": SESSION_ID})
            check(drain_until(app, "session.removed"), "app_frames.json", "SessionRemoved")
    # The offline projection has `latency_ms: null` and a `last_seen`, so validate it too.
    check(client.get("/api/devices", headers=auth).json(), "http.json", "DeviceListResponse")


def test_pairing_progress_matches_the_schema(client: TestClient, auth: dict[str, str]) -> None:
    with client.websocket_connect("/ws/app", headers=auth) as app:
        drain_until(app, "hello")
        enroll_device(client, auth)
        for _ in range(4):
            frame = app.receive_json()
            if frame.get("type") == "pairing.progress":
                check(frame, "app_frames.json", "PairingProgress")
                return
    raise AssertionError("no pairing.progress frame arrived")
