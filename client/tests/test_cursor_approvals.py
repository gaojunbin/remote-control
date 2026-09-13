"""A tool call held by Cursor's hook, answered from an app.

The hook runs as a real child process talking to a real Unix socket, because
the whole point of it is that it blocks in another process: the runner has to
raise the call, wait for an answer that arrives long afterwards, and send it
back down the connection the hook is still holding.
"""

from __future__ import annotations

import asyncio
import json
import sys
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

import pytest

from rc_client.agents.cursor import broker
from rc_client.agents.cursor import runtime as cursor_runtime
from tests.test_cursor_runner import CHAT, build, wait_for

# The hook, started the way Cursor starts it: stdin in, one decision out.
HOOK = "from rc_client.agents.cursor import hook; raise SystemExit(hook.main())"
PAYLOAD = {
    "hook_event_name": "preToolUse",
    "conversation_id": CHAT,
    "generation_id": "gen-1",
    "tool_name": "shell",
    "tool_input": {"command": "rm -rf build"},
    "tool_use_id": "call-9",
    "cwd": "/tmp/work",
}


@pytest.fixture(autouse=True)
async def _broker() -> AsyncIterator[None]:
    """The socket path comes from `RC_CLIENT_HOME`, which the hook child inherits."""
    yield
    await broker.reset()


async def run_hook(payload: dict[str, Any]) -> asyncio.subprocess.Process:
    process = await asyncio.create_subprocess_exec(
        sys.executable,
        "-c",
        HOOK,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    assert process.stdin is not None
    process.stdin.write(json.dumps(payload).encode("utf-8"))
    await process.stdin.drain()
    process.stdin.close()
    return process


async def decision_of(process: asyncio.subprocess.Process) -> str | None:
    out, _ = await asyncio.wait_for(process.communicate(), timeout=30)
    assert process.returncode == 0
    text = out.decode("utf-8").strip()
    return json.loads(text)["permission"] if text else None


async def test_an_app_answers_a_held_tool_call(tmp_path: Path) -> None:
    runner, recorder, _ = build(tmp_path, "turn", resume=CHAT)
    await runner.start()
    hook = await run_hook(PAYLOAD)
    await wait_for(lambda: recorder.events("approval"))

    pending = recorder.events("approval")[0]
    assert pending["status"] == "pending"
    assert pending["title"] == "rm -rf build"
    assert pending["tool"] == "shell"
    assert [option["id"] for option in pending["options"]] == ["allow", "deny"]
    assert [option["style"] for option in pending["options"]] == ["primary", "danger"]
    assert "needs_approval" in recorder.states()

    assert await runner.approve(pending["request_id"], "allow", None) is True
    assert await decision_of(hook) == "allow"
    await runner.close()
    resolved = recorder.events("approval")[-1]
    assert resolved["status"] == "resolved"
    assert resolved["decision"] == {"option_id": "allow", "by": "remote"}


async def test_a_denial_reaches_the_hook(tmp_path: Path) -> None:
    runner, recorder, _ = build(tmp_path, "turn", resume=CHAT)
    await runner.start()
    hook = await run_hook(PAYLOAD)
    await wait_for(lambda: recorder.events("approval"))
    request_id = recorder.events("approval")[0]["request_id"]
    await runner.approve(request_id, "deny", None)
    assert await decision_of(hook) == "deny"
    await runner.close()


async def test_an_option_we_never_offered_is_not_consent(tmp_path: Path) -> None:
    runner, recorder, _ = build(tmp_path, "turn", resume=CHAT)
    await runner.start()
    hook = await run_hook(PAYLOAD)
    await wait_for(lambda: recorder.events("approval"))
    request_id = recorder.events("approval")[0]["request_id"]
    await runner.approve(request_id, "definitely-yes", None)
    assert await decision_of(hook) is None
    await runner.close()
    assert recorder.events("approval")[-1]["status"] == "expired"


async def test_somebody_else_s_cursor_session_is_left_alone(tmp_path: Path) -> None:
    """The hook is machine-wide: a conversation we do not drive gets no answer."""
    runner, recorder, _ = build(tmp_path, "turn", resume=CHAT)
    await runner.start()
    hook = await run_hook({**PAYLOAD, "conversation_id": "someone-elses-chat"})
    assert await decision_of(hook) is None
    await runner.close()
    assert recorder.events("approval") == []


async def test_a_payload_without_a_conversation_says_nothing(tmp_path: Path) -> None:
    runner, _, _ = build(tmp_path, "turn", resume=CHAT)
    await runner.start()
    hook = await run_hook({k: v for k, v in PAYLOAD.items() if k != "conversation_id"})
    assert await decision_of(hook) is None
    await runner.close()


async def test_the_hook_is_silent_when_no_daemon_is_listening() -> None:
    """Nothing is registered, so the socket does not exist; the hook still exits 0."""
    hook = await run_hook(PAYLOAD)
    assert await decision_of(hook) is None


async def test_the_socket_closes_when_the_last_session_goes(tmp_path: Path) -> None:
    runner, _, _ = build(tmp_path, "turn", resume=CHAT)
    await runner.start()
    assert cursor_runtime.socket_path().is_socket()
    await runner.close()
    assert not cursor_runtime.socket_path().exists()


async def test_an_interrupt_releases_a_call_the_agent_is_waiting_on(tmp_path: Path) -> None:
    runner, recorder, _ = build(tmp_path, "slow", resume=CHAT)
    await runner.start()
    await runner.send("go")
    hook = await run_hook(PAYLOAD)
    await wait_for(lambda: recorder.events("approval"))
    assert await runner.interrupt() is True
    assert await decision_of(hook) is None
    await runner.close()
    assert recorder.events("approval")[-1]["status"] == "expired"
