"""Terminal-session mirroring: transcript and rollout tailing plus holder logic."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from rc_client.agents.claude.holders import (
    Holder,
    HolderScan,
    _looks_like_claude,
    _session_from_argv,
)
from rc_client.agents.claude.transcripts import TranscriptTailer
from rc_client.agents.codex.rollouts import RolloutTailer
from rc_client.procscan import Proc


def write_rows(path: Path, rows: list[dict[str, Any]]) -> None:
    with path.open("a", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row) + "\n")


def user_row(text: str, uuid: str = "u1") -> dict[str, Any]:
    return {"type": "user", "uuid": uuid, "message": {"role": "user", "content": text}}


def assistant_row(content: list[dict[str, Any]], message_id: str = "msg_1") -> dict[str, Any]:
    return {"type": "assistant", "uuid": "a1", "message": {"id": message_id, "content": content}}


def test_tailer_reads_only_appended_bytes(tmp_path: Path) -> None:
    path = tmp_path / "t.jsonl"
    write_rows(path, [user_row("first")])
    tailer = TranscriptTailer(path=str(path), cwd="/repo")
    assert len(tailer.read_new()) == 1
    assert tailer.read_new() == []
    write_rows(path, [user_row("second", uuid="u2")])
    rows = tailer.read_new()
    assert len(rows) == 1
    assert rows[0]["uuid"] == "u2"


def test_tailer_ignores_a_partial_trailing_line(tmp_path: Path) -> None:
    path = tmp_path / "t.jsonl"
    write_rows(path, [user_row("first")])
    with path.open("a", encoding="utf-8") as handle:
        handle.write('{"type": "user", "uuid"')
    tailer = TranscriptTailer(path=str(path), cwd="/repo")
    assert len(tailer.read_new()) == 1
    assert tailer.read_new() == []


def test_tailer_restarts_when_the_file_shrinks(tmp_path: Path) -> None:
    path = tmp_path / "t.jsonl"
    write_rows(path, [user_row("first"), user_row("second", uuid="u2")])
    tailer = TranscriptTailer(path=str(path), cwd="/repo")
    tailer.read_new()
    path.write_text("")
    assert tailer.read_new() == []
    write_rows(path, [user_row("fresh", uuid="u9")])
    assert [row["uuid"] for row in tailer.read_new()] == ["u9"]


def test_tailer_restarts_when_the_file_is_replaced(tmp_path: Path) -> None:
    path = tmp_path / "t.jsonl"
    write_rows(path, [user_row("first")])
    tailer = TranscriptTailer(path=str(path), cwd="/repo")
    tailer.read_new()
    replacement = tmp_path / "new.jsonl"
    write_rows(replacement, [user_row("rotated", uuid="u9")])
    replacement.replace(path)
    assert [row["uuid"] for row in tailer.read_new()] == ["u9"]


def test_transcript_user_rows_become_terminal_user_messages() -> None:
    tailer = TranscriptTailer(path="/dev/null", cwd="/repo")
    emits = tailer.translate(user_row("please fix the test"))
    assert emits[0].kind == "user_message"
    assert emits[0].fields["source"] == "terminal"
    assert emits[0].fields["block_id"] == "u1"
    assert tailer.awaiting_reply is True


def test_transcript_meta_and_placeholder_rows_are_skipped() -> None:
    tailer = TranscriptTailer(path="/dev/null", cwd="/repo")
    meta = dict(user_row("<command-name>/clear</command-name>"), isMeta=True)
    assert tailer.translate(meta) == []
    assert tailer.translate(user_row("<local-command-stdout>done")) == []
    assert tailer.translate(user_row("No response requested.")) == []
    assert tailer.awaiting_reply is False


def test_transcript_assistant_text_clears_the_running_flag() -> None:
    tailer = TranscriptTailer(path="/dev/null", cwd="/repo")
    tailer.translate(user_row("go"))
    emits = tailer.translate(assistant_row([{"type": "text", "text": "all done"}]))
    assert emits[0].kind == "assistant_text"
    assert emits[0].fields == {"block_id": "msg_1:0", "text": "all done", "done": True}
    assert tailer.awaiting_reply is False


def test_transcript_tool_use_keeps_running_until_the_final_text() -> None:
    tailer = TranscriptTailer(path="/dev/null", cwd="/repo")
    tailer.translate(user_row("go"))
    call = tailer.translate(
        assistant_row(
            [{"type": "tool_use", "id": "toolu_1", "name": "Bash", "input": {"command": "ls"}}]
        )
    )
    assert call[0].fields["tool_kind"] == "shell"
    assert tailer.awaiting_reply is True
    result = tailer.translate(
        {
            "type": "user",
            "uuid": "u2",
            "message": {
                "role": "user",
                "content": [{"type": "tool_result", "tool_use_id": "toolu_1", "content": "a\nb"}],
            },
            "toolUseResult": {"stdout": "a\nb"},
        }
    )
    assert result[0].fields["status"] == "succeeded"
    assert result[0].fields["output"] == "a\nb"


def test_rollout_tailer_tracks_turn_boundaries(tmp_path: Path) -> None:
    path = tmp_path / "rollout.jsonl"
    tailer = RolloutTailer(path=str(path), cwd="/repo")
    assert tailer.translate({"type": "event_msg", "payload": {"type": "task_started"}}) == []
    running_after_start = tailer.running
    emits = tailer.translate(
        {
            "type": "event_msg",
            "payload": {
                "type": "item_completed",
                "item": {
                    "type": "AgentMessage",
                    "id": "m1",
                    "content": [{"type": "Text", "text": "ok"}],
                },
            },
        }
    )
    assert emits[0].kind == "assistant_text"
    tailer.translate({"type": "event_msg", "payload": {"type": "task_complete"}})
    running_after_complete = tailer.running
    assert (running_after_start, running_after_complete) == (True, False)
    assert tailer.translate({"type": "response_item", "payload": {"type": "message"}}) == []


def test_claude_process_detection_and_session_extraction() -> None:
    live = Proc(pid=10, ppid=1, start="s", command="/Users/me/.local/bin/claude --resume abc-123")
    daemon = Proc(pid=11, ppid=1, start="s", command="/Users/me/.local/bin/claude daemon")
    other = Proc(pid=12, ppid=1, start="s", command="/usr/bin/python -m http.server")
    assert _looks_like_claude(live)
    assert not _looks_like_claude(daemon)
    assert not _looks_like_claude(other)
    assert _session_from_argv(live.argv) == "abc-123"
    assert _session_from_argv(["claude", "--session-id=xyz"]) == "xyz"
    assert _session_from_argv(["claude"]) is None
    assert _session_from_argv(["claude", "--resume"]) is None


def test_holder_scan_matches_by_session_id_then_by_unique_cwd(tmp_path: Path) -> None:
    named = Holder(pid=1, identity=(1, "a"), session_id="sess", cwd=None)
    anonymous = Holder(pid=2, identity=(2, "b"), session_id=None, cwd=str(tmp_path))
    scan = HolderScan(holders=[named, anonymous], complete=True)
    assert scan.for_session("sess", None) is named
    assert scan.for_session("other", str(tmp_path)) is anonymous

    ambiguous = HolderScan(
        holders=[anonymous, Holder(pid=3, identity=(3, "c"), session_id=None, cwd=str(tmp_path))],
        complete=True,
    )
    assert ambiguous.for_session("other", str(tmp_path)) is None
