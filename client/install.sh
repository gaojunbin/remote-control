#!/bin/sh
# remote-control device installer.
#
# The gateway serves this file at GET /install.sh with the placeholder on the GATEWAY line
# replaced by its PUBLIC_ORIGIN, so the usual invocations are:
#
#   curl -fsSL https://rc.example.com/install.sh | sh
#   curl -fsSL https://rc.example.com/install.sh | sh -s -- --pair RC-7K42-QX9M
#
# It installs uv and a private Python 3.12 virtual environment under
# ~/.rc-client, installs the rc_client wheel from the gateway, enrolls the
# device and registers the background service. Without --pair the host prints a
# QR code and waits for an app to scan it. Re-running upgrades in place.
set -eu

GATEWAY="__GATEWAY_ORIGIN__"
PAIR=""
NAME=""
MANUAL=0
UNINSTALL=0
SHELL_RC=1
CODEX=1

RC_HOME="${RC_CLIENT_HOME:-$HOME/.rc-client}"
VENV="$RC_HOME/venv"
UV_BIN=""

log() { printf '%s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'USAGE'
Usage: install.sh [--pair RC-XXXX-XXXX] [options]

Without --pair this host prints a QR code and waits for you to scan it with the
Remote Control app, or to open its link in a signed-in browser.

  --pair CODE        pairing code minted in an app; omit it to pair by scanning
  --name NAME        device name shown in the apps (default: this hostname)
  --gateway ORIGIN   override the gateway origin baked into this script
  --no-shell-rc      do not add the shim directory to your shell startup file
  --no-codex         skip the shared Codex app-server daemon setup
  --manual           print the steps instead of running them
  --uninstall        stop and remove the service, keep ~/.rc-client
  -h, --help         show this message
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        --pair) PAIR="${2:-}"; shift 2 ;;
        --pair=*) PAIR="${1#*=}"; shift ;;
        --name) NAME="${2:-}"; shift 2 ;;
        --name=*) NAME="${1#*=}"; shift ;;
        --gateway) GATEWAY="${2:-}"; shift 2 ;;
        --gateway=*) GATEWAY="${1#*=}"; shift ;;
        --no-shell-rc) SHELL_RC=0; shift ;;
        --no-codex) CODEX=0; shift ;;
        --manual) MANUAL=1; shift ;;
        --uninstall) UNINSTALL=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) fail "unknown option: $1" ;;
    esac
done

OS="$(uname -s)"
ARCH="$(uname -m)"
case "$OS" in
    Darwin)
        PLATFORM="macos"
        [ "$(id -u)" -eq 0 ] && fail "do not run this installer as root on macOS; run it as the logged-in user"
        ;;
    Linux) PLATFORM="linux" ;;
    *) fail "unsupported operating system: $OS" ;;
esac
case "$ARCH" in
    arm64|aarch64|x86_64|amd64) : ;;
    *) fail "unsupported architecture: $ARCH" ;;
esac

if [ "$UNINSTALL" -eq 1 ]; then
    if [ -x "$VENV/bin/rc-client" ]; then
        [ "$SHELL_RC" -eq 1 ] || set -- --no-shell-rc
        "$VENV/bin/rc-client" uninstall "$@" || true
    fi
    log "remote-control service removed. Delete $RC_HOME to remove the data as well."
    exit 0
fi

# Only the assignment above may carry the placeholder: the gateway replaces every occurrence, so
# a literal here would turn into the configured origin and reject it. An unsubstituted script has
# no scheme and falls through to the same error.
case "$GATEWAY" in
    http://*|https://*) : ;;
    *) fail "no gateway origin; pass --gateway https://your-gateway" ;;
esac
GATEWAY="${GATEWAY%/}"

# The wheel is downloaded and then executed as a service, so plain HTTP is only
# acceptable where nobody can sit on the path. This mirrors the daemon's own
# rule in rc_client/config.py, but has to hold *before* the first download.
case "$GATEWAY" in
    http://*)
        GATEWAY_HOST="${GATEWAY#http://}"
        GATEWAY_HOST="${GATEWAY_HOST%%/*}"
        GATEWAY_HOST="${GATEWAY_HOST%%:*}"
        GATEWAY_HOST="${GATEWAY_HOST#[}"
        GATEWAY_HOST="${GATEWAY_HOST%]}"
        # A name like 192.168.evil.com resolves wherever its owner points it, so
        # only literal loopback and RFC1918 addresses count as local.
        if [ "$GATEWAY_HOST" != "localhost" ] && [ "$GATEWAY_HOST" != "::1" ]; then
            case "$GATEWAY_HOST" in
                *[!0-9.]*) fail "refusing plain http:// to $GATEWAY_HOST; use https://" ;;
            esac
            case "$GATEWAY_HOST" in
                127.*|10.*|192.168.*|169.254.*|0.0.0.0) : ;;
                172.1[6-9].*|172.2[0-9].*|172.3[01].*) : ;;
                *) fail "refusing plain http:// to $GATEWAY_HOST; use https:// for a public gateway" ;;
            esac
        fi
        ;;
esac

if [ "$MANUAL" -eq 1 ]; then
    cat <<MANUAL
Manual installation on $PLATFORM/$ARCH:

  1. curl -LsSf https://astral.sh/uv/install.sh | sh
  2. uv python install 3.12
  3. uv venv --python 3.12 "$VENV"
  4. curl -fsSL -O -J "$GATEWAY/dist/rc_client-latest.whl"   # saves the real wheel name
  5. uv pip install --python "$VENV/bin/python" --upgrade ./rc_client-*.whl
  6. mkdir -p "$RC_HOME/state" && shasum -a 256 ./rc_client-*.whl | cut -d' ' -f1 \
       > "$RC_HOME/state/client-build"
  7. "$VENV/bin/rc-client" enroll --gateway "$GATEWAY" --pair <your pairing code>
     or "$VENV/bin/rc-client" enroll --gateway "$GATEWAY" --scan   # prints a QR code
  8. "$VENV/bin/rc-client" service install && "$VENV/bin/rc-client" service start
  9. "$VENV/bin/rc-client" shim install   # lets the apps drive terminal Claude sessions
 10. "$VENV/bin/rc-client" codex setup    # lets the apps drive terminal Codex sessions

The pairing code is single use and expires after 10 minutes. Step 6 is what lets
an app update this device later; skip it and the apps report no build for it.
MANUAL
    exit 0
fi

mkdir -p "$RC_HOME"
chmod 700 "$RC_HOME"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

find_uv() {
    if command -v uv >/dev/null 2>&1; then
        command -v uv
    elif [ -x "$HOME/.local/bin/uv" ]; then
        printf '%s\n' "$HOME/.local/bin/uv"
    fi
}

UV_BIN="$(find_uv || true)"
if [ -z "$UV_BIN" ]; then
    log "Installing uv into ~/.local/bin ..."
    curl -LsSf https://astral.sh/uv/install.sh | sh >/dev/null 2>&1 \
        || fail "could not install uv; install it manually and re-run"
    UV_BIN="$(find_uv || true)"
    [ -n "$UV_BIN" ] || fail "uv was installed but is not on PATH; open a new shell and re-run"
fi
log "Using uv at $UV_BIN"

log "Preparing Python 3.12 ..."
"$UV_BIN" python install 3.12 >/dev/null 2>&1 || fail "could not install Python 3.12"

if [ ! -x "$VENV/bin/python" ]; then
    "$UV_BIN" venv --python 3.12 "$VENV" >/dev/null 2>&1 \
        || fail "could not create the virtual environment at $VENV"
fi

# `pip install <url>` reads the wheel's Python, ABI and platform tags out of the filename, and the
# stable alias carries none of them, so save the download under the name the gateway reports and
# install that file instead of the alias URL.
log "Downloading the rc-client package from $GATEWAY ..."
DOWNLOAD="$(mktemp -d)"
trap 'rm -rf "$DOWNLOAD"' EXIT INT TERM
( cd "$DOWNLOAD" && curl -fsSL -O -J "$GATEWAY/dist/rc_client-latest.whl" ) \
    || fail "could not download $GATEWAY/dist/rc_client-latest.whl"
WHEEL=""
for candidate in "$DOWNLOAD"/*.whl; do
    if [ -f "$candidate" ]; then
        WHEEL="$candidate"
    fi
done
[ -n "$WHEEL" ] || fail "the gateway did not name the wheel it served; upgrade the gateway"

log "Installing the rc-client package ..."
"$UV_BIN" pip install --python "$VENV/bin/python" --upgrade --quiet "$WHEEL" \
    || fail "could not install rc_client from $WHEEL"

RC="$VENV/bin/rc-client"
[ -x "$RC" ] || fail "rc-client was not installed into $VENV"

# Record which wheel this is, so an app can see whether the device runs the one
# the gateway serves and can ask it to install that one instead.
BUILD="$(sha256_of "$WHEEL" || true)"
if [ -n "$BUILD" ]; then
    mkdir -p "$RC_HOME/state"
    chmod 700 "$RC_HOME/state"
    printf '%s\n' "$BUILD" > "$RC_HOME/state/client-build"
    chmod 600 "$RC_HOME/state/client-build"
else
    log "warning: no sha256 tool on this host; the apps cannot update this device"
fi

log "Enrolling this device ..."
if [ -n "$PAIR" ]; then
    set -- enroll --gateway "$GATEWAY" --pair "$PAIR"
    ENROLL_HINT="mint a fresh pairing code and try again"
else
    set -- enroll --gateway "$GATEWAY" --scan
    ENROLL_HINT="run this installer again for a fresh code"
fi
[ -n "$NAME" ] && set -- "$@" --name "$NAME"
"$RC" "$@" || fail "enrollment failed; $ENROLL_HINT"

log "Registering the background service ..."
"$RC" service install || fail "could not install the service"
"$RC" service start || fail "could not start the service"

# The shim lets the apps attach to a Claude session started in a terminal. It is
# inert for every non-interactive invocation, so installing it is safe even for
# someone who never uses that feature.
log "Installing the claude shim ..."
if [ "$SHELL_RC" -eq 1 ]; then
    "$RC" shim install || log "warning: could not install the claude shim"
else
    "$RC" shim install --no-shell-rc || log "warning: could not install the claude shim"
fi

# The shared Codex app-server daemon is what lets the apps see and drive a Codex
# session started in a terminal. Only the standalone build can bootstrap it, so
# `codex setup` installs that build when it is missing, whatever other `codex`
# happens to be on PATH. It is idempotent and never enables OpenAI remote
# control. It never removes another Codex install either: a foreign one on PATH
# is only warned about, in the lines printed below.
if [ "$CODEX" -eq 1 ]; then
    log "Setting up the shared Codex daemon ..."
    "$RC" codex setup || log "warning: the shared Codex daemon is not ready; run '$RC codex status'"
fi

log ""
log "Detected agents:"
"$RC" agents | sed -n 's/.*"agent": "\(.*\)",/  - \1/p' || true
log ""
log "remote-control is running. Useful commands:"
log "  $RC status          show the device and service status"
log "  $RC service stop    stop the daemon"
log "  $RC uninstall       remove the service (add --purge to delete $RC_HOME)"
log ""
log "  $RC shim status     show the claude shim used to attach terminal sessions"
log "  $RC codex status    show the shared Codex daemon used to attach terminal sessions"
log ""
log "Start Codex as a bare \`codex\` with no -c, --enable or --disable flags:"
log "those launch a private app-server the apps cannot see."
log ""
log "Add $VENV/bin to your PATH to call rc-client directly."
