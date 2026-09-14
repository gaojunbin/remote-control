"""The oldest iOS app the gateway works with: the constant, the two env values, the bodies (A31)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from rc_gateway.app import build_state, create_app
from rc_gateway.compat import IOS_MINIMUM_APP_VERSION, apps_view, is_release_version
from rc_gateway.config import ConfigError, load_config

from .conftest import FIXTURE_DIR, drain_until, make_config

UPDATE_URL = "https://testflight.apple.com/join/EXAMPLE"


def fixture(*parts: str) -> Any:
    return json.loads(FIXTURE_DIR.joinpath(*parts).read_text(encoding="utf-8"))


def env(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, **values: str) -> None:
    monkeypatch.setenv("DATA_DIR", str(tmp_path))
    monkeypatch.setenv("PUBLIC_ORIGIN", "https://rc.example.com")
    monkeypatch.setenv("RC_PASSWORD", "hunter2hunter2")
    monkeypatch.delenv("WEB_PUSH_CONTACT", raising=False)
    for name in ("IOS_MIN_APP_VERSION", "IOS_UPDATE_URL"):
        monkeypatch.delenv(name, raising=False)
    for name, value in values.items():
        monkeypatch.setenv(name, value)


# --- J1 the constant and the two env values -----------------------------------------------


def test_the_release_constant_is_a_major_minor_patch_version() -> None:
    """The apps compare it component by component, and the schema accepts nothing else."""
    assert is_release_version(IOS_MINIMUM_APP_VERSION)


@pytest.mark.parametrize(
    "value", ["1", "1.2", "1.2.3.4", "v1.2.3", "1.2.3-beta", "latest", "1.2.x"]
)
def test_only_three_numeric_components_count_as_a_version(value: str) -> None:
    assert not is_release_version(value)


def test_the_constant_is_the_default_and_the_env_overrides_it(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    env(monkeypatch, tmp_path)
    unset = load_config(load_env_file=False)
    assert unset.ios_minimum_version == IOS_MINIMUM_APP_VERSION
    assert unset.ios_update_url == ""

    env(monkeypatch, tmp_path, IOS_MIN_APP_VERSION="2.3.4", IOS_UPDATE_URL=UPDATE_URL)
    overridden = load_config(load_env_file=False)
    assert overridden.ios_minimum_version == "2.3.4"
    assert overridden.ios_update_url == UPDATE_URL


def test_a_minimum_that_is_not_a_version_stops_the_gateway(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    env(monkeypatch, tmp_path, IOS_MIN_APP_VERSION="1.2")
    with pytest.raises(ConfigError) as refused:
        load_config(load_env_file=False)
    assert "IOS_MIN_APP_VERSION" in str(refused.value)
    assert "major.minor.patch" in str(refused.value)


def test_the_update_url_must_be_https(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """A plain-http link would fail the schema and reach the app as a dead button."""
    env(monkeypatch, tmp_path, IOS_UPDATE_URL="http://testflight.apple.com/join/EXAMPLE")
    with pytest.raises(ConfigError) as refused:
        load_config(load_env_file=False)
    assert "IOS_UPDATE_URL" in str(refused.value)


def test_the_view_omits_an_update_url_that_was_not_configured(tmp_path: Path) -> None:
    assert apps_view(make_config(tmp_path)) == {"ios": {"minimum_version": "0.1.0"}}
    with_url = make_config(tmp_path, ios_minimum_version="2.0.0", ios_update_url=UPDATE_URL)
    assert apps_view(with_url) == {"ios": {"minimum_version": "2.0.0", "update_url": UPDATE_URL}}


# --- J2 what health, config and hello report ----------------------------------------------


def test_health_config_and_hello_all_carry_the_minimum(tmp_path: Path) -> None:
    """The three places an app can learn the minimum, whichever it sees first (8.16)."""
    expected = fixture("http", "health.response.json")["apps"]
    config = make_config(tmp_path, ios_minimum_version="0.1.0", ios_update_url=UPDATE_URL)
    apps = {"ios": {"minimum_version": "0.1.0", "update_url": UPDATE_URL}}
    assert set(apps["ios"]) == set(expected["ios"])

    with TestClient(create_app(build_state(config))) as client:
        assert client.get("/api/health").json()["apps"] == apps
        token = client.post(
            "/api/login",
            json={"username": "admin", "password": config.password},
            headers={"Origin": config.public_origin},
        ).json()["token"]
        auth = {"Authorization": f"Bearer {token}"}
        assert client.get("/api/config", headers=auth).json()["apps"] == apps
        with client.websocket_connect("/ws/app", headers=auth) as app:
            assert drain_until(app, "hello")["apps"] == apps


def test_health_needs_no_credential_to_state_the_minimum(client: TestClient) -> None:
    """An app too old to sign in still learns why before it asks for a password."""
    body = client.get("/api/health").json()
    assert body["apps"] == {"ios": {"minimum_version": IOS_MINIMUM_APP_VERSION}}
