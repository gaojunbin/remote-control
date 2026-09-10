"""The object every route and socket reads from.

FastAPI stores one ``GatewayState`` on ``app.state.gateway``; nothing else is global, so a test can
build a complete gateway on a temporary directory with injected senders.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .apns import ApnsProvider
from .auth_store import AuthSessionStore
from .config import Config
from .devices import DeviceStore
from .hub import Hub
from .index import SessionIndex
from .push import PushService
from .push_store import PushStore
from .ratelimit import RateLimiter
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
    hub: Hub = field(init=False)
    push: PushService = field(init=False)
    transcriber: Transcriber | None = None
    apns: ApnsProvider | None = None

    def stt_view(self) -> dict[str, Any]:
        return {
            "enabled": self.config.stt.enabled,
            "languages": list(self.config.stt.languages),
        }

    def config_view(self) -> dict[str, Any]:
        return {
            "public_origin": self.config.public_origin,
            "stt": self.stt_view(),
            "push": {
                "web_enabled": self.push.web_enabled,
                "apns_enabled": self.push.apns_enabled,
            },
            "version": VERSION,
        }

    async def device_name(self, device_id: str) -> str:
        record = await self.devices.get(device_id)
        return record.name if record is not None else device_id
