"""Durable registration stores for Web Push and APNs.

Adapted from cc-remote's ``relay/push.py`` and ``relay/native_push.py`` (MIT, see
THIRD_PARTY_NOTICES.md), reduced to what a single-user gateway needs. Rows are bound to the session
that created them so signing out stops the notifications that login started.
"""

from __future__ import annotations

import asyncio
import os
import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .migrations import Migration, apply_migrations

#: No column has been added since these tables' first release; see rc_gateway/migrations.py.
MIGRATIONS: tuple[Migration, ...] = ()


@dataclass(frozen=True)
class WebPushSubscription:
    endpoint: str
    p256dh: str
    auth: str
    session_jti: str
    expires_at: float

    def browser_payload(self) -> dict[str, Any]:
        return {"endpoint": self.endpoint, "keys": {"p256dh": self.p256dh, "auth": self.auth}}


@dataclass(frozen=True)
class ApnsRegistration:
    device_token: str
    environment: str
    bundle_id: str
    session_jti: str
    expires_at: float


@dataclass(frozen=True)
class ApnsDelivery:
    delivery_id: int
    device_token: str
    environment: str
    payload: str
    attempts: int
    due_at: float


class PushStore:
    """One SQLite file holding Web Push subscriptions, APNs tokens and the delivery journal."""

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
                CREATE TABLE IF NOT EXISTS web_push (
                    endpoint TEXT PRIMARY KEY,
                    p256dh TEXT NOT NULL,
                    auth TEXT NOT NULL,
                    session_jti TEXT NOT NULL,
                    expires_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS apns_tokens (
                    device_token TEXT PRIMARY KEY,
                    environment TEXT NOT NULL,
                    bundle_id TEXT NOT NULL,
                    session_jti TEXT NOT NULL,
                    expires_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS apns_deliveries (
                    delivery_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    device_token TEXT NOT NULL,
                    environment TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    due_at REAL NOT NULL,
                    created_at REAL NOT NULL
                )
                """
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS apns_deliveries_due_idx ON apns_deliveries(due_at)"
            )
            apply_migrations(connection, MIGRATIONS)

    # ---- web push ----

    async def upsert_web(self, subscription: WebPushSubscription) -> None:
        await asyncio.to_thread(self._upsert_web, subscription)

    def _upsert_web(self, subscription: WebPushSubscription) -> None:
        now = time.time()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO web_push(endpoint, p256dh, auth, session_jti, expires_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(endpoint) DO UPDATE SET
                    p256dh=excluded.p256dh, auth=excluded.auth,
                    session_jti=excluded.session_jti, expires_at=excluded.expires_at,
                    updated_at=excluded.updated_at
                """,
                (
                    subscription.endpoint,
                    subscription.p256dh,
                    subscription.auth,
                    subscription.session_jti,
                    subscription.expires_at,
                    now,
                ),
            )

    async def remove_web(self, endpoint: str) -> None:
        await asyncio.to_thread(self._remove_web, endpoint)

    def _remove_web(self, endpoint: str) -> None:
        with self._connect() as connection:
            connection.execute("DELETE FROM web_push WHERE endpoint=?", (endpoint,))

    async def list_web(self) -> list[WebPushSubscription]:
        return await asyncio.to_thread(self._list_web)

    def _list_web(self) -> list[WebPushSubscription]:
        now = time.time()
        with self._connect() as connection:
            connection.execute("DELETE FROM web_push WHERE expires_at<=?", (now,))
            rows = connection.execute(
                "SELECT endpoint, p256dh, auth, session_jti, expires_at FROM web_push"
            ).fetchall()
        return [
            WebPushSubscription(
                endpoint=str(row["endpoint"]),
                p256dh=str(row["p256dh"]),
                auth=str(row["auth"]),
                session_jti=str(row["session_jti"]),
                expires_at=float(row["expires_at"]),
            )
            for row in rows
        ]

    # ---- apns ----

    async def upsert_apns(self, registration: ApnsRegistration) -> None:
        await asyncio.to_thread(self._upsert_apns, registration)

    def _upsert_apns(self, registration: ApnsRegistration) -> None:
        now = time.time()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO apns_tokens(
                    device_token, environment, bundle_id, session_jti, expires_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_token) DO UPDATE SET
                    environment=excluded.environment, bundle_id=excluded.bundle_id,
                    session_jti=excluded.session_jti, expires_at=excluded.expires_at,
                    updated_at=excluded.updated_at
                """,
                (
                    registration.device_token,
                    registration.environment,
                    registration.bundle_id,
                    registration.session_jti,
                    registration.expires_at,
                    now,
                ),
            )

    async def remove_apns(self, device_token: str) -> None:
        await asyncio.to_thread(self._remove_apns, device_token)

    def _remove_apns(self, device_token: str) -> None:
        with self._connect() as connection:
            connection.execute("DELETE FROM apns_tokens WHERE device_token=?", (device_token,))
            connection.execute("DELETE FROM apns_deliveries WHERE device_token=?", (device_token,))

    async def list_apns(self) -> list[ApnsRegistration]:
        return await asyncio.to_thread(self._list_apns)

    def _list_apns(self) -> list[ApnsRegistration]:
        now = time.time()
        with self._connect() as connection:
            connection.execute("DELETE FROM apns_tokens WHERE expires_at<=?", (now,))
            rows = connection.execute(
                "SELECT device_token, environment, bundle_id, session_jti, expires_at "
                "FROM apns_tokens"
            ).fetchall()
        return [
            ApnsRegistration(
                device_token=str(row["device_token"]),
                environment=str(row["environment"]),
                bundle_id=str(row["bundle_id"]),
                session_jti=str(row["session_jti"]),
                expires_at=float(row["expires_at"]),
            )
            for row in rows
        ]

    async def revoke_session(self, jti: str) -> None:
        """Sign-out removes every registration that login created."""
        await asyncio.to_thread(self._revoke_session, jti)

    def _revoke_session(self, jti: str) -> None:
        with self._connect() as connection:
            connection.execute("DELETE FROM web_push WHERE session_jti=?", (jti,))
            connection.execute("DELETE FROM apns_tokens WHERE session_jti=?", (jti,))

    # ---- delivery journal ----

    async def enqueue(self, device_token: str, environment: str, payload: str) -> None:
        await asyncio.to_thread(self._enqueue, device_token, environment, payload)

    def _enqueue(self, device_token: str, environment: str, payload: str) -> None:
        now = time.time()
        with self._connect() as connection:
            connection.execute(
                "INSERT INTO apns_deliveries("
                "device_token, environment, payload, due_at, created_at"
                ") VALUES (?, ?, ?, ?, ?)",
                (device_token, environment, payload, now, now),
            )

    async def claim_due(self, limit: int = 32) -> list[ApnsDelivery]:
        return await asyncio.to_thread(self._claim_due, limit)

    def _claim_due(self, limit: int) -> list[ApnsDelivery]:
        now = time.time()
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            rows = connection.execute(
                "SELECT delivery_id, device_token, environment, payload, attempts, due_at "
                "FROM apns_deliveries WHERE due_at<=? ORDER BY due_at LIMIT ?",
                (now, limit),
            ).fetchall()
            for row in rows:
                connection.execute(
                    "UPDATE apns_deliveries SET attempts=attempts+1, due_at=? WHERE delivery_id=?",
                    (now + 300, int(row["delivery_id"])),
                )
            connection.commit()
        finally:
            connection.close()
        return [
            ApnsDelivery(
                delivery_id=int(row["delivery_id"]),
                device_token=str(row["device_token"]),
                environment=str(row["environment"]),
                payload=str(row["payload"]),
                attempts=int(row["attempts"]),
                due_at=float(row["due_at"]),
            )
            for row in rows
        ]

    async def finish(self, delivery_id: int) -> None:
        await asyncio.to_thread(self._finish, delivery_id)

    def _finish(self, delivery_id: int) -> None:
        with self._connect() as connection:
            connection.execute("DELETE FROM apns_deliveries WHERE delivery_id=?", (delivery_id,))

    async def retry_at(self, delivery_id: int, when: float) -> None:
        await asyncio.to_thread(self._retry_at, delivery_id, when)

    def _retry_at(self, delivery_id: int, when: float) -> None:
        with self._connect() as connection:
            connection.execute(
                "UPDATE apns_deliveries SET due_at=? WHERE delivery_id=?", (when, delivery_id)
            )

    async def pending_count(self) -> int:
        return await asyncio.to_thread(self._pending_count)

    def _pending_count(self) -> int:
        with self._connect() as connection:
            return int(connection.execute("SELECT COUNT(*) FROM apns_deliveries").fetchone()[0])
