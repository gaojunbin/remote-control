"""`rc-client self-update`: install the wheel the gateway serves and restart.

An app asks for this with `device.update` (A22). The daemon answers the request
and then spawns this command in its own session, so the update outlives the
service restart it performs at the end. Nothing is installed unless the wheel's
SHA-256 is exactly the build that was asked for.
"""

from __future__ import annotations

import asyncio
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import httpx

from .build import as_digest, digest_of, write_build
from .channel import shellrc
from .config import ensure_dirs, load_config, log_dir, state_dir
from .errors import RcError

WHEEL_PATH = "/dist/rc_client-latest.whl"
WHEEL_NAME = re.compile(r"[A-Za-z0-9._+-]{1,128}\.whl")
FILENAME_FIELD = re.compile(r'filename="?([^";]+)"?')
DOWNLOAD_TIMEOUT = 300.0
MAX_WHEEL_BYTES = 200 * 1024 * 1024
LOG_NAME = "update.log"
MAX_LOG_BYTES = 256 * 1024
MAX_MESSAGE_CHARS = 200

# `self-update` refuses a wheel that is not the requested build with this code,
# so a caller can tell "the gateway served something else" from a real failure.
WRONG_BUILD = "the wheel the gateway served is not the requested build; nothing was installed"


def update_log_path() -> Path:
    return log_dir() / LOG_NAME


def rc_client_executable() -> str:
    """The `rc-client` that belongs to this interpreter's environment."""
    candidate = Path(sys.executable).parent / "rc-client"
    if candidate.is_file():
        return str(candidate)
    argv0 = Path(sys.argv[0]).resolve()
    if argv0.name == "rc-client" and argv0.is_file():
        return str(argv0)
    raise RcError("not_found", "cannot locate the rc-client executable to restart")


def find_uv() -> str:
    """The same `uv` the installer used: on PATH, then the place it installs one."""
    found = shutil.which("uv")
    if found:
        return found
    fallback = Path.home() / ".local" / "bin" / "uv"
    if fallback.is_file() and os.access(fallback, os.X_OK):
        return str(fallback)
    raise RcError("not_found", "uv is not installed on this host; re-run the installer")


def _wheel_name(disposition: str | None) -> str:
    """The real wheel filename the gateway names, which carries its tags."""
    match = FILENAME_FIELD.search(disposition or "")
    name = Path(match.group(1)).name if match else ""
    if not WHEEL_NAME.fullmatch(name):
        raise RcError("internal", "the gateway did not name the wheel it served; upgrade it")
    return name


async def _download(origin: str, directory: Path) -> Path:
    """Fetch the served wheel under the name the gateway gives it."""
    url = f"{origin}{WHEEL_PATH}"
    print(f"downloading {url}", flush=True)
    async with (
        httpx.AsyncClient(timeout=DOWNLOAD_TIMEOUT, trust_env=False) as client,
        client.stream("GET", url) as response,
    ):
        if response.status_code >= 400:
            raise RcError("internal", f"the gateway answered HTTP {response.status_code}")
        target = directory / _wheel_name(response.headers.get("content-disposition"))
        written = 0
        with target.open("wb") as handle:
            async for chunk in response.aiter_bytes():
                written += len(chunk)
                if written > MAX_WHEEL_BYTES:
                    raise RcError("internal", "the served wheel is implausibly large")
                handle.write(chunk)
    return target


async def _step(description: str, *command: str) -> None:
    print(f"$ {' '.join(command)}", flush=True)
    process = await asyncio.create_subprocess_exec(*command)
    code = await process.wait()
    if code != 0:
        raise RcError("internal", f"{description} exited with code {code}")


def _shim_arguments() -> list[str]:
    """Leave the shell startup file exactly as the installer left it."""
    return [] if shellrc.installed(shellrc.rc_file()) else ["--no-shell-rc"]


async def self_update(build: str) -> bool:
    """Install the requested build and restart the service. False when refused."""
    requested = as_digest(build)
    if requested is None:
        raise RcError("bad_request", "--build must be a SHA-256 hex digest")
    config = load_config()
    ensure_dirs()
    shim_arguments = _shim_arguments()
    workdir = Path(tempfile.mkdtemp(dir=state_dir(), prefix="update-"))
    try:
        wheel = await _download(config.gateway_origin, workdir)
        served = digest_of(wheel)
        if served != requested:
            print(WRONG_BUILD, file=sys.stderr, flush=True)
            return False
        await _step(
            "the package install",
            find_uv(),
            "pip",
            "install",
            "--python",
            sys.executable,
            "--upgrade",
            str(wheel),
        )
        write_build(served)
        executable = rc_client_executable()
        await _step("the service install", executable, "service", "install")
        await _step("the shim install", executable, "shim", "install", *shim_arguments)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
    print(f"updated to {served}", flush=True)
    return True


def _open_log() -> int:
    """A file descriptor for the update log, restarted when it has grown large."""
    ensure_dirs()
    path = update_log_path()
    mode = "w" if path.exists() and path.stat().st_size > MAX_LOG_BYTES else "a"
    handle = path.open(mode, encoding="utf-8")
    try:
        handle.write(f"\n--- {time.strftime('%Y-%m-%d %H:%M:%S')} rc-client self-update\n")
        handle.flush()
        return os.dup(handle.fileno())
    finally:
        handle.close()


async def spawn_self_update(build: str) -> asyncio.subprocess.Process:
    """Start the updater in its own session, with its output in the update log."""
    descriptor = _open_log()
    try:
        return await asyncio.create_subprocess_exec(
            rc_client_executable(),
            "self-update",
            "--build",
            build,
            stdin=subprocess.DEVNULL,
            stdout=descriptor,
            stderr=descriptor,
            start_new_session=True,
        )
    finally:
        os.close(descriptor)


def log_tail() -> str:
    """The last meaningful line of the update log, for an `update.failed` message."""
    try:
        text = update_log_path().read_text(encoding="utf-8", errors="replace")
    except OSError:
        return "the update failed and left no log"
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return lines[-1][:MAX_MESSAGE_CHARS] if lines else "the update failed without saying why"
