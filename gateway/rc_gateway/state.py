"""The object every route and socket reads from.

FastAPI stores one ``GatewayState`` on ``app.state.gateway``; nothing else is global, so a test can
build a complete gateway on a temporary directory with injected senders.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .apns import ApnsProvider
from .auth_store import AuthSessionStore
from .client_dist import ServedClient, served_client
from .config import Config
from .devices import DeviceStore
from .hub import Hub
from .index import SessionIndex
from .pairing_requests import PairingRequests
from .push import PushService
from .push_store import PushStore
from .ratelimit import RateLimiter
from .rejects import RejectionLog
from .session_registry import SessionRegistry
from .stt import Transcriber

VERSION = "0.1.0"


@dataclass
class GatewayState:
    config: Config
    devices: DeviceStore
    index: SessionIndex
    push_store: PushStore
    auth_store: AuthSessionStore
    sessions: SessionRegistry
    login_limiter: RateLimiter
    enroll_limiter: RateLimiter
    pairing_limiter: RateLimiter
    hub: Hub = field(init=False)
    push: PushService = field(init=False)
    transcriber: Transcriber | None = None
    apns: ApnsProvider | None = None
    #: Refused `/ws/device` upgrades, so an orphaned daemon is visible without flooding the log.
    device_rejects: RejectionLog = field(default_factory=RejectionLog)
    #: Claim tokens waiting to be scanned (A23). Memory only: a restart forgets them.
    pairing_requests: PairingRequests = field(default_factory=PairingRequests)

    def stt_view(self) -> dict[str, Any]:
        return {
            "enabled": self.config.stt.enabled,
            "languages": list(self.config.stt.languages),
        }

    def client_view(self) -> dict[str, Any] | None:
        """The served wheel (A22), or None in a checkout where none has been built."""
        served = self.served_client()
        if served is None:
            return None
        return {"version": served.version, "build": served.build, "url": served.url}

    def served_client(self) -> ServedClient | None:
        return served_client(self.config.client_dist_dir)

    def config_view(self) -> dict[str, Any]:
        view: dict[str, Any] = {
            "public_origin": self.config.public_origin,
            "stt": self.stt_view(),
            "push": {
                "web_enabled": self.push.web_enabled,
                "apns_enabled": self.push.apns_enabled,
            },
            "version": VERSION,
        }
        # A22. The schema makes `client` required, and there is no honest value for a build that
        # does not exist, so a gateway with no wheel omits the field: no wheel, no update to offer.
        client = self.client_view()
        if client is not None:
            view["client"] = client
        return view

    async def device_name(self, device_id: str) -> str:
        record = await self.devices.get(device_id)
        return record.name if record is not None else device_id
