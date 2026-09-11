"""The daemon end of the channel: a Unix socket the `rc-client channel` bridges dial.

A connection is either one attached CLI session, held open for as long as that
session lives, or a single `session_start` frame from the hook Claude Code runs
when a terminal enters a session. The socket lives inside the device home with
owner-only permissions, because anything that can write to it can inject
prompts into a live agent and approve its tool calls.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import re
import stat
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from ..channel import wire
from ..logging_setup import logger

log = logger("rc_client.attach")

REGISTER_TIMEOUT = 10.0
_SESSION_ID = re.compile(r"[A-Za-z0-9_-]{1,80}")


@dataclass(slots=True, frozen=True)
class SessionStart:
    """The session a terminal CLI serves from now on, as its hook reported it."""

    session_id: str
    cwd: str
    pid: int
    source: str
    transcript_path: str


class AttachSink(Protocol):
    """What the hub has to provide for an attachment to be useful."""

    async def attach_registered(self, attachment: Attachment) -> None: ...

    async def attach_permission_request(
        self, attachment: Attachment, payload: dict[str, Any]
    ) -> None: ...

    async def attach_closed(self, attachment: Attachment) -> None: ...

    async def attach_session_started(self, start: SessionStart) -> None: ...


@dataclass(slots=True)
class Attachment:
    """A live channel bridge for one terminal-started session."""

    session_id: str
    cwd: str
    pid: int
    claude_version: str | None
    writer: asyncio.StreamWriter | None = field(default=None, repr=False)

    @property
    def alive(self) -> bool:
        return self.writer is not None

    async def inject(self, message_id: str, text: str) -> bool:
        return await self._send(wire.inject(message_id, text))

    async def relay_permission(self, request_id: str, behavior: str) -> bool:
        return await self._send(wire.permission(request_id, behavior))

    async def _send(self, message: dict[str, Any]) -> bool:
        writer = self.writer
        if writer is None:
            return False
        try:
            writer.write(wire.encode(message))
            await writer.drain()
        except (OSError, RuntimeError):
            self.detach()
            return False
        return True

    def detach(self) -> None:
        writer, self.writer = self.writer, None
        if writer is not None:
            writer.close()


class AttachServer:
    """Accepts channel bridges and hands each registered one to the sink."""

    def __init__(self, path: Path, sink: AttachSink) -> None:
        self.path = path
        self._sink = sink
        self._server: asyncio.AbstractServer | None = None
        self._connections: set[asyncio.Task[None]] = set()

    async def start(self) -> None:
        self._prepare_directory()
        self._unlink_stale()
        self._server = await asyncio.start_unix_server(self._serve, path=str(self.path))
        os.chmod(self.path, 0o600)
        log.info("channel socket listening", path=str(self.path))

    def _prepare_directory(self) -> None:
        """Own the directory outright: whoever can write here can drive an agent."""
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
        with contextlib.suppress(FileNotFoundError, OSError):
            self.path.unlink()

    async def _serve(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        task = asyncio.current_task()
        if task is not None:
            self._connections.add(task)
        try:
            await self._session(reader, writer)
        except (asyncio.IncompleteReadError, ConnectionError, OSError):
            pass
        finally:
            if task is not None:
                self._connections.discard(task)
            writer.close()
            with contextlib.suppress(Exception):
                await writer.wait_closed()

    async def _session(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        message = await self._opening_frame(reader)
        if message is None:
            return
        if message.get("type") == wire.SESSION_START:
            start = _session_start(message)
            if start is not None:
                await self._sink.attach_session_started(start)
            return
        attachment = await self._register(message, writer)
        if attachment is None:
            return
        await self._sink.attach_registered(attachment)
        try:
            async for line in reader:
                message = wire.decode(line)
                if message is None:
                    continue
                if message.get("type") == wire.PERMISSION_REQUEST:
                    await self._sink.attach_permission_request(attachment, message)
                elif message.get("type") == wire.CLOSED:
                    break
        finally:
            attachment.detach()
            await self._sink.attach_closed(attachment)

    @staticmethod
    async def _opening_frame(reader: asyncio.StreamReader) -> dict[str, Any] | None:
        """What the connection is for; a caller that says nothing in time is dropped."""
        try:
            line = await asyncio.wait_for(reader.readline(), timeout=REGISTER_TIMEOUT)
        except (TimeoutError, OSError):
            return None
        return wire.decode(line)

    @staticmethod
    async def _register(message: dict[str, Any], writer: asyncio.StreamWriter) -> Attachment | None:
        """The first frame must identify the session, or the connection is dropped."""
        if message.get("type") != wire.REGISTER:
            return None
        session_id = str(message.get("session_id") or "")
        if not session_id:
            return None
        raw_version = message.get("claude_version")
        writer.write(wire.encode({"type": wire.REGISTERED}))
        await writer.drain()
        return Attachment(
            session_id=session_id,
            cwd=str(message.get("cwd") or ""),
            pid=int(message.get("pid") or 0),
            claude_version=str(raw_version) if raw_version else None,
            writer=writer,
        )


def _session_start(message: dict[str, Any]) -> SessionStart | None:
    """Read a `session_start` frame, or None when it names no live CLI.

    The hook runs unattended in the terminal, so a frame that cannot be trusted
    to identify a process and a session is dropped rather than guessed at: an
    unusable one would move a live attachment onto the wrong conversation.
    """
    session_id = str(message.get("session_id") or "")
    if not _SESSION_ID.fullmatch(session_id):
        return None
    try:
        pid = int(message.get("pid") or 0)
    except (TypeError, ValueError):
        return None
    if pid <= 0:
        return None
    source = str(message.get("source") or "")
    return SessionStart(
        session_id=session_id,
        cwd=str(message.get("cwd") or ""),
        pid=pid,
        source=source if source in wire.SESSION_START_SOURCES else "startup",
        transcript_path=str(message.get("transcript_path") or ""),
    )
