"""launchd integration for macOS (a user agent, never a root daemon)."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path
from xml.sax.saxutils import escape

from ..channel.paths import path_with_shim
from ..config import client_home, log_dir
from ..errors import RcError

LABEL = "dev.remote-control.client"
PLIST_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" \
"http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>{label}</string>
  <key>ProgramArguments</key>
  <array>
    <string>{executable}</string>
    <string>run</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ThrottleInterval</key><integer>5</integer>
  <key>ProcessType</key><string>Background</string>
  <key>WorkingDirectory</key><string>{home}</string>
  <key>StandardOutPath</key><string>{stdout}</string>
  <key>StandardErrorPath</key><string>{stderr}</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>{path}</string>
    <key>RC_CLIENT_HOME</key><string>{client_home}</string>
  </dict>
</dict>
</plist>
"""


def plist_path() -> Path:
    return Path.home() / "Library/LaunchAgents" / f"{LABEL}.plist"


def _run(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(list(args), capture_output=True, text=True, check=False)


def _domain() -> str:
    return f"gui/{os.getuid()}"


def render(executable: str) -> str:
    """Build the plist, escaping every value: paths may contain & or <."""
    return PLIST_TEMPLATE.format(
        label=escape(LABEL),
        executable=escape(executable),
        home=escape(str(Path.home())),
        stdout=escape(str(log_dir() / "rc-client.out.log")),
        stderr=escape(str(log_dir() / "rc-client.err.log")),
        path=escape(path_with_shim(os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"))),
        client_home=escape(str(client_home())),
    )


def install(executable: str | None = None) -> Path:
    target = plist_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    log_dir().mkdir(parents=True, exist_ok=True, mode=0o700)
    binary = executable or _resolve_executable()
    target.write_text(render(binary), encoding="utf-8")
    _run("launchctl", "bootout", f"{_domain()}/{LABEL}")
    result = _run("launchctl", "bootstrap", _domain(), str(target))
    if result.returncode != 0:
        raise RcError("internal", f"launchctl bootstrap failed: {result.stderr.strip()[:200]}")
    return target


def uninstall() -> None:
    _run("launchctl", "bootout", f"{_domain()}/{LABEL}")
    plist_path().unlink(missing_ok=True)


def start() -> None:
    """Kickstart the job, bootstrapping it first when `stop` booted it out."""
    target = plist_path()
    if not target.exists():
        raise RcError("not_found", "the service is not installed; run rc-client service install")
    if _run("launchctl", "print", f"{_domain()}/{LABEL}").returncode != 0:
        result = _run("launchctl", "bootstrap", _domain(), str(target))
        if result.returncode != 0:
            raise RcError("internal", f"launchctl bootstrap failed: {result.stderr.strip()[:200]}")
        return
    result = _run("launchctl", "kickstart", "-k", f"{_domain()}/{LABEL}")
    if result.returncode != 0:
        raise RcError("internal", f"launchctl kickstart failed: {result.stderr.strip()[:200]}")


def stop() -> None:
    _run("launchctl", "bootout", f"{_domain()}/{LABEL}")


def status() -> str:
    if not plist_path().exists():
        return "not installed"
    result = _run("launchctl", "print", f"{_domain()}/{LABEL}")
    if result.returncode != 0:
        return "installed, not loaded"
    for line in result.stdout.splitlines():
        if "state =" in line:
            return line.strip()
    return "loaded"


def _resolve_executable() -> str:
    candidate = Path(sys.argv[0]).resolve()
    if candidate.name == "rc-client" and candidate.exists():
        return str(candidate)
    guess = Path(sys.executable).parent / "rc-client"
    if guess.exists():
        return str(guess)
    raise RcError("not_found", "cannot locate the rc-client executable to register")
