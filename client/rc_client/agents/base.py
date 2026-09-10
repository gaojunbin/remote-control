"""The interface every agent adapter implements, plus the translator output type.

Translators are pure: they turn one agent message into a list of `Emit`s, which
the adapter hands to the session channel. Keeping them side-effect free is what
makes them testable against recorded fixtures without running an agent.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol, runtime_checkable

TOOL_KINDS = (
    "shell",
    "read",
    "edit",
    "write",
    "search",
    "web",
    "mcp",
    "subagent",
    "todo",
    "other",
)


@dataclass(slots=True)
class Emit:
    """One event to publish. `delta` marks coalesced streaming appends."""

    kind: str
    fields: dict[str, Any] = field(default_factory=dict)
    delta: bool = False


@dataclass(slots=True)
class SendResult:
    accepted: str
    queued_id: str | None = None


@runtime_checkable
class SessionRunner(Protocol):
    """A live agent session driven by the daemon."""

    agent: str

    async def start(self) -> None: ...

    async def send(
        self,
        text: str,
        attachments: list[dict[str, Any]] | None = None,
        source: str = "remote",
    ) -> None:
        """Start a turn. ``source`` is the protocol trigger: `remote`, `terminal` or `queue`."""
        ...

    async def interrupt(self) -> bool: ...

    async def approve(self, request_id: str, option_id: str, message: str | None) -> bool: ...

    async def answer(self, request_id: str, answers: dict[str, Any]) -> bool: ...

    async def apply_settings(
        self, model: str | None, permission_mode: str | None, effort: str | None
    ) -> None: ...

    async def close(self) -> None: ...

    @property
    def busy(self) -> bool: ...

    @property
    def supports_steer(self) -> bool: ...

    async def steer(self, text: str) -> bool: ...
