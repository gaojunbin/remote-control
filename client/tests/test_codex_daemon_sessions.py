"""Amendment A11: Codex sessions driven through the shared app-server daemon."""

from __future__ import annotations

import asyncio
import os
from pathlib import Path
from typing import Any

import pytest

from rc_client.agents.codex.daemon import approvals, terminals, threads
from rc_client.agents.codex.daemon.service import CodexDaemonService, thread_config
from rc_client.agents.codex.daemon.session import CodexDaemonSession
from rc_client.errors import RcError
from rc_client.models import AgentInfo, Choice
from rc_client.procscan import Proc
from rc_client.registry import Registry
from rc_client.sessions.hub import SessionHub
from tests.fake_codex_daemon import FakeDaemon, FakeTerminals

THREAD = "01a08bde-23d6-7262-a563-68dcfb2c4b59"


def agents() -> list[AgentInfo]:
    return [
        AgentInfo(
            agent="codex",
            available=True,
            path="/bin/codex",
            models=[Choice("gpt-5.4-codex", "GPT-5.4 Codex")],
            default_model="gpt-5.4-codex",
            permission_modes=[Choice("on-request", "Ask when needed")],
            default_permission_mode="on-request",
            efforts=[Choice("low", "Low"), Choice("medium", "Medium")],
            capabilities=["interrupt", "queue", "steer", "history", "attachments", "effort"],
            attach="daemon",
            attach_ready=True,
            shared_interrupt=True,
            shared_settings=True,
            shared_attachments=True,
        )
    ]


def thread_row(thread_id: str = THREAD, **extra: Any) -> dict[str, Any]:
    row = {
        "id": thread_id,
        "sessionId": thread_id,
        "cwd": "/repo",
        "preview": "Typecheck the web app",
        "ephemeral": False,
        "createdAt": 1788946200000,
        "updatedAt": 1788946241700,
        "model": "gpt-5.4-codex",
        "reasoningEffort": "medium",
        "status": {"type": "idle"},
    }
    row.update(extra)
    return row


class Harness:
    """A hub with the daemon service wired to an in-process fake daemon."""

    def __init__(self, tmp_path: Path, daemon: FakeDaemon) -> None:
        self.frames: list[dict[str, Any]] = []
        self.registry = Registry(tmp_path / "state.sqlite3")

        async def publish(frame: dict[str, Any]) -> None:
            self.frames.append(frame)

        self.hub = SessionHub(self.registry, publish, "dev-1", agents)
        # A terminal is sitting in the threads' directory until a test says otherwise.
        self.terminals = FakeTerminals({"/repo"})
        self.service = CodexDaemonService(self.hub, "0.1.0", scan_terminals=self.terminals)
        self.hub.codex_daemon = self.service
        self.daemon = daemon

    def bubbles(self) -> dict[str, str]:
        """`block_id -> source` for the user messages, one entry per bubble.

        The daemon sends the same item on `item/started` and `item/completed`,
        and both events carry the same block id, so the apps show one bubble.
        """
        return {event["block_id"]: event["source"] for event in self.events("user_message")}

    def events(self, kind: str) -> list[dict[str, Any]]:
        return [
            frame["event"]
            for frame in self.frames
            if frame.get("type") == "session.event" and frame["event"]["kind"] == kind
        ]

    def close(self) -> None:
        self.registry.close()


@pytest.fixture
async def harness(tmp_path: Path, socket_dir: Path, monkeypatch: pytest.MonkeyPatch) -> Any:
    server = FakeDaemon(socket_dir / "codex.sock")
    await server.start()
    monkeypatch.setenv("RC_CODEX_DAEMON_SOCKET", str(server.path))
    built = Harness(tmp_path, server)
    try:
        yield built
    finally:
        await built.service.stop()
        await server.stop()
        built.close()


async def started(harness: Harness, loaded: list[str] | None = None) -> None:
    harness.daemon.replies["thread/list"] = {"data": [thread_row()]}
    harness.daemon.replies["thread/loaded/list"] = {"data": loaded if loaded is not None else []}
    harness.daemon.replies["thread/resume"] = {
        "thread": thread_row(),
        "model": "gpt-5.4-codex",
        "reasoningEffort": "medium",
        "approvalPolicy": "on-request",
    }
    harness.daemon.replies["thread/items/list"] = {"data": []}
    harness.daemon.replies["turn/start"] = {"turn": {"id": "turn-1"}}
    harness.daemon.replies["turn/steer"] = {"turnId": "turn-1"}
    assert await harness.service.start("/bin/codex") is True


async def settle(predicate: Any, timeout: float = 2.0) -> None:
    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        if predicate():
            return
        await asyncio.sleep(0.01)
    raise AssertionError("condition never became true")


# ------------------------------------------------------------ origin/control


def test_the_amendment_table_maps_every_situation() -> None:
    assert threads.resolve(created_here=True, loaded=True, terminal_seen=False) == (
        "remote",
        "remote",
    )
    assert threads.resolve(created_here=True, loaded=True, terminal_seen=True) == (
        "remote",
        "shared",
    )
    assert threads.resolve(created_here=False, loaded=True, terminal_seen=False) == (
        "terminal",
        "shared",
    )
    assert threads.resolve(created_here=False, loaded=False, terminal_seen=True) == (
        "terminal",
        "none",
    )


def test_a_thread_whose_terminal_left_stays_ours_to_drive() -> None:
    gone = {"created_here": False, "loaded": True, "terminal_seen": True, "terminal_live": False}
    assert threads.resolve(**gone) == ("terminal", "none")
    assert threads.resolve(**gone, local_turn=True) == ("terminal", "remote")
    # A thread this device started never depended on a terminal being there.
    assert threads.resolve(
        created_here=True, loaded=True, terminal_seen=True, terminal_live=False
    ) == ("remote", "remote")


def test_only_a_bare_codex_on_a_terminal_counts_as_a_tui() -> None:
    def proc(command: str, has_tty: bool = True) -> Proc:
        return Proc(pid=7, ppid=1, start="s", command=command, has_tty=has_tty)

    assert terminals.looks_like_a_tui(proc("/Users/me/.local/bin/codex")) is True
    assert terminals.looks_like_a_tui(proc("codex resume 01a08bde")) is True
    # No terminal, a helper subcommand, or an embedded server of its own.
    assert terminals.looks_like_a_tui(proc("codex", has_tty=False)) is False
    assert terminals.looks_like_a_tui(proc("codex app-server --listen unix://")) is False
    assert terminals.looks_like_a_tui(proc("/App/codex -c features.host=true app-server")) is False
    assert terminals.looks_like_a_tui(proc("codex --enable hooks")) is False
    assert terminals.looks_like_a_tui(proc("codex exec review the diff")) is False
    assert terminals.looks_like_a_tui(proc("/usr/bin/python -m http.server")) is False


def test_a_scan_places_a_terminal_by_the_directory_it_runs_in(tmp_path: Path) -> None:
    scan = terminals.TerminalScan(cwds={os.path.realpath(str(tmp_path))}, complete=True)
    assert scan.holds(str(tmp_path)) is True
    assert scan.holds(str(tmp_path / "sub")) is False
    assert scan.holds("") is False


def test_a_title_generation_thread_is_never_a_session() -> None:
    rows = [thread_row(), thread_row("ephemeral-1", ephemeral=True)]
    assert [item.thread_id for item in threads.summaries(rows)] == [THREAD]


def test_an_active_status_is_recognised() -> None:
    assert threads.is_active({"type": "active", "activeFlags": []}) is True
    assert threads.is_active({"type": "idle"}) is False


# ------------------------------------------------------------------ indexing


async def test_history_becomes_resumable_sessions_and_loaded_ones_become_shared(
    harness: Harness,
) -> None:
    await started(harness, loaded=[])
    assert harness.hub.entry(THREAD).session.control == "none"
    assert harness.hub.entry(THREAD).session.origin == "terminal"

    harness.daemon.replies["thread/loaded/list"] = {"data": [THREAD]}
    await harness.service.refresh()
    entry = harness.hub.entry(THREAD)
    assert entry.session.control == "shared"
    assert isinstance(entry.runner, CodexDaemonSession)
    assert harness.daemon.sent("thread/resume")[-1]["excludeTurns"] is True


async def test_a_thread_started_elsewhere_becomes_a_shared_session(harness: Harness) -> None:
    await started(harness, loaded=[])
    await harness.daemon.notify("thread/started", {"thread": thread_row("t-new")})
    await settle(lambda: "t-new" in harness.hub.entries)
    assert harness.hub.entry("t-new").session.control == "shared"
    assert harness.hub.entry("t-new").session.origin == "terminal"


async def test_an_ephemeral_thread_start_is_ignored(harness: Harness) -> None:
    await started(harness, loaded=[])
    await harness.daemon.notify("thread/started", {"thread": thread_row("t-title", ephemeral=True)})
    await asyncio.sleep(0.1)
    assert "t-title" not in harness.hub.entries


async def test_closing_a_thread_hands_the_session_back(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    await harness.daemon.notify("thread/closed", {"threadId": THREAD})
    await settle(lambda: harness.hub.entry(THREAD).runner is None)
    assert harness.hub.entry(THREAD).session.control == "none"


async def test_a_message_typed_in_the_terminal_makes_a_remote_thread_shared(
    harness: Harness,
) -> None:
    await started(harness, loaded=[THREAD])
    entry = harness.hub.entry(THREAD)
    entry.session.origin = "remote"
    await harness.service.publish_control(entry)
    assert entry.session.control == "remote"

    await harness.daemon.notify(
        "item/completed",
        {
            "threadId": THREAD,
            "item": {
                "id": "u1",
                "type": "userMessage",
                "clientId": "61d36cea",
                "content": [{"text": "hello"}],
            },
        },
    )
    await settle(lambda: harness.hub.entry(THREAD).session.control == "shared")
    assert [event["source"] for event in harness.events("user_message")] == ["terminal"]


# ------------------------------------------------------------ terminal exits


async def test_a_tui_that_exits_hands_an_idle_thread_back(harness: Harness) -> None:
    """The daemon says nothing when a TUI leaves; the missing process is the news."""
    await started(harness, loaded=[THREAD])
    assert harness.hub.entry(THREAD).session.control == "shared"

    harness.terminals.cwds = set()
    await harness.service.refresh()

    entry = harness.hub.entry(THREAD)
    assert entry.session.control == "none"
    assert entry.session.origin == "terminal"
    # The subscription stays: the thread is still loaded and still ours to read.
    assert isinstance(entry.runner, CodexDaemonSession)
    assert harness.events("meta")[-1]["control"] == "none"


async def test_a_tui_that_exits_mid_turn_leaves_our_own_turn_running(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    await harness.hub.send({"id": "req-1", "session_id": THREAD, "text": "go"})

    harness.terminals.cwds = set()
    await harness.service.refresh_terminals()
    assert harness.hub.entry(THREAD).session.control == "remote"

    await harness.daemon.notify(
        "turn/completed", {"threadId": THREAD, "turn": {"id": "turn-1", "status": "completed"}}
    )
    await settle(lambda: harness.hub.entry(THREAD).session.control == "none")


async def test_a_terminal_that_types_again_takes_the_thread_back(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    harness.terminals.cwds = set()
    await harness.service.refresh_terminals()
    assert harness.hub.entry(THREAD).session.control == "none"

    await harness.daemon.echo_prompt(THREAD, "back at my desk", item_id="u9", client_id="tui-7")
    await settle(lambda: harness.hub.entry(THREAD).session.control == "shared")


async def test_a_terminal_that_just_typed_outranks_the_next_scan(harness: Harness) -> None:
    """A TUI resumed from another directory is invisible to the scan, not gone."""
    await started(harness, loaded=[THREAD])
    harness.terminals.cwds = set()
    await harness.daemon.echo_prompt(THREAD, "still here", item_id="u9", client_id="tui-7")
    await settle(lambda: len(harness.bubbles()) == 1)

    await harness.service.refresh_terminals()
    assert harness.hub.entry(THREAD).session.control == "shared"
    await harness.service.refresh_terminals()
    assert harness.hub.entry(THREAD).session.control == "none"


async def test_an_incomplete_scan_never_hands_a_session_over(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    harness.terminals.cwds = set()
    harness.terminals.complete = False
    await harness.service.refresh_terminals()
    assert harness.hub.entry(THREAD).session.control == "shared"


async def test_a_terminal_that_comes_back_makes_the_thread_shared_again(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    harness.terminals.cwds = set()
    await harness.service.refresh_terminals()
    assert harness.hub.entry(THREAD).session.control == "none"

    harness.terminals.cwds = {"/repo"}
    await harness.service.refresh_terminals()
    assert harness.hub.entry(THREAD).session.control == "shared"


async def test_nothing_is_scanned_while_no_thread_claims_a_terminal(harness: Harness) -> None:
    await started(harness, loaded=[])
    scans = harness.terminals.scans
    await harness.service.refresh()
    assert harness.terminals.scans == scans


# -------------------------------------------------------------------- input


async def test_sending_while_idle_starts_a_turn_and_shows_one_bubble(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    result = await harness.hub.send({"id": "req-1", "session_id": THREAD, "text": "go"})

    assert result == {"accepted": "sent"}
    start = harness.daemon.sent("turn/start")[-1]
    assert start["input"] == [{"type": "text", "text": "go"}]
    messages = harness.events("user_message")
    assert len(messages) == 1
    assert messages[0]["delivery"] == "delivered"


async def test_our_own_prompt_is_not_echoed_twice(harness: Harness) -> None:
    """The daemon replays the prompt on `item/started` and again on `item/completed`."""
    await started(harness, loaded=[THREAD])
    await harness.hub.send({"id": "req-1", "session_id": THREAD, "text": "go"})
    await harness.daemon.echo_prompt(THREAD, "go", item_id="u1")
    await asyncio.sleep(0.1)
    assert list(harness.bubbles().values()) == ["remote"]


async def test_a_stamped_echo_of_our_own_prompt_is_still_ours(harness: Harness) -> None:
    """A `clientId` on our own echo must not read as a terminal sharing the thread."""
    await started(harness, loaded=[THREAD])
    entry = harness.hub.entry(THREAD)
    entry.session.origin = "remote"
    await harness.service.publish_control(entry)
    harness.daemon.echo_client_id = "dev-99"

    await harness.hub.send({"id": "req-1", "session_id": THREAD, "text": "go"})
    await harness.daemon.echo_prompt(THREAD, "go", item_id="u1")
    await asyncio.sleep(0.1)
    assert list(harness.bubbles().values()) == ["remote"]
    assert harness.hub.entry(THREAD).session.control == "remote"

    # Another client id on a message we never sent is the terminal typing.
    await harness.daemon.echo_prompt(THREAD, "and now this", item_id="u2", client_id="tui-7")
    await settle(lambda: harness.hub.entry(THREAD).session.control == "shared")
    assert list(harness.bubbles().values()) == ["remote", "terminal"]


async def test_only_the_first_match_consumes_an_echo(harness: Harness) -> None:
    """A terminal user typing the same words still gets their own bubble."""
    await started(harness, loaded=[THREAD])
    await harness.hub.send({"id": "req-1", "session_id": THREAD, "text": "go"})
    await harness.daemon.echo_prompt(THREAD, "go", item_id="u1")
    await harness.daemon.echo_prompt(THREAD, "go", item_id="u2")
    await settle(lambda: len(harness.bubbles()) == 2)
    assert list(harness.bubbles().values()) == ["remote", "terminal"]


async def test_a_backfilled_echo_is_not_republished(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    await harness.hub.send({"id": "req-1", "session_id": THREAD, "text": "go"})
    await harness.daemon.echo_prompt(THREAD, "go", item_id="u1")
    await asyncio.sleep(0.1)
    harness.daemon.replies["thread/items/list"] = {
        "data": [
            {
                "type": "userMessage",
                "id": "u1",
                "clientId": None,
                "content": [{"type": "text", "text": "go"}],
            }
        ]
    }
    runner = harness.hub.entry(THREAD).runner
    assert isinstance(runner, CodexDaemonSession)
    await runner.backfill()
    assert list(harness.bubbles().values()) == ["remote"]


async def test_sending_during_a_turn_steers_it(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    await harness.hub.send({"id": "req-1", "session_id": THREAD, "text": "go"})
    result = await harness.hub.send({"id": "req-2", "session_id": THREAD, "text": "actually stop"})

    assert result == {"accepted": "steered"}
    steer = harness.daemon.sent("turn/steer")[-1]
    assert steer["expectedTurnId"] == "turn-1"


async def test_an_explicit_queue_holds_the_message_until_the_turn_ends(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    await harness.hub.send({"id": "req-1", "session_id": THREAD, "text": "go"})
    result = await harness.hub.send(
        {"id": "req-2", "session_id": THREAD, "text": "then this", "mode": "queue"}
    )
    assert result == {"accepted": "queued", "queued_id": "req-2"}
    assert harness.events("queue")[-1]["pending"][0]["text"] == "then this"

    await harness.daemon.notify(
        "turn/completed", {"threadId": THREAD, "turn": {"id": "turn-1", "status": "completed"}}
    )
    await settle(lambda: len(harness.daemon.sent("turn/start")) == 2)
    assert harness.daemon.sent("turn/start")[-1]["input"][0]["text"] == "then this"


async def test_stopping_interrupts_the_turn_whoever_started_it(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    await harness.daemon.notify("turn/started", {"threadId": THREAD, "turn": {"id": "turn-9"}})
    await settle(lambda: harness.hub.entry(THREAD).session.turn is not None)

    async def stop() -> None:
        await harness.hub.stop({"session_id": THREAD})

    task = asyncio.create_task(stop())
    interrupt = await harness.daemon.wait_for_call("turn/interrupt")
    assert interrupt == {"threadId": THREAD, "turnId": "turn-9"}
    await harness.daemon.notify(
        "turn/completed", {"threadId": THREAD, "turn": {"id": "turn-9", "status": "interrupted"}}
    )
    await task
    assert harness.events("turn_completed")[-1]["stop_reason"] == "interrupted"


async def test_settings_reach_the_daemon_because_they_are_shared(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    await harness.hub.set_options(
        {"session_id": THREAD, "permission_mode": "on-request", "effort": "low"}
    )
    update = harness.daemon.sent("thread/settings/update")[-1]
    assert update["threadId"] == THREAD
    assert update["approvalPolicy"] == "on-request"
    assert update["effort"] == "low"


async def test_a_settings_change_from_the_terminal_is_mirrored(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    await harness.daemon.notify(
        "thread/settings/updated",
        {"threadId": THREAD, "threadSettings": {"effort": "low", "model": "gpt-5.4-codex"}},
    )
    await settle(lambda: harness.hub.entry(THREAD).session.effort == "low")


async def test_takeover_is_a_conflict_on_a_shared_session(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    with pytest.raises(RcError) as caught:
        await harness.hub.takeover({"session_id": THREAD})
    assert caught.value.code == "conflict"
    assert caught.value.message == "already attached"


async def test_images_travel_as_inputs_and_other_files_are_named_in_the_prompt(
    harness: Harness,
) -> None:
    await started(harness, loaded=[THREAD])
    await harness.hub.send(
        {
            "id": "req-1",
            "session_id": THREAD,
            "text": "look",
            "attachments": [
                {"name": "shot.png", "mime": "image/png", "data_base64": "aGk="},
                {"name": "notes.txt", "mime": "text/plain", "data_base64": "aGk="},
            ],
        }
    )
    inputs = harness.daemon.sent("turn/start")[-1]["input"]
    assert inputs[0]["type"] == "text"
    assert "notes.txt" in inputs[0]["text"]
    assert inputs[1]["type"] == "localImage"
    assert inputs[1]["path"].endswith("shot.png")


# ---------------------------------------------------------------- approvals


async def test_an_approval_offers_the_options_the_daemon_lists(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    await harness.daemon.ask(
        11,
        "item/commandExecution/requestApproval",
        {
            "threadId": THREAD,
            "turnId": "turn-1",
            "itemId": "exec-1",
            "command": "/bin/zsh -lc 'touch approved.txt'",
            "cwd": "/repo",
            "commandActions": [{"type": "unknown", "command": "touch approved.txt"}],
            "availableDecisions": [
                "accept",
                {"acceptWithExecpolicyAmendment": {"execpolicy_amendment": ["touch"]}},
                "cancel",
            ],
        },
    )
    await settle(lambda: harness.events("approval"))
    card = harness.events("approval")[0]
    assert [option["id"] for option in card["options"]] == ["allow", "allow_always", "deny"]
    assert card["input"]["command_actions"][0]["command"] == "touch approved.txt"
    assert card["tool_kind"] == "shell"

    await harness.hub.approve(
        {"session_id": THREAD, "request_id": card["request_id"], "option_id": "allow"}
    )
    answer = await asyncio.wait_for(harness.daemon.answers.get(), timeout=2)
    assert answer["result"] == {"decision": "accept"}
    assert harness.events("approval")[-1]["decision"] == {"option_id": "allow", "by": "remote"}


async def test_the_amendment_option_carries_its_payload_back(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    amendment = {"acceptWithExecpolicyAmendment": {"execpolicy_amendment": ["touch", "a.txt"]}}
    await harness.daemon.ask(
        12,
        "item/commandExecution/requestApproval",
        {
            "threadId": THREAD,
            "turnId": "turn-1",
            "itemId": "exec-2",
            "command": "touch a.txt",
            "availableDecisions": ["accept", amendment, "cancel"],
        },
    )
    await settle(lambda: harness.events("approval"))
    card = harness.events("approval")[0]
    await harness.hub.approve(
        {"session_id": THREAD, "request_id": card["request_id"], "option_id": "allow_always"}
    )
    answer = await asyncio.wait_for(harness.daemon.answers.get(), timeout=2)
    assert answer["result"] == {"decision": amendment}


async def test_a_prompt_answered_in_the_terminal_resolves_as_elsewhere(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    await harness.daemon.ask(
        13,
        "item/commandExecution/requestApproval",
        {
            "threadId": THREAD,
            "turnId": "turn-1",
            "itemId": "exec-3",
            "command": "touch b.txt",
            "availableDecisions": ["accept", "cancel"],
        },
    )
    await settle(lambda: harness.events("approval"))
    await harness.daemon.notify("serverRequest/resolved", {"threadId": THREAD, "requestId": 13})
    await settle(lambda: len(harness.events("approval")) >= 2)
    last = harness.events("approval")[-1]
    assert last["status"] == "resolved"
    assert last["decision"] == {"option_id": "elsewhere", "by": "terminal"}
    answer = await asyncio.wait_for(harness.daemon.answers.get(), timeout=2)
    assert answer["result"] == {}


async def test_a_question_is_answered_with_the_option_labels(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    await harness.daemon.ask(
        14,
        "item/tool/requestUserInput",
        {
            "threadId": THREAD,
            "turnId": "turn-1",
            "itemId": "ask-1",
            "isBlocking": True,
            "questions": [
                {
                    "id": "q1",
                    "question": "Which one?",
                    "options": [{"label": "Left"}, {"label": "Right"}],
                }
            ],
        },
    )
    await settle(lambda: harness.events("question"))
    card = harness.events("question")[0]
    await harness.hub.answer(
        {"session_id": THREAD, "request_id": card["request_id"], "answers": {"q1": "o1"}}
    )
    answer = await asyncio.wait_for(harness.daemon.answers.get(), timeout=2)
    assert answer["result"] == {"answers": {"q1": {"answers": ["Right"]}}}


def test_option_mapping_covers_the_amendment_vocabulary() -> None:
    built = approvals.build_options(["accept", "acceptForSession", "cancel"], ())
    assert [option["id"] for option in built.options] == ["allow", "allow_session", "deny"]
    assert [option["style"] for option in built.options] == ["primary", "secondary", "danger"]
    assert built.decisions["deny"] == "cancel"


def test_decline_is_dropped_when_cancel_is_also_offered() -> None:
    built = approvals.build_options(["accept", "decline", "cancel"], ())
    assert [option["id"] for option in built.options] == ["allow", "deny"]
    assert built.decisions["deny"] == "cancel"


def test_decline_becomes_deny_when_it_is_the_only_refusal() -> None:
    built = approvals.build_options(["accept", "decline"], ())
    assert built.decisions["deny"] == "decline"


def test_a_prompt_with_no_decisions_falls_back_to_the_known_set() -> None:
    built = approvals.build_options(None, approvals.FILE_CHANGE_DECISIONS)
    assert [option["id"] for option in built.options] == ["allow", "allow_session", "deny"]


def test_a_permission_grant_is_scoped_by_the_option_chosen() -> None:
    params = {"permissions": {"fileSystem": {"read": ["/repo"]}}}
    assert approvals.permission_reply("allow", params)["scope"] == "turn"
    assert approvals.permission_reply("allow_session", params)["scope"] == "session"
    assert approvals.permission_reply("deny", params)["permissions"] == {}


# ---------------------------------------------------------------- fallback


async def test_the_hub_uses_the_spawned_app_server_when_the_daemon_is_absent(
    tmp_path: Path, socket_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("RC_CODEX_DAEMON_SOCKET", str(socket_dir / "absent.sock"))
    frames: list[dict[str, Any]] = []

    async def publish(frame: dict[str, Any]) -> None:
        frames.append(frame)

    registry = Registry(tmp_path / "state.sqlite3")
    try:
        hub = SessionHub(registry, publish, "dev-1", agents)
        service = CodexDaemonService(hub, "0.1.0")
        hub.codex_daemon = service
        assert await service.start("/bin/codex") is False
        assert service.ready is False
    finally:
        registry.close()


def test_a_thread_config_override_is_read_from_the_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("RC_CODEX_THREAD_CONFIG", '{"features": {"hooks": false}}')
    assert thread_config() == {"features": {"hooks": False}}
    monkeypatch.setenv("RC_CODEX_THREAD_CONFIG", "not json")
    assert thread_config() is None
    monkeypatch.delenv("RC_CODEX_THREAD_CONFIG")
    assert thread_config() is None


# ------------------------------------------------------- reconnect/backfill


async def test_a_reconnect_re_resumes_and_backfills_from_the_last_item(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    await harness.daemon.notify(
        "item/completed",
        {
            "threadId": THREAD,
            "item": {"id": "a1", "type": "agentMessage", "text": "first"},
        },
    )
    await settle(lambda: harness.events("assistant_text"))
    harness.daemon.replies["thread/items/list"] = {
        "data": [
            {"id": "a2", "type": "agentMessage", "text": "missed"},
            {"id": "a1", "type": "agentMessage", "text": "first"},
        ]
    }
    resumes = len(harness.daemon.sent("thread/resume"))

    await harness.daemon.drop()
    await settle(lambda: len(harness.daemon.sent("thread/resume")) > resumes, timeout=8)
    await settle(
        lambda: (
            [event["text"] for event in harness.events("assistant_text")] == ["first", "missed"]
        ),
        timeout=5,
    )


async def test_a_daemon_bootstrapped_later_switches_the_mode_without_a_restart(
    tmp_path: Path, socket_dir: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Amendment A11: the device re-probes the socket on the mirror's scan interval."""
    socket = socket_dir / "late.sock"
    monkeypatch.setenv("RC_CODEX_DAEMON_SOCKET", str(socket))
    frames: list[dict[str, Any]] = []

    async def publish(frame: dict[str, Any]) -> None:
        frames.append(frame)

    switches: list[bool] = []

    async def on_mode_change() -> None:
        switches.append(True)

    registry = Registry(tmp_path / "state.sqlite3")
    server = FakeDaemon(socket)
    try:
        hub = SessionHub(registry, publish, "dev-1", agents)
        service = CodexDaemonService(hub, "0.1.0", on_mode_change)
        hub.codex_daemon = service
        await service.tick("/bin/codex")
        before = service.ready
        assert (before, switches) == (False, [])

        await server.start()
        server.replies["thread/list"] = {"data": [thread_row()]}
        server.replies["thread/loaded/list"] = {"data": []}
        await service.tick("/bin/codex")
        after = service.ready
        assert (after, switches) == (True, [True])
        assert THREAD in hub.entries
    finally:
        await service.stop()
        await server.stop()
        registry.close()


async def test_an_option_the_block_never_offered_is_a_bad_request(harness: Harness) -> None:
    """PROTOCOL 6.3: `elsewhere` and anything else unoffered is rejected, not obeyed."""
    await started(harness, loaded=[THREAD])
    await harness.daemon.ask(
        21,
        "item/commandExecution/requestApproval",
        {
            "threadId": THREAD,
            "turnId": "turn-1",
            "itemId": "exec-9",
            "command": "touch c.txt",
            "availableDecisions": ["accept", "cancel"],
        },
    )
    await settle(lambda: harness.events("approval"))
    card = harness.events("approval")[0]
    for option_id in ("elsewhere", "allow_session", "nonsense"):
        with pytest.raises(RcError) as caught:
            await harness.hub.approve(
                {
                    "session_id": THREAD,
                    "request_id": card["request_id"],
                    "option_id": option_id,
                }
            )
        assert caught.value.code == "bad_request"
    await harness.hub.approve(
        {"session_id": THREAD, "request_id": card["request_id"], "option_id": "deny"}
    )
    answer = await asyncio.wait_for(harness.daemon.answers.get(), timeout=2)
    assert answer["result"] == {"decision": "cancel"}


async def test_interrupt_mode_stops_the_turn_and_then_sends(harness: Harness) -> None:
    """A11 clarification: `mode` keeps the protocol vocabulary on a shared thread."""
    await started(harness, loaded=[THREAD])
    await harness.hub.send({"id": "req-1", "session_id": THREAD, "text": "go"})

    async def send_interrupting() -> dict[str, Any]:
        return await harness.hub.send(
            {
                "id": "req-2",
                "session_id": THREAD,
                "text": "no, this instead",
                "mode": "interrupt",
            }
        )

    task = asyncio.create_task(send_interrupting())
    await harness.daemon.wait_for_call("turn/interrupt")
    await harness.daemon.notify(
        "turn/completed", {"threadId": THREAD, "turn": {"id": "turn-1", "status": "interrupted"}}
    )
    assert await task == {"accepted": "sent"}
    assert harness.daemon.sent("turn/start")[-1]["input"][0]["text"] == "no, this instead"
    assert harness.events("turn_completed")[0]["stop_reason"] == "interrupted"


async def test_a_thread_deleted_in_codex_stops_being_a_session(harness: Harness) -> None:
    """`codex delete` removes the thread, so the device must not keep a ghost of it."""
    other = "01a08c99-0000-7000-8000-000000000001"
    harness.daemon.replies["thread/list"] = {"data": [thread_row(), thread_row(other)]}
    harness.daemon.replies["thread/loaded/list"] = {"data": []}
    harness.daemon.replies["thread/items/list"] = {"data": []}
    assert await harness.service.start("/bin/codex") is True
    assert {THREAD, other} <= set(harness.hub.entries)

    harness.daemon.replies["thread/list"] = {"data": [thread_row()]}
    await harness.service.refresh()

    assert other not in harness.hub.entries
    assert THREAD in harness.hub.entries
    removed = [f for f in harness.frames if f.get("type") == "session.removed"]
    assert [f["session_id"] for f in removed] == [other]
    assert harness.service.knows(other) is False


async def test_an_older_thread_that_fell_off_the_page_is_kept(harness: Harness) -> None:
    """A page is not the whole history: absence only proves deletion above the cutoff."""
    old = "01a08c99-0000-7000-8000-000000000002"
    harness.daemon.replies["thread/list"] = {
        "data": [thread_row(), thread_row(old, updatedAt=1788000000000)]
    }
    harness.daemon.replies["thread/loaded/list"] = {"data": []}
    harness.daemon.replies["thread/items/list"] = {"data": []}
    assert await harness.service.start("/bin/codex") is True

    harness.daemon.replies["thread/list"] = {"data": [thread_row()]}
    await harness.service.refresh()

    assert old in harness.hub.entries
    assert [f for f in harness.frames if f.get("type") == "session.removed"] == []


async def test_a_loaded_thread_is_never_forgotten(harness: Harness) -> None:
    """A thread we drive has no rollout to list yet; absence there means nothing."""
    await started(harness, loaded=[THREAD])
    harness.daemon.replies["thread/list"] = {"data": []}
    await harness.service.refresh()
    assert THREAD in harness.hub.entries
    assert [f for f in harness.frames if f.get("type") == "session.removed"] == []


# ------------------------------------------------------------------- titles


async def test_the_name_codex_gives_a_thread_becomes_the_session_title(
    harness: Harness,
) -> None:
    await started(harness, loaded=[THREAD])
    assert harness.hub.entry(THREAD).session.title == "Typecheck the web app"

    await harness.daemon.notify(
        "thread/name/updated", {"threadId": THREAD, "threadName": "Fix the web typecheck"}
    )
    await settle(lambda: harness.hub.entry(THREAD).session.title == "Fix the web typecheck")
    assert harness.events("meta")[-1]["title"] == "Fix the web typecheck"
    updates = [
        frame["session"]["title"]
        for frame in harness.frames
        if frame.get("type") == "session.updated"
    ]
    assert updates[-1] == "Fix the web typecheck"


async def test_a_title_the_user_set_survives_a_rename(harness: Harness) -> None:
    await started(harness, loaded=[THREAD])
    await harness.hub.set_options({"session_id": THREAD, "title": "Ledger migration"})

    await harness.daemon.notify(
        "thread/name/updated", {"threadId": THREAD, "threadName": "Fix the web typecheck"}
    )
    await asyncio.sleep(0.1)
    assert harness.hub.entry(THREAD).session.title == "Ledger migration"


async def test_a_named_thread_in_history_is_titled_by_its_name(harness: Harness) -> None:
    harness.daemon.replies["thread/list"] = {"data": [thread_row(name="Fix the web typecheck")]}
    harness.daemon.replies["thread/loaded/list"] = {"data": []}
    assert await harness.service.start("/bin/codex") is True
    assert harness.hub.entry(THREAD).session.title == "Fix the web typecheck"


def test_a_thread_summary_separates_its_name_from_its_first_prompt() -> None:
    assert threads.ThreadSummary.parse(thread_row()).name == ""  # type: ignore[union-attr]
    named = threads.ThreadSummary.parse(thread_row(name="Fix the web typecheck"))
    assert named is not None
    assert (named.name, named.title) == ("Fix the web typecheck", "Fix the web typecheck")
