"""Entry point for the ``rc-gateway`` console script."""

from __future__ import annotations

import sys

import uvicorn

from .config import ConfigError, load_config
from .frames import WS_MAX_MESSAGE_BYTES
from .logging import configure_logging, uvicorn_log_config


def main() -> int:
    try:
        config = load_config()
    except ConfigError as exc:
        print(f"rc-gateway: {exc}", file=sys.stderr)
        return 2
    configure_logging(config.log_level)
    uvicorn.run(
        "rc_gateway.app:create_configured_app",
        factory=True,
        host=config.host,
        port=config.port,
        log_config=uvicorn_log_config(config.log_level),
        access_log=False,
        ws_max_size=WS_MAX_MESSAGE_BYTES,
        proxy_headers=False,
        # The gateway advertises no version banner; SecurityHeaders sets the rest.
        server_header=False,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
