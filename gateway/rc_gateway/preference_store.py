"""The switches an account carries, stored on the gateway rather than in an app (A35).

A preference is a choice that must read the same in the browser, on the phone and on every device
of the account — the browser cannot own it, because the device has to act on it while no app is
running. The file follows ``push_store.py``: its own SQLite database under ``DATA_DIR``, opened at
0600, with additive migrations for anything a later amendment adds.

An account with no row has never touched the switches and reads the defaults, so a fresh gateway
needs no seeding and a deleted account leaves nothing behind.
"""

from __future__ import annotations

import asyncio
import os
import sqlite3
import time
from collections.abc import Mapping
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any

from .migrations import Migration, apply_migrations

#: Columns added since this table's first release; see rc_gateway/migrations.py. Empty until a
#: second preference exists, and the place that one is declared when it does.
MIGRATIONS: tuple[Migration, ...] = ()


@dataclass(frozen=True)
class Preferences:
    """One account's switches, as ``objects.json#/$defs/Preferences`` (A35)."""

    #: Whether a session the vendor's usage limit stopped is resumed by its device once the limit
    #: resets (PROTOCOL 7.2). Off until the person turns it on.
    resume_after_limit: bool = False

    def view(self) -> dict[str, Any]:
        return {"resume_after_limit": self.resume_after_limit}


#: Every preference the wire knows, so a PATCH body is validated against one list.
FIELDS: tuple[str, ...] = ("resume_after_limit",)

DEFAULTS = Preferences()


class PreferenceStore:
    """One SQLite file holding one row per account."""

    def __init__(self, path: Path | str) -> None:
        self.path = Path(path).expanduser()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()
        # A preference names an account, so the file is no more readable than the others.
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
                CREATE TABLE IF NOT EXISTS preferences (
                    username TEXT PRIMARY KEY,
                    resume_after_limit INTEGER NOT NULL DEFAULT 0,
                    updated_at REAL NOT NULL
                )
                """
            )
            apply_migrations(connection, MIGRATIONS)

    async def get(self, username: str) -> Preferences:
        """One account's switches. An account with no row reads the defaults."""
        return await asyncio.to_thread(self._get, username)

    def _get(self, username: str) -> Preferences:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT resume_after_limit FROM preferences WHERE username=?", (username,)
            ).fetchone()
        return _from_row(row)

    async def patch(self, username: str, values: Mapping[str, bool]) -> Preferences:
        """Set the switches named in ``values`` and return the whole object afterwards."""
        return await asyncio.to_thread(self._patch, username, values)

    def _patch(self, username: str, values: Mapping[str, bool]) -> Preferences:
        # Read and write in one transaction: two apps flipping different switches at once must
        # not have the second overwrite the first with a value it read before the change.
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT resume_after_limit FROM preferences WHERE username=?", (username,)
            ).fetchone()
            merged = replace(_from_row(row), **dict(values))
            connection.execute(
                """
                INSERT INTO preferences(username, resume_after_limit, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(username) DO UPDATE SET
                    resume_after_limit=excluded.resume_after_limit,
                    updated_at=excluded.updated_at
                """,
                (username, 1 if merged.resume_after_limit else 0, time.time()),
            )
            connection.commit()
        finally:
            connection.close()
        return merged

    async def remove_for_user(self, username: str) -> None:
        """A deleted account takes its switches with it, as its registrations go (A24)."""
        await asyncio.to_thread(self._remove_for_user, username)

    def _remove_for_user(self, username: str) -> None:
        with self._connect() as connection:
            connection.execute("DELETE FROM preferences WHERE username=?", (username,))


def _from_row(row: sqlite3.Row | None) -> Preferences:
    if row is None:
        return DEFAULTS
    return Preferences(resume_after_limit=bool(row["resume_after_limit"]))
