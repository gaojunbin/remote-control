"""How this device reaches its gateway: directly, or through a proxy it was told about.

The gateway link, enrollment, pairing and self-update all dial the same origin, and they dial
it directly unless the person says otherwise. Adopting the system proxy silently would route a
private tunnel through whatever `HTTPS_PROXY` happens to hold, and a SOCKS entry there fails with
an ImportError unless the optional socks packages are installed. A host that can only reach the
internet through a proxy, a cluster login node behind an HTTP proxy being the usual case, opts in
once at enrollment (`rc-client enroll --proxy env`, or a URL) and the choice is kept in
`config.toml` as `proxy`.
"""

from __future__ import annotations

from typing import Any, Literal
from urllib.parse import urlsplit

from .errors import RcError

DIRECT = ""
ENVIRONMENT = "env"
SCHEMES = {"http", "https", "socks4", "socks5", "socks5h"}


def normalise_proxy(value: str | None) -> str:
    """`""` (direct), `"env"` (follow the environment) or a proxy URL; anything else is an error."""
    text = (value or "").strip()
    if text in {DIRECT, ENVIRONMENT}:
        return text
    split = urlsplit(text)
    if split.scheme not in SCHEMES or not split.netloc:
        raise RcError(
            "bad_request",
            f"invalid proxy {text!r}; use `env` or a URL such as http://proxy.example:3128",
        )
    return text


def httpx_options(proxy: str) -> dict[str, Any]:
    """Keyword arguments for `httpx.AsyncClient` that reach the gateway the way `proxy` says."""
    if proxy == ENVIRONMENT:
        return {"trust_env": True}
    return {"trust_env": False, "proxy": proxy or None}


def websocket_proxy(proxy: str) -> str | Literal[True] | None:
    """The `proxy` argument for `websockets.connect`: None dials directly, True follows the env."""
    if proxy == ENVIRONMENT:
        return True
    return proxy or None
