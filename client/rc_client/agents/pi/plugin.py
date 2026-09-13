"""What this device offers for pi, and how one of its sessions is built."""

from __future__ import annotations

from ...errors import RcError
from ...models import AgentInfo
from ..base import SessionRunner
from ..registry import DetectContext, RunnerSpec
from . import catalog as catalogue
from . import runtime
from .adapter import PiRunner

AGENT = "pi"

# No `attachments`: pi's `prompt` takes images, but nothing else in a pi
# session is remote-controlled well enough yet to be worth carrying bytes for.
# No `takeover`: a human's pi has no IPC surface to take anything over from.
CAPABILITIES = [
    "worktree",
    "interrupt",
    "queue",
    "steer",
    "effort",
    "history",
]


async def detect(context: DetectContext) -> AgentInfo:
    path = runtime.resolve_binary()
    version = await runtime.probe_version(path) if path else None
    catalog = await catalogue.load(path)
    return AgentInfo(
        agent=AGENT,
        available=bool(path),
        version=version,
        path=path,
        models=list(catalog.models),
        default_model=catalog.default_model,
        # pi has no permission system at all (A25): an empty list is what tells
        # an app to draw no permission picker.
        permission_modes=[],
        default_permission_mode=None,
        efforts=list(catalog.efforts),
        default_effort=catalog.default_effort,
        capabilities=list(CAPABILITIES),
        # pi runs one process per client with no daemon, no socket and no
        # server mode, so a session a person started in a terminal cannot be
        # attached to or driven from here.
        attach=None,
        attach_ready=False,
        shared_interrupt=False,
        shared_settings=False,
        shared_attachments=False,
    )


async def build_runner(spec: RunnerSpec) -> SessionRunner:
    if not spec.info.path:
        raise RcError("agent_unavailable", "pi is not installed on this device")
    session = spec.session
    return PiRunner(
        spec.channel,
        binary=spec.info.path,
        cwd=session.cwd,
        # `--session-id` creates the session when it is new and reopens it when
        # it is not, so resuming and starting take the same path.
        session_id=spec.resume or session.session_id,
        model=session.model,
        effort=session.effort,
        on_turn_end=spec.on_turn_end,
        on_session_id=spec.on_session_id,
    )
