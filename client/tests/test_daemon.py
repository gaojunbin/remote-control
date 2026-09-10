"""The whole daemon, wired to a fake gateway over a real WebSocket."""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

import pytest

from rc_client.agents.claude import transcripts
from rc_client.agents.codex import rollouts
from rc_client.config import Config
from rc_client.daemon import Daemon
from rc_client.models import Session
from tests.test_gateway_link import FakeGateway


@pytest.fixture
async def running_daemon(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> AsyncIterator[tuple[Daemon, FakeGateway]]:
    # Point the mirroring roots at empty directories: these tests exercise the
    # daemon wiring, not the transcripts that happen to exist on this machine.
    empty = tmp_path / "agent-state"
    empty.mkdir()
    monkeypatch.setattr(transcripts, "PROJECTS_DIR", empty / "claude")
    monkeypatch.setattr(rollouts, "SESSIONS_DIR", empty / "codex")
    server = FakeGateway()
    await server.start()
    config = Config(
        gateway_origin="http://127.0.0.1:8787",
        device_id="dev-1",
        device_token="tok-1",
        name="test-device",
    )
    daemon = Daemon(config)
    daemon.link.url = server.url
    task = asyncio.create_task(daemon.run())
    try:
        await asyncio.wait_for(server.ready.wait(), timeout=10)

        async def settle() -> None:
            while not daemon.link.connected:
                await asyncio.sleep(0.01)

        await asyncio.wait_for(settle(), timeout=10)
        yield daemon, server
    finally:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
        await server.stop()


async def request(server: FakeGateway, frame: dict[str, Any]) -> dict[str, Any]:
    await server.send(frame)

    async def poll() -> dict[str, Any]:
        while True:
            for reply in server.frames("reply"):
                if reply.get("id") == frame["id"]:
                    return reply
            await asyncio.sleep(0.01)

    return await asyncio.wait_for(poll(), timeout=15)


async def test_hello_describes_this_device_and_its_agents(
    running_daemon: tuple[Daemon, FakeGateway],
) -> None:
    _, server = running_daemon
    hello = server.frames("hello")[0]
    assert hello["protocol"] == 1
    assert hello["name"] == "test-device"
    assert hello["platform"] in {"macos", "linux"}
    assert {info["agent"] for info in hello["agents"]} == {"claude", "codex"}
    assert isinstance(hello["sessions"], list)
    assert "device_token" not in hello


async def test_device_dirs_is_answered_over_the_socket(
    running_daemon: tuple[Daemon, FakeGateway], tmp_path: Path
) -> None:
    _, server = running_daemon
    root = tmp_path / "workspace"
    (root / "project").mkdir(parents=True)
    (root / ".hidden").mkdir()
    reply = await request(
        server,
        {
            "type": "device.dirs",
            "id": "r1",
            "from": "app-1",
            "device_id": "dev-1",
            "path": str(root),
        },
    )
    assert reply["ok"] is True
    assert reply["from"] == "app-1"
    assert [entry["name"] for entry in reply["result"]["entries"]] == ["project"]
    assert reply["result"]["path"] == str(root)


async def test_device_git_reports_a_non_repository(
    running_daemon: tuple[Daemon, FakeGateway], tmp_path: Path
) -> None:
    _, server = running_daemon
    reply = await request(
        server,
        {
            "type": "device.git",
            "id": "r2",
            "from": "app-1",
            "device_id": "dev-1",
            "path": str(tmp_path),
        },
    )
    assert reply["result"] == {"is_repo": False}


async def test_device_git_requires_a_path(
    running_daemon: tuple[Daemon, FakeGateway],
) -> None:
    _, server = running_daemon
    reply = await request(
        server, {"type": "device.git", "id": "r3", "from": "app-1", "device_id": "dev-1"}
    )
    assert reply["ok"] is False
    assert reply["error"]["code"] == "bad_request"


async def test_device_agents_re_detects_and_returns_the_agent_list(
    running_daemon: tuple[Daemon, FakeGateway],
) -> None:
    _, server = running_daemon
    reply = await request(
        server, {"type": "device.agents", "id": "r4", "from": "app-1", "device_id": "dev-1"}
    )
    agents = reply["result"]["agents"]
    assert {info["agent"] for info in agents} == {"claude", "codex"}
    for info in agents:
        assert isinstance(info["capabilities"], list)


async def test_requests_for_unknown_sessions_are_not_found(
    running_daemon: tuple[Daemon, FakeGateway],
) -> None:
    _, server = running_daemon
    reply = await request(
        server,
        {"type": "session.history", "id": "r5", "from": "app-1", "session_id": "nope"},
    )
    assert reply["ok"] is False
    assert reply["error"]["code"] == "not_found"


async def test_session_events_reach_the_gateway_unsolicited(
    running_daemon: tuple[Daemon, FakeGateway], tmp_path: Path
) -> None:
    daemon, server = running_daemon
    session = Session(
        session_id="1f0a1c1e-2b3d-4c5f-8a9b-0c1d2e3f4a5b",
        device_id="dev-1",
        agent="claude",
        cwd=str(tmp_path),
    )
    entry = daemon.hub.register_mirrored(session)
    await entry.channel.emit("notice", level="info", text="mirrored a terminal session")
    await entry.channel.publish_summary()

    async def poll() -> dict[str, Any]:
        while True:
            for frame in server.frames("session.event"):
                if frame["session_id"] == session.session_id:
                    return frame
            await asyncio.sleep(0.01)

    event = await asyncio.wait_for(poll(), timeout=10)
    assert event["session_id"] == session.session_id
    assert event["event"]["kind"] == "notice"
    assert event["event"]["seq"] == 1

    summary = next(
        frame
        for frame in reversed(server.frames("session.updated"))
        if frame["session"]["session_id"] == session.session_id
    )
    assert summary["session"]["device_id"] == "dev-1"
    assert summary["session"]["last_seq"] == 1


async def test_history_and_send_idempotency_survive_over_the_socket(
    running_daemon: tuple[Daemon, FakeGateway], tmp_path: Path
) -> None:
    daemon, server = running_daemon
    session = Session(
        session_id="2f0a1c1e-2b3d-4c5f-8a9b-0c1d2e3f4a5c",
        device_id="dev-1",
        agent="claude",
        cwd=str(tmp_path),
        control="terminal",
    )
    daemon.hub.register_mirrored(session)

    reply = await request(
        server,
        {
            "type": "session.send",
            "id": "r6",
            "from": "app-1",
            "session_id": session.session_id,
            "text": "go",
            "mode": "auto",
        },
    )
    assert reply["error"]["code"] == "conflict"

    history = await request(
        server,
        {
            "type": "session.history",
            "id": "r7",
            "from": "app-1",
            "session_id": session.session_id,
            "limit": 10,
        },
    )
    assert history["result"] == {"events": [], "has_more": False}
