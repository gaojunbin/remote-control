"""What the device advertises for Cursor, and where it looks for the CLI."""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest

from rc_client.agents.cursor import catalog as cursor_catalog
from rc_client.agents.cursor import runtime as cursor_runtime
from rc_client.agents.cursor.plugin import detect
from rc_client.agents.registry import DetectContext
from tests.helpers import load_fixture, object_validator

LISTING = """
Available models:
  auto
  gpt-5
  sonnet-4-thinking
  composer-1
"""


def cursor_binary(directory: Path, name: str = "cursor-agent", version: str = "") -> Path:
    """An executable that answers `--version` and refuses `--list-models`."""
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / name
    path.write_text(
        "#!/bin/sh\n"
        'if [ "$1" = "--version" ]; then\n'
        f'  echo "{version or "2026.09.02-c22c1a3"}"\n'
        "  exit 0\n"
        "fi\n"
        'echo "Error: Authentication required." >&2\n'
        "exit 1\n",
        encoding="utf-8",
    )
    path.chmod(path.stat().st_mode | 0o111)
    return path


def listing_binary(directory: Path, listing: str) -> Path:
    """A signed-in CLI: `--list-models` answers and exits zero.

    Only shell builtins, because these tests run with an empty `PATH` so that
    nothing can reach the developer's own `cursor-agent`.
    """
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "cursor-agent"
    echoes = "\n".join(f"echo '{line}'" for line in listing.strip().splitlines())
    path.write_text(
        "#!/bin/sh\n"
        'if [ "$1" = "--version" ]; then echo "2026.09.02-c22c1a3"; exit 0; fi\n'
        f"{echoes}\nexit 0\n",
        encoding="utf-8",
    )
    path.chmod(path.stat().st_mode | 0o111)
    return path


@pytest.fixture(autouse=True)
def cursor_home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[Path]:
    """A scratch `$HOME`, so nothing here reads the developer's own Cursor."""
    home = tmp_path / "home"
    (home / ".cursor").mkdir(parents=True)
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: home))
    monkeypatch.delenv("RC_CURSOR_BIN", raising=False)
    monkeypatch.setenv("PATH", str(tmp_path / "empty"))
    cursor_catalog.clear_cache()
    yield home
    cursor_catalog.clear_cache()


async def test_cursor_is_unavailable_when_nothing_is_installed() -> None:
    info = await detect(DetectContext())
    assert info.available is False
    assert info.path is None
    assert info.version is None
    # An agent that is not installed still says what it would offer.
    assert [choice.id for choice in info.models] == ["auto", "gpt-5", "sonnet-4-thinking"]


async def test_the_installer_s_own_target_is_found_without_a_path(
    cursor_home: Path,
) -> None:
    installed = cursor_binary(cursor_home / ".local/bin")
    info = await detect(DetectContext())
    assert info.path == str(installed)
    assert info.version == "2026.09.02-c22c1a3"
    assert info.available is True


async def test_path_beats_the_installer_s_target(
    cursor_home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    cursor_binary(cursor_home / ".local/bin")
    on_path = cursor_binary(tmp_path / "elsewhere", version="2026.01.01-aaaaaaa")
    monkeypatch.setenv("PATH", str(on_path.parent))
    info = await detect(DetectContext())
    assert info.path == str(on_path)
    assert info.version == "2026.01.01-aaaaaaa"


async def test_an_explicit_override_wins(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    override = cursor_binary(tmp_path / "override")
    monkeypatch.setenv("RC_CURSOR_BIN", str(override))
    assert cursor_runtime.resolve_binary() == str(override)


async def test_a_signed_in_cli_supplies_the_model_list(tmp_path: Path) -> None:
    binary = listing_binary(tmp_path / "signed-in", LISTING)
    models = await cursor_catalog.load(str(binary))
    # `auto` always leads, and a heading is not a model.
    assert [choice.id for choice in models] == [
        "auto",
        "gpt-5",
        "sonnet-4-thinking",
        "composer-1",
    ]
    assert [choice.label for choice in models] == [
        "Auto",
        "GPT-5",
        "Sonnet 4 Thinking",
        "Composer 1",
    ]


async def test_the_model_list_is_asked_for_once_per_binary(tmp_path: Path) -> None:
    """`--list-models` is a network call on a signed-in machine; detection rescans often."""
    directory = tmp_path / "counted"
    directory.mkdir()
    marker = directory / "calls"
    binary = directory / "cursor-agent"
    binary.write_text(
        f"#!/bin/sh\necho x >> \"{marker}\"\ncat <<'MODELS'\n{LISTING.strip()}\nMODELS\n",
        encoding="utf-8",
    )
    binary.chmod(binary.stat().st_mode | 0o111)
    await cursor_catalog.load(str(binary))
    await cursor_catalog.load(str(binary))
    assert marker.read_text(encoding="utf-8").count("x") == 1


def test_an_unusable_listing_falls_back_to_what_the_fixture_names() -> None:
    assert cursor_catalog.parse_models("Error: Authentication required.\n") == []
    assert cursor_catalog.parse_models("") == []


def test_a_version_line_is_taken_whole() -> None:
    """Cursor's version is a calendar build, not three dotted numbers."""
    assert cursor_runtime.parse_version("2026.09.02-c22c1a3\n") == "2026.09.02-c22c1a3"
    assert cursor_runtime.parse_version("\n\n2026.09.02-c22c1a3\n") == "2026.09.02-c22c1a3"
    assert cursor_runtime.parse_version("Error: not signed in\n") is None


async def test_what_cursor_advertises_matches_the_protocol_fixture(cursor_home: Path) -> None:
    """`fixtures/objects/agent.cursor.json` is the contract for this agent."""
    binary = cursor_binary(cursor_home / ".local/bin")
    info = await detect(DetectContext())
    expected = load_fixture("objects/agent.cursor.json")
    payload = info.to_dict()
    validator = object_validator("AgentInfo")
    if validator is not None:
        validator.validate(payload)
    assert payload["version"] == expected["version"]
    assert payload["models"] == expected["models"]
    assert payload["default_model"] == expected["default_model"]
    assert payload["permission_modes"] == expected["permission_modes"]
    assert payload["efforts"] == []
    assert payload["default_effort"] is None
    assert payload["capabilities"] == expected["capabilities"]
    assert payload["attach"] is None
    assert payload["attach_ready"] is False
    assert not payload["shared_interrupt"]
    assert not payload["shared_settings"]
    assert not payload["shared_attachments"]
    assert payload["speeds"] == []
    assert payload["path"] == str(binary)


async def test_the_registry_dispatches_to_this_plugin(
    cursor_home: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Adding `cursor` to `AGENT_IDS` is the whole of the merge; nothing else moves."""
    from rc_client.agents import registry

    monkeypatch.setattr(registry, "AGENT_IDS", ("cursor",))
    assert registry.plugin("cursor").AGENT == "cursor"
    assert [info.agent for info in await registry.detect_all(DetectContext())] == ["cursor"]
