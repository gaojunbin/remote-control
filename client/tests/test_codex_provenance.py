"""Amendment A18: which Codex threads on this machine are the device's to show.

The originator/source pairs below were read off this Mac on 2026-09-12, from
`thread/list` on Codex 0.154 and from the `session_meta` of the rollouts on
disk, so the predicate is tested against what Codex actually writes.
"""

from __future__ import annotations

from rc_client.agents.codex.provenance import (
    DAEMON_CLIENT_NAME,
    EMBEDDED_CLIENT_NAME,
    owned_here,
)


def test_a_thread_this_device_opened_is_ours_under_either_name() -> None:
    """The daemon connection names one; the app-server we spawn names the other."""
    assert owned_here(DAEMON_CLIENT_NAME, "vscode") is True
    assert owned_here(EMBEDDED_CLIENT_NAME, "vscode") is True


def test_a_terminal_running_its_own_codex_is_ours() -> None:
    assert owned_here("codex-tui", "cli") is True
    assert owned_here("codex_cli_rs", "cli") is True
    assert owned_here("codex_exec", "exec") is True


def test_another_application_on_this_machine_is_not_ours() -> None:
    """The ChatGPT desktop app: its chats and its scheduled automations."""
    assert owned_here("Codex Desktop", "vscode") is False
    assert owned_here("codex_work_desktop", "vscode") is False


def test_a_subagent_is_not_a_session_whoever_spawned_it() -> None:
    """A subagent carries its parent's originator, so `source` has to decide alone."""
    spawn = {"subagent": {"thread_spawn": {"parent_thread_id": "01a08229", "depth": 1}}}
    assert owned_here("codex-tui", spawn) is False
    assert owned_here("Codex Desktop", {"subAgent": {"other": "guardian"}}) is False
    # A subagent of one of this device's own threads is still not a session.
    assert owned_here(DAEMON_CLIENT_NAME, spawn) is False
    assert owned_here(EMBEDDED_CLIENT_NAME, spawn) is False


def test_provenance_that_cannot_be_read_is_foreign() -> None:
    assert owned_here(None, None) is False
    assert owned_here("", "") is False
    assert owned_here(7, ["cli"]) is False
    # A name of ours says nothing on its own: where the client sits has to be
    # readable too, or the thread could be anything.
    assert owned_here(DAEMON_CLIENT_NAME, None) is False
    assert owned_here(DAEMON_CLIENT_NAME, "") is False
    # A name we do not connect under, wherever it claims to sit.
    assert owned_here("spike-A", "vscode") is False
    assert owned_here("Codex Desktop", "appServer") is False
