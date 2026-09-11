"""SQLite index of the last known session summary per session.

The device owns session history; the gateway keeps only the newest ``Session`` object (protocol §3)
so lists render while a device is offline, plus the highest ``seq`` it has observed so a
reconnecting app can ask for the right slice. Summaries are stored as opaque JSON: the gateway reads
only the fields it routes or filters on.

Sequence numbers arrive on the hot path between a device and the apps watching it, so they are
recorded in memory and written to SQLite by a background task. Reads overlay whatever is still
unwritten, which makes the recorded value visible immediately: an app can never read a summary
older than an event it has already received live.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import os
import sqlite3
import time
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any

from .logging import logger
from .migrations import Migration, apply_migrations

log = logger("rc_gateway.index")

#: No column has been added since this table's first release; see rc_gateway/migrations.py.
MIGRATIONS: tuple[Migration, ...] = ()

Session = dict[str, Any]


@dataclass(frozen=True)
class IndexedSession:
    session_id: str
    device_id: str
    state: str
    last_seq: int
    summary: Session


class SessionIndex:
    def __init__(self, path: Path | str) -> None:
        self.path = Path(path).expanduser()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()
        # Session titles, working directories and push endpoints are not world-readable.
        os.chmod(self.path, 0o600)
        # Sequence numbers observed but not yet on disk. Every read overlays them, so this map,
        # not the table, is the newest truth until the flush task drains it.
        self._pending_seq: dict[str, int] = {}
        self._flush: asyncio.Task[None] | None = None
        # Serialises the database against the flush task, so a read and the overlay it applies
        # cannot straddle a write that removes the pending entry it was about to use.
        self._db_lock = asyncio.Lock()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA busy_timeout=10000")
        return connection

    def _initialize(self) -> None:
        with self._connect() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    state TEXT NOT NULL DEFAULT '',
                    archived INTEGER NOT NULL DEFAULT 0,
                    last_seq INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL,
                    summary TEXT NOT NULL
                )
                """
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS sessions_device_idx ON sessions(device_id)"
            )
            apply_migrations(connection, MIGRATIONS)

    async def upsert(self, summary: Session) -> IndexedSession | None:
        """Store a summary announced by a device. Returns the stored row.

        Returns ``None`` when the summary is unusable or when another device already owns the
        session: ownership is set on first sight and never rebound, so a compromised device cannot
        take over another machine's session by announcing a summary for it.
        """
        session_id = summary.get("session_id")
        async with self._db_lock:
            pending = self._pending_seq.get(session_id, 0) if isinstance(session_id, str) else 0
            indexed = await asyncio.to_thread(self._upsert, summary, pending)
            if indexed is None:
                return None
            if self._pending_seq.get(indexed.session_id, 0) <= indexed.last_seq:
                self._pending_seq.pop(indexed.session_id, None)
            return self._overlaid(indexed)

    async def owner(self, session_id: str) -> str | None:
        """The device that owns a session, or ``None`` when the gateway has never seen it."""
        async with self._db_lock:
            return await asyncio.to_thread(self._owner, session_id)

    def _owner(self, session_id: str) -> str | None:
        if not session_id:
            return None
        with self._connect() as connection:
            row = connection.execute(
                "SELECT device_id FROM sessions WHERE session_id=?", (session_id,)
            ).fetchone()
        return str(row["device_id"]) if row is not None else None

    def _upsert(self, summary: Session, pending_seq: int) -> IndexedSession | None:
        session_id = summary.get("session_id")
        device_id = summary.get("device_id")
        if not isinstance(session_id, str) or not isinstance(device_id, str):
            return None
        if not session_id or not device_id:
            return None
        state = str(summary.get("state") or "")
        archived = 1 if summary.get("archived") else 0
        last_seq = _as_int(summary.get("last_seq"))
        updated_at = _as_int(summary.get("updated_at")) or int(time.time() * 1000)
        with self._connect() as connection:
            row = connection.execute(
                "SELECT last_seq, device_id FROM sessions WHERE session_id=?", (session_id,)
            ).fetchone()
            if row is not None and str(row["device_id"]) != device_id:
                return None
            # A device restarting mid-stream may announce a summary whose last_seq lags the
            # events it already sent, and the newest of those may not have been written yet.
            # Never move the cursor backwards.
            stored_seq = int(row["last_seq"]) if row is not None else 0
            merged_seq = max(last_seq, stored_seq, pending_seq)
            stored = dict(summary)
            stored["last_seq"] = merged_seq
            connection.execute(
                """
                INSERT INTO sessions(
                    session_id, device_id, state, archived, last_seq, updated_at, summary
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    state=excluded.state,
                    archived=excluded.archived,
                    last_seq=excluded.last_seq,
                    updated_at=excluded.updated_at,
                    summary=excluded.summary
                """,
                (
                    session_id,
                    device_id,
                    state,
                    archived,
                    merged_seq,
                    updated_at,
                    _dumps(stored),
                ),
            )
        return IndexedSession(
            session_id=session_id,
            device_id=device_id,
            state=state,
            last_seq=merged_seq,
            summary=stored,
        )

    # ---- sequence cursor ----

    def record_seq(self, session_id: str, seq: int) -> None:
        """Note the highest ``seq`` a session has reached, without waiting for the disk.

        The value is authoritative for every read the moment this returns, so an event can be
        fanned out to apps before SQLite has caught up without the apps ever observing a summary
        that predates it. Losing the unwritten tail to a crash only makes a reconnecting app
        resynchronise, which the protocol already handles.
        """
        if not session_id or seq <= self._pending_seq.get(session_id, 0):
            return
        self._pending_seq[session_id] = seq
        if self._flush is None or self._flush.done():
            self._flush = asyncio.create_task(self._flush_pending())

    async def flush(self) -> None:
        """Write every recorded sequence number, in as few transactions as the batches allow."""
        while self._pending_seq:
            batch = dict(self._pending_seq)
            async with self._db_lock:
                await asyncio.to_thread(self._record_seqs, batch)
                for session_id, seq in batch.items():
                    if self._pending_seq.get(session_id) == seq:
                        del self._pending_seq[session_id]

    async def _flush_pending(self) -> None:
        try:
            await self.flush()
        except Exception:
            # The entries stay in `_pending_seq`, so reads remain correct and the next recorded
            # sequence number schedules another attempt.
            log.exception("session index flush failed", sessions=len(self._pending_seq))
        finally:
            # No await separates the loop's last check from here, so a sequence number recorded
            # after that check always finds a finished task and schedules a new one.
            self._flush = None

    async def close(self) -> None:
        """Drain the outstanding sequence writes. Called once, when the gateway shuts down."""
        task = self._flush
        if task is not None:
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        await self._flush_pending()

    def _record_seqs(self, batch: dict[str, int]) -> None:
        with self._connect() as connection:
            for session_id, seq in batch.items():
                row = connection.execute(
                    "SELECT summary, last_seq FROM sessions WHERE session_id=?", (session_id,)
                ).fetchone()
                if row is None or int(row["last_seq"]) >= seq:
                    continue
                summary = _loads(row["summary"])
                summary["last_seq"] = seq
                connection.execute(
                    "UPDATE sessions SET last_seq=?, summary=? WHERE session_id=?",
                    (seq, _dumps(summary), session_id),
                )

    def _overlaid(self, indexed: IndexedSession) -> IndexedSession:
        """Raise a stored row to the newest recorded sequence number."""
        pending = self._pending_seq.get(indexed.session_id, 0)
        if pending <= indexed.last_seq:
            return indexed
        return replace(indexed, last_seq=pending, summary={**indexed.summary, "last_seq": pending})

    def _overlaid_summary(self, summary: Session) -> Session:
        session_id = summary.get("session_id")
        if not isinstance(session_id, str):
            return summary
        pending = self._pending_seq.get(session_id, 0)
        if pending <= _as_int(summary.get("last_seq")):
            return summary
        return {**summary, "last_seq": pending}

    # ---- reads ----

    async def get(self, session_id: str) -> IndexedSession | None:
        async with self._db_lock:
            indexed = await asyncio.to_thread(self._get, session_id)
            return None if indexed is None else self._overlaid(indexed)

    def _get(self, session_id: str) -> IndexedSession | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT session_id, device_id, state, last_seq, summary "
                "FROM sessions WHERE session_id=?",
                (session_id,),
            ).fetchone()
        return None if row is None else _indexed(row)

    async def list_sessions(
        self, *, device_id: str | None = None, archived: bool | None = None
    ) -> list[Session]:
        async with self._db_lock:
            rows = await asyncio.to_thread(self._list, device_id, archived)
            return [self._overlaid_summary(row) for row in rows]

    def _list(self, device_id: str | None, archived: bool | None) -> list[Session]:
        clauses: list[str] = []
        params: list[Any] = []
        if device_id is not None:
            clauses.append("device_id=?")
            params.append(device_id)
        if archived is not None:
            clauses.append("archived=?")
            params.append(1 if archived else 0)
        where = f" WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as connection:
            rows = connection.execute(
                f"SELECT summary FROM sessions{where} ORDER BY updated_at DESC, session_id",
                params,
            ).fetchall()
        return [_loads(row["summary"]) for row in rows]

    # ---- removal ----

    async def remove(self, session_id: str) -> str | None:
        """Delete one session. Returns its device id when it existed."""
        async with self._db_lock:
            device_id = await asyncio.to_thread(self._remove, session_id)
            self._pending_seq.pop(session_id, None)
            return device_id

    def _remove(self, session_id: str) -> str | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT device_id FROM sessions WHERE session_id=?", (session_id,)
            ).fetchone()
            if row is None:
                return None
            connection.execute("DELETE FROM sessions WHERE session_id=?", (session_id,))
        return str(row["device_id"])

    async def remove_for_device(self, device_id: str) -> list[str]:
        async with self._db_lock:
            removed = await asyncio.to_thread(self._remove_for_device, device_id)
            for session_id in removed:
                self._pending_seq.pop(session_id, None)
            return removed

    def _remove_for_device(self, device_id: str) -> list[str]:
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT session_id FROM sessions WHERE device_id=?", (device_id,)
            ).fetchall()
            connection.execute("DELETE FROM sessions WHERE device_id=?", (device_id,))
        return [str(row["session_id"]) for row in rows]


def _indexed(row: Any) -> IndexedSession:
    return IndexedSession(
        session_id=str(row["session_id"]),
        device_id=str(row["device_id"]),
        state=str(row["state"]),
        last_seq=int(row["last_seq"]),
        summary=_loads(row["summary"]),
    )


def _dumps(summary: Session) -> str:
    return json.dumps(summary, ensure_ascii=False, separators=(",", ":"))


def _loads(raw: Any) -> Session:
    try:
        value = json.loads(raw)
    except (TypeError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def _as_int(value: Any) -> int:
    return value if isinstance(value, int) and not isinstance(value, bool) else 0
