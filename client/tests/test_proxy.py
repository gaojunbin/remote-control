"""How a proxy choice turns into httpx and websockets arguments."""

from __future__ import annotations

import pytest

from rc_client.errors import RcError
from rc_client.proxy import httpx_options, normalise_proxy, websocket_proxy


def test_direct_env_and_urls_are_accepted() -> None:
    assert normalise_proxy(None) == ""
    assert normalise_proxy("  ") == ""
    assert normalise_proxy(" env ") == "env"
    assert normalise_proxy("http://proxy.example:3128") == "http://proxy.example:3128"
    assert normalise_proxy("socks5h://127.0.0.1:1080") == "socks5h://127.0.0.1:1080"


@pytest.mark.parametrize("value", ["bogus", "proxy.example:3128", "ftp://proxy.example", "http://"])
def test_anything_else_is_a_clear_error(value: str) -> None:
    with pytest.raises(RcError) as caught:
        normalise_proxy(value)
    assert caught.value.code == "bad_request"
    assert "invalid proxy" in str(caught.value)


def test_direct_dials_ignore_the_environment() -> None:
    assert httpx_options("") == {"trust_env": False, "proxy": None}
    assert websocket_proxy("") is None


def test_env_follows_the_environment() -> None:
    assert httpx_options("env") == {"trust_env": True}
    assert websocket_proxy("env") is True


def test_a_url_names_one_proxy_and_still_ignores_the_environment() -> None:
    url = "http://proxy.example:3128"
    assert httpx_options(url) == {"trust_env": False, "proxy": url}
    assert websocket_proxy(url) == url
