"""Shared fixtures."""

from __future__ import annotations

import shutil
import tempfile
from collections.abc import Iterator
from pathlib import Path

import pytest

from rc_client.agents.grok import runtime as grok_runtime


@pytest.fixture(autouse=True)
def grok_home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Point Grok's state directory at a scratch path.

    `~/.grok/sessions` holds a real person's conversations, and mirroring walks
    it on every scan; a test must never adopt one of those.
    """
    home = tmp_path / "grok-home"
    monkeypatch.setattr(grok_runtime, "home", lambda: home)


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
