"""What this device offers for Grok Build, and how one of its sessions is built."""

from __future__ import annotations

from ...errors import RcError
from ...models import AgentInfo, Choice
from ..base import SessionRunner
from ..registry import DetectContext, RunnerSpec
from . import catalog as catalogue
from . import runtime
from .adapter import GrokRunner

AGENT = "grok"

PERMISSION_MODES = [
    Choice("default", "Ask when needed"),
    Choice("acceptEdits", "Auto-accept edits"),
    Choice("auto", "Auto mode"),
    Choice("dontAsk", "Deny unless allowed"),
    Choice("plan", "Plan mode"),
    Choice("bypassPermissions", "Bypass permissions"),
]
# No `attachments`: this build answers `promptCapabilities.image: false`, so a
# prompt has nowhere to carry one. No `steer`: a prompt sent mid-turn queues.
CAPABILITIES = [
    "worktree",
    "interrupt",
    "queue",
    "effort",
    "history",
]


async def detect(context: DetectContext) -> AgentInfo:
    path = runtime.resolve_binary()
    version = await runtime.probe_version(path) if path else None
    models = catalogue.load()
    return AgentInfo(
        agent=AGENT,
        available=bool(path),
        version=version,
        path=path,
        models=list(models.models),
        default_model=models.default_model,
        permission_modes=list(PERMISSION_MODES),
        default_permission_mode="default",
        efforts=list(models.efforts),
        default_effort=models.default_effort,
        capabilities=list(CAPABILITIES),
        # Grok's leader process could share a terminal session, but it is off by
        # default and enabling it means editing a person's own configuration, so
        # terminal sessions are mirrored and resumed rather than attached.
        attach=None,
        attach_ready=False,
        shared_interrupt=False,
        shared_settings=False,
        shared_attachments=False,
    )


async def build_runner(spec: RunnerSpec) -> SessionRunner:
    if not spec.info.path:
        raise RcError("agent_unavailable", "grok is not installed on this device")
    session = spec.session
    return GrokRunner(
        spec.channel,
        binary=spec.info.path,
        cwd=session.cwd,
        catalog=catalogue.load(),
        model=session.model,
        permission_mode=session.permission_mode,
        effort=session.effort,
        resume=spec.resume,
        on_turn_end=spec.on_turn_end,
        on_session_id=spec.on_session_id,
    )
