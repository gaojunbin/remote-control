"""Amendment A10: a session the device shares with a live terminal CLI."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from rc_client.agents.claude import transcripts
from rc_client.errors import RcError
from rc_client.models import AgentInfo, Choice
from rc_client.registry import Registry
from rc_client.sessions.attach import Attachment
from rc_client.sessions.hub import SessionEntry, SessionHub
from rc_client.sessions.shared import SharedState


class FakeAttachment(Attachment):
    """An attachment that records what it would have sent to the bridge."""

    def __init__(self, session_id: str = "sess-1", cwd: str = "/repo") -> None:
        super().__init__(session_id=session_id, cwd=cwd, pid=4242, claude_version="2.1.267")
        self.injected: list[tuple[str, str]] = []
        self.verdicts: list[tuple[str, str]] = []
        self.reachable = True

    async def inject(self, message_id: str, text: str) -> bool:
        if not self.reachable:
            return False
        self.injected.append((message_id, text))
        return True

    async def relay_permission(self, request_id: str, behavior: str) -> bool:
        self.verdicts.append((request_id, behavior))
        return True

    def detach(self) -> None:
        self.reachable = False


def agents() -> list[AgentInfo]:
    return [
        AgentInfo(
            agent="claude",
            available=True,
            path="/bin/claude",
            models=[Choice("default", "Default")],
            default_model="default",
            capabilities=["takeover", "interrupt", "queue", "history"],
            attach="channel",
            attach_ready=True,
            shared_interrupt=False,
        )
    ]


class Harness:
    """A hub with one attached session and the frames it published."""

    def __init__(self, tmp_path: Path) -> None:
        self.frames: list[dict[str, Any]] = []
        self.registry = Registry(tmp_path / "state.sqlite3")

        async def publish(frame: dict[str, Any]) -> None:
            self.frames.append(frame)

        self.hub = SessionHub(self.registry, publish, "dev-1", agents)
        self.attachment = FakeAttachment()

    async def attach(self) -> SessionEntry:
        await self.hub.attach_registered(self.attachment)
        return self.hub.entry("sess-1")

    def events(self, kind: str) -> list[dict[str, Any]]:
        return [
            frame["event"]
            for frame in self.frames
            if frame.get("type") == "session.event" and frame["event"]["kind"] == kind
        ]

    def sessions(self) -> list[dict[str, Any]]:
        return [frame["session"] for frame in self.frames if frame.get("type") == "session.updated"]

    def close(self) -> None:
        self.registry.close()


@pytest.fixture
async def harness(tmp_path: Path) -> Any:
    built = Harness(tmp_path)
    yield built
    built.close()


async def test_registering_creates_a_shared_session_and_announces_the_control_change(
    harness: Harness,
) -> None:
    entry = await harness.attach()
    assert entry.session.control == "shared"
    assert entry.session.origin == "terminal"
    assert entry.session.cwd == "/repo"
    assert entry.session.state == "idle"
    assert harness.events("meta")[0]["control"] == "shared"
    assert harness.sessions()[-1]["control"] == "shared"


async def test_a_shared_session_is_never_readonly(harness: Harness) -> None:
    entry = await harness.attach()
    states = [entry.session.state]
    await harness.hub.shared.tick(entry, running=True)
    states.append(entry.session.state)
    await harness.hub.shared.tick(entry, running=False)
    states.append(entry.session.state)
    assert states == ["idle", "running", "idle"]
    assert "readonly" not in states


async def test_sending_while_idle_injects_at_once_and_shows_one_delivered_bubble(
    harness: Harness,
) -> None:
    entry = await harness.attach()
    result = await harness.hub.send({"id": "req-1", "session_id": "sess-1", "text": "go"})

    assert result == {"accepted": "sent"}
    assert [text for _, text in harness.attachment.injected] == ["go"]
    messages = harness.events("user_message")
    assert len(messages) == 1
    assert messages[0]["delivery"] == "delivered"
    assert messages[0]["source"] == "remote"
    assert entry.queue == []
    assert entry.session.title == "go"


async def test_sending_mid_turn_holds_the_message_until_the_transcript_goes_idle(
    harness: Harness,
) -> None:
    entry = await harness.attach()
    await harness.hub.shared.tick(entry, running=True)

    result = await harness.hub.send({"id": "req-2", "session_id": "sess-1", "text": "later"})
    assert result == {"accepted": "queued", "queued_id": "req-2"}
    assert harness.attachment.injected == []
    assert harness.events("user_message")[-1]["delivery"] == "pending"
    assert harness.events("queue")[-1]["pending"] == [
        {"id": "req-2", "text": "later", "ts": entry.queue[0]["ts"]}
    ]

    await harness.hub.shared.tick(entry, running=False)
    assert [text for _, text in harness.attachment.injected] == ["later"]
    bubbles = harness.events("user_message")
    assert bubbles[-1]["delivery"] == "delivered"
    assert bubbles[-1]["block_id"] == bubbles[-2]["block_id"], "the pending bubble is replaced"
    assert harness.events("queue")[-1]["pending"] == []


async def test_only_one_message_is_in_flight_until_the_transcript_confirms_it(
    harness: Harness,
) -> None:
    entry = await harness.attach()
    await harness.hub.send({"id": "a", "session_id": "sess-1", "text": "one"})
    await harness.hub.send({"id": "b", "session_id": "sess-1", "text": "two"})

    assert [text for _, text in harness.attachment.injected] == ["one"]
    assert [item["text"] for item in entry.queue] == ["two"]

    # The transcript has not caught up yet, so a tick must not release the second.
    await harness.hub.shared.tick(entry, running=False)
    assert [text for _, text in harness.attachment.injected] == ["one"]

    message_id, _ = harness.attachment.injected[0]
    await harness.hub.shared.echo_delivered(entry, message_id)
    await harness.hub.shared.tick(entry, running=False)
    assert [text for _, text in harness.attachment.injected] == ["one", "two"]


async def test_the_transcript_echo_of_an_injection_is_not_a_second_bubble() -> None:
    tailer = transcripts.TranscriptTailer(path="/nonexistent", cwd="/repo")
    row = {
        "type": "user",
        "uuid": "row-1",
        "isMeta": True,
        "origin": {"kind": "channel", "server": "rc"},
        "message": {
            "role": "user",
            "content": '<channel source="rc" origin="app" message_id="m1">\nhello\n</channel>',
        },
    }
    emits = tailer.translate(row)
    assert [emit.kind for emit in emits] == [transcripts.CHANNEL_DELIVERED]
    assert emits[0].fields == {"message_id": "m1"}
    assert tailer.awaiting_reply is True


async def test_a_message_typed_in_the_terminal_still_becomes_a_bubble() -> None:
    tailer = transcripts.TranscriptTailer(path="/nonexistent", cwd="/repo")
    emits = tailer.translate(
        {
            "type": "user",
            "uuid": "row-2",
            "origin": {"kind": "human"},
            "message": {"role": "user", "content": "typed here"},
        }
    )
    assert [emit.kind for emit in emits] == ["user_message"]
    assert emits[0].fields["source"] == "terminal"


async def test_an_absorbed_injection_is_marked_and_re_sent_once(harness: Harness) -> None:
    entry = await harness.attach()
    await harness.hub.send({"id": "req-3", "session_id": "sess-1", "text": "do it"})
    message_id, _ = harness.attachment.injected[0]

    await harness.hub.shared.echo_absorbed(entry, message_id)
    bubbles = harness.events("user_message")
    assert bubbles[-1]["delivery"] == "absorbed"
    assert bubbles[-1]["block_id"] == bubbles[0]["block_id"]
    assert [item["text"] for item in entry.queue] == ["do it"]

    await harness.hub.shared.tick(entry, running=False)
    assert [text for _, text in harness.attachment.injected] == ["do it", "do it"]
    assert harness.events("user_message")[-1]["delivery"] == "delivered"

    # Absorbed a second time: the device stops re-sending and says so.
    second_id, _ = harness.attachment.injected[1]
    await harness.hub.shared.echo_absorbed(entry, second_id)
    assert entry.queue == []
    assert harness.events("notice")[-1]["level"] == "warn"


async def test_the_absorbed_attachment_row_is_recognised_by_its_message_id() -> None:
    tailer = transcripts.TranscriptTailer(path="/nonexistent", cwd="/repo")
    emits = tailer.translate(
        {
            "type": "attachment",
            "attachment": {
                "type": "queued_command",
                "prompt": '<channel source="rc" origin="app" message_id="q1">\nhi\n</channel>',
                "origin": {"kind": "channel", "server": "rc"},
                "isMeta": True,
            },
        }
    )
    assert [emit.kind for emit in emits] == [transcripts.CHANNEL_ABSORBED]
    assert emits[0].fields == {"message_id": "q1"}


async def test_an_approval_is_relayed_with_only_allow_and_deny(harness: Harness) -> None:
    entry = await harness.attach()
    await harness.hub.attach_permission_request(
        harness.attachment,
        {
            "request_id": "rttfm",
            "tool_name": "Write",
            "description": "Write a file to the local filesystem.",
            "input_preview": '{"file_path": "/repo/hello.txt", "content": "hi"}',
        },
    )
    approval = harness.events("approval")[-1]
    assert approval["status"] == "pending"
    assert [option["id"] for option in approval["options"]] == ["allow", "deny"]
    assert approval["tool_kind"] == "write"
    assert approval["title"] == "hello.txt"
    assert approval["input"]["tool_name"] == "Write"
    assert "diff" not in approval
    assert entry.session.state == "needs_approval"

    await harness.hub.approve(
        {"session_id": "sess-1", "request_id": approval["request_id"], "option_id": "allow"}
    )
    assert harness.attachment.verdicts == [("rttfm", "allow")]
    resolved = harness.events("approval")[-1]
    assert resolved["status"] == "resolved"
    assert resolved["decision"] == {"option_id": "allow", "by": "remote"}
    assert resolved["block_id"] == approval["block_id"]
    assert entry.session.state != "needs_approval"


async def test_replying_to_an_approval_the_terminal_already_answered_is_a_no_op(
    harness: Harness,
) -> None:
    await harness.attach()
    assert (
        await harness.hub.approve(
            {"session_id": "sess-1", "request_id": "gone", "option_id": "deny"}
        )
        == {}
    )
    assert harness.attachment.verdicts == []
    with pytest.raises(RcError) as raised:
        await harness.hub.approve(
            {"session_id": "sess-1", "request_id": "gone", "option_id": "allow_session"}
        )
    assert raised.value.code == "bad_request"


async def test_an_approval_answered_in_the_terminal_is_closed_from_the_transcript(
    harness: Harness,
) -> None:
    entry = await harness.attach()
    await harness.hub.attach_permission_request(
        harness.attachment,
        {"request_id": "r1", "tool_name": "Bash", "description": "run", "input_preview": "ls"},
    )
    await harness.hub.shared.tool_finished(entry, "Bash", failed=False)

    resolved = harness.events("approval")[-1]
    assert resolved["decision"] == {"option_id": "allow", "by": "terminal"}
    assert harness.attachment.verdicts == [], "the terminal already answered"

    state = entry.shared
    assert isinstance(state, SharedState)
    assert state.approvals == {}


async def test_a_refused_tool_closes_its_approval_as_denied_in_the_terminal(
    harness: Harness,
) -> None:
    entry = await harness.attach()
    await harness.hub.attach_permission_request(
        harness.attachment,
        {"request_id": "r2", "tool_name": "Write", "description": "w", "input_preview": "{}"},
    )
    await harness.hub.shared.tool_finished(entry, "Write", failed=True)
    assert harness.events("approval")[-1]["decision"]["option_id"] == "deny"


async def test_closing_the_bridge_expires_approvals_and_hands_control_back(
    harness: Harness,
) -> None:
    entry = await harness.attach()
    await harness.hub.attach_permission_request(
        harness.attachment,
        {"request_id": "r3", "tool_name": "Bash", "description": "d", "input_preview": "ls"},
    )
    await harness.hub.shared.closed(entry, harness.attachment, "none")

    assert entry.shared is None
    assert entry.session.control == "none"
    assert entry.session.state != "readonly"
    assert harness.events("approval")[-1]["status"] == "expired"
    assert harness.events("meta")[-1]["control"] == "none"


async def test_closing_the_bridge_with_a_live_cli_leaves_the_session_to_the_terminal(
    harness: Harness,
) -> None:
    entry = await harness.attach()
    await harness.hub.shared.closed(entry, harness.attachment, "terminal")
    assert entry.session.control == "terminal"
    assert entry.session.state == "readonly"


async def test_messages_still_pending_survive_the_detachment(harness: Harness) -> None:
    entry = await harness.attach()
    await harness.hub.shared.tick(entry, running=True)
    await harness.hub.send({"id": "req-4", "session_id": "sess-1", "text": "afterwards"})
    await harness.hub.shared.closed(entry, harness.attachment, "none")

    assert [item["text"] for item in entry.queue] == ["afterwards"]
    assert harness.events("queue")[-1]["pending"][0]["id"] == "req-4"


async def test_the_requests_a_shared_session_refuses(harness: Harness) -> None:
    await harness.attach()
    for params, code in (
        ({"session_id": "sess-1"}, "unsupported"),
        ({"session_id": "sess-1", "request_id": "x", "answers": {}}, "unsupported"),
    ):
        with pytest.raises(RcError) as raised:
            await (harness.hub.stop if "answers" not in params else harness.hub.answer)(params)
        assert raised.value.code == code

    with pytest.raises(RcError) as raised:
        await harness.hub.set_options({"session_id": "sess-1", "model": "opus"})
    assert raised.value.code == "unsupported"

    with pytest.raises(RcError) as raised:
        await harness.hub.takeover({"session_id": "sess-1"})
    assert raised.value.code == "conflict"
    assert raised.value.message == "already attached"

    with pytest.raises(RcError) as raised:
        await harness.hub.send(
            {
                "session_id": "sess-1",
                "text": "look",
                "attachments": [{"name": "a.png", "mime": "image/png", "data_base64": "aGk="}],
            }
        )
    assert raised.value.code == "unsupported"


async def test_the_title_can_still_be_changed_on_a_shared_session(harness: Harness) -> None:
    await harness.attach()
    result = await harness.hub.set_options({"session_id": "sess-1", "title": "Pairing codes"})
    assert result["session"]["title"] == "Pairing codes"


async def test_a_pending_message_can_be_removed_from_the_queue(harness: Harness) -> None:
    entry = await harness.attach()
    await harness.hub.shared.tick(entry, running=True)
    await harness.hub.send({"id": "req-5", "session_id": "sess-1", "text": "never mind"})
    await harness.hub.queue_remove({"session_id": "sess-1", "queued_id": "req-5"})
    await harness.hub.shared.tick(entry, running=False)
    assert harness.attachment.injected == []


async def test_an_injected_turn_opens_and_closes_a_turn_for_the_apps(harness: Harness) -> None:
    entry = await harness.attach()
    await harness.hub.send({"id": "req-t", "session_id": "sess-1", "text": "go"})

    turns = [entry.session.turn]
    assert entry.session.state == "running"
    started = harness.events("turn_started")[-1]
    assert started["trigger"] == "remote"

    message_id, _ = harness.attachment.injected[0]
    await harness.hub.shared.echo_delivered(entry, message_id)
    await harness.hub.shared.tick(entry, running=False)
    turns.append(entry.session.turn)
    assert [turn is not None for turn in turns] == [True, False]
    assert harness.events("turn_completed")[-1]["stop_reason"] == "completed"


async def test_a_turn_the_terminal_started_is_labelled_as_such(harness: Harness) -> None:
    entry = await harness.attach()
    await harness.hub.shared.tick(entry, running=True)
    assert harness.events("turn_started")[-1]["trigger"] == "terminal"
    await harness.hub.shared.tick(entry, running=False)
    assert harness.events("turn_completed")[-1]["stop_reason"] == "completed"


async def test_a_turn_still_open_when_the_bridge_leaves_is_closed_as_stopped(
    harness: Harness,
) -> None:
    entry = await harness.attach()
    await harness.hub.shared.tick(entry, running=True)
    await harness.hub.shared.closed(entry, harness.attachment, "none")
    assert entry.session.turn is None
    assert harness.events("turn_completed")[-1]["stop_reason"] == "stopped"


async def test_a_control_change_alone_emits_meta_without_a_status_event(
    harness: Harness,
) -> None:
    """A10 clarification 3: `status` follows a transition only when `state` moves."""
    entry = await harness.attach()
    assert harness.events("meta")[-1]["control"] == "shared"
    assert harness.events("status") == [], "the session was already idle"

    before = len(harness.events("status"))
    await harness.hub.shared.closed(entry, harness.attachment, "none")
    assert harness.events("meta")[-1]["control"] == "none"
    assert len(harness.events("status")) == before, "idle to idle moves no state"
    assert harness.sessions()[-1]["control"] == "none"


async def test_a_transition_that_moves_the_state_emits_both(harness: Harness) -> None:
    entry = await harness.attach()
    await harness.hub.shared.closed(entry, harness.attachment, "terminal")
    assert harness.events("meta")[-1]["control"] == "terminal"
    assert harness.events("status")[-1]["state"] == "readonly"
    assert harness.sessions()[-1]["state"] == "readonly"
