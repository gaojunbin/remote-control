"""Detect the agents installed on this device and describe their capabilities."""

from __future__ import annotations

import asyncio

from .. import __version__
from ..channel import shim
from ..models import AgentInfo, Choice
from .claude import runtime as claude_runtime
from .codex import runtime as codex_runtime
from .codex.daemon.rpc import handshake_ok
from .codex.daemon.transport import socket_exists
from .codex.models import catalog_cache

CLAUDE_MODELS = [
    Choice("default", "Default"),
    Choice("fable", "Fable"),
    Choice("opus", "Opus"),
    Choice("sonnet", "Sonnet"),
    Choice("haiku", "Haiku"),
]
CLAUDE_PERMISSION_MODES = [
    Choice("default", "Ask before edits"),
    Choice("acceptEdits", "Auto-accept edits"),
    Choice("plan", "Plan mode"),
    Choice("bypassPermissions", "Bypass permissions"),
]
CLAUDE_EFFORTS = [
    Choice("low", "Low"),
    Choice("medium", "Medium"),
    Choice("high", "High"),
    Choice("xhigh", "Extra high"),
    Choice("max", "Max"),
]
CLAUDE_CAPABILITIES = [
    "takeover",
    "interrupt",
    "queue",
    "attachments",
    "effort",
    "history",
    "worktree",
]

CODEX_PERMISSION_MODES = [
    Choice("untrusted", "Ask for everything"),
    Choice("on-request", "Ask when needed"),
    Choice("never", "Never ask"),
]
CODEX_CAPABILITIES = [
    "interrupt",
    "queue",
    "steer",
    "history",
    "worktree",
    "attachments",
    "effort",
]

# `default` means "do not pass a model"; the real id arrives from the SDK init
# message and is reported later as `meta.model`.
CLAUDE_DEFAULT_MODEL = "default"


async def detect_claude() -> AgentInfo:
    path = claude_runtime.resolve_binary()
    version = await claude_runtime.probe_version(path) if path else None
    return AgentInfo(
        agent="claude",
        available=bool(path),
        version=version,
        path=path,
        models=list(CLAUDE_MODELS),
        default_model=CLAUDE_DEFAULT_MODEL,
        permission_modes=list(CLAUDE_PERMISSION_MODES),
        default_permission_mode="default",
        efforts=list(CLAUDE_EFFORTS),
        default_effort=None,
        capabilities=list(CLAUDE_CAPABILITIES),
        attach="channel",
        attach_ready=shim.status().ready,
        shared_interrupt=False,
        shared_settings=False,
        shared_attachments=False,
    )


async def codex_daemon_ready() -> bool:
    """A real handshake, not a file-exists check: A11 defines `attach_ready` that way."""
    if not socket_exists():
        return False
    return await handshake_ok(__version__)


async def detect_codex(daemon_ready: bool | None = None) -> AgentInfo:
    path = codex_runtime.resolve_binary()
    version = await codex_runtime.probe_version(path) if path else None
    catalog = await catalog_cache.get(path) if path else None
    ready = await codex_daemon_ready() if daemon_ready is None else daemon_ready
    return AgentInfo(
        agent="codex",
        available=bool(path),
        version=version,
        path=path,
        models=list(catalog.models) if catalog else [],
        default_model=catalog.default_model if catalog else None,
        permission_modes=list(CODEX_PERMISSION_MODES),
        default_permission_mode="on-request",
        efforts=list(catalog.efforts) if catalog else [],
        default_effort=catalog.default_effort if catalog else None,
        speeds=list(catalog.speeds) if catalog else [],
        capabilities=list(CODEX_CAPABILITIES),
        attach="daemon",
        attach_ready=ready,
        shared_interrupt=True,
        shared_settings=True,
        shared_attachments=True,
    )


async def detect_agents(codex_daemon_ready: bool | None = None) -> list[AgentInfo]:
    claude, codex = await asyncio.gather(detect_claude(), detect_codex(codex_daemon_ready))
    return [claude, codex]
