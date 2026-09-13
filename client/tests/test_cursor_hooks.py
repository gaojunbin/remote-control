"""`rc-client cursor setup`: merging one entry into a file another product owns.

The real `~/.cursor/hooks.json` on the development machine is registered to a
third-party app for eleven events, so every test here starts from a file that
already has somebody else's entries in it and checks they come out unchanged.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from pathlib import Path

import pytest

from rc_client.agents.cursor import runtime as cursor_runtime
from rc_client.agents.cursor import setup

OTHER = '"/opt/homebrew/bin/node" "/Applications/Another App.app/hooks/cursor-hook.js"'
EXISTING = {
    "hooks": {
        "sessionStart": [{"command": OTHER}],
        "preToolUse": [{"command": OTHER}],
        "postToolUse": [{"command": OTHER}],
    },
    "version": 1,
}


@pytest.fixture(autouse=True)
def cursor_home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[Path]:
    """A scratch `~/.cursor`, because the real one belongs to somebody."""
    home = tmp_path / "home"
    (home / ".cursor").mkdir(parents=True)
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: home))
    yield home / ".cursor"


def write(directory: Path, config: dict[str, object]) -> Path:
    path = directory / "hooks.json"
    path.write_text(json.dumps(config, indent=2), encoding="utf-8")
    return path


def read(path: Path) -> dict[str, object]:
    loaded: dict[str, object] = json.loads(path.read_text(encoding="utf-8"))
    return loaded


def commands(config: dict[str, object], event: str = "preToolUse") -> list[str]:
    hooks = config["hooks"]
    assert isinstance(hooks, dict)
    return [entry["command"] for entry in hooks.get(event, [])]


def test_our_entry_is_appended_and_nobody_else_s_is_moved(cursor_home: Path) -> None:
    path = write(cursor_home, EXISTING)
    setup.install()
    config = read(path)
    listed = commands(config)
    assert listed[0] == OTHER
    assert setup.is_ours(listed[1])
    assert len(listed) == 2
    # Every other event is left exactly as it was.
    assert commands(config, "sessionStart") == [OTHER]
    assert commands(config, "postToolUse") == [OTHER]
    assert config["version"] == 1


def test_running_setup_twice_leaves_one_entry(cursor_home: Path) -> None:
    path = write(cursor_home, EXISTING)
    setup.install()
    lines = setup.install()
    assert any("already installed" in line for line in lines)
    assert len(commands(read(path))) == 2


def test_remove_takes_only_ours_out(cursor_home: Path) -> None:
    path = write(cursor_home, EXISTING)
    setup.install()
    setup.remove()
    config = read(path)
    assert commands(config) == [OTHER]
    assert commands(config, "sessionStart") == [OTHER]


def test_remove_drops_the_event_when_we_were_the_only_one(cursor_home: Path) -> None:
    path = write(cursor_home, {"hooks": {"sessionStart": [{"command": OTHER}]}, "version": 1})
    setup.install()
    setup.remove()
    hooks = read(path)["hooks"]
    assert isinstance(hooks, dict)
    assert "preToolUse" not in hooks
    assert "sessionStart" in hooks


def test_setup_creates_the_file_when_cursor_has_never_written_one(cursor_home: Path) -> None:
    path = cursor_home / "hooks.json"
    assert not path.exists()
    setup.install()
    config = read(path)
    assert len(commands(config)) == 1
    assert config["version"] == 1


def test_a_file_that_is_not_json_is_not_destroyed(cursor_home: Path) -> None:
    """An unreadable configuration is somebody's, so setup reports rather than guesses."""
    path = cursor_home / "hooks.json"
    path.write_text("{ this is not json", encoding="utf-8")
    config, changed = setup.merge(setup.read_config(path), "cmd")
    assert changed is True
    # Nothing was read, so nothing of theirs can be preserved; the caller is
    # told, and `read_config` never raises into the middle of a write.
    assert setup.read_config(path) == {}
    assert config["hooks"] == {"preToolUse": [{"command": "cmd", "timeout": setup.HOOK_TIMEOUT}]}


def test_a_symlinked_configuration_is_edited_through_the_link(
    cursor_home: Path, tmp_path: Path
) -> None:
    """These files are often links into a synchronised folder; the link must survive."""
    real = tmp_path / "synced" / "hooks.json"
    real.parent.mkdir(parents=True)
    real.write_text(json.dumps(EXISTING, indent=2), encoding="utf-8")
    link = cursor_home / "hooks.json"
    link.symlink_to(real)
    setup.install()
    assert link.is_symlink()
    assert link.readlink() == real
    assert len(commands(read(real))) == 2


def test_only_our_own_command_is_recognised() -> None:
    assert setup.is_ours(setup.hook_line()) is True
    assert setup.is_ours(OTHER) is False
    assert setup.is_ours("") is False
    assert setup.is_ours('unbalanced "quote') is False


def test_status_reports_what_is_registered(cursor_home: Path) -> None:
    write(cursor_home, EXISTING)
    setup.install()
    lines = setup.status()
    assert any("2 registered, 1 ours" in line for line in lines)
    assert any(str(cursor_runtime.socket_path()) in line for line in lines)
