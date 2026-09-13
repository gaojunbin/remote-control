"""The agent registry: one package per agent, one list of ids.

Every agent the device can drive lives in `rc_client/agents/<id>/` and exposes a
`plugin` module with `AGENT`, `detect` and `build_runner`. `AGENT_IDS` below is
the only place an id is written down, so teaching the device a new agent is a
new package plus a name in that tuple — nothing in the daemon, the hub or the
CLI changes.

Plugin modules are imported on first use rather than at import time, so a broken
adapter cannot stop the daemon from starting with the agents that do work.
"""

from __future__ import annotations

import asyncio
import importlib
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING, Protocol, cast

from ..errors import RcError
from ..models import AgentInfo, Command, Session
from .base import SessionRunner

if TYPE_CHECKING:  # pragma: no cover - imported for types only
    from ..sessions.channel import SessionChannel
    from ..sessions.hub import SessionEntry
    from .codex.daemon.service import CodexDaemonService

# Detection order, and the order `hello` reports agents in.
AGENT_IDS = ("claude", "codex", "grok", "pi")

TurnEndCallback = Callable[[], Awaitable[None]]
SessionIdCallback = Callable[[str], Awaitable[None]]


@dataclass(slots=True)
class DetectContext:
    """What a detector needs beyond the filesystem it can read for itself."""

    # Whether the shared Codex app-server answered a handshake (A11). `None`
    # asks the Codex plugin to find out for itself.
    codex_daemon_ready: bool | None = None


@dataclass(slots=True)
class RunnerSpec:
    """Everything an adapter needs to build one live session."""

    entry: SessionEntry
    info: AgentInfo
    resume: str | None
    on_turn_end: TurnEndCallback
    on_session_id: SessionIdCallback
    codex_daemon: CodexDaemonService | None = None

    @property
    def channel(self) -> SessionChannel:
        return self.entry.channel

    @property
    def session(self) -> Session:
        return self.entry.session


class AgentPlugin(Protocol):
    """The shape every `rc_client/agents/<id>/plugin.py` satisfies."""

    AGENT: str
    detect: Callable[[DetectContext], Awaitable[AgentInfo]]
    build_runner: Callable[[RunnerSpec], Awaitable[SessionRunner]]


_loaded: dict[str, AgentPlugin] = {}


def plugin(agent: str) -> AgentPlugin:
    """The adapter for `agent`, or the error the session frames answer with."""
    if agent not in AGENT_IDS:
        raise RcError("unsupported", f"no adapter for agent {agent}")
    cached = _loaded.get(agent)
    if cached is None:
        cached = cast(AgentPlugin, importlib.import_module(f"{__package__}.{agent}.plugin"))
        _loaded[agent] = cached
    return cached


async def detect_all(context: DetectContext | None = None) -> list[AgentInfo]:
    """Ask every agent what it offers, concurrently, in `AGENT_IDS` order."""
    ready = context or DetectContext()
    return list(await asyncio.gather(*(plugin(agent).detect(ready) for agent in AGENT_IDS)))


async def runner_for(agent: str, spec: RunnerSpec) -> SessionRunner:
    return await plugin(agent).build_runner(spec)


async def offline_commands(agent: str, session: Session) -> list[Command]:
    """What a session with no live process can still be said to offer (A27).

    A plugin may expose `async def commands(session) -> list[Command]` for the
    list it knows without starting anything: Codex's fixed table, pi's prompt
    templates and skills on disk, the list Grok last advertised. Without it the
    answer is empty until the session is resumed.
    """
    offline = getattr(plugin(agent), "commands", None)
    if offline is None:
        return []
    return list(await offline(session))
