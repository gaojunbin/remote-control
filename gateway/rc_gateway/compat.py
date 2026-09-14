"""The oldest separately installed apps this gateway still works with (A31).

The web app is served by this gateway and the device client updates itself from the apps, so the
iOS app is the one component installed on its own and therefore the only one that can be older
than the gateway it talks to. ``IOS_MINIMUM_APP_VERSION`` is that floor, and it is the one value a
release raises.

**Rule for every release: raise this constant in the same change that stops the gateway working
with older iOS builds, and leave it alone for an additive change an older app can ignore.** An app
below the minimum shows a blocking "Update required" screen and does nothing else, so a minimum
raised without cause locks working apps out of a gateway that would have served them.
"""

from __future__ import annotations

import re
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover - import cycle: config reads the constant below
    from .config import Config

#: The oldest iOS build this gateway still works with, as `major.minor.patch`.
IOS_MINIMUM_APP_VERSION = "0.1.0"

#: The only version form the apps compare, and the one the protocol schema accepts.
RELEASE_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


def is_release_version(value: str) -> bool:
    """Whether ``value`` is a `major.minor.patch` version."""
    return RELEASE_VERSION.match(value) is not None


def apps_view(config: Config) -> dict[str, Any]:
    """The `apps` object carried by `GET /api/health`, `GET /api/config` and `hello`.

    ``update_url`` is present only when the operator configured one: an app with nowhere to send
    the user draws its "Update required" screen without a button rather than a dead link.
    """
    ios: dict[str, Any] = {"minimum_version": config.ios_minimum_version}
    if config.ios_update_url:
        ios["update_url"] = config.ios_update_url
    return {"ios": ios}
