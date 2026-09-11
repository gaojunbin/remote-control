"""Amendment A11: bringing the shared Codex daemon up, and supervising it."""

from __future__ import annotations

import plistlib
from pathlib import Path

import pytest

from rc_client.agents.codex import runtime
from rc_client.agents.codex.daemon import setup
from rc_client.service import codex as supervision


def test_a_present_standalone_is_never_reinstalled() -> None:
    assert setup.installer_plan(has_standalone=True, local_bin_on_path=False) == (
        setup.INSTALL_PRESENT
    )
    assert setup.installer_plan(has_standalone=True, local_bin_on_path=True) == (
        setup.INSTALL_PRESENT
    )


def test_the_installer_only_runs_when_its_target_is_already_on_path() -> None:
    """Codex's installer rewrites a shell profile otherwise, and those are symlinks here."""
    assert setup.installer_plan(has_standalone=False, local_bin_on_path=True) == setup.INSTALL_RUN
    assert setup.installer_plan(has_standalone=False, local_bin_on_path=False) == (
        setup.INSTALL_MANUAL
    )


def test_a_codex_on_path_does_not_count_as_a_standalone_install(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The npm and Homebrew builds cannot bootstrap the daemon, so they are not "installed"."""
    npm_bin = tmp_path / "npm"
    npm_bin.mkdir()
    fake = npm_bin / "codex"
    fake.write_text("#!/bin/sh\n", encoding="utf-8")
    fake.chmod(0o755)
    monkeypatch.setenv("PATH", str(npm_bin))
    monkeypatch.setattr(setup, "STANDALONE", tmp_path / "absent/bin/codex")
    assert setup.standalone_binary() is None
    assert setup.installer_plan(setup.standalone_binary() is not None, True) == setup.INSTALL_RUN


def test_the_standalone_binary_is_the_one_the_daemon_commands_use(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    standalone = tmp_path / "packages/standalone/current/bin/codex"
    standalone.parent.mkdir(parents=True)
    standalone.write_text("#!/bin/sh\n", encoding="utf-8")
    standalone.chmod(0o755)
    monkeypatch.setattr(setup, "STANDALONE", standalone)
    assert setup.standalone_binary() == str(standalone)
    standalone.chmod(0o644)
    assert setup.standalone_binary() is None


def test_the_manual_route_prints_both_commands() -> None:
    assert len(setup.MANUAL_COMMANDS) == 2
    assert setup.INSTALL_URL in setup.MANUAL_COMMANDS[0]
    assert str(setup.LOCAL_BIN) in setup.MANUAL_COMMANDS[1]


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
    healthy = setup.DaemonStatus("/bin/codex", "/bin/codex", "/s.sock", True, True, "loaded")
    assert healthy.summary() == "healthy (loaded)"
    assert healthy.healthy is True
    stale = setup.DaemonStatus("/bin/codex", "/bin/codex", "/s.sock", True, False, "not installed")
    assert stale.summary() == "socket present but not answering"
    missing = setup.DaemonStatus(None, None, "/s.sock", False, False, "not installed")
    assert missing.summary() == "not bootstrapped"
    assert len(missing.lines()) == 6


def test_a_codex_on_path_from_another_build_is_warned_about_never_removed() -> None:
    foreign = setup.DaemonStatus(
        "/home/u/.codex/packages/standalone/current/bin/codex",
        "/opt/homebrew/bin/codex",
        "/s.sock",
        True,
        True,
        "loaded",
    )
    assert foreign.foreign_codex == "/opt/homebrew/bin/codex"
    assert foreign.summary() == "healthy (loaded); warning: foreign codex on PATH"
    warning = " ".join(foreign.warnings())
    assert warning.startswith("warning: PATH resolves codex to /opt/homebrew/bin/codex")
    assert "npm uninstall -g @openai/codex" in warning
    assert "brew uninstall codex" in warning
    assert len(foreign.lines()) == 8


def test_no_warning_when_path_reaches_the_standalone_build(tmp_path: Path) -> None:
    """`~/.local/bin/codex` is a symlink into `current`, so only realpath can decide."""
    standalone = tmp_path / "current/bin/codex"
    standalone.parent.mkdir(parents=True)
    standalone.write_text("#!/bin/sh\n", encoding="utf-8")
    link = tmp_path / "codex"
    link.symlink_to(standalone)
    status = setup.DaemonStatus(str(standalone), str(link), "/s.sock", True, True, "loaded")
    assert status.foreign_codex is None
    assert status.warnings() == []
    assert status.summary() == "healthy (loaded)"
    assert len(status.lines()) == 6


def test_a_path_codex_is_foreign_when_no_standalone_is_installed() -> None:
    status = setup.DaemonStatus(
        None, "/usr/local/bin/codex", "/s.sock", False, False, "not installed"
    )
    assert status.foreign_codex == "/usr/local/bin/codex"
    assert status.summary() == "not bootstrapped; warning: foreign codex on PATH"


async def test_setup_stops_with_instructions_when_it_must_not_touch_a_dotfile(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(setup, "standalone_binary", lambda: None)
    monkeypatch.setattr(setup, "on_path", lambda directory, value=None: False)
    ok, lines = await setup.setup()
    assert ok is False
    assert any(setup.INSTALL_URL in line for line in lines)


async def test_setup_refuses_to_install_when_told_not_to(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(setup, "standalone_binary", lambda: None)
    monkeypatch.setattr(setup, "on_path", lambda directory, value=None: True)
    ok, lines = await setup.setup(install_missing=False)
    assert ok is False
    assert "--no-install" in lines[0]


async def test_setup_bootstraps_and_supervises_the_standalone_binary_not_the_one_on_path(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    standalone = str(tmp_path / "standalone-codex")
    used: list[str] = []

    async def record_bootstrap(binary: str) -> tuple[bool, str]:
        used.append(binary)
        return True, "bootstrapped"

    monkeypatch.setattr(setup, "standalone_binary", lambda: standalone)
    monkeypatch.setattr(setup, "on_path", lambda directory, value=None: True)
    monkeypatch.setattr(setup, "bootstrap", record_bootstrap)
    monkeypatch.setattr(supervision, "install", lambda binary: Path(binary + ".plist"))
    monkeypatch.setattr(supervision, "post_install_hint", lambda: None)

    async def fake_status() -> setup.DaemonStatus:
        return setup.DaemonStatus(standalone, "/opt/homebrew/bin/codex", "/s", True, True, "loaded")

    monkeypatch.setattr(setup, "status", fake_status)
    ok, lines = await setup.setup()
    assert ok is True
    assert used == [standalone]
    assert any(line.startswith(f"Supervision installed at {standalone}") for line in lines)
    assert any(line.startswith("warning: PATH resolves codex to") for line in lines)


def test_the_launchd_agent_runs_daemon_start_under_our_own_label() -> None:
    parsed = plistlib.loads(supervision.render_plist("/opt/codex").encode("utf-8"))
    assert parsed["Label"] == "dev.remote-control.codex-daemon"
    assert parsed["ProgramArguments"] == ["/opt/codex", "app-server", "daemon", "start"]
    assert parsed["RunAtLoad"] is True
    assert parsed["StartInterval"] == supervision.NUDGE_SECONDS
    # Throttling the app-server throttles every Codex round trip through it.
    assert "ProcessType" not in parsed
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


def _executable(path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("#!/bin/sh\n", encoding="utf-8")
    path.chmod(0o755)
    return path


def test_the_standalone_build_outranks_a_codex_on_path(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A device-started session has to be the build the shared daemon runs."""
    standalone = _executable(tmp_path / "codex-home/packages/standalone/current/bin/codex")
    on_path = _executable(tmp_path / "npm/codex")
    override = _executable(tmp_path / "override/codex")
    monkeypatch.setattr(runtime, "STANDALONE", standalone)
    monkeypatch.setenv("PATH", str(on_path.parent))
    monkeypatch.delenv("RC_CODEX_BIN", raising=False)
    assert runtime.resolve_binary() == str(standalone)
    monkeypatch.setenv("RC_CODEX_BIN", str(override))
    assert runtime.resolve_binary() == str(override)


def test_path_is_used_when_no_standalone_build_exists(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Sessions still run without the daemon; only its bootstrap needs the standalone."""
    on_path = _executable(tmp_path / "npm/codex")
    monkeypatch.setattr(runtime, "STANDALONE", tmp_path / "absent/codex")
    monkeypatch.setenv("PATH", str(on_path.parent))
    monkeypatch.delenv("RC_CODEX_BIN", raising=False)
    assert runtime.resolve_binary() == str(on_path)
