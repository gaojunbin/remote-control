"""Where the attachment's files live: the socket, the shim and the MCP config.

A Unix socket path is limited to 104 bytes on macOS, and `RC_CLIENT_HOME` can be
pointed anywhere, so a home that would overflow that falls back to a short
per-user directory under the system temporary directory.
"""

from __future__ import annotations

import hashlib
import os
import sys
import tempfile
from pathlib import Path

from ..config import client_home, state_dir

SOCKET_NAME = "channel.sock"
MCP_CONFIG_NAME = "claude-mcp.json"
SHIM_NAME = "claude"
CHANNEL_SERVER_NAME = "rc"

# Leave room under the 104-byte sun_path limit for the name itself.
MAX_SOCKET_PATH_BYTES = 100


def bin_dir() -> Path:
    return client_home() / "bin"


def shim_path() -> Path:
    return bin_dir() / SHIM_NAME


def mcp_config_path() -> Path:
    return state_dir() / MCP_CONFIG_NAME


def socket_path() -> Path:
    """The daemon's channel socket, shortened when the home directory is deep."""
    preferred = state_dir() / SOCKET_NAME
    if len(str(preferred).encode("utf-8")) <= MAX_SOCKET_PATH_BYTES:
        return preferred
    digest = hashlib.sha256(str(client_home()).encode("utf-8")).hexdigest()[:10]
    return Path(tempfile.gettempdir()) / f"rc-{os.getuid()}-{digest}" / SOCKET_NAME


def channel_command() -> list[str]:
    """How to start `rc-client channel` from wherever this code is installed."""
    script = Path(sys.executable).with_name("rc-client")
    if script.is_file() and os.access(script, os.X_OK):
        return [str(script), "channel"]
    return [sys.executable, "-m", "rc_client.cli", "channel"]


def path_with_shim(base: str | None = None) -> str:
    """`base` with the shim directory in front, added at most once.

    The daemon reports `attach_ready` by resolving `claude` on its own PATH, so
    the service environment has to see the shim the installer wrote.
    """
    directory = str(bin_dir())
    entries = [item for item in (base or "").split(os.pathsep) if item and item != directory]
    return os.pathsep.join([directory, *entries])
