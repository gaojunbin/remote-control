"""What this device offers for Claude Code, and how one of its sessions is built."""

from __future__ import annotations

from ...channel import shim
from ...models import AgentInfo, Choice
from ..base import SessionRunner
from ..registry import DetectContext, RunnerSpec
from . import runtime
from .adapter import ClaudeRunner

AGENT = "claude"

MODELS = [
    Choice("default", "Default"),
    Choice("fable", "Fable"),
    Choice("opus", "Opus"),
    Choice("sonnet", "Sonnet"),
    Choice("haiku", "Haiku"),
]
PERMISSION_MODES = [
    Choice("default", "Ask before edits"),
    Choice("acceptEdits", "Auto-accept edits"),
    Choice("plan", "Plan mode"),
    Choice("bypassPermissions", "Bypass permissions"),
]
EFFORTS = [
    Choice("low", "Low"),
    Choice("medium", "Medium"),
    Choice("high", "High"),
    Choice("xhigh", "Extra high"),
    Choice("max", "Max"),
]
CAPABILITIES = [
    "takeover",
    "interrupt",
    "queue",
    "attachments",
    "effort",
    "history",
    "worktree",
]

# `default` means "do not pass a model"; the real id arrives from the SDK init
# message and is reported later as `meta.model`.
DEFAULT_MODEL = "default"


async def detect(context: DetectContext) -> AgentInfo:
    path = runtime.resolve_binary()
    version = await runtime.probe_version(path) if path else None
    return AgentInfo(
        agent=AGENT,
        available=bool(path),
        version=version,
        path=path,
        models=list(MODELS),
        default_model=DEFAULT_MODEL,
        permission_modes=list(PERMISSION_MODES),
        default_permission_mode="default",
        efforts=list(EFFORTS),
        default_effort=None,
        capabilities=list(CAPABILITIES),
        attach="channel",
        attach_ready=shim.status().ready,
        shared_interrupt=False,
        shared_settings=False,
        shared_attachments=False,
    )


async def build_runner(spec: RunnerSpec) -> SessionRunner:
    session = spec.session
    return ClaudeRunner(
        spec.channel,
        binary=spec.info.path,
        cwd=session.cwd,
        model=session.model,
        permission_mode=session.permission_mode,
        effort=session.effort,
        resume=spec.resume,
        session_id=session.session_id,
        on_turn_end=spec.on_turn_end,
        on_session_id=spec.on_session_id,
    )
