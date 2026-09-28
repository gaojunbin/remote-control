"""The oldest iOS and Mac apps the gateway works with (A31, A45).

The two constants, the four env values and the three bodies that carry them. The two minimums move
separately, so every check covers both apps and neither app's settings may reach the other's entry.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from rc_gateway.app import build_state, create_app
from rc_gateway.compat import (
    IOS_MINIMUM_APP_VERSION,
    MACOS_MINIMUM_APP_VERSION,
    apps_view,
    is_release_version,
)
from rc_gateway.config import ConfigError, load_config

from .conftest import FIXTURE_DIR, drain_until, make_config

UPDATE_URL = "https://testflight.apple.com/join/EXAMPLE"
MAC_UPDATE_URL = "https://example.com/remote-control-mac"
APP_VARIABLES = (
    "IOS_MIN_APP_VERSION",
    "IOS_UPDATE_URL",
    "MACOS_MIN_APP_VERSION",
    "MACOS_UPDATE_URL",
)


def fixture(*parts: str) -> Any:
    return json.loads(FIXTURE_DIR.joinpath(*parts).read_text(encoding="utf-8"))


def env(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, **values: str) -> None:
    monkeypatch.setenv("DATA_DIR", str(tmp_path))
    monkeypatch.setenv("PUBLIC_ORIGIN", "https://rc.example.com")
    monkeypatch.setenv("RC_PASSWORD", "hunter2hunter2")
    monkeypatch.delenv("WEB_PUSH_CONTACT", raising=False)
    for name in APP_VARIABLES:
        monkeypatch.delenv(name, raising=False)
    for name, value in values.items():
        monkeypatch.setenv(name, value)


# --- J1 the constants and the four env values ---------------------------------------------


@pytest.mark.parametrize("constant", [IOS_MINIMUM_APP_VERSION, MACOS_MINIMUM_APP_VERSION])
def test_the_release_constants_are_major_minor_patch_versions(constant: str) -> None:
    """The apps compare them component by component, and the schema accepts nothing else."""
    assert is_release_version(constant)


@pytest.mark.parametrize(
    "value", ["1", "1.2", "1.2.3.4", "v1.2.3", "1.2.3-beta", "latest", "1.2.x"]
)
def test_only_three_numeric_components_count_as_a_version(value: str) -> None:
    assert not is_release_version(value)


def test_the_constants_are_the_defaults_and_the_env_overrides_them(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    env(monkeypatch, tmp_path)
    unset = load_config(load_env_file=False)
    assert unset.ios_minimum_version == IOS_MINIMUM_APP_VERSION
    assert unset.ios_update_url == ""
    assert unset.macos_minimum_version == MACOS_MINIMUM_APP_VERSION
    assert unset.macos_update_url == ""

    env(
        monkeypatch,
        tmp_path,
        IOS_MIN_APP_VERSION="2.3.4",
        IOS_UPDATE_URL=UPDATE_URL,
        MACOS_MIN_APP_VERSION="1.12.0",
        MACOS_UPDATE_URL=MAC_UPDATE_URL,
    )
    overridden = load_config(load_env_file=False)
    assert overridden.ios_minimum_version == "2.3.4"
    assert overridden.ios_update_url == UPDATE_URL
    assert overridden.macos_minimum_version == "1.12.0"
    assert overridden.macos_update_url == MAC_UPDATE_URL


def test_one_apps_variables_leave_the_other_app_alone(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A45: the two minimums move separately, so neither app falls back on the other's values."""
    env(monkeypatch, tmp_path, IOS_MIN_APP_VERSION="2.3.4", IOS_UPDATE_URL=UPDATE_URL)
    ios_only = load_config(load_env_file=False)
    assert ios_only.macos_minimum_version == MACOS_MINIMUM_APP_VERSION
    assert ios_only.macos_update_url == ""

    env(monkeypatch, tmp_path, MACOS_MIN_APP_VERSION="1.12.0", MACOS_UPDATE_URL=MAC_UPDATE_URL)
    mac_only = load_config(load_env_file=False)
    assert mac_only.ios_minimum_version == IOS_MINIMUM_APP_VERSION
    assert mac_only.ios_update_url == ""


@pytest.mark.parametrize("name", ["IOS_MIN_APP_VERSION", "MACOS_MIN_APP_VERSION"])
def test_a_minimum_that_is_not_a_version_stops_the_gateway(
    name: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    env(monkeypatch, tmp_path, **{name: "1.2"})
    with pytest.raises(ConfigError) as refused:
        load_config(load_env_file=False)
    assert str(refused.value).startswith(f"{name} is not a major.minor.patch version")


@pytest.mark.parametrize("name", ["IOS_UPDATE_URL", "MACOS_UPDATE_URL"])
def test_the_update_url_must_be_https(
    name: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A plain-http link would fail the schema and reach the app as a dead button."""
    env(monkeypatch, tmp_path, **{name: "http://testflight.apple.com/join/EXAMPLE"})
    with pytest.raises(ConfigError) as refused:
        load_config(load_env_file=False)
    assert str(refused.value).startswith(f"{name} must be an https:// address")


def test_the_view_omits_an_update_url_that_was_not_configured(tmp_path: Path) -> None:
    assert apps_view(make_config(tmp_path)) == {
        "ios": {"minimum_version": IOS_MINIMUM_APP_VERSION},
        "macos": {"minimum_version": MACOS_MINIMUM_APP_VERSION},
    }
    mac_url = make_config(tmp_path, macos_update_url=MAC_UPDATE_URL)
    assert apps_view(mac_url) == {
        "ios": {"minimum_version": IOS_MINIMUM_APP_VERSION},
        "macos": {"minimum_version": MACOS_MINIMUM_APP_VERSION, "update_url": MAC_UPDATE_URL},
    }
    both_urls = make_config(
        tmp_path,
        ios_minimum_version="2.0.0",
        ios_update_url=UPDATE_URL,
        macos_minimum_version="1.12.0",
        macos_update_url=MAC_UPDATE_URL,
    )
    assert apps_view(both_urls) == {
        "ios": {"minimum_version": "2.0.0", "update_url": UPDATE_URL},
        "macos": {"minimum_version": "1.12.0", "update_url": MAC_UPDATE_URL},
    }


# --- J2 what health, config and hello report ----------------------------------------------


def test_health_config_and_hello_all_carry_both_minimums(tmp_path: Path) -> None:
    """The three places an app can learn its minimum, whichever it sees first (8.16).

    The gateway is configured as the fixture is, so every body matches it value for value.
    """
    expected = fixture("http", "health.response.json")["apps"]
    config = make_config(
        tmp_path,
        ios_minimum_version=expected["ios"]["minimum_version"],
        ios_update_url=expected["ios"]["update_url"],
        macos_minimum_version=expected["macos"]["minimum_version"],
    )

    with TestClient(create_app(build_state(config))) as client:
        assert client.get("/api/health").json()["apps"] == expected
        token = client.post(
            "/api/login",
            json={"username": "admin", "password": config.password},
            headers={"Origin": config.public_origin},
        ).json()["token"]
        auth = {"Authorization": f"Bearer {token}"}
        assert client.get("/api/config", headers=auth).json()["apps"] == expected
        with client.websocket_connect("/ws/app", headers=auth) as app:
            assert drain_until(app, "hello")["apps"] == expected


def test_health_needs_no_credential_to_state_the_minimums(client: TestClient) -> None:
    """An app too old to sign in still learns why before it asks for a password."""
    body = client.get("/api/health").json()
    assert body["apps"] == {
        "ios": {"minimum_version": IOS_MINIMUM_APP_VERSION},
        "macos": {"minimum_version": MACOS_MINIMUM_APP_VERSION},
    }
