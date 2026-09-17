"""Amendment A35 on the gateway: the account's switches, and what the device does about a limit.

Four things are checked here — the store, the two REST paths, the fan-out to every app and device
of the account, and the three pushes a ``resume`` event cues — plus the frozen fixtures for each.
"""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

from fastapi.testclient import TestClient

from rc_gateway.apns import ApnsProvider, ApnsResponse
from rc_gateway.frames import FORWARDED_BY_SESSION
from rc_gateway.hub import SessionResumeNotice
from rc_gateway.preference_store import DEFAULTS, Preferences, PreferenceStore
from rc_gateway.push import (
    KIND_LIMIT_REACHED,
    KIND_RESUME_DROPPED,
    KIND_RESUMED,
    PushService,
    build_payload,
    resume_kind,
)
from rc_gateway.push_store import ApnsRegistration, PushStore, WebPushSubscription

from .conftest import (
    FIXTURE_DIR,
    FakeWebPushSender,
    add_member,
    collect_until,
    device_hello,
    drain_until,
    enroll_device,
    session_summary,
)

SESSION_ID = "6d1f3c58-8b2e-4d67-9a4f-2e7c1b0d5a93"
ENDPOINT = "https://push.example.com/subscription/resume"
KEY = "A" * 32


def fixture(*parts: str) -> Any:
    return json.loads((FIXTURE_DIR.joinpath(*parts)).read_text(encoding="utf-8"))


# ---- the store ----


async def test_an_account_with_no_row_reads_the_defaults(tmp_path: Path) -> None:
    store = PreferenceStore(tmp_path / "preferences.sqlite3")
    assert await store.get("nobody") == DEFAULTS
    assert DEFAULTS.resume_after_limit is False


async def test_a_switch_is_stored_per_account_and_survives_a_reopen(tmp_path: Path) -> None:
    path = tmp_path / "preferences.sqlite3"
    store = PreferenceStore(path)
    assert await store.patch("ada", {"resume_after_limit": True}) == Preferences(True)
    assert await store.get("grace") == DEFAULTS

    reopened = PreferenceStore(path)
    assert await reopened.get("ada") == Preferences(True)
    assert (await reopened.patch("ada", {})) == Preferences(True)
    assert await reopened.patch("ada", {"resume_after_limit": False}) == DEFAULTS


async def test_a_deleted_account_leaves_no_switches(tmp_path: Path) -> None:
    store = PreferenceStore(tmp_path / "preferences.sqlite3")
    await store.patch("ada", {"resume_after_limit": True})
    await store.remove_for_user("ada")
    assert await store.get("ada") == DEFAULTS


def test_the_store_file_is_not_world_readable(tmp_path: Path) -> None:
    path = tmp_path / "preferences.sqlite3"
    PreferenceStore(path)
    assert path.stat().st_mode & 0o077 == 0


# ---- REST ----


def test_preferences_start_off_and_match_the_fixture(
    client: TestClient, auth: dict[str, str]
) -> None:
    response = client.get("/api/preferences", headers=auth)
    assert response.status_code == 200
    assert set(response.json()) == set(fixture("http", "preferences.response.json"))
    assert response.json() == {"preferences": {"resume_after_limit": False}}


def test_patch_sets_the_switch_and_returns_the_whole_object(
    client: TestClient, auth: dict[str, str]
) -> None:
    patch = fixture("http", "preferences.patch.request.json")
    response = client.patch("/api/preferences", json=patch, headers=auth)
    assert response.status_code == 200
    assert response.json() == {"preferences": {"resume_after_limit": True}}
    assert client.get("/api/preferences", headers=auth).json()["preferences"] == {
        "resume_after_limit": True
    }


def test_preferences_need_a_credential(client: TestClient) -> None:
    assert client.get("/api/preferences").status_code == 401
    assert client.patch("/api/preferences", json={"resume_after_limit": True}).status_code == 401


def test_one_account_never_reads_or_writes_another(
    client: TestClient, auth: dict[str, str]
) -> None:
    member = add_member(client, auth, "ada")
    client.patch("/api/preferences", json={"resume_after_limit": True}, headers=member)
    assert client.get("/api/preferences", headers=member).json()["preferences"] == {
        "resume_after_limit": True
    }
    # The admin's own switch is untouched: there is no path here that names an account.
    assert client.get("/api/preferences", headers=auth).json()["preferences"] == {
        "resume_after_limit": False
    }


def test_a_non_boolean_switch_is_a_bad_request(client: TestClient, auth: dict[str, str]) -> None:
    response = client.patch("/api/preferences", json={"resume_after_limit": "yes"}, headers=auth)
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "bad_request"


def test_a_field_this_gateway_does_not_know_is_ignored(
    client: TestClient, auth: dict[str, str]
) -> None:
    response = client.patch("/api/preferences", json={"invented_later": True}, headers=auth)
    assert response.status_code == 200
    assert response.json() == {"preferences": {"resume_after_limit": False}}


def test_a_deleted_account_takes_its_switches_with_it(
    client: TestClient, auth: dict[str, str]
) -> None:
    member = add_member(client, auth, "ada")
    client.patch("/api/preferences", json={"resume_after_limit": True}, headers=member)
    assert client.delete("/api/users/ada", headers=auth).status_code == 200
    recreated = add_member(client, auth, "ada")
    assert client.get("/api/preferences", headers=recreated).json()["preferences"] == {
        "resume_after_limit": False
    }


# ---- sockets ----


def test_hello_carries_the_account_preferences(client: TestClient, auth: dict[str, str]) -> None:
    client.patch("/api/preferences", json={"resume_after_limit": True}, headers=auth)
    with client.websocket_connect("/ws/app", headers=auth) as app:
        hello = drain_until(app, "hello")
    assert hello["preferences"] == {"resume_after_limit": True}


def test_a_device_is_told_the_preferences_right_after_the_ack(
    client: TestClient, auth: dict[str, str]
) -> None:
    enrolled = enroll_device(client, auth)
    client.patch("/api/preferences", json={"resume_after_limit": True}, headers=auth)
    headers = {"Authorization": f"Bearer {enrolled['device_token']}"}
    with client.websocket_connect("/ws/device", headers=headers) as device:
        device.send_json(device_hello())
        ack = device.receive_json()
        frame = device.receive_json()
    assert ack["type"] == "hello_ack"
    assert frame == fixture("device", "preferences.json")


def test_a_change_reaches_every_app_and_device_of_the_account(
    client: TestClient, auth: dict[str, str]
) -> None:
    enrolled = enroll_device(client, auth)
    other = add_member(client, auth, "ada")
    headers = {"Authorization": f"Bearer {enrolled['device_token']}"}
    with client.websocket_connect("/ws/device", headers=headers) as device:
        device.send_json(device_hello())
        drain_until(device, "preferences")
        with (
            client.websocket_connect("/ws/app", headers=auth) as app,
            client.websocket_connect("/ws/app", headers=other) as stranger,
        ):
            drain_until(app, "hello")
            drain_until(stranger, "hello")
            client.patch("/api/preferences", json={"resume_after_limit": True}, headers=auth)
            announced = drain_until(app, "preferences.updated")
            pushed = drain_until(device, "preferences")
            # A switch is one account's (A24). The PATCH returns only after its broadcast, so a
            # frame that leaked to the other account is already queued ahead of this marker.
            stranger.send_json(
                {"type": "session.subscribe", "id": "marker", "session_id": SESSION_ID}
            )
            quiet = collect_until(stranger, "reply")
            client.patch("/api/preferences", json={"resume_after_limit": True}, headers=other)
            theirs = drain_until(stranger, "preferences.updated")

    assert announced == fixture("app", "preferences.updated.json")
    assert pushed == {"type": "preferences", "preferences": {"resume_after_limit": True}}
    assert [frame["type"] for frame in quiet] == ["reply"]
    assert theirs["preferences"] == {"resume_after_limit": True}


def test_a_patch_that_changes_nothing_announces_nothing(
    client: TestClient, auth: dict[str, str]
) -> None:
    with client.websocket_connect("/ws/app", headers=auth) as app:
        drain_until(app, "hello")
        client.patch("/api/preferences", json={"resume_after_limit": False}, headers=auth)
        # A frame the gateway does send, behind the one it must not: `ping` would take 25 s.
        client.patch("/api/preferences", json={"resume_after_limit": True}, headers=auth)
        assert drain_until(app, "preferences.updated")["preferences"] == {
            "resume_after_limit": True
        }


# ---- forwarding ----


def test_the_two_resume_requests_are_forwarded() -> None:
    assert {"session.resume_set", "session.resume_cancel"} <= FORWARDED_BY_SESSION


def test_resume_set_reaches_the_device_and_its_reply_comes_back(
    client: TestClient, auth: dict[str, str]
) -> None:
    enrolled = enroll_device(client, auth)
    request = fixture("app", "session.resume_set.json")
    headers = {"Authorization": f"Bearer {enrolled['device_token']}"}
    with client.websocket_connect("/ws/device", headers=headers) as device:
        device.send_json(
            device_hello(sessions=[session_summary(SESSION_ID, enrolled["device_id"])])
        )
        drain_until(device, "preferences")
        with client.websocket_connect("/ws/app", headers=auth) as app:
            drain_until(app, "hello")
            app.send_json(request)
            seen = drain_until(device, "session.resume_set")
            assert seen["at"] == request["at"]
            assert seen["device_id"] == enrolled["device_id"]
            reply = dict(fixture("app", "reply.session.resume_set.json"))
            reply["from"] = seen["from"]
            device.send_json(reply)
            delivered = drain_until(app, "reply")
    assert delivered["id"] == request["id"]
    assert delivered["result"]["session"]["resume"] == {
        "at": 1788967860000,
        "estimated": False,
        "attempts": 0,
        "window_minutes": 300,
    }


def test_a_pending_resume_survives_the_session_index(
    client: TestClient, auth: dict[str, str]
) -> None:
    enrolled = enroll_device(client, auth)
    summary = dict(fixture("objects", "session.resume-pending.json"))
    summary["device_id"] = enrolled["device_id"]
    headers = {"Authorization": f"Bearer {enrolled['device_token']}"}
    with client.websocket_connect("/ws/device", headers=headers) as device:
        device.send_json(device_hello(sessions=[summary]))
        drain_until(device, "preferences")
        with client.websocket_connect("/ws/app", headers=auth) as app:
            hello = drain_until(app, "hello")
    listed = client.get("/api/sessions", headers=auth).json()["sessions"]
    assert [item["resume"] for item in listed] == [summary["resume"]]
    assert [item["resume"] for item in hello["sessions"]] == [summary["resume"]]


def test_a_limit_stop_and_a_resume_event_travel_untouched(
    client: TestClient, auth: dict[str, str]
) -> None:
    enrolled = enroll_device(client, auth)
    completed = fixture("events", "turn_completed.limit.json")
    scheduled = fixture("events", "resume.json")
    headers = {"Authorization": f"Bearer {enrolled['device_token']}"}
    with client.websocket_connect("/ws/device", headers=headers) as device:
        device.send_json(
            device_hello(sessions=[session_summary(SESSION_ID, enrolled["device_id"])])
        )
        drain_until(device, "preferences")
        with client.websocket_connect("/ws/app", headers=auth) as app:
            drain_until(app, "hello")
            app.send_json({"type": "session.subscribe", "id": "sub-1", "session_id": SESSION_ID})
            drain_until(app, "reply")
            for event in (completed, scheduled):
                device.send_json(
                    {"type": "session.event", "session_id": SESSION_ID, "event": event}
                )
            first = drain_until(app, "session.event")
            second = drain_until(app, "session.event")
    assert first["event"] == completed
    assert first["event"]["limit"] == {"window_minutes": 300, "resets_at": 1788966000000}
    assert second["event"] == scheduled


# ---- push ----


def test_resume_kinds_follow_the_amendment() -> None:
    assert resume_kind("scheduled") == KIND_LIMIT_REACHED
    assert resume_kind("fired") == KIND_RESUMED
    assert resume_kind("dropped") == KIND_RESUME_DROPPED
    assert resume_kind("rescheduled") is None
    assert resume_kind("cancelled") is None
    assert resume_kind("") is None


def test_the_limit_push_payload_matches_the_fixture() -> None:
    expected = fixture("http", "push.payload.limit.json")
    payload = build_payload(
        expected["rc"]["kind"],
        {
            "device_id": expected["rc"]["device_id"],
            "session_id": expected["rc"]["session_id"],
        },
        expected["rc"]["device_name"],
    )
    assert payload == expected


def _subscribe(client: TestClient, auth: dict[str, str]) -> None:
    response = client.post(
        "/api/push/web/subscribe",
        json={"subscription": {"endpoint": ENDPOINT, "keys": {"p256dh": KEY, "auth": KEY}}},
        headers=auth,
    )
    assert response.status_code == 200, response.text


def _resume_event(seq: int, status: str) -> dict[str, Any]:
    event: dict[str, Any] = {
        "seq": seq,
        "ts": 1788948000000 + seq,
        "kind": "resume",
        "status": status,
    }
    if status in ("scheduled", "rescheduled"):
        event["at"] = 1788966060000
        event["estimated"] = False
    return event


def _settle(sender: FakeWebPushSender, pushes: int) -> None:
    deadline = time.monotonic() + 2.0
    while len(sender.sent) < pushes and time.monotonic() < deadline:
        time.sleep(0.005)
    assert len(sender.sent) == pushes, [payload["rc"]["kind"] for _, payload in sender.sent]


def test_resume_events_push_three_kinds_and_stay_silent_for_two(
    client: TestClient, auth: dict[str, str], web_sender: FakeWebPushSender
) -> None:
    enrolled = enroll_device(client, auth)
    _subscribe(client, auth)
    headers = {"Authorization": f"Bearer {enrolled['device_token']}"}
    with client.websocket_connect("/ws/device", headers=headers) as device:
        device.send_json(
            device_hello(sessions=[session_summary(SESSION_ID, enrolled["device_id"])])
        )
        drain_until(device, "preferences")
        # An app socket that never subscribes: nobody is watching this session, so the
        # notification is the only way its owner would hear about the pause.
        with client.websocket_connect("/ws/app", headers=auth) as app:
            drain_until(app, "hello")
            expected = {"scheduled": 1, "rescheduled": 1, "fired": 2, "cancelled": 2, "dropped": 3}
            for seq, status in enumerate(expected, start=1):
                device.send_json(
                    {
                        "type": "session.event",
                        "session_id": SESSION_ID,
                        "event": _resume_event(seq, status),
                    }
                )
                # One at a time, so a silent status is proved silent rather than raced past.
                _settle(web_sender, expected[status])
            # And nothing arrives late from the two that must say nothing at all.
            time.sleep(0.2)
            assert len(web_sender.sent) == 3

    kinds = [payload["rc"]["kind"] for _, payload in web_sender.sent]
    assert kinds == [KIND_LIMIT_REACHED, KIND_RESUMED, KIND_RESUME_DROPPED]
    endpoint, first = web_sender.sent[0]
    assert endpoint == ENDPOINT
    assert first["rc"]["session_id"] == SESSION_ID
    assert first["rc"]["device_id"] == enrolled["device_id"]
    assert first["rc"]["title"] == "mac-studio: paused by the usage limit"
    assert set(first) == {"rc"}
    assert web_sender.sent[1][1]["rc"]["title"] == "mac-studio: resumed after the limit reset"
    assert web_sender.sent[2][1]["rc"]["title"] == "mac-studio: not resumed"


async def test_a_resume_notice_reaches_web_push_and_apns(tmp_path: Path) -> None:
    """The hook itself, both transports, without a socket in the way."""
    store = PushStore(tmp_path / "push.sqlite3")
    await store.upsert_web(
        WebPushSubscription(
            endpoint=ENDPOINT,
            p256dh=KEY,
            auth=KEY,
            session_jti="j" * 20,
            username="admin",
            expires_at=9_999_999_999,
        )
    )
    await store.upsert_apns(
        ApnsRegistration(
            device_token="ab" * 32,
            environment="sandbox",
            bundle_id="com.example.app",
            session_jti="j" * 20,
            username="admin",
            expires_at=9_999_999_999,
        )
    )
    apns_bodies: list[bytes] = []

    async def apns_sender(url: str, headers: dict[str, str], payload: bytes) -> ApnsResponse:
        apns_bodies.append(payload)
        return ApnsResponse(200)

    provider = ApnsProvider(
        "ABCDE12345",
        "FGHIJ67890",
        "com.example.app",
        _p256_pem(),
        environment="sandbox",
        sender=apns_sender,
    )
    web = FakeWebPushSender()
    service = PushService(
        store,
        device_name=_device_name,
        # Web delivery is off without a VAPID pair, and this test wants both transports.
        vapid_private_key="key",
        vapid_contact="mailto:admin@example.com",
        web_sender=web,
        apns=provider,
    )

    for status in ("scheduled", "rescheduled", "fired", "cancelled", "dropped"):
        await service.on_session_resume(
            SessionResumeNotice(
                status=status,
                session_id=SESSION_ID,
                device_id="c5efb1ec-2912-4619-90f7-93b5172fd712",
                owner="admin",
                has_active_subscriber=False,
            )
        )

    assert [payload["rc"]["kind"] for _, payload in web.sent] == [
        KIND_LIMIT_REACHED,
        KIND_RESUMED,
        KIND_RESUME_DROPPED,
    ]
    assert [json.loads(body)["rc"]["kind"] for body in apns_bodies] == [
        KIND_LIMIT_REACHED,
        KIND_RESUMED,
        KIND_RESUME_DROPPED,
    ]
    assert json.loads(apns_bodies[0])["aps"]["alert"]["body"] == (
        "mac-studio-office: paused by the usage limit"
    )


async def test_no_resume_push_while_an_app_is_watching(tmp_path: Path) -> None:
    store = PushStore(tmp_path / "push.sqlite3")
    await store.upsert_web(
        WebPushSubscription(
            endpoint=ENDPOINT,
            p256dh=KEY,
            auth=KEY,
            session_jti="j" * 20,
            username="admin",
            expires_at=9_999_999_999,
        )
    )
    web = FakeWebPushSender()
    service = PushService(
        store,
        device_name=_device_name,
        vapid_private_key="key",
        vapid_contact="mailto:admin@example.com",
        web_sender=web,
    )
    await service.on_session_resume(
        SessionResumeNotice(
            status="scheduled",
            session_id=SESSION_ID,
            device_id="d",
            owner="admin",
            has_active_subscriber=True,
        )
    )
    assert web.sent == []


async def _device_name(device_id: str) -> str:
    return "mac-studio-office"


def _p256_pem() -> bytes:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    key = ec.generate_private_key(ec.SECP256R1())
    return key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
