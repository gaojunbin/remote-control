"""One device must not reach another device's sessions.

The single-user model still has a boundary: every enrolled machine is a separate trust domain, and
a compromised or buggy client must not be able to hijack, forge, silence or delete the sessions of
another. Each test here is one of the review's High findings.
"""

from __future__ import annotations

from typing import Any

from fastapi.testclient import TestClient

from rc_gateway.state import GatewayState

from .conftest import device_hello, drain_until, enroll_device, session_summary

SESSION_A = "aaaa1111-2222-4333-8444-555555555555"
SESSION_B = "bbbb1111-2222-4333-8444-555555555555"


def _headers(enrolled: dict[str, Any]) -> dict[str, str]:
    return {"Authorization": f"Bearer {enrolled['device_token']}"}


def test_a_device_cannot_take_over_another_devices_session(
    client: TestClient, auth: dict[str, str], state: GatewayState
) -> None:
    victim = enroll_device(client, auth, name="victim")
    attacker = enroll_device(client, auth, name="attacker")
    with client.websocket_connect("/ws/device", headers=_headers(victim)) as owner:
        owner.send_json(device_hello(sessions=[session_summary(SESSION_B, victim["device_id"])]))
        owner.receive_json()
        with client.websocket_connect("/ws/device", headers=_headers(attacker)) as intruder:
            intruder.send_json(device_hello())
            intruder.receive_json()
            intruder.send_json(
                {
                    "type": "session.updated",
                    "session": session_summary(SESSION_B, attacker["device_id"], title="stolen"),
                }
            )
            # A frame the gateway ignores produces no observable effect, so settle on a frame it
            # does act on before asserting.
            intruder.send_json(
                {
                    "type": "session.updated",
                    "session": session_summary(SESSION_A, attacker["device_id"]),
                }
            )
            with client.websocket_connect("/ws/app", headers=auth) as app:
                drain_until(app, "hello")
    listed = client.get("/api/sessions", headers=auth).json()["sessions"]
    owners = {item["session_id"]: item["device_id"] for item in listed}
    assert owners[SESSION_B] == victim["device_id"]
    assert owners[SESSION_A] == attacker["device_id"]
    assert next(item for item in listed if item["session_id"] == SESSION_B)["title"] != "stolen"


def test_a_device_cannot_inject_events_into_another_devices_session(
    client: TestClient, auth: dict[str, str]
) -> None:
    victim = enroll_device(client, auth, name="victim")
    attacker = enroll_device(client, auth, name="attacker")
    with client.websocket_connect("/ws/device", headers=_headers(victim)) as owner:
        owner.send_json(device_hello(sessions=[session_summary(SESSION_B, victim["device_id"])]))
        owner.receive_json()
        with client.websocket_connect("/ws/app", headers=auth) as app:
            drain_until(app, "hello")
            app.send_json({"type": "session.subscribe", "id": "s1", "session_id": SESSION_B})
            drain_until(app, "reply")
            with client.websocket_connect("/ws/device", headers=_headers(attacker)) as intruder:
                intruder.send_json(device_hello())
                intruder.receive_json()
                intruder.send_json(
                    {
                        "type": "session.event",
                        "session_id": SESSION_B,
                        "event": {
                            "seq": 9_000_000,
                            "ts": 1,
                            "kind": "assistant_text",
                            "block_id": "forged",
                            "text": "INJECTED",
                            "done": True,
                        },
                    }
                )
            # The genuine event still arrives, and its low seq was never poisoned by the forgery.
            owner.send_json(
                {
                    "type": "session.event",
                    "session_id": SESSION_B,
                    "event": {
                        "seq": 5,
                        "ts": 2,
                        "kind": "assistant_text",
                        "block_id": "real",
                        "text": "genuine",
                        "done": True,
                    },
                }
            )
            streamed = drain_until(app, "session.event")
    assert streamed["event"]["text"] == "genuine"
    assert streamed["event"]["seq"] == 5
    assert streamed["device_id"] == victim["device_id"]


def test_a_device_cannot_delete_another_devices_session(
    client: TestClient, auth: dict[str, str]
) -> None:
    victim = enroll_device(client, auth, name="victim")
    attacker = enroll_device(client, auth, name="attacker")
    with client.websocket_connect("/ws/device", headers=_headers(victim)) as owner:
        owner.send_json(device_hello(sessions=[session_summary(SESSION_B, victim["device_id"])]))
        owner.receive_json()
        with client.websocket_connect("/ws/device", headers=_headers(attacker)) as intruder:
            intruder.send_json(device_hello())
            intruder.receive_json()
            intruder.send_json({"type": "session.removed", "session_id": SESSION_B})
            intruder.send_json(
                {
                    "type": "session.updated",
                    "session": session_summary(SESSION_A, attacker["device_id"]),
                }
            )
            with client.websocket_connect("/ws/app", headers=auth) as app:
                drain_until(app, "hello")
    listed = {
        item["session_id"] for item in client.get("/api/sessions", headers=auth).json()["sessions"]
    }
    assert SESSION_B in listed


def test_a_device_cannot_answer_a_request_pending_on_another(
    client: TestClient, auth: dict[str, str]
) -> None:
    victim = enroll_device(client, auth, name="victim")
    attacker = enroll_device(client, auth, name="attacker")
    with client.websocket_connect("/ws/device", headers=_headers(victim)) as owner:
        owner.send_json(device_hello(sessions=[session_summary(SESSION_B, victim["device_id"])]))
        owner.receive_json()
        with client.websocket_connect("/ws/device", headers=_headers(attacker)) as intruder:
            intruder.send_json(device_hello())
            intruder.receive_json()
            with client.websocket_connect("/ws/app", headers=auth) as app:
                drain_until(app, "hello")
                app.send_json(
                    {"type": "session.send", "id": "r1", "session_id": SESSION_B, "text": "hi"}
                )
                forwarded = drain_until(owner, "session.send")
                intruder.send_json(
                    {
                        "type": "reply",
                        "id": "r1",
                        "from": forwarded["from"],
                        "ok": True,
                        "result": {"accepted": "sent", "forged": True},
                    }
                )
                owner.send_json(
                    {
                        "type": "reply",
                        "id": "r1",
                        "from": forwarded["from"],
                        "ok": True,
                        "result": {"accepted": "queued"},
                    }
                )
                reply = drain_until(app, "reply")
    assert reply["result"] == {"accepted": "queued"}


def test_ownership_survives_a_reconnect(client: TestClient, auth: dict[str, str]) -> None:
    """The binding is set at first sight and is not re-derived from whoever speaks next."""
    victim = enroll_device(client, auth, name="victim")
    attacker = enroll_device(client, auth, name="attacker")
    with client.websocket_connect("/ws/device", headers=_headers(victim)) as owner:
        owner.send_json(device_hello(sessions=[session_summary(SESSION_B, victim["device_id"])]))
        owner.receive_json()
    # The owner is offline; the attacker announces the same session in its own hello.
    with client.websocket_connect("/ws/device", headers=_headers(attacker)) as intruder:
        intruder.send_json(
            device_hello(sessions=[session_summary(SESSION_B, attacker["device_id"])])
        )
        intruder.receive_json()
    listed = client.get("/api/sessions", headers=auth).json()["sessions"]
    assert [item["device_id"] for item in listed] == [victim["device_id"]]
