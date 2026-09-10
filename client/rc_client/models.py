"""Wire objects from PROTOCOL §3, as dataclasses with `to_dict` serialisers."""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Literal

SessionState = Literal[
    "starting", "idle", "running", "needs_approval", "needs_input", "error", "stopped", "readonly"
]
Control = Literal["remote", "terminal", "shared", "none"]
# How a terminal session of this agent can be attached (amendment A10).
Attach = Literal["channel", "daemon"]
Origin = Literal["remote", "terminal"]


def now_ms() -> int:
    return int(time.time() * 1000)


@dataclass(slots=True)
class Choice:
    """An `{id, label}` pair used for models, permission modes and efforts."""

    id: str
    label: str

    def to_dict(self) -> dict[str, str]:
        return {"id": self.id, "label": self.label}


@dataclass(slots=True)
class AgentInfo:
    agent: str
    available: bool
    version: str | None = None
    path: str | None = None
    models: list[Choice] = field(default_factory=list)
    default_model: str | None = None
    permission_modes: list[Choice] = field(default_factory=list)
    default_permission_mode: str | None = None
    efforts: list[Choice] = field(default_factory=list)
    default_effort: str | None = None
    capabilities: list[str] = field(default_factory=list)
    attach: Attach | None = None
    attach_ready: bool = False
    shared_interrupt: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "agent": self.agent,
            "available": self.available,
            "version": self.version,
            "path": self.path,
            "models": [choice.to_dict() for choice in self.models],
            "default_model": self.default_model,
            "permission_modes": [choice.to_dict() for choice in self.permission_modes],
            "default_permission_mode": self.default_permission_mode,
            "efforts": [choice.to_dict() for choice in self.efforts],
            "default_effort": self.default_effort,
            "capabilities": list(self.capabilities),
            "attach": self.attach,
            "attach_ready": self.attach_ready,
            "shared_interrupt": self.shared_interrupt,
        }


@dataclass(slots=True)
class Session:
    session_id: str
    device_id: str
    agent: str
    cwd: str
    title: str = ""
    git: dict[str, Any] | None = None
    state: SessionState = "starting"
    state_detail: str | None = None
    origin: Origin = "remote"
    control: Control = "remote"
    model: str | None = None
    permission_mode: str | None = None
    effort: str | None = None
    created_at: int = field(default_factory=now_ms)
    updated_at: int = field(default_factory=now_ms)
    last_seq: int = 0
    archived: bool = False
    turn: dict[str, Any] | None = None
    todos: dict[str, int] | None = None
    usage: dict[str, Any] | None = None
    queued: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "session_id": self.session_id,
            "device_id": self.device_id,
            "agent": self.agent,
            "title": self.title,
            "cwd": self.cwd,
            "git": self.git,
            "state": self.state,
            "state_detail": self.state_detail,
            "origin": self.origin,
            "control": self.control,
            "model": self.model,
            "permission_mode": self.permission_mode,
            "effort": self.effort,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "last_seq": self.last_seq,
            "archived": self.archived,
            "turn": self.turn,
            "todos": self.todos,
            "usage": self.usage,
            "queued": self.queued,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> Session:
        return cls(
            session_id=str(data["session_id"]),
            device_id=str(data.get("device_id") or ""),
            agent=str(data["agent"]),
            cwd=str(data.get("cwd") or ""),
            title=str(data.get("title") or ""),
            git=data.get("git"),
            state=data.get("state", "idle"),
            state_detail=data.get("state_detail"),
            origin=data.get("origin", "remote"),
            control=data.get("control", "none"),
            model=data.get("model"),
            permission_mode=data.get("permission_mode"),
            effort=data.get("effort"),
            created_at=int(data.get("created_at") or now_ms()),
            updated_at=int(data.get("updated_at") or now_ms()),
            last_seq=int(data.get("last_seq") or 0),
            archived=bool(data.get("archived")),
            turn=data.get("turn"),
            todos=data.get("todos"),
            usage=data.get("usage"),
            queued=int(data.get("queued") or 0),
        )


def title_from_text(text: str) -> str:
    """First line of the first message, at most 60 characters (PROTOCOL §8 rule)."""
    line = text.strip().splitlines()[0].strip() if text.strip() else ""
    if len(line) > 60:
        return line[:59].rstrip() + "…"
    return line
