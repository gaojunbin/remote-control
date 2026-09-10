"""Shared fixtures."""

from __future__ import annotations

import shutil
import tempfile
from collections.abc import Iterator
from pathlib import Path

import pytest


@pytest.fixture(autouse=True)
def client_home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[Path]:
    home = tmp_path / "rc-client"
    monkeypatch.setenv("RC_CLIENT_HOME", str(home))
    # Point the shared Codex daemon at a path that cannot exist, so a test never
    # reaches the developer's own daemon and drives their real threads.
    monkeypatch.setenv("RC_CODEX_DAEMON_SOCKET", str(tmp_path / "no-codex-daemon.sock"))
    yield home


@pytest.fixture
def socket_dir() -> Iterator[Path]:
    """A directory short enough for an AF_UNIX path, which caps at 104 bytes."""
    directory = tempfile.mkdtemp(prefix="rc-")
    try:
        yield Path(directory)
    finally:
        shutil.rmtree(directory, ignore_errors=True)
