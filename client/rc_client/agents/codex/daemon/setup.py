"""`rc-client codex setup` and `rc-client codex status`.

Three steps, each safe to repeat: make sure a standalone Codex exists, ask it to
bootstrap its shared daemon, and register our own supervision because the
bootstrap leaves none. The one thing this never does is enable remote control:
that enrols the machine with OpenAI's relay, which is not what this project is.
"""

from __future__ import annotations

import asyncio
import json
import os
import stat
import tempfile
from dataclasses import dataclass
from pathlib import Path

import httpx

from .... import __version__
from ....logging_setup import logger
from ....service import codex as supervision
from ..runtime import resolve_binary
from .rpc import handshake_ok
from .transport import socket_exists, socket_path

log = logger("rc_client.codex.setup")

STANDALONE = Path.home() / ".codex/packages/standalone/current/bin/codex"
LOCAL_BIN = Path.home() / ".local/bin"
INSTALL_URL = "https://chatgpt.com/codex/install.sh"
DOWNLOAD_TIMEOUT = 60.0
BOOTSTRAP_TIMEOUT = 60.0
MAX_INSTALLER_BYTES = 1024 * 1024

MANUAL_COMMANDS = (
    f"curl -fsSL {INSTALL_URL} | sh",
    'export PATH="$HOME/.local/bin:$PATH"',
)

# Codex's own installer rewrites the shell profile when its target directory is
# not already on PATH, replacing a symlinked dotfile with a regular file. The
# only lever that suppresses that branch is the directory already being there.
INSTALL_PRESENT = "present"
INSTALL_RUN = "run"
INSTALL_MANUAL = "manual"


def path_entries(path_value: str | None = None) -> list[str]:
    raw = os.environ.get("PATH", "") if path_value is None else path_value
    return [os.path.abspath(os.path.expanduser(item)) for item in raw.split(os.pathsep) if item]


def on_path(directory: Path, path_value: str | None = None) -> bool:
    return str(directory.expanduser().resolve()) in path_entries(path_value)


def installer_plan(has_codex: bool, local_bin_on_path: bool) -> str:
    """What to do about a missing standalone Codex, without ever touching a dotfile."""
    if has_codex:
        return INSTALL_PRESENT
    return INSTALL_RUN if local_bin_on_path else INSTALL_MANUAL


def looks_like_shell_script(text: str) -> bool:
    """A downloaded installer is executed, so it has to prove it is a script first."""
    first = text.lstrip().splitlines()[0] if text.strip() else ""
    return first.startswith("#!") and "sh" in first


@dataclass(slots=True)
class DaemonStatus:
    """What `rc-client codex status` reports, and `rc-client status` summarises."""

    binary: str | None
    standalone: bool
    socket: str
    socket_present: bool
    handshake: bool
    supervision: str

    @property
    def healthy(self) -> bool:
        return self.handshake

    def lines(self) -> list[str]:
        return [
            f"codex binary        {self.binary or 'not found'}",
            f"standalone install  {'yes' if self.standalone else 'no'}",
            f"daemon socket       {self.socket}",
            f"socket present      {'yes' if self.socket_present else 'no'}",
            f"handshake           {'ok' if self.handshake else 'failed'}",
            f"supervision         {self.supervision}",
        ]

    def summary(self) -> str:
        if self.handshake:
            return f"healthy ({self.supervision})"
        if self.socket_present:
            return "socket present but not answering"
        return "not bootstrapped"


async def status() -> DaemonStatus:
    present = socket_exists()
    return DaemonStatus(
        binary=resolve_binary(),
        standalone=STANDALONE.is_file(),
        socket=str(socket_path()),
        socket_present=present,
        handshake=await handshake_ok(__version__) if present else False,
        supervision=supervision.status(),
    )


async def install_codex() -> tuple[bool, str]:
    """Run the official installer, having read what was downloaded first."""
    try:
        async with httpx.AsyncClient(timeout=DOWNLOAD_TIMEOUT, follow_redirects=True) as client:
            response = await client.get(INSTALL_URL)
            response.raise_for_status()
            body = response.text[:MAX_INSTALLER_BYTES]
    except httpx.HTTPError as exc:
        return False, f"could not download the Codex installer: {exc}"
    if not looks_like_shell_script(body):
        return False, f"{INSTALL_URL} did not return a shell script; install Codex by hand"
    with tempfile.TemporaryDirectory() as directory:
        script = Path(directory) / "codex-install.sh"
        script.write_text(body, encoding="utf-8")
        script.chmod(script.stat().st_mode | stat.S_IXUSR)
        environment = dict(os.environ, CODEX_NON_INTERACTIVE="1")
        process = await asyncio.create_subprocess_exec(
            "/bin/sh",
            str(script),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
            env=environment,
        )
        out, _ = await process.communicate()
    text = out.decode("utf-8", "replace").strip()
    return process.returncode == 0, text[-2000:]


async def bootstrap(binary: str) -> tuple[bool, str]:
    """`codex app-server daemon bootstrap`, never with `--remote-control`."""
    try:
        process = await asyncio.create_subprocess_exec(
            binary,
            "app-server",
            "daemon",
            "bootstrap",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
        )
        out, _ = await asyncio.wait_for(process.communicate(), timeout=BOOTSTRAP_TIMEOUT)
    except (OSError, TimeoutError) as exc:
        return False, f"could not bootstrap the Codex daemon: {exc}"
    text = out.decode("utf-8", "replace").strip()
    if process.returncode != 0:
        return False, text[-2000:]
    try:
        parsed = json.loads(text.splitlines()[-1]) if text else {}
    except (json.JSONDecodeError, IndexError):
        parsed = {}
    return True, str(parsed.get("status") or "bootstrapped")


async def setup(install_missing: bool = True) -> tuple[bool, list[str]]:
    """Bring the shared daemon up on this machine. Returns (ok, printable lines)."""
    lines: list[str] = []
    binary = resolve_binary()
    plan = installer_plan(bool(binary), on_path(LOCAL_BIN))
    if plan == INSTALL_MANUAL:
        lines.append("Codex is not installed and ~/.local/bin is not on your PATH.")
        lines.append("Run these two commands, then re-run `rc-client codex setup`:")
        lines.extend(f"  {command}" for command in MANUAL_COMMANDS)
        return False, lines
    if plan == INSTALL_RUN:
        if not install_missing:
            lines.append("Codex is not installed; re-run without --no-install to install it.")
            return False, lines
        ok, detail = await install_codex()
        lines.append(f"Codex installer: {'ok' if ok else 'failed'}")
        if not ok:
            lines.append(f"  {detail}")
            return False, lines
        binary = resolve_binary()
    if not binary:
        lines.append("Codex is still not on PATH; open a new shell and re-run.")
        return False, lines
    ok, detail = await bootstrap(binary)
    lines.append(f"Daemon bootstrap: {detail}")
    if not ok:
        return False, lines
    try:
        target = supervision.install(binary)
        lines.append(f"Supervision installed at {target}")
    except Exception as exc:
        lines.append(f"Supervision could not be installed: {exc}")
    hint = supervision.post_install_hint()
    if hint:
        lines.append(hint)
    current = await status()
    lines.extend(current.lines())
    if not current.healthy:
        lines.append("The daemon did not answer a handshake; see the lines above.")
    return current.healthy, lines
