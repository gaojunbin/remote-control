"""Amendment A23: a host asks to be claimed, an app claims it by scanning."""

from __future__ import annotations

import asyncio
import re
import time

import httpx
import pytest
from fastapi.testclient import TestClient

from rc_gateway.app import create_app
from rc_gateway.auth import make_session_token, session_token_claims
from rc_gateway.pairing_requests import MAX_OUTSTANDING, generate_claim_token, is_claim_token
from rc_gateway.state import GatewayState

from .conftest import ORIGIN, drain_until

TOKEN_PATTERN = re.compile(r"^[0-9A-HJKMNP-TV-Z]{26}$")


def test_claim_tokens_are_crockford_base32() -> None:
    tokens = {generate_claim_token() for _ in range(64)}
    assert len(tokens) == 64
    for token in tokens:
        assert TOKEN_PATTERN.fullmatch(token), token
        assert is_claim_token(token)
    assert not is_claim_token("short")
    assert not is_claim_token("I" * 26)


def test_a_request_is_minted_with_a_scannable_url(client: TestClient) -> None:
    body = client.post("/api/pairing/requests").json()
    assert TOKEN_PATTERN.fullmatch(body["token"])
    assert body["claim_url"] == f"{ORIGIN}/pair#{body['token']}"
    assert body["expires_at"] > int(time.time()) * 1000


def test_the_poll_waits_then_hands_the_code_over_once(
    state: GatewayState, client: TestClient, auth: dict[str, str]
) -> None:
    state.pairing_requests.poll_timeout = 0.05
    token = client.post("/api/pairing/requests").json()["token"]

    waiting = client.get(f"/api/pairing/requests/{token}")
    assert waiting.status_code == 200
    assert waiting.json() == {"status": "waiting"}

    claimed = client.post(f"/api/pairing/requests/{token}/claim", headers=auth)
    assert claimed.status_code == 200
    code = claimed.json()["code"]

    delivered = client.get(f"/api/pairing/requests/{token}")
    assert delivered.json() == {
        "status": "claimed",
        "code": code,
        "expires_at": claimed.json()["expires_at"],
    }
    # Spent: the host has the code, and a replayed poll must not hand it out again.
    assert client.get(f"/api/pairing/requests/{token}").status_code == 404


@pytest.mark.asyncio
async def test_the_poll_wakes_as_soon_as_the_token_is_claimed(state: GatewayState) -> None:
    state.pairing_requests.poll_timeout = 5.0
    app = create_app(state)
    headers = {"Authorization": f"Bearer {await _login(state)}"}
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url=ORIGIN) as http:
        token = (await http.post("/api/pairing/requests")).json()["token"]
        poll = asyncio.create_task(http.get(f"/api/pairing/requests/{token}"))
        await asyncio.sleep(0.05)
        assert not poll.done()
        claimed = await http.post(f"/api/pairing/requests/{token}/claim", headers=headers)
        answered = await asyncio.wait_for(poll, timeout=2.0)
    assert answered.json() == {
        "status": "claimed",
        "code": claimed.json()["code"],
        "expires_at": claimed.json()["expires_at"],
    }


def test_an_expired_token_is_gone(
    state: GatewayState, client: TestClient, auth: dict[str, str]
) -> None:
    state.pairing_requests.ttl = 0
    token = client.post("/api/pairing/requests").json()["token"]
    assert client.get(f"/api/pairing/requests/{token}").status_code == 410
    assert client.post(f"/api/pairing/requests/{token}/claim", headers=auth).status_code == 404
    assert client.get(f"/api/pairing/requests/{token}").status_code == 404


def test_a_token_is_claimed_once(client: TestClient, auth: dict[str, str]) -> None:
    token = client.post("/api/pairing/requests").json()["token"]
    assert client.post(f"/api/pairing/requests/{token}/claim", headers=auth).status_code == 200
    second = client.post(f"/api/pairing/requests/{token}/claim", headers=auth)
    assert second.status_code == 409
    assert second.json()["error"]["code"] == "conflict"


def test_claiming_needs_a_signed_in_user(client: TestClient) -> None:
    token = client.post("/api/pairing/requests").json()["token"]
    assert client.post(f"/api/pairing/requests/{token}/claim").status_code == 401


def test_an_unknown_token_is_not_found(client: TestClient, auth: dict[str, str]) -> None:
    assert client.get("/api/pairing/requests/NOTATOKEN").status_code == 404
    assert client.get(f"/api/pairing/requests/{generate_claim_token()}").status_code == 404
    assert client.post("/api/pairing/requests/NOTATOKEN/claim", headers=auth).status_code == 404


def test_minting_is_rate_limited_per_address(client: TestClient) -> None:
    statuses = [client.post("/api/pairing/requests").status_code for _ in range(8)]
    assert statuses[:6] == [200] * 6
    assert statuses[6:] == [429, 429]
    assert client.post("/api/pairing/requests").json()["error"]["code"] == "too_many_requests"


def test_outstanding_tokens_are_capped(state: GatewayState, client: TestClient) -> None:
    state.pairing_limiter.reset()
    for _ in range(MAX_OUTSTANDING):
        assert state.pairing_requests.mint() is not None
        state.pairing_limiter.reset()
    assert client.post("/api/pairing/requests").status_code == 429
    assert len(state.pairing_requests) == MAX_OUTSTANDING


def test_a_claimed_code_enrols_like_a_typed_one(client: TestClient, auth: dict[str, str]) -> None:
    """The claim mints the ordinary pairing code, progress and enrolment included."""
    with client.websocket_connect("/ws/app", headers=auth) as app:
        drain_until(app, "hello")
        token = client.post("/api/pairing/requests").json()["token"]
        code = client.post(f"/api/pairing/requests/{token}/claim", headers=auth).json()["code"]
        started = drain_until(app, "pairing.progress")
        assert started == {"type": "pairing.progress", "code": code, "step": "waiting"}

        enrolled = client.post(
            "/api/devices/enroll",
            json={
                "code": client.get(f"/api/pairing/requests/{token}").json()["code"],
                "name": "scanned-box",
                "platform": "linux",
                "hostname": "box",
                "arch": "x86_64",
                "client_version": "0.1.0",
            },
        )
        assert enrolled.status_code == 200, enrolled.text
        progress = drain_until(app, "pairing.progress")
    assert progress["step"] == "enrolled"
    assert progress["code"] == code
    assert progress["device"]["name"] == "scanned-box"


async def _login(state: GatewayState) -> str:
    token, _ = make_session_token(state.config.secret, 3600)
    claims = session_token_claims(token, state.config.secret)
    assert claims is not None
    await state.sessions.register(claims)
    return token
