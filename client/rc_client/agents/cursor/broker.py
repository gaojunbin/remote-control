"""The daemon end of Cursor's `preToolUse` hook: one socket, one decision each.

Cursor's print mode has no approval event and no channel to answer one on, so a
tool call can only be held by a hook process that blocks. That hook dials this
socket, names the conversation it belongs to, and waits; if the conversation is
one this device drives, the runner raises an `approval` block and the answer
goes back down the same connection. Every other conversation on the machine —
a person's own `cursor-agent`, another product's — is answered with silence, so
the hook steps aside and Cursor's own rules decide.

The socket lives inside the device home with owner-only permissions, because
anything that can write to it can approve an agent's tool calls.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import stat
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

from ...channel import wire
from ...logging_setup import logger

log = logger("rc_client.cursor")

PRE_TOOL_USE = "pre_tool_use"
ALLOW = "allow"
DENY = "deny"
DECISIONS = (ALLOW, DENY)
# Long enough for a person to reach their phone, short enough that a forgotten
# request does not pin a hook process for ever. Cursor's own hook timeout ends
# the wait sooner whenever one is configured.
DECISION_TIMEOUT = 3600.0
OPENING_TIMEOUT = 10.0

# A decision for one held tool call: `allow`, `deny`, or None to stand aside.
Decider = Callable[[dict[str, Any]], Awaitable[str | None]]


class ApprovalBroker:
    """Accepts hook processes and routes each to the runner that owns its chat."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self._deciders: dict[str, Decider] = {}
        self._server: asyncio.AbstractServer | None = None
        self._opening: asyncio.Task[None] | None = None
        self._connections: set[asyncio.Task[None]] = set()

    @property
    def listening(self) -> bool:
        return self._server is not None

    @property
    def empty(self) -> bool:
        return not self._deciders

    # ------------------------------------------------------------- lifecycle

    async def start(self) -> None:
        """Bind once, however many sessions ask for the socket at the same time."""
        if self._server is not None:
            return
        if self._opening is None:
            self._opening = asyncio.create_task(self._open(), name="cursor-broker-open")
        try:
            await self._opening
        finally:
            if self._server is None:
                self._opening = None

    async def _open(self) -> None:
        self._prepare_directory()
        self._unlink_stale()
        self._server = await asyncio.start_unix_server(self._serve, path=str(self.path))
        os.chmod(self.path, 0o600)
        log.info("cursor approval socket listening", path=str(self.path))

    def _prepare_directory(self) -> None:
        """Own the directory outright: whoever can write here can approve a tool."""
        directory = self.path.parent
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        info = directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
            raise OSError(f"{directory} is not a directory this user owns")
        if stat.S_IMODE(info.st_mode) & 0o077:
            os.chmod(directory, 0o700)

    def _unlink_stale(self) -> None:
        """A socket left by a killed daemon would refuse to bind."""
        with contextlib.suppress(FileNotFoundError, OSError):
            if self.path.is_socket():
                self.path.unlink()

    async def stop(self) -> None:
        opening, self._opening = self._opening, None
        if opening is not None and not opening.done():
            opening.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await opening
        server, self._server = self._server, None
        if server is not None:
            server.close()
            with contextlib.suppress(Exception):
                await server.wait_closed()
        for task in list(self._connections):
            task.cancel()
        for task in list(self._connections):
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        self._connections.clear()
        self._deciders.clear()
        with contextlib.suppress(FileNotFoundError, OSError):
            self.path.unlink()

    # ------------------------------------------------------------ membership

    def register(self, conversation_id: str, decide: Decider) -> None:
        self._deciders[conversation_id] = decide

    def unregister(self, conversation_id: str) -> None:
        self._deciders.pop(conversation_id, None)

    # ------------------------------------------------------------ connection

    async def _serve(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        task = asyncio.current_task()
        if task is not None:
            self._connections.add(task)
        try:
            await self._handle(reader, writer)
        except (asyncio.IncompleteReadError, ConnectionError, OSError):
            pass
        finally:
            if task is not None:
                self._connections.discard(task)
            writer.close()
            with contextlib.suppress(Exception):
                await writer.wait_closed()

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        message = await self._opening_frame(reader)
        if message is None or message.get("type") != PRE_TOOL_USE:
            return
        decide = self._deciders.get(str(message.get("conversation_id") or ""))
        if decide is None:
            # Somebody else's Cursor session: say nothing and let Cursor decide.
            return
        try:
            verdict = await asyncio.wait_for(decide(message), timeout=DECISION_TIMEOUT)
        except TimeoutError:
            verdict = None
        except Exception as exc:  # pragma: no cover - a runner fault must not hang a hook
            log.warning("cursor approval failed", error=str(exc))
            verdict = None
        writer.write(wire.encode({"permission": verdict if verdict in DECISIONS else None}))
        await writer.drain()

    @staticmethod
    async def _opening_frame(reader: asyncio.StreamReader) -> dict[str, Any] | None:
        try:
            line = await asyncio.wait_for(reader.readline(), timeout=OPENING_TIMEOUT)
        except (TimeoutError, ValueError):
            return None
        return wire.decode(line) if line else None


_shared: ApprovalBroker | None = None


async def register(path: Path, conversation_id: str, decide: Decider) -> None:
    """Hold `conversation_id` on the one shared broker, starting it if need be."""
    global _shared
    broker = _shared
    if broker is not None and broker.path != path:
        _shared = None
        await broker.stop()
        broker = None
    if broker is None:
        broker = ApprovalBroker(path)
        _shared = broker
    broker.register(conversation_id, decide)
    await broker.start()


async def unregister(conversation_id: str) -> None:
    """Drop a conversation, and close the socket once no session needs it."""
    global _shared
    broker = _shared
    if broker is None:
        return
    broker.unregister(conversation_id)
    if broker.empty:
        _shared = None
        await broker.stop()


async def reset() -> None:
    """Forget the shared broker, which each test needs on its own event loop."""
    global _shared
    broker, _shared = _shared, None
    if broker is not None:
        await broker.stop()
