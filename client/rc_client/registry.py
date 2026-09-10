"""SQLite registry for sessions, history events and `session.send` idempotency.

Writes are small and local, so they run inline on the event loop behind one
lock rather than in a thread pool. `next_seq` is persisted, which is what keeps
per-session `seq` monotonic across daemon restarts.
"""

from __future__ import annotations

import json
import sqlite3
import threading
from pathlib import Path
from typing import Any

from .events import bound_block, dedup_key
from .models import Session, now_ms

MAX_EVENTS_PER_SESSION = 20_000
PRUNE_EVERY = 500
REQUEST_TTL_MS = 24 * 60 * 60 * 1000
_SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions (
    session_id TEXT PRIMARY KEY,
    agent      TEXT NOT NULL,
    data       TEXT NOT NULL,
    next_seq   INTEGER NOT NULL DEFAULT 1,
    archived   INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
    session_id TEXT NOT NULL,
    dedup_key  TEXT NOT NULL,
    order_seq  INTEGER NOT NULL,
    seq        INTEGER NOT NULL,
    kind       TEXT NOT NULL,
    wire       TEXT NOT NULL,
    full       TEXT,
    PRIMARY KEY (session_id, dedup_key)
);
CREATE INDEX IF NOT EXISTS events_order ON events(session_id, order_seq);
CREATE INDEX IF NOT EXISTS events_seq ON events(session_id, seq);
CREATE INDEX IF NOT EXISTS events_seq ON events(session_id, seq);
CREATE TABLE IF NOT EXISTS kv (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS requests (
    session_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    result     TEXT NOT NULL,
    ts         INTEGER NOT NULL,
    PRIMARY KEY (session_id, request_id)
);
"""


class Registry:
    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self._lock = threading.RLock()
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        with self._lock:
            self._db.execute("PRAGMA journal_mode=WAL")
            self._db.execute("PRAGMA synchronous=NORMAL")
            self._db.execute("PRAGMA auto_vacuum=INCREMENTAL")
            self._db.executescript(_SCHEMA)
            self._db.commit()
            # Reclaim pages left behind by pruned events and expired requests.
            self._db.execute("PRAGMA incremental_vacuum")
            self._db.commit()

    def close(self) -> None:
        with self._lock:
            self._db.close()

    # ---------------------------------------------------------------- sessions

    def upsert_session(self, session: Session) -> None:
        with self._lock:
            self._db.execute(
                """
                INSERT INTO sessions (session_id, agent, data, next_seq, archived, updated_at)
                VALUES (?, ?, ?, COALESCE(
                    (SELECT next_seq FROM sessions WHERE session_id = ?), 1), ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    agent = excluded.agent, data = excluded.data,
                    archived = excluded.archived, updated_at = excluded.updated_at
                """,
                (
                    session.session_id,
                    session.agent,
                    json.dumps(session.to_dict(), ensure_ascii=False),
                    session.session_id,
                    1 if session.archived else 0,
                    session.updated_at,
                ),
            )
            self._db.commit()

    def load_sessions(self) -> list[Session]:
        """Every decodable session row; a damaged one is skipped, not fatal."""
        with self._lock:
            rows = self._db.execute("SELECT data FROM sessions ORDER BY updated_at DESC").fetchall()
        sessions: list[Session] = []
        for row in rows:
            try:
                payload = json.loads(row["data"])
                if not isinstance(payload, dict) or not payload.get("session_id"):
                    continue
                sessions.append(Session.from_dict(payload))
            except (json.JSONDecodeError, KeyError, TypeError, ValueError):
                continue
        return sessions

    def delete_session(self, session_id: str) -> None:
        with self._lock:
            self._db.execute("DELETE FROM sessions WHERE session_id = ?", (session_id,))
            self._db.execute("DELETE FROM events WHERE session_id = ?", (session_id,))
            self._db.execute("DELETE FROM requests WHERE session_id = ?", (session_id,))
            self._db.commit()

    def rekey_session(self, old_id: str, new_id: str) -> None:
        """Move a provisional session id to the agent's real one."""
        if old_id == new_id:
            return
        with self._lock:
            self._db.execute("DELETE FROM sessions WHERE session_id = ?", (new_id,))
            for table in ("sessions", "events", "requests"):
                self._db.execute(
                    f"UPDATE OR REPLACE {table} SET session_id = ? WHERE session_id = ?",
                    (new_id, old_id),
                )
            self._db.commit()

    # ------------------------------------------------------------------- seqs

    def next_seq(self, session_id: str) -> int:
        with self._lock:
            row = self._db.execute(
                "SELECT next_seq FROM sessions WHERE session_id = ?", (session_id,)
            ).fetchone()
            if row is None:
                self._db.execute(
                    "INSERT INTO sessions (session_id, agent, data, next_seq, archived, updated_at)"
                    " VALUES (?, '', '{}', 2, 0, ?)",
                    (session_id, now_ms()),
                )
                self._db.commit()
                return 1
            seq = int(row["next_seq"])
            self._db.execute(
                "UPDATE sessions SET next_seq = ? WHERE session_id = ?", (seq + 1, session_id)
            )
            self._db.commit()
            return seq

    # ----------------------------------------------------------------- events

    def store_event(self, session_id: str, wire: dict[str, Any], full: dict[str, Any]) -> None:
        """Persist an event if `session.history` should ever replay it."""
        key = dedup_key(wire)
        if key is None:
            return
        wire_json = json.dumps(wire, ensure_ascii=False)
        full_json = json.dumps(bound_block(full), ensure_ascii=False)
        with self._lock:
            self._db.execute(
                """
                INSERT INTO events (session_id, dedup_key, order_seq, seq, kind, wire, full)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id, dedup_key) DO UPDATE SET
                    seq = excluded.seq, kind = excluded.kind,
                    wire = excluded.wire, full = excluded.full
                """,
                (
                    session_id,
                    key,
                    int(wire["seq"]),
                    int(wire["seq"]),
                    str(wire["kind"]),
                    wire_json,
                    full_json if full_json != wire_json else None,
                ),
            )
            self._db.commit()
            if int(wire["seq"]) % PRUNE_EVERY == 0:
                self._prune(session_id)

    def _prune(self, session_id: str) -> None:
        count = self._db.execute(
            "SELECT COUNT(*) AS n FROM events WHERE session_id = ?", (session_id,)
        ).fetchone()["n"]
        if count <= MAX_EVENTS_PER_SESSION:
            return
        self._db.execute(
            """
            DELETE FROM events WHERE session_id = ? AND order_seq IN (
                SELECT order_seq FROM events WHERE session_id = ?
                ORDER BY order_seq ASC LIMIT ?
            )
            """,
            (session_id, session_id, count - MAX_EVENTS_PER_SESSION),
        )
        self._db.commit()

    def history(
        self,
        session_id: str,
        before_seq: int | None = None,
        limit: int = 200,
        after_seq: int | None = None,
    ) -> tuple[list[dict[str, Any]], bool]:
        """Latest event per block, ascending by `seq` (PROTOCOL.md section 8).

        `seq` is the sequence of a block's most recent version, so a block that is updated later
        moves later in the page. That is what the contract asks for and what `before_seq` pages
        against; `order_seq` only drives pruning, which retires the oldest blocks first.

        `after_seq` (amendment A9) walks the other way, oldest first, so the gateway can backfill
        the events a device produced while the link was down.
        """
        capped = max(1, min(limit, 1000))
        if after_seq is not None:
            with self._lock:
                rows = self._db.execute(
                    "SELECT wire FROM events WHERE session_id = ? AND seq > ?"
                    " ORDER BY seq ASC LIMIT ?",
                    (session_id, after_seq, capped + 1),
                ).fetchall()
            has_more = len(rows) > capped
            return [json.loads(row["wire"]) for row in rows[:capped]], has_more

        params: list[Any] = [session_id]
        clause = ""
        if before_seq is not None:
            clause = " AND seq < ?"
            params.append(before_seq)
        params.append(capped + 1)
        with self._lock:
            rows = self._db.execute(
                f"SELECT wire, seq FROM events WHERE session_id = ?{clause}"
                " ORDER BY seq DESC LIMIT ?",
                params,
            ).fetchall()
        has_more = len(rows) > capped
        selected = list(reversed(rows[:capped]))
        return [json.loads(row["wire"]) for row in selected], has_more

    def first_seqs(self, session_id: str) -> dict[str, int]:
        """The seq at which each stored block first appeared (amendment A8)."""
        with self._lock:
            rows = self._db.execute(
                "SELECT dedup_key, order_seq FROM events"
                " WHERE session_id = ? AND dedup_key LIKE 'block:%'",
                (session_id,),
            ).fetchall()
        return {str(row["dedup_key"])[len("block:") :]: int(row["order_seq"]) for row in rows}

    def block(self, session_id: str, block_id: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._db.execute(
                "SELECT wire, full FROM events WHERE session_id = ? AND dedup_key = ?",
                (session_id, f"block:{block_id}"),
            ).fetchone()
        if row is None:
            return None
        payload: dict[str, Any] = json.loads(row["full"] or row["wire"])
        return payload

    def last_seq(self, session_id: str) -> int:
        with self._lock:
            row = self._db.execute(
                "SELECT next_seq FROM sessions WHERE session_id = ?", (session_id,)
            ).fetchone()
        return int(row["next_seq"]) - 1 if row else 0

    # -------------------------------------------------------------- key/value

    def get_kv(self, key: str) -> str | None:
        with self._lock:
            row = self._db.execute("SELECT value FROM kv WHERE key = ?", (key,)).fetchone()
        return str(row["value"]) if row else None

    def set_kv(self, key: str, value: str) -> None:
        with self._lock:
            self._db.execute("INSERT OR REPLACE INTO kv (key, value) VALUES (?, ?)", (key, value))
            self._db.commit()

    # ------------------------------------------------------------ idempotency

    def remember_request(self, session_id: str, request_id: str, result: dict[str, Any]) -> None:
        stamp = now_ms()
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO requests (session_id, request_id, result, ts)"
                " VALUES (?, ?, ?, ?)",
                (session_id, request_id, json.dumps(result, ensure_ascii=False), stamp),
            )
            # An idempotency record only has to outlive the app's retries.
            self._db.execute(
                "DELETE FROM requests WHERE session_id = ? AND ts < ?",
                (session_id, stamp - REQUEST_TTL_MS),
            )
            self._db.commit()

    def recall_request(self, session_id: str, request_id: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._db.execute(
                "SELECT result FROM requests WHERE session_id = ? AND request_id = ?",
                (session_id, request_id),
            ).fetchone()
        if row is None:
            return None
        payload: dict[str, Any] = json.loads(row["result"])
        return payload
