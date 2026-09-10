"""Request forwarding, reply routing, offline and timeout replies, session fan-out."""

from __future__ import annotations

from typing import Any

from fastapi.testclient import TestClient

from rc_gateway.state import GatewayState

from .conftest import close_code_for, device_hello, drain_until, enroll_device, session_summary

SESSION_ID = "11111111-2222-3333-4444-555555555555"


def _device_headers(enrolled: dict[str, Any]) -> dict[str, str]:
    return {"Authorization": f"Bearer {enrolled['device_token']}"}


def test_app_hello_carries_devices_and_sessions(client: TestClient, auth: dict[str, str]) -> None:
    enroll_device(client, auth)
    with client.websocket_connect("/ws/app", headers=auth) as app:
        hello = app.receive_json()
    assert hello["type"] == "hello"
    assert hello["protocol"] == 1
    assert hello["user"] == {"username": "admin"}
    assert len(hello["devices"]) == 1
    assert hello["sessions"] == []
    assert hello["stt"] == {"enabled": True, "languages": ["auto", "zh", "en"]}
    assert hello["server_time"] > 0


def test_app_socket_requires_a_credential(client: TestClient) -> None:
    assert close_code_for(client, "/ws/app") == 4401


def test_request_is_forwarded_with_from_and_the_reply_comes_back(
    client: TestClient, auth: dict[str, str]
) -> None:
    enrolled = enroll_device(client, auth)
    with client.websocket_connect("/ws/device", headers=_device_headers(enrolled)) as device:
        device.send_json(
            device_hello(sessions=[session_summary(SESSION_ID, enrolled["device_id"])])
        )
        assert device.receive_json()["type"] == "hello_ack"
        with client.websocket_connect("/ws/app", headers=auth) as app:
            drain_until(app, "hello")
            app.send_json(
                {"type": "session.send", "id": "req-1", "session_id": SESSION_ID, "text": "hi"}
            )
            forwarded = drain_until(device, "session.send")
            assert forwarded["id"] == "req-1"
            assert forwarded["device_id"] == enrolled["device_id"]
            assert forwarded["from"]
            assert forwarded["text"] == "hi"

            device.send_json(
                {
                    "type": "reply",
                    "id": "req-1",
                    "from": forwarded["from"],
                    "ok": True,
                    "result": {"accepted": "sent"},
                }
            )
            reply = drain_until(app, "reply")
            assert reply["ok"] is True
            assert reply["result"] == {"accepted": "sent"}
            assert "from" not in reply


def test_a_request_addressed_by_device_id_is_forwarded_unchanged(
    client: TestClient, auth: dict[str, str]
) -> None:
    enrolled = enroll_device(client, auth)
    with client.websocket_connect("/ws/device", headers=_device_headers(enrolled)) as device:
        device.send_json(device_hello())
        device.receive_json()
        with client.websocket_connect("/ws/app", headers=auth) as app:
            drain_until(app, "hello")
            app.send_json(
                {
                    "type": "session.create",
                    "id": "req-2",
                    "device_id": enrolled["device_id"],
                    "agent": "claude",
                    "cwd": "/tmp",
                }
            )
            forwarded = drain_until(device, "session.create")
            assert forwarded["agent"] == "claude"
            assert forwarded["cwd"] == "/tmp"


def test_offline_device_yields_a_device_offline_reply(
    client: TestClient, auth: dict[str, str]
) -> None:
    enrolled = enroll_device(client, auth)
    with client.websocket_connect("/ws/app", headers=auth) as app:
        drain_until(app, "hello")
        app.send_json(
            {
                "type": "device.agents",
                "id": "req-3",
                "device_id": enrolled["device_id"],
            }
        )
        reply = drain_until(app, "reply")
        assert reply["ok"] is False
        assert reply["error"]["code"] == "device_offline"


def test_unknown_session_yields_not_found(client: TestClient, auth: dict[str, str]) -> None:
    with client.websocket_connect("/ws/app", headers=auth) as app:
        drain_until(app, "hello")
        app.send_json({"type": "session.stop", "id": "req-4", "session_id": "nope"})
        reply = drain_until(app, "reply")
        assert reply["error"]["code"] == "not_found"


def test_a_silent_device_produces_a_timeout_reply(client: TestClient, auth: dict[str, str]) -> None:
    enrolled = enroll_device(client, auth)
    with client.websocket_connect("/ws/device", headers=_device_headers(enrolled)) as device:
        device.send_json(
            device_hello(sessions=[session_summary(SESSION_ID, enrolled["device_id"])])
        )
        device.receive_json()
        with client.websocket_connect("/ws/app", headers=auth) as app:
            drain_until(app, "hello")
            app.send_json({"type": "session.stop", "id": "req-5", "session_id": SESSION_ID})
            drain_until(device, "session.stop")
            reply = drain_until(app, "reply")
            assert reply["id"] == "req-5"
            assert reply["error"]["code"] == "timeout"


def test_pending_requests_fail_when_the_device_drops(
    client: TestClient, auth: dict[str, str]
) -> None:
    enrolled = enroll_device(client, auth)
    with client.websocket_connect("/ws/app", headers=auth) as app:
        drain_until(app, "hello")
        with client.websocket_connect("/ws/device", headers=_device_headers(enrolled)) as device:
            device.send_json(
                device_hello(sessions=[session_summary(SESSION_ID, enrolled["device_id"])])
            )
            device.receive_json()
            drain_until(app, "device.updated")
            app.send_json({"type": "session.stop", "id": "req-6", "session_id": SESSION_ID})
            drain_until(device, "session.stop")
        reply = drain_until(app, "reply")
        assert reply["id"] == "req-6"
        assert reply["error"]["code"] == "device_offline"


def test_a_multi_megabyte_attachment_forwards_intact(
    client: TestClient, auth: dict[str, str]
) -> None:
    """A session.send may carry attachments, so the device queue must not treat it as a flood."""
    enrolled = enroll_device(client, auth)
    with client.websocket_connect("/ws/device", headers=_device_headers(enrolled)) as device:
        device.send_json(
            device_hello(sessions=[session_summary(SESSION_ID, enrolled["device_id"])])
        )
        device.receive_json()
        with client.websocket_connect("/ws/app", headers=auth) as app:
            drain_until(app, "hello")
            blob = "A" * (4 * 1024 * 1024)
            app.send_json(
                {
                    "type": "session.send",
                    "id": "req-big",
                    "session_id": SESSION_ID,
                    "text": "look at this",
                    "attachments": [{"name": "shot.png", "mime": "image/png", "data_base64": blob}],
                    "mode": "auto",
                }
            )
            forwarded = drain_until(device, "session.send")
            assert forwarded["attachments"][0]["data_base64"] == blob
            assert forwarded["attachments"][0]["name"] == "shot.png"


def test_an_unsupported_app_request_is_rejected(client: TestClient, auth: dict[str, str]) -> None:
    with client.websocket_connect("/ws/app", headers=auth) as app:
        drain_until(app, "hello")
        app.send_json({"type": "session.nonsense", "id": "req-7"})
        reply = drain_until(app, "reply")
        assert reply["error"]["code"] == "bad_request"


def test_session_updates_reach_every_app(client: TestClient, auth: dict[str, str]) -> None:
    enrolled = enroll_device(client, auth)
    with client.websocket_connect("/ws/device", headers=_device_headers(enrolled)) as device:
        device.send_json(device_hello())
        device.receive_json()
        with client.websocket_connect("/ws/app", headers=auth) as app:
            drain_until(app, "hello")
            device.send_json(
                {
                    "type": "session.updated",
                    "session": session_summary(SESSION_ID, enrolled["device_id"], state="running"),
                }
            )
            pushed = drain_until(app, "session.updated")
            assert pushed["session"]["session_id"] == SESSION_ID
            assert pushed["session"]["state"] == "running"

            device.send_json({"type": "session.removed", "session_id": SESSION_ID})
            removed = drain_until(app, "session.removed")
            assert removed == {
                "type": "session.removed",
                "session_id": SESSION_ID,
                "device_id": enrolled["device_id"],
            }


def test_pairing_progress_reaches_enrolled_online_and_agents(
    client: TestClient, auth: dict[str, str]
) -> None:
    with client.websocket_connect("/ws/app", headers=auth) as app:
        drain_until(app, "hello")
        enrolled = enroll_device(client, auth)
        waiting = drain_until(app, "pairing.progress")
        assert waiting["step"] == "waiting"
        steps = [waiting["step"]]
        headers = _device_headers(enrolled)
        with client.websocket_connect("/ws/device", headers=headers) as device:
            device.send_json(device_hello())
            device.receive_json()
            for _ in range(8):
                frame = app.receive_json()
                if frame["type"] == "pairing.progress":
                    steps.append(frame["step"])
                    if frame["step"] == "agents":
                        assert frame["device"]["device_id"] == enrolled["device_id"]
                        break
    assert steps == ["waiting", "enrolled", "online", "agents"]


def test_device_state_survives_a_gateway_restart(
    client: TestClient, auth: dict[str, str], state: GatewayState
) -> None:
    enrolled = enroll_device(client, auth)
    with client.websocket_connect("/ws/device", headers=_device_headers(enrolled)) as device:
        device.send_json(
            device_hello(sessions=[session_summary(SESSION_ID, enrolled["device_id"])])
        )
        device.receive_json()
    listed = client.get("/api/sessions", headers=auth).json()["sessions"]
    assert [item["session_id"] for item in listed] == [SESSION_ID]
    assert listed[0]["device_id"] == enrolled["device_id"]
    filtered = client.get("/api/sessions", params={"device_id": "other"}, headers=auth).json()[
        "sessions"
    ]
    assert filtered == []


def test_attachments_beyond_the_protocol_bounds_are_refused(
    client: TestClient, auth: dict[str, str]
) -> None:
    """Section 5 caps attachments; the gateway enforces it so devices never have to."""
    enrolled = enroll_device(client, auth)
    with client.websocket_connect("/ws/device", headers=_device_headers(enrolled)) as device:
        device.send_json(
            device_hello(sessions=[session_summary(SESSION_ID, enrolled["device_id"])])
        )
        device.receive_json()
        with client.websocket_connect("/ws/app", headers=auth) as app:
            drain_until(app, "hello")
            app.send_json(
                {
                    "type": "session.send",
                    "id": "many",
                    "session_id": SESSION_ID,
                    "text": "hi",
                    "attachments": [
                        {"name": f"{index}.png", "mime": "image/png", "data_base64": "AAAA"}
                        for index in range(9)
                    ],
                }
            )
            reply = drain_until(app, "reply")
            assert reply["error"]["code"] == "too_large"

            app.send_json(
                {
                    "type": "session.send",
                    "id": "big",
                    "session_id": SESSION_ID,
                    "text": "hi",
                    "attachments": [
                        {"name": "a.png", "mime": "image/png", "data_base64": "A" * 9_000_000}
                    ],
                }
            )
            reply = drain_until(app, "reply")
            assert reply["error"]["code"] == "too_large"
