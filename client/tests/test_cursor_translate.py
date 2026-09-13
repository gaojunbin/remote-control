"""Cursor's `stream-json` lines, mapped to protocol events.

The fixtures under `tests/fixtures/cursor/` were written from Cursor's own
documentation and from the module in its shipped bundle that prints these
lines, not recorded from a live turn: the CLI on the development machine is not
signed in. `tests/fixtures/cursor/README.md` says which field came from where.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from rc_client.agents.base import Emit
from rc_client.agents.cursor.translate import SESSION, CursorTranslator

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "cursor"
SESSION_ID = "9f1c0f4e-1d1c-4a1e-9d6f-2f0f1f7c2c31"


def read(name: str) -> list[dict[str, Any]]:
    text = (FIXTURES / name).read_text(encoding="utf-8")
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def run(name: str) -> list[Emit]:
    translator = CursorTranslator(cwd="/tmp/work")
    emits: list[Emit] = []
    for message in read(name):
        emits.extend(translator.line(message))
    return emits


def of(emits: list[Emit], kind: str) -> list[Emit]:
    return [emit for emit in emits if emit.kind == kind]


def last_call(emits: list[Emit], tool: str) -> dict[str, Any]:
    """The final state of one tool call, which is its `completed` line."""
    return [emit.fields for emit in emits if emit.fields.get("tool") == tool][-1]


def test_the_init_line_names_the_chat_and_nothing_else() -> None:
    """Its `model` is a display name, not an id, so it never becomes `meta`."""
    emits = run("turn.jsonl")
    session = of(emits, SESSION)
    assert len(session) == 1
    assert session[0].fields == {"session_id": SESSION_ID, "cwd": "/tmp/work"}
    assert of(emits, "meta") == []


def test_text_streams_as_deltas_and_the_buffered_replays_are_dropped() -> None:
    """Partial output prints every token twice: once as a delta, once flushed."""
    emits = run("turn.jsonl")
    text = of(emits, "assistant_text")
    deltas = [emit.fields["delta"] for emit in text if emit.delta]
    assert deltas == ["Let me ", "look first.", "OK"]
    done = [emit for emit in text if not emit.delta]
    assert [emit.fields["text"] for emit in done] == ["Let me look first.", "OK"]
    assert all(emit.fields["done"] for emit in done)
    # Two separate blocks: the tool call between them ends the first.
    assert done[0].fields["block_id"] != done[1].fields["block_id"]


def test_thinking_is_streamed_and_closed_by_its_own_event() -> None:
    """The published docs say print mode drops reasoning; this build streams it."""
    emits = run("turn.jsonl")
    thinking = of(emits, "thinking")
    assert [emit.fields["delta"] for emit in thinking if emit.delta] == [
        "The user wants ",
        "one word back.",
    ]
    closed = [emit for emit in thinking if not emit.delta]
    assert closed[-1].fields["text"] == "The user wants one word back."
    assert closed[-1].fields["done"] is True


def test_a_shell_call_reports_its_command_its_output_and_its_exit_code() -> None:
    emits = run("turn.jsonl")
    calls = of(emits, "tool_call")
    started, completed = calls[0].fields, calls[-1].fields
    assert started["block_id"] == "call-1"
    assert started["tool"] == "shell"
    assert started["tool_kind"] == "shell"
    assert started["title"] == "ls -la"
    assert started["status"] == "running"
    assert completed["status"] == "succeeded"
    assert completed["output"] == "total 0\n"
    assert completed["summary"] == "exit 0"
    assert completed["duration_ms"] == 200


def test_the_turn_ends_with_the_token_counts_the_result_carries() -> None:
    """The published docs say the stream has no usage; this build puts it on `result`."""
    emits = run("turn.jsonl")
    completed = of(emits, "turn_completed")[-1].fields
    assert completed["stop_reason"] == "completed"
    assert completed["duration_ms"] == 1234
    # `inputTokens` arrives net of the cache, so the total adds the cache back.
    assert completed["usage"] == {
        "input_tokens": 120,
        "output_tokens": 8,
        "total_tokens": 4178,
    }


def test_every_tool_union_key_becomes_a_kind_and_an_unknown_one_is_other() -> None:
    emits = of(run("tools.jsonl"), "tool_call")
    kinds = {emit.fields["tool"]: emit.fields["tool_kind"] for emit in emits}
    assert kinds == {
        "read": "read",
        "edit": "edit",
        "grep": "search",
        "updateTodos": "todo",
        "recordScreen": "other",
    }
    read = last_call(emits, "read")
    assert read["title"] == "src/main.py"
    assert read["output"] == "print('hi')\n"


def test_a_result_that_is_not_success_fails_the_call() -> None:
    emits = of(run("tools.jsonl"), "tool_call")
    grep = last_call(emits, "grep")
    assert grep["status"] == "failed"
    assert grep["output"] == "no matches"
    edit = last_call(emits, "edit")
    # Nothing in an edit result reads as text, so the object itself is shown.
    assert json.loads(edit["output"])["linesCreated"] == 3


def test_the_todo_tool_also_publishes_the_plan() -> None:
    todos = of(run("tools.jsonl"), "todos")
    assert len(todos) == 1
    assert todos[0].fields["items"] == [
        {"id": "t1", "text": "Read the file", "status": "completed"},
        {"id": "t2", "text": "Edit the file", "status": "in_progress"},
        {"id": "t3", "text": "Run the tests", "status": "pending"},
    ]


def test_a_turn_whose_text_never_streamed_still_publishes_its_answer() -> None:
    """A result is the authoritative full text when no delta carried it."""
    translator = CursorTranslator()
    emits = translator.line(
        {"type": "result", "subtype": "success", "is_error": False, "result": "done"}
    )
    text = of(emits, "assistant_text")
    assert [emit.fields["text"] for emit in text] == ["done"]
    assert text[0].fields["done"] is True


def test_a_failed_result_ends_the_turn_with_an_error() -> None:
    translator = CursorTranslator()
    emits = translator.line({"type": "result", "subtype": "error", "is_error": True})
    assert of(emits, "turn_completed")[0].fields["stop_reason"] == "error"


def test_a_call_still_running_when_the_process_dies_is_closed() -> None:
    translator = CursorTranslator()
    translator.line(read("turn.jsonl")[8])
    closed = translator.close_tools("cancelled")
    assert [emit.fields["status"] for emit in closed] == ["cancelled"]
    assert "ended_at" in closed[0].fields


def test_unknown_lines_are_ignored() -> None:
    translator = CursorTranslator()
    assert translator.line({"type": "interaction_query", "subtype": "request"}) == []
    assert translator.line({"type": "system", "subtype": "task_notification"}) == []
    assert translator.line({"type": "user", "message": {"content": []}}) == []
    assert translator.line({}) == []
