"""Mirror agent sessions that were started in a terminal on this device.

Discovery re-scans every 10 s; live files are tailed every 2 s. A session the
daemon drives itself is never ingested from disk, so events are never doubled.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
from dataclasses import dataclass
from typing import Any

from ..agents.claude import transcripts
from ..agents.claude.holders import scan_holders
from ..agents.codex import rollouts
from ..config import MirrorConfig
from ..logging_setup import logger
from ..models import Session, now_ms, title_from_text
from ..procscan import file_writers
from .hub import SessionEntry, SessionHub

log = logger("rc_client.mirror")

SCAN_INTERVAL = 10.0
TAIL_INTERVAL = 2.0
BACKFILL_BYTES = 4 * 1024 * 1024

Tailer = transcripts.TranscriptTailer | rollouts.RolloutTailer


@dataclass(slots=True)
class ClaudeMirror:
    tailer: transcripts.TranscriptTailer


@dataclass(slots=True)
class CodexMirror:
    tailer: rollouts.RolloutTailer


class MirrorService:
    def __init__(self, hub: SessionHub, limits: MirrorConfig | None = None) -> None:
        self.hub = hub
        self.limits = limits or MirrorConfig()
        self._claude: dict[str, ClaudeMirror] = {}
        self._codex: dict[str, CodexMirror] = {}
        self._tasks: list[asyncio.Task[None]] = []

    def start(self) -> None:
        self._tasks = [
            asyncio.create_task(self._scan_loop()),
            asyncio.create_task(self._tail_loop()),
        ]

    async def stop(self) -> None:
        for task in self._tasks:
            task.cancel()
        for task in self._tasks:
            with contextlib.suppress(asyncio.CancelledError):
                await task
        self._tasks = []

    # ---------------------------------------------------------------- scans

    async def _scan_loop(self) -> None:
        while True:
            try:
                await self.scan_once()
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("terminal session scan failed")
            await asyncio.sleep(SCAN_INTERVAL)

    async def scan_once(self) -> None:
        """Re-scan the agent state directories and refresh who controls what.

        Only the filesystem walk runs off the loop: registering a mirror
        creates tasks, so it must happen on the event loop thread.
        """
        found_claude = await asyncio.to_thread(
            transcripts.discover, self.limits.max_sessions, self.limits.max_age_days
        )
        found_codex = await asyncio.to_thread(
            rollouts.discover, self.limits.max_sessions, self.limits.max_age_days
        )
        self._adopt_claude(found_claude)
        self._adopt_codex(found_codex)
        await self._refresh_claude_control()
        await self._refresh_codex_control()

    def _offset_key(self, session_id: str) -> str:
        return f"mirror-offset:{session_id}"

    def _initial_offset(self, session_id: str, path: str) -> int:
        stored = self.hub.registry.get_kv(self._offset_key(session_id))
        if stored is not None and stored.isdigit():
            return int(stored)
        try:
            size = os.path.getsize(path)
        except OSError:
            return 0
        return max(0, size - BACKFILL_BYTES)

    def _adopt_claude(self, found: list[transcripts.TranscriptInfo]) -> None:
        for info in found:
            if info.session_id in self._claude:
                continue
            entry = self.hub.entries.get(info.session_id)
            if entry is not None and not self._adoptable(entry):
                continue
            if entry is None:
                entry = self.hub.register_mirrored(
                    Session(
                        session_id=info.session_id,
                        device_id=self.hub.device_id,
                        agent="claude",
                        cwd=info.cwd,
                        title="",
                        state="idle",
                        origin="terminal",
                        control="none",
                        created_at=int(info.mtime * 1000),
                        updated_at=int(info.mtime * 1000),
                    ),
                    transcript=info.path,
                )
            tailer = transcripts.TranscriptTailer(path=info.path, cwd=info.cwd)
            tailer.offset = self._initial_offset(info.session_id, info.path)
            entry.transcript = info.path
            entry.channel.start()
            self._claude[info.session_id] = ClaudeMirror(tailer=tailer)

    def _adopt_codex(self, found: list[rollouts.RolloutInfo]) -> None:
        for info in found:
            if info.thread_id in self._codex:
                continue
            entry = self.hub.entries.get(info.thread_id)
            if entry is not None and not self._adoptable(entry):
                continue
            if entry is None:
                entry = self.hub.register_mirrored(
                    Session(
                        session_id=info.thread_id,
                        device_id=self.hub.device_id,
                        agent="codex",
                        cwd=info.cwd,
                        title="",
                        state="idle",
                        origin="terminal",
                        control="none",
                        created_at=int(info.mtime * 1000),
                        updated_at=int(info.mtime * 1000),
                    ),
                    transcript=info.path,
                )
            tailer = rollouts.RolloutTailer(path=info.path, cwd=info.cwd)
            tailer.offset = self._initial_offset(info.thread_id, info.path)
            entry.transcript = info.path
            entry.channel.start()
            self._codex[info.thread_id] = CodexMirror(tailer=tailer)

    @staticmethod
    def _adoptable(entry: SessionEntry) -> bool:
        """Whether the transcript on disk is someone else's to read.

        A session we drive writes its own transcript, so re-reading it would
        replay the whole conversation as terminal messages with fresh block ids.
        `origin` is persisted, which is what makes this survive a restart, when
        the runner is gone but the session is still ours.
        """
        return entry.runner is None and entry.session.origin != "remote"

    def _live_entry(self, session_id: str, mirrors: dict[str, Any]) -> SessionEntry | None:
        """Re-resolve the hub entry, dropping the mirror when it is no longer ours.

        The hub replaces the entry object on rekey and removes it on delete, so
        holding the object would keep a deleted session alive and would fight a
        live runner for the same id.
        """
        entry = self.hub.entries.get(session_id)
        if entry is None or not self._adoptable(entry):
            mirrors.pop(session_id, None)
            return None
        return entry

    def _claude_tracked(self) -> list[tuple[str, SessionEntry, ClaudeMirror | None]]:
        """Every Claude session whose owner has to be re-checked this round.

        Sessions with a transcript come from the mirror; a session an attachment
        created before its first turn has no transcript yet, and would otherwise
        never learn that its CLI has gone.
        """
        tracked: list[tuple[str, SessionEntry, ClaudeMirror | None]] = []
        for session_id, mirror in list(self._claude.items()):
            entry = self._live_entry(session_id, self._claude)
            if entry is not None:
                tracked.append((session_id, entry, mirror))
        seen = {session_id for session_id, _, _ in tracked}
        for session_id, entry in list(self.hub.entries.items()):
            if session_id in seen or entry.session.agent != "claude":
                continue
            if entry.shared is None and entry.session.control != "terminal":
                continue
            if entry.runner is None:
                tracked.append((session_id, entry, None))
        return tracked

    async def _refresh_claude_control(self) -> None:
        tracked = self._claude_tracked()
        if not tracked:
            return
        scan = await scan_holders()
        for session_id, entry, mirror in tracked:
            running = mirror.tailer.awaiting_reply if mirror is not None else False
            if entry.shared is not None:
                await self.hub.shared.tick(entry, running)
                continue
            if not scan.complete:
                continue
            holder = scan.for_session(session_id, entry.session.cwd)
            if holder is not None:
                entry.holder_pid = holder.pid
                entry.holder_identity = holder.identity
                await self._set_control(entry, "terminal", running)
            else:
                entry.holder_pid = None
                entry.holder_identity = None
                if mirror is not None:
                    mirror.tailer.awaiting_reply = False
                await self._set_control(entry, "none", False)

    async def _refresh_codex_control(self) -> None:
        for session_id, mirror in list(self._codex.items()):
            entry = self._live_entry(session_id, self._codex)
            if entry is None:
                continue
            writers, known = await file_writers(mirror.tailer.path)
            if not known:
                continue
            owned = bool([pid for pid in writers if pid != os.getpid()])
            await self._set_control(entry, "terminal" if owned else "none", mirror.tailer.running)

    async def _set_control(self, entry: SessionEntry, control: str, running: bool) -> None:
        """Amendment A7: `readonly` only while the terminal holds an idle session."""
        changed = entry.session.control != control
        if changed:
            entry.session.control = control  # type: ignore[assignment]
            await entry.channel.emit("meta", control=control)
        if control == "terminal":
            await entry.channel.set_state("running" if running else "readonly")
        else:
            await entry.channel.set_state("idle")
        if changed:
            await entry.channel.publish_summary()

    # ----------------------------------------------------------------- tails

    async def _tail_loop(self) -> None:
        while True:
            await asyncio.sleep(TAIL_INTERVAL)
            try:
                await self.tail_once()
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("terminal session tail failed")

    async def tail_once(self) -> None:
        for session_id, claude_mirror in list(self._claude.items()):
            entry = self._live_entry(session_id, self._claude)
            if entry is None:
                continue
            await self._tail_one(session_id, entry, claude_mirror.tailer)
        for session_id, codex_mirror in list(self._codex.items()):
            entry = self._live_entry(session_id, self._codex)
            if entry is None:
                continue
            await self._tail_one(session_id, entry, codex_mirror.tailer)

    async def _tail_one(self, session_id: str, entry: SessionEntry, tailer: Tailer) -> None:
        """Read what is new, then report turn state as those rows left it.

        Reading the flag first would report the previous pass's state, which
        closes a turn one tail interval after the message that started it.
        """
        rows = await asyncio.to_thread(tailer.read_new)
        if not rows:
            return
        for row in rows:
            for emit in tailer.translate(row):
                await self._apply(entry, emit)
        self.hub.registry.set_kv(self._offset_key(session_id), str(tailer.offset))
        await self._after_rows(entry, tailer.busy)

    async def _after_rows(self, entry: SessionEntry, running: bool) -> None:
        entry.session.updated_at = now_ms()
        if entry.shared is not None:
            await self.hub.shared.tick(entry, running)
        elif entry.session.control == "terminal":
            await entry.channel.set_state("running" if running else "readonly")
        await entry.channel.publish_summary()

    async def _apply(self, entry: SessionEntry, emit: Any) -> None:
        if emit.kind in _CHANNEL_ECHOES:
            await self._channel_echo(entry, emit)
            return
        if entry.shared is not None and emit.kind == "tool_call":
            status = str(emit.fields.get("status") or "")
            if status in {"succeeded", "failed"}:
                await self.hub.shared.tool_finished(
                    entry, str(emit.fields.get("tool") or ""), status == "failed"
                )
        if emit.kind == "todos":
            await entry.channel.publish_todos(list(emit.fields.get("items") or []))
            return
        if emit.kind == "user_message" and not entry.session.title:
            entry.session.title = title_from_text(str(emit.fields.get("text") or ""))
        await entry.channel.emit(emit.kind, **emit.fields)

    async def _channel_echo(self, entry: SessionEntry, emit: Any) -> None:
        """Internal transcript signals about our own injections; never published."""
        message_id = str(emit.fields.get("message_id") or "")
        if not message_id:
            return
        if emit.kind == transcripts.CHANNEL_DELIVERED:
            await self.hub.shared.echo_delivered(entry, message_id)
        else:
            await self.hub.shared.echo_absorbed(entry, message_id)


_CHANNEL_ECHOES = {transcripts.CHANNEL_DELIVERED, transcripts.CHANNEL_ABSORBED}
