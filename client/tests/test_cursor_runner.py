"""The Cursor runner against a fake `cursor-agent -p`.

The fake is a real child process printing real NDJSON
(`tests/test_cursor_fake_agent.py`), so the spawn, the line reader, the exit
handling and the kill path are exercised end to end.
"""

from __future__ import annotations

import asyncio
import json
import stat
import sys
from collections.abc import AsyncIterator, Callable
from pathlib import Path
from typing import Any

import pytest

from rc_client.agents.cursor import broker
from rc_client.agents.cursor import runtime as cursor_runtime
from rc_client.agents.cursor.adapter import CursorRunner
from rc_client.errors import RcError
from rc_client.models import Session
from rc_client.registry import Registry
from rc_client.sessions.channel import SessionChannel
from tests.helpers import event_validator

CHAT = "9f1c0f4e-1d1c-4a1e-9d6f-2f0f1f7c2c31"
FAKE = Path(__file__).resolve().parent / "test_cursor_fake_agent.py"


class Recorder:
    def __init__(self) -> None:
        self.frames: list[dict[str, Any]] = []

    async def __call__(self, frame: dict[str, Any]) -> None:
        self.frames.append(frame)

    def events(self, kind: str) -> list[dict[str, Any]]:
        return [
            frame["event"]
            for frame in self.frames
            if frame.get("type") == "session.event" and frame["event"]["kind"] == kind
        ]

    def states(self) -> list[str]:
        return [event["state"] for event in self.events("status")]


class Peer:
    """The fake agent's scratch directory, and what the runner asked it."""

    def __init__(self, directory: Path) -> None:
        self.directory = directory

    def argv(self) -> list[str]:
        path = self.directory / "argv.json"
        if not path.exists():
            return []
        loaded: list[str] = json.loads(path.read_text(encoding="utf-8"))
        return loaded


def shim(tmp_path: Path, mode: str) -> tuple[str, Peer]:
    """An executable that answers `cursor-agent -p …` from the fixtures."""
    directory = tmp_path / f"peer-{mode}"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "mode").write_text(mode, encoding="utf-8")
    path = tmp_path / f"cursor-agent-{mode}"
    path.write_text(
        f'#!/bin/sh\nRC_FAKE_CURSOR_DIR="{directory}" exec "{sys.executable}" "{FAKE}" "$@"\n',
        encoding="utf-8",
    )
    path.chmod(path.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
    return str(path), Peer(directory)


def build(
    tmp_path: Path,
    mode: str,
    resume: str | None = None,
    model: str | None = "auto",
    permission_mode: str = "default",
) -> tuple[CursorRunner, Recorder, Peer]:
    registry = Registry(tmp_path / "state.sqlite3")
    session = Session(session_id="temp-1", device_id="d1", agent="cursor", cwd=str(tmp_path))
    registry.upsert_session(session)
    recorder = Recorder()
    channel = SessionChannel(registry, session, recorder)
    binary, peer = shim(tmp_path, mode)
    runner = CursorRunner(
        channel,
        binary=binary,
        cwd=str(tmp_path),
        model=model,
        permission_mode=permission_mode,
        resume=resume,
    )
    return runner, recorder, peer


async def wait_for(check: Callable[[], Any], timeout: float = 15.0) -> None:
    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        if check():
            return
        await asyncio.sleep(0.02)
    raise AssertionError("timed out waiting for the runner")


@pytest.fixture(autouse=True)
async def _broker(socket_dir: Path, monkeypatch: pytest.MonkeyPatch) -> AsyncIterator[None]:
    """A short socket path per test, and no broker left over from the last one."""
    monkeypatch.setattr(cursor_runtime, "socket_path", lambda: socket_dir / "cursor-hook.sock")
    yield
    await broker.reset()


async def test_a_turn_streams_the_lines_the_process_prints(tmp_path: Path) -> None:
    runner, recorder, _ = build(tmp_path, "turn")
    rekeyed: list[str] = []
    runner._on_session_id = _collect(rekeyed)
    await runner.start()
    await runner.send("Reply with exactly OK")
    await wait_for(lambda: recorder.events("turn_completed"))
    await runner.close()

    assert rekeyed == [CHAT]
    assert recorder.events("user_message")[0]["text"] == "Reply with exactly OK"
    assert recorder.events("thinking")[-1]["done"] is True
    assert recorder.events("assistant_text")[-1]["text"] == "OK"
    completed = recorder.events("turn_completed")[0]
    assert completed["stop_reason"] == "completed"
    assert completed["usage"]["total_tokens"] == 4178
    assert recorder.states()[-1] == "idle"


def test_the_command_line_is_print_mode_with_the_prompt_behind_a_separator(
    tmp_path: Path,
) -> None:
    runner, _, _ = build(tmp_path, "turn")
    assert runner.spawn_args("hello") == [
        "-p",
        "--output-format",
        "stream-json",
        "--stream-partial-output",
        "--",
        "hello",
    ]
    runner._model = "gpt-5"
    runner._permission_mode = "plan"
    runner._chat_id = CHAT
    assert runner.spawn_args("hi") == [
        "-p",
        "--output-format",
        "stream-json",
        "--stream-partial-output",
        "--resume",
        CHAT,
        "--model",
        "gpt-5",
        "--mode",
        "plan",
        "--",
        "hi",
    ]


def test_force_mode_is_a_flag_and_default_mode_is_none(tmp_path: Path) -> None:
    runner, _, _ = build(tmp_path, "turn", permission_mode="force")
    assert "--force" in runner.spawn_args("hi")
    runner._permission_mode = "default"
    assert "--force" not in runner.spawn_args("hi")
    assert "--mode" not in runner.spawn_args("hi")


async def test_the_second_turn_resumes_the_chat_the_first_created(tmp_path: Path) -> None:
    runner, recorder, peer = build(tmp_path, "turn")
    await runner.start()
    await runner.send("one")
    await wait_for(lambda: recorder.events("turn_completed"))
    await runner.send("two")
    await wait_for(lambda: len(recorder.events("turn_completed")) == 2)
    await runner.close()
    argv = peer.argv()
    assert argv[argv.index("--resume") + 1] == CHAT
    assert argv[-1] == "two"


async def test_an_interrupt_kills_the_process_and_closes_what_it_left_open(
    tmp_path: Path,
) -> None:
    runner, recorder, _ = build(tmp_path, "slow")
    await runner.start()
    await runner.send("Reply with exactly OK")
    await wait_for(lambda: recorder.events("tool_call"))
    assert await runner.interrupt() is True
    await wait_for(lambda: recorder.events("turn_completed"))
    await runner.close()
    assert recorder.events("turn_completed")[0]["stop_reason"] == "interrupted"
    # The half-written block and the running call are both finished off.
    assert recorder.events("thinking")[-1]["done"] is True
    assert recorder.events("tool_call")[-1]["status"] == "cancelled"


async def test_an_interrupt_without_a_turn_does_nothing(tmp_path: Path) -> None:
    runner, _, _ = build(tmp_path, "turn")
    await runner.start()
    assert await runner.interrupt() is False
    await runner.close()


async def test_a_process_that_exits_badly_ends_the_turn_with_an_error(tmp_path: Path) -> None:
    runner, recorder, _ = build(tmp_path, "fail")
    await runner.start()
    await runner.send("Reply with exactly OK")
    await wait_for(lambda: recorder.events("turn_completed"))
    await runner.close()
    assert recorder.events("turn_completed")[0]["stop_reason"] == "error"
    assert "status 2" in recorder.events("error")[0]["message"]


async def test_settings_apply_to_the_next_turn(tmp_path: Path) -> None:
    runner, recorder, peer = build(tmp_path, "turn")
    await runner.start()
    await runner.apply_settings(model="gpt-5", permission_mode="ask", effort=None)
    await runner.send("go")
    await wait_for(lambda: recorder.events("turn_completed"))
    await runner.close()
    argv = peer.argv()
    assert argv[argv.index("--model") + 1] == "gpt-5"
    assert argv[argv.index("--mode") + 1] == "ask"
    meta = recorder.events("meta")
    assert any(event.get("model") == "gpt-5" for event in meta)
    assert any(event.get("permission_mode") == "ask" for event in meta)


async def test_an_effort_or_a_speed_tier_is_refused(tmp_path: Path) -> None:
    runner, _, _ = build(tmp_path, "turn")
    await runner.start()
    with pytest.raises(RcError) as effort:
        await runner.apply_settings(None, None, "high")
    with pytest.raises(RcError) as speed:
        await runner.apply_settings(None, None, None, speed="priority")
    await runner.close()
    assert effort.value.code == "unsupported"
    assert speed.value.code == "unsupported"


async def test_attachments_are_refused_rather_than_silently_dropped(tmp_path: Path) -> None:
    runner, _, _ = build(tmp_path, "turn")
    await runner.start()
    with pytest.raises(RcError) as caught:
        await runner.send("look", [{"name": "shot.png", "mime": "image/png", "data": "aGk="}])
    await runner.close()
    assert caught.value.code == "unsupported"


async def test_every_event_a_turn_publishes_validates(tmp_path: Path) -> None:
    validator = event_validator()
    if validator is None:
        return
    runner, recorder, _ = build(tmp_path, "turn")
    await runner.start()
    await runner.send("Reply with exactly OK")
    await wait_for(lambda: recorder.events("turn_completed"))
    await runner.close()
    for frame in recorder.frames:
        if frame.get("type") == "session.event":
            validator.validate(frame["event"])


def _collect(sink: list[str]) -> Any:
    async def on_session_id(session_id: str) -> None:
        sink.append(session_id)

    return on_session_id


async def test_closing_mid_turn_leaves_no_child_behind(tmp_path: Path) -> None:
    """A turn task cancelled by `close` must still take its process with it."""
    runner, recorder, _ = build(tmp_path, "slow")
    await runner.start()
    await runner.send("go")
    await wait_for(lambda: recorder.events("tool_call"))
    await runner.close()
    assert runner._process is not None
    assert runner._process.returncode is not None


async def test_a_queued_message_can_be_sent_from_the_turn_end_callback(tmp_path: Path) -> None:
    """The hub drains its queue from `on_turn_end`, while the task still winds down."""
    runner, recorder, peer = build(tmp_path, "turn")
    sent: list[str] = []

    async def drain() -> None:
        if not sent:
            sent.append("two")
            await runner.send("two", source="queue")

    runner._on_turn_end = drain
    await runner.start()
    await runner.send("one")
    await wait_for(lambda: len(recorder.events("turn_completed")) == 2)
    await runner.close()
    assert peer.argv()[-1] == "two"
    assert [event["source"] for event in recorder.events("user_message")] == ["remote", "queue"]
