"""Decode the canonical fixtures in ``protocol/fixtures`` against the running gateway.

These are the contract agent's normative examples. Every one of them must move through the real
routes and sockets, so a divergence between the gateway and the frozen protocol fails here rather
than during integration.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from rc_gateway.push import build_payload
from rc_gateway.replay import ReplayBuffer
from rc_gateway.state import GatewayState

from .conftest import FIXTURE_DIR, drain_until, enroll_device

pytestmark = pytest.mark.skipif(
    not FIXTURE_DIR.is_dir(), reason="protocol/fixtures has not been produced yet"
)


def fixture(*parts: str) -> Any:
    return json.loads((FIXTURE_DIR.joinpath(*parts)).read_text(encoding="utf-8"))


def event_files() -> list[str]:
    directory = FIXTURE_DIR / "events"
    return sorted(item.name for item in directory.glob("*.json")) if directory.is_dir() else []


def forwarded_files() -> list[str]:
    directory = FIXTURE_DIR / "device" / "forwarded"
    return sorted(item.name for item in directory.glob("*.json")) if directory.is_dir() else []


def test_health_and_config_match_the_fixture_shape(
    client: TestClient, auth: dict[str, str]
) -> None:
    expected = fixture("http", "health.response.json")
    body = client.get("/api/health").json()
    assert set(expected) <= set(body)
    assert body["protocol"] == expected["protocol"]
    assert body["auth"] == expected["auth"]

    expected_config = fixture("http", "config.response.json")
    config = client.get("/api/config", headers=auth).json()
    assert set(config) == set(expected_config)
    assert set(config["stt"]) == set(expected_config["stt"])
    assert set(config["push"]) == set(expected_config["push"])


def test_device_hello_fixture_is_accepted(client: TestClient, auth: dict[str, str]) -> None:
    enrolled = enroll_device(client, auth)
    hello = fixture("device", "hello.json")
    expected_ack = fixture("device", "hello_ack.json")
    headers = {"Authorization": f"Bearer {enrolled['device_token']}"}
    with client.websocket_connect("/ws/device", headers=headers) as device:
        device.send_json(hello)
        ack = device.receive_json()
    assert set(ack) == set(expected_ack)
    assert ack["config"] == expected_ack["config"]
    assert ack["device_id"] == enrolled["device_id"]

    sessions = client.get("/api/sessions", headers=auth).json()["sessions"]
    assert {item["session_id"] for item in sessions} == {
        item["session_id"] for item in hello["sessions"]
    }
    # The gateway stamps its own device_id: the device cannot claim to own another machine.
    assert {item["device_id"] for item in sessions} == {enrolled["device_id"]}

    devices = client.get("/api/devices", headers=auth).json()["devices"]
    assert devices[0]["name"] == hello["name"]
    assert [agent["agent"] for agent in devices[0]["agents"]] == ["claude", "codex"]


@pytest.mark.parametrize("name", event_files())
def test_every_event_fixture_replays(name: str) -> None:
    event = fixture("events", name)
    buffer = ReplayBuffer()
    assert buffer.append(event, 512) is True
    assert buffer.last_seq == event["seq"]
    events, resync = buffer.replay_from(event["seq"] - 1)
    assert events == [event]
    assert resync is False


@pytest.mark.parametrize("name", forwarded_files())
def test_every_forwarded_fixture_round_trips(
    name: str, client: TestClient, auth: dict[str, str]
) -> None:
    forwarded = fixture("device", "forwarded", name)
    request = {key: value for key, value in forwarded.items() if key not in ("from", "device_id")}
    enrolled = enroll_device(client, auth)
    hello = fixture("device", "hello.json")
    headers = {"Authorization": f"Bearer {enrolled['device_token']}"}
    with client.websocket_connect("/ws/device", headers=headers) as device:
        device.send_json(hello)
        device.receive_json()
        with client.websocket_connect("/ws/app", headers=auth) as app:
            drain_until(app, "hello")
            if "device_id" in forwarded and "session_id" not in forwarded:
                request["device_id"] = enrolled["device_id"]
            app.send_json(request)
            seen = drain_until(device, forwarded["type"])
            assert seen["id"] == forwarded["id"]
            assert seen["device_id"] == enrolled["device_id"]
            assert seen["from"]
            for key, value in request.items():
                if key != "device_id":
                    assert seen[key] == value

            reply = dict(fixture("device", "reply.json"))
            reply["id"] = forwarded["id"]
            reply["from"] = seen["from"]
            device.send_json(reply)
            delivered = drain_until(app, "reply")
            assert delivered["id"] == forwarded["id"]
            assert delivered["ok"] is True


def test_subscribe_reply_matches_the_fixture_shape(
    client: TestClient, auth: dict[str, str]
) -> None:
    expected = fixture("replay", "subscribe.reply.json")
    enrolled = enroll_device(client, auth)
    hello = fixture("device", "hello.json")
    session_id = hello["sessions"][0]["session_id"]
    headers = {"Authorization": f"Bearer {enrolled['device_token']}"}
    with client.websocket_connect("/ws/device", headers=headers) as device:
        device.send_json(hello)
        device.receive_json()
        with client.websocket_connect("/ws/app", headers=auth) as app:
            drain_until(app, "hello")
            app.send_json(
                {"type": "session.subscribe", "id": expected["id"], "session_id": session_id}
            )
            reply = drain_until(app, "reply")
    assert set(reply) == set(expected)
    assert set(reply["result"]) == set(expected["result"])
    assert set(reply["result"]["session"]) == set(expected["result"]["session"])


def test_push_payload_matches_the_fixture(client: TestClient, auth: dict[str, str]) -> None:
    expected = fixture("http", "push.payload.json")
    payload = build_payload(
        expected["rc"]["kind"],
        {
            "device_id": expected["rc"]["device_id"],
            "session_id": expected["rc"]["session_id"],
        },
        expected["rc"]["device_name"],
    )
    assert payload == expected


def test_app_hello_matches_the_fixture_shape(client: TestClient, auth: dict[str, str]) -> None:
    expected = fixture("app", "hello.json")
    with client.websocket_connect("/ws/app", headers=auth) as app:
        hello = drain_until(app, "hello")
    assert set(hello) == set(expected)


def test_enroll_request_fixture_is_accepted(
    client: TestClient, auth: dict[str, str], state: GatewayState
) -> None:
    request = dict(fixture("http", "devices.enroll.request.json"))
    request["code"] = client.post("/api/devices/pairing", headers=auth).json()["code"]
    response = client.post("/api/devices/enroll", json=request)
    assert response.status_code == 200
    assert set(response.json()) == set(fixture("http", "devices.enroll.response.json"))
    listed = client.get("/api/devices", headers=auth).json()["devices"][0]
    assert [agent["agent"] for agent in listed["agents"]] == ["claude", "codex"]


def test_device_list_fixture_shape(client: TestClient, auth: dict[str, str]) -> None:
    expected = fixture("http", "devices.list.response.json")["devices"][0]
    enroll_device(client, auth)
    listed = client.get("/api/devices", headers=auth).json()["devices"][0]
    assert set(listed) == set(expected)


def test_tool_category_is_named_tool_kind() -> None:
    """Amendment A1: ``kind`` is the event discriminator, ``tool_kind`` the tool category."""
    categories = {
        "shell",
        "read",
        "edit",
        "write",
        "search",
        "web",
        "mcp",
        "subagent",
        "todo",
        "other",
    }
    checked = 0
    for name in event_files():
        event = fixture("events", name)
        if event["kind"] not in ("tool_call", "approval"):
            continue
        checked += 1
        assert event["tool_kind"] in categories, name
        assert event["kind"] in ("tool_call", "approval"), name
    assert checked > 0


def test_usage_objects_carry_the_required_counters() -> None:
    """Amendment A2: token counts are required integers, cost and context are optional."""
    hello = fixture("device", "hello.json")
    seen = 0
    for summary in hello["sessions"]:
        usage = summary.get("usage")
        if usage is None:
            continue
        seen += 1
        for field in ("input_tokens", "output_tokens", "total_tokens"):
            assert isinstance(usage[field], int), field
        for field in ("context_used", "context_window", "cost_usd"):
            assert (
                field not in usage or usage[field] is None or isinstance(usage[field], (int, float))
            )
    assert seen > 0


def test_attachment_shapes_differ_by_direction() -> None:
    """Amendment A3: an event describes an attachment, a request carries its bytes."""
    request = fixture("device", "forwarded", "session.send.json")
    for item in request.get("attachments", []):
        assert set(item) == {"name", "mime", "data_base64"}
    for name in event_files():
        event = fixture("events", name)
        if event["kind"] != "user_message":
            continue
        for item in event.get("attachments", []):
            assert set(item) == {"name", "mime", "size"}


def test_fixture_directory_is_complete() -> None:
    """Guard against a fixture set that silently loses files during integration."""
    required = [
        Path("device/hello.json"),
        Path("device/hello_ack.json"),
        Path("device/reply.json"),
        Path("app/hello.json"),
        Path("replay/subscribe.reply.json"),
        Path("http/push.payload.json"),
    ]
    missing = [str(item) for item in required if not (FIXTURE_DIR / item).is_file()]
    assert missing == []
