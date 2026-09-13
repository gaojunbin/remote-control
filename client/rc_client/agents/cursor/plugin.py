"""What this device offers for Cursor, and how one of its sessions is built."""

from __future__ import annotations

from ...errors import RcError
from ...models import AgentInfo, Choice
from ..base import SessionRunner
from ..registry import DetectContext, RunnerSpec
from . import catalog as catalogue
from . import runtime
from .adapter import CursorRunner

AGENT = "cursor"

# The four rows of PROTOCOL.md 4.3, which are the flags `MODE_FLAGS` spells.
PERMISSION_MODES = [
    Choice("default", "Ask when needed"),
    Choice("force", "Never ask"),
    Choice("plan", "Plan mode"),
    Choice("ask", "Ask, read only"),
]
# No `effort`: effort rides inside a parameterised model id rather than being a
# setting of its own. No `steer`: a turn is a process already given its prompt.
# No `attachments`: print mode takes a prompt and nothing else.
CAPABILITIES = [
    "worktree",
    "interrupt",
    "queue",
    "history",
]


async def detect(context: DetectContext) -> AgentInfo:
    path = runtime.resolve_binary()
    version = await runtime.probe_version(path) if path else None
    models = await catalogue.load(path)
    return AgentInfo(
        agent=AGENT,
        available=bool(path),
        version=version,
        path=path,
        models=models,
        default_model=catalogue.AUTO,
        permission_modes=list(PERMISSION_MODES),
        default_permission_mode="default",
        # Cursor has no separate effort control; `agent.cursor.json` says so.
        efforts=[],
        default_effort=None,
        capabilities=list(CAPABILITIES),
        # `persist attach` is a terminal reattach with no documented protocol,
        # and the CLI keeps no readable transcript, so a Cursor session this
        # device did not start can be neither attached nor mirrored.
        attach=None,
        attach_ready=False,
        shared_interrupt=False,
        shared_settings=False,
        shared_attachments=False,
    )


async def build_runner(spec: RunnerSpec) -> SessionRunner:
    if not spec.info.path:
        raise RcError("agent_unavailable", "cursor-agent is not installed on this device")
    session = spec.session
    return CursorRunner(
        spec.channel,
        binary=spec.info.path,
        cwd=session.cwd,
        model=session.model,
        permission_mode=session.permission_mode,
        resume=spec.resume,
        on_turn_end=spec.on_turn_end,
        on_session_id=spec.on_session_id,
    )
