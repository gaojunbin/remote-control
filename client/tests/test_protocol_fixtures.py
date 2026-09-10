"""Decode the frozen protocol fixtures with the device's own types."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path
from typing import Any

import pytest

from rc_client.errors import RcError
from rc_client.events import HISTORY_KINDS, bound_event, dedup_key, should_store
from rc_client.models import AgentInfo, Choice, Session
from rc_client.registry import Registry
from tests.helpers import FIXTURE_ROOT, event_validator, load_fixture


def fixture_names(subdirectory: str) -> list[str]:
    directory = FIXTURE_ROOT / subdirectory
    if not directory.is_dir():
        return []
    return sorted(path.name for path in directory.glob("*.json"))


def agent_info_from(payload: dict[str, Any]) -> AgentInfo:
    return AgentInfo(
        agent=payload["agent"],
        available=payload["available"],
        version=payload.get("version"),
        path=payload.get("path"),
        models=[Choice(**item) for item in payload.get("models") or []],
        default_model=payload.get("default_model"),
        permission_modes=[Choice(**item) for item in payload.get("permission_modes") or []],
        default_permission_mode=payload.get("default_permission_mode"),
        efforts=[Choice(**item) for item in payload.get("efforts") or []],
        default_effort=payload.get("default_effort"),
        capabilities=list(payload.get("capabilities") or []),
        attach=payload.get("attach"),
        attach_ready=bool(payload.get("attach_ready")),
        shared_interrupt=bool(payload.get("shared_interrupt")),
    )


def test_device_hello_round_trips_through_the_device_types() -> None:
    hello = load_fixture("device/hello.json")
    assert hello["protocol"] == 1
    agents = [agent_info_from(item) for item in hello["agents"]]
    # The A10 attachment fields are optional, so a fixture predating them still
    # round-trips: every key it carries must survive, extras are additions.
    for info, payload in zip(agents, hello["agents"], strict=True):
        produced = info.to_dict()
        assert {key: produced[key] for key in payload} == payload
        assert set(produced) - set(payload) <= {"attach", "attach_ready", "shared_interrupt"}
    sessions = [Session.from_dict(item) for item in hello["sessions"]]
    assert [session.to_dict() for session in sessions] == hello["sessions"]


def test_hello_ack_carries_the_delta_and_event_limits_this_device_uses() -> None:
    from rc_client.events import DELTA_FLUSH_MS, MAX_EVENT_BYTES

    ack = load_fixture("device/hello_ack.json")
    assert ack["config"]["delta_flush_ms"] == DELTA_FLUSH_MS
    assert ack["config"]["max_event_bytes"] == MAX_EVENT_BYTES


def test_error_reply_codes_are_ones_this_device_can_raise() -> None:
    reply = load_fixture("device/reply.error.json")
    error = RcError(reply["error"]["code"], reply["error"]["message"])
    assert error.to_dict() == reply["error"]


@pytest.mark.parametrize("name", fixture_names("events"))
def test_every_event_fixture_is_classified_and_bounded_consistently(name: str) -> None:
    event = load_fixture(f"events/{name}")
    assert bound_event(event) == event, "fixtures must already respect the size bounds"

    kind = event["kind"]
    key = dedup_key(event)
    if kind in HISTORY_KINDS:
        assert key is not None
    else:
        assert key is None
        assert should_store(event) is False

    validator = event_validator()
    if validator is not None:
        validator.validate(event)


def test_streaming_fixtures_are_deltas_and_final_ones_are_stored() -> None:
    delta = load_fixture("events/assistant_text.delta.json")
    done = load_fixture("events/assistant_text.done.json")
    assert delta["done"] is False
    assert should_store(delta) is False
    assert should_store(done) is True
    assert delta["block_id"] == done["block_id"]


def test_tool_call_and_approval_fixtures_use_tool_kind_for_the_tool_category() -> None:
    for name in ("events/approval.pending.json", "device/session.event.json"):
        payload = load_fixture(name)
        event = payload.get("event", payload)
        if event["kind"] in {"tool_call", "approval"}:
            assert "tool_kind" in event
            assert event["kind"] != event["tool_kind"]


def test_approval_fixtures_offer_a_primary_and_a_danger_option() -> None:
    approval = load_fixture("events/approval.pending.json")
    styles = {option["style"] for option in approval["options"]}
    assert "primary" in styles
    assert "danger" in styles


def test_a_history_page_fixture_replays_through_the_registry(tmp_path: Any) -> None:
    page = load_fixture("history/page.json")
    events = page["result"]["events"] if "result" in page else page["events"]
    registry = Registry(tmp_path / "state.sqlite3")
    for event in events:
        registry.store_event("sess", event, event)
    stored, _ = registry.history("sess", limit=1000)
    assert [event["seq"] for event in stored] == [event["seq"] for event in events]
    registry.close()


@pytest.mark.parametrize("name", fixture_names("device/forwarded"))
def test_every_forwarded_request_has_a_device_handler(name: str) -> None:
    from rc_client.config import Config
    from rc_client.daemon import Daemon

    request = load_fixture(f"device/forwarded/{name}")
    daemon = Daemon(
        Config(
            gateway_origin="https://rc.example.com",
            device_id="dev-1",
            device_token="t",
            name="test",
        )
    )
    try:
        assert request["type"] in daemon._handlers()
        assert request["id"]
        assert request.get("from")
    finally:
        daemon.registry.close()


@pytest.mark.parametrize("agent", ["claude", "codex"])
def test_timeline_fixtures_replay_through_the_registry_and_the_bounds(agent: str) -> None:
    """A whole recorded session must classify and page exactly as this device stores it."""
    path = FIXTURE_ROOT / "timelines" / f"{agent}.json"
    if not path.exists():
        pytest.skip(f"timeline fixture for {agent} is not present")
    payload = json.loads(path.read_text(encoding="utf-8"))
    events = [frame["event"] for frame in payload["frames"] if frame.get("type") == "session.event"]
    assert events

    emitted = HISTORY_KINDS | {"status", "meta", "queue"}
    kinds = {event["kind"] for event in events}
    assert kinds <= emitted, f"unexpected kinds for {agent}: {kinds - emitted}"
    assert [event["seq"] for event in events] == sorted(event["seq"] for event in events)

    with tempfile.TemporaryDirectory() as directory:
        registry = Registry(Path(directory) / "state.sqlite3")
        for event in events:
            assert bound_event(event) == event
            registry.store_event("sess", event, event)
        stored, _ = registry.history("sess", limit=1000)
        replayed = {event["kind"] for event in stored}
        assert replayed <= HISTORY_KINDS
        assert "status" not in replayed
        assert "meta" not in replayed
        assert not any(event.get("delta") for event in stored)

        blocks = {
            event["block_id"] for event in events if "block_id" in event and should_store(event)
        }
        for block_id in blocks:
            assert registry.block("sess", block_id) is not None
        registry.close()


@pytest.mark.parametrize("name", fixture_names("objects"))
def test_every_object_fixture_decodes_with_the_device_types(name: str) -> None:
    payload = load_fixture(f"objects/{name}")
    if name.startswith("session."):
        session = Session.from_dict(payload)
        assert session.to_dict() == payload
    elif name.startswith("agent."):
        info = agent_info_from(payload)
        assert info.to_dict() == payload
    else:  # pragma: no cover - a new object family needs a decoder here
        pytest.fail(f"no device decoder for objects/{name}")


def test_the_shared_session_fixtures_match_what_an_attached_session_reports() -> None:
    """A10 4.4/4.5: `shared` is idle or running, never `readonly`."""
    idle = Session.from_dict(load_fixture("objects/session.shared-idle.json"))
    running = Session.from_dict(load_fixture("objects/session.shared-running.json"))
    assert idle.control == "shared" and idle.state == "idle" and idle.turn is None
    assert running.control == "shared" and running.state in {"running", "needs_approval"}
    assert idle.origin == "terminal"


def test_the_attachable_agent_fixture_matches_what_this_device_advertises() -> None:
    from rc_client.sessions.shared import APPROVAL_OPTIONS

    info = agent_info_from(load_fixture("objects/agent.claude-attach.json"))
    assert info.attach == "channel"
    assert info.attach_ready is True
    assert info.shared_interrupt is False
    assert [option["id"] for option in APPROVAL_OPTIONS] == ["allow", "deny"]


@pytest.mark.parametrize("delivery", ["pending", "delivered", "absorbed"])
def test_the_delivery_fixtures_cover_every_state_the_device_emits(delivery: str) -> None:
    event = load_fixture(f"events/user_message.{delivery}.json")
    assert event["delivery"] == delivery
    assert event["source"] == "remote"
    assert event["kind"] == "user_message"
    assert should_store(event) is True


def test_the_shared_approval_fixtures_offer_exactly_allow_and_deny() -> None:
    from rc_client.sessions.shared import APPROVAL_OPTIONS

    pending = load_fixture("events/approval.shared-pending.json")
    terminal = load_fixture("events/approval.shared-terminal.json")
    assert pending["options"] == APPROVAL_OPTIONS
    assert terminal["options"] == APPROVAL_OPTIONS
    assert set(pending["input"]) == {"tool_name", "description", "input_preview"}
    assert "diff" not in pending
    assert terminal["decision"] == {"option_id": "allow", "by": "terminal"}
    assert terminal["block_id"] == pending["block_id"]
    assert terminal["first_seq"] == pending["seq"]


def test_the_rejected_values_are_ones_this_device_never_produces() -> None:
    """The negative fixtures: `attached` is not a control, `queued` not a delivery."""
    from rc_client.sessions.shared import pending_item

    invalid = FIXTURE_ROOT.parent / "fixtures_invalid"
    control = json.loads((invalid / "app__control_attached.json").read_text(encoding="utf-8"))
    assert control["session"]["control"] == "attached"
    assert control["session"]["control"] not in {"remote", "terminal", "shared", "none"}

    delivery = json.loads((invalid / "events__delivery_queued.json").read_text(encoding="utf-8"))
    assert delivery["delivery"] == "queued"
    assert set(pending_item("hi", "req").keys()) >= {"message_id", "block_id"}
