"""Amendment A11: bringing the shared Codex daemon up, and supervising it."""

from __future__ import annotations

import plistlib
from pathlib import Path

import pytest

from rc_client.agents.codex.daemon import setup
from rc_client.service import codex as supervision


def test_a_present_codex_is_never_reinstalled() -> None:
    assert setup.installer_plan(has_codex=True, local_bin_on_path=False) == setup.INSTALL_PRESENT
    assert setup.installer_plan(has_codex=True, local_bin_on_path=True) == setup.INSTALL_PRESENT


def test_the_installer_only_runs_when_its_target_is_already_on_path() -> None:
    """Codex's installer rewrites a shell profile otherwise, and those are symlinks here."""
    assert setup.installer_plan(has_codex=False, local_bin_on_path=True) == setup.INSTALL_RUN
    assert setup.installer_plan(has_codex=False, local_bin_on_path=False) == setup.INSTALL_MANUAL


def test_the_manual_route_prints_both_commands() -> None:
    assert len(setup.MANUAL_COMMANDS) == 2
    assert setup.INSTALL_URL in setup.MANUAL_COMMANDS[0]
    assert ".local/bin" in setup.MANUAL_COMMANDS[1]


def test_on_path_compares_resolved_directories(tmp_path: Path) -> None:
    directory = tmp_path / "bin"
    directory.mkdir()
    assert setup.on_path(directory, f"/usr/bin:{directory}") is True
    assert setup.on_path(directory, "/usr/bin:/bin") is False


def test_a_download_that_is_not_a_script_is_never_executed() -> None:
    assert setup.looks_like_shell_script("#!/bin/sh\necho hi\n") is True
    assert setup.looks_like_shell_script("#!/usr/bin/env bash\n") is True
    assert setup.looks_like_shell_script("<!doctype html><html>") is False
    assert setup.looks_like_shell_script("") is False


def test_the_status_summary_says_what_to_do_next() -> None:
    healthy = setup.DaemonStatus("/bin/codex", True, "/s.sock", True, True, "loaded")
    assert healthy.summary() == "healthy (loaded)"
    assert healthy.healthy is True
    stale = setup.DaemonStatus("/bin/codex", True, "/s.sock", True, False, "not installed")
    assert stale.summary() == "socket present but not answering"
    missing = setup.DaemonStatus(None, False, "/s.sock", False, False, "not installed")
    assert missing.summary() == "not bootstrapped"
    assert len(missing.lines()) == 6


async def test_setup_stops_with_instructions_when_it_must_not_touch_a_dotfile(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(setup, "resolve_binary", lambda: None)
    monkeypatch.setattr(setup, "on_path", lambda directory, value=None: False)
    ok, lines = await setup.setup()
    assert ok is False
    assert any(setup.INSTALL_URL in line for line in lines)


async def test_setup_refuses_to_install_when_told_not_to(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(setup, "resolve_binary", lambda: None)
    monkeypatch.setattr(setup, "on_path", lambda directory, value=None: True)
    ok, lines = await setup.setup(install_missing=False)
    assert ok is False
    assert "--no-install" in lines[0]


def test_the_launchd_agent_runs_daemon_start_under_our_own_label() -> None:
    parsed = plistlib.loads(supervision.render_plist("/opt/codex").encode("utf-8"))
    assert parsed["Label"] == "dev.remote-control.codex-daemon"
    assert parsed["ProgramArguments"] == ["/opt/codex", "app-server", "daemon", "start"]
    assert parsed["RunAtLoad"] is True
    assert parsed["StartInterval"] == supervision.NUDGE_SECONDS
    # `enable-remote-control` enrols the machine with OpenAI's relay.
    assert "remote-control" not in " ".join(parsed["ProgramArguments"][1:])


def test_the_launchd_agent_escapes_a_path_that_would_break_the_plist() -> None:
    parsed = plistlib.loads(supervision.render_plist("/opt/a&b/codex").encode("utf-8"))
    assert parsed["ProgramArguments"][0] == "/opt/a&b/codex"


def test_the_systemd_unit_nudges_the_idempotent_start_command() -> None:
    unit = supervision.render_unit("/opt/codex")
    assert "ExecStart=/opt/codex app-server daemon start" in unit
    assert f"RestartSec={supervision.NUDGE_SECONDS}" in unit
    assert "WantedBy=default.target" in unit
    assert "--remote-control" not in unit


def test_the_linger_hint_is_linux_only() -> None:
    assert (supervision.post_install_hint() is None) is supervision.IS_MACOS
