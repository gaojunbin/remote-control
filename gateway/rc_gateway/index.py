"""SQLite index of the last known session summary per session.

The device owns session history; the gateway keeps only the newest ``Session`` object (protocol §3)
so lists render while a device is offline, plus the highest ``seq`` it has observed so a
reconnecting app can ask for the right slice. Summaries are stored as opaque JSON: the gateway reads
only the fields it routes or filters on.
"""

from __future__ import annotations

import asyncio
import json
import os
import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .migrations import Migration, apply_migrations

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
        return await asyncio.to_thread(self._upsert, summary)

    async def owner(self, session_id: str) -> str | None:
        """The device that owns a session, or ``None`` when the gateway has never seen it."""
        return await asyncio.to_thread(self._owner, session_id)

    def _owner(self, session_id: str) -> str | None:
        if not session_id:
            return None
        with self._connect() as connection:
            row = connection.execute(
                "SELECT device_id FROM sessions WHERE session_id=?", (session_id,)
            ).fetchone()
        return str(row["device_id"]) if row is not None else None

    def _upsert(self, summary: Session) -> IndexedSession | None:
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
            # events it already sent. Never move the cursor backwards.
            merged_seq = max(last_seq, int(row["last_seq"]) if row is not None else 0)
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
                    json.dumps(stored, ensure_ascii=False, separators=(",", ":")),
                ),
            )
        return IndexedSession(
            session_id=session_id,
            device_id=device_id,
            state=state,
            last_seq=merged_seq,
            summary=stored,
        )

    async def record_seq(self, session_id: str, seq: int) -> None:
        await asyncio.to_thread(self._record_seq, session_id, seq)

    def _record_seq(self, session_id: str, seq: int) -> None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT summary, last_seq FROM sessions WHERE session_id=?", (session_id,)
            ).fetchone()
            if row is None or int(row["last_seq"]) >= seq:
                return
            summary = _loads(row["summary"])
            summary["last_seq"] = seq
            connection.execute(
                "UPDATE sessions SET last_seq=?, summary=? WHERE session_id=?",
                (seq, json.dumps(summary, ensure_ascii=False, separators=(",", ":")), session_id),
            )

    async def get(self, session_id: str) -> IndexedSession | None:
        return await asyncio.to_thread(self._get, session_id)

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
        return await asyncio.to_thread(self._list, device_id, archived)

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

    async def remove(self, session_id: str) -> str | None:
        """Delete one session. Returns its device id when it existed."""
        return await asyncio.to_thread(self._remove, session_id)

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
        return await asyncio.to_thread(self._remove_for_device, device_id)

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


def _loads(raw: Any) -> Session:
    try:
        value = json.loads(raw)
    except (TypeError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def _as_int(value: Any) -> int:
    return value if isinstance(value, int) and not isinstance(value, bool) else 0
