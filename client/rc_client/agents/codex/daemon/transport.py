"""The daemon control socket: where it lives and how to open it.

Authentication is filesystem permissions only — the socket is owner-only — so
the device has to run as the same user as the terminal that starts `codex`.
"""

from __future__ import annotations

import os
from pathlib import Path

from websockets.asyncio.client import ClientConnection, unix_connect

from ..runtime import CODEX_HOME

DAEMON_SOCKET = "app-server-control/app-server-control.sock"
# The daemon answers a handshake in milliseconds when it is healthy; a longer
# wait would only stall startup behind a socket left by a dead process.
CONNECT_TIMEOUT = 5.0
# Codex streams whole items, and a long command's aggregated output arrives in
# one frame, so the default 1 MiB ceiling is too small.
MAX_FRAME_BYTES = 32 * 1024 * 1024


def socket_path() -> Path:
    """The control socket, honouring `CODEX_HOME` like the CLI does."""
    override = os.environ.get("RC_CODEX_DAEMON_SOCKET", "").strip()
    if override:
        return Path(override).expanduser()
    return CODEX_HOME / DAEMON_SOCKET


def socket_exists(path: Path | None = None) -> bool:
    """Whether a socket file is there at all. Not proof that it answers."""
    target = path or socket_path()
    try:
        return target.is_socket()
    except OSError:
        return False


async def open_connection(path: Path | None = None) -> ClientConnection:
    """Complete the WebSocket upgrade on the control socket.

    Raises whatever the connection attempt raised: a missing socket, a refused
    connection and a failed upgrade are all "the daemon is not usable", and the
    caller decides between falling back and retrying.
    """
    target = path or socket_path()
    return await unix_connect(
        path=str(target),
        uri="ws://localhost/",
        open_timeout=CONNECT_TIMEOUT,
        max_size=MAX_FRAME_BYTES,
        ping_interval=None,
        # The daemon closes the connection without a response when the client
        # offers `permessage-deflate`, so the handshake must not advertise it.
        compression=None,
    )
