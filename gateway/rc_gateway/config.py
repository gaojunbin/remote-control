"""Gateway configuration.

One configuration entry: the repository root ``.env`` (loaded with python-dotenv when the gateway
runs from a source checkout) or plain environment variables (Docker). Secrets that the operator did
not supply are generated once into ``DATA_DIR`` so a fresh ``docker compose up -d`` works with only
``PUBLIC_ORIGIN`` and ``RC_PASSWORD``.
"""

from __future__ import annotations

import base64
import ipaddress
import os
import secrets
from dataclasses import dataclass, field
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from dotenv import load_dotenv

from .origins import canonical_origin

REPO_ROOT = Path(__file__).resolve().parents[2]
SESSION_TTL_SECONDS = 30 * 24 * 3600
PAIRING_TTL_SECONDS = 600
DEFAULT_STT_LANGUAGES = ("auto", "zh", "en")
#: The speech backends the gateway has a client for; ``none`` disables voice input.
STT_BACKENDS = ("openai", "mimo")
STT_PROVIDERS = ("none", *STT_BACKENDS)
DEFAULT_TRUSTED_PROXIES = ("127.0.0.0/8", "::1/128")


class ConfigError(RuntimeError):
    """The gateway cannot start with the configuration it was given."""


@dataclass(frozen=True)
class SttConfig:
    provider: str
    base_url: str
    api_key: str
    model: str
    languages: tuple[str, ...]

    @property
    def enabled(self) -> bool:
        return self.provider in STT_BACKENDS


@dataclass(frozen=True)
class ApnsConfig:
    team_id: str
    key_id: str
    key_path: str
    topic: str
    environment: str

    @property
    def enabled(self) -> bool:
        return bool(self.team_id and self.key_id and self.key_path and self.topic)


@dataclass(frozen=True)
class Config:
    public_origin: str
    password: str
    secret: str
    data_dir: Path
    host: str
    port: int
    log_level: str
    web_dist_dir: Path
    client_install_script: Path
    client_dist_dir: Path
    web_push_contact: str
    stt: SttConfig
    apns: ApnsConfig
    vapid_private_pem: Path
    vapid_public_key: str = ""
    #: Peers whose ``X-Forwarded-For`` the gateway believes. Loopback only by default, because a
    #: proxy that appends to the header (the standard nginx recipe does) lets a caller choose the
    #: first element and therefore its own rate-limit bucket.
    trusted_proxy_networks: tuple[str, ...] = field(default=DEFAULT_TRUSTED_PROXIES)

    @property
    def https_origin(self) -> bool:
        return self.public_origin.startswith("https://")

    @property
    def secure_cookie(self) -> bool:
        return self.https_origin

    @property
    def web_push_enabled(self) -> bool:
        return bool(self.vapid_public_key and self.web_push_contact)

    def db_path(self, name: str) -> Path:
        return self.data_dir / name


def _env(name: str, default: str = "") -> str:
    return (os.environ.get(name) or default).strip()


def _read_or_create_secret(path: Path) -> str:
    """Return a persistent random secret, created 0600 and never visible at any wider mode."""
    if path.exists():
        value = path.read_text(encoding="utf-8").strip()
        if value:
            return value
    value = secrets.token_urlsafe(48)
    # O_EXCL with an explicit mode: the file that signs every session token must not exist at
    # 0644 for even the moment between write and chmod.
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(value + "\n")
    return value


def _ensure_vapid_keys(path: Path) -> str:
    """Create a P-256 VAPID key pair once; return the base64url application server key."""
    from py_vapid import Vapid01

    if path.exists():
        vapid = Vapid01.from_file(str(path))
    else:
        vapid = Vapid01()
        vapid.generate_keys()
        # py_vapid writes with the default umask, so reserve the path at 0600 first and let it
        # write into a file that was never group- or world-readable.
        os.close(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600))
        vapid.save_key(str(path))
    point = vapid.public_key.public_bytes(
        encoding=serialization.Encoding.X962,
        format=serialization.PublicFormat.UncompressedPoint,
    )
    return base64.urlsafe_b64encode(point).rstrip(b"=").decode("ascii")


def _split_languages(raw: str) -> tuple[str, ...]:
    values = tuple(item.strip() for item in raw.split(",") if item.strip())
    return values or DEFAULT_STT_LANGUAGES


def _stt_config() -> SttConfig:
    """Read the ``STT_*`` block, rejecting a provider the gateway has no client for."""
    provider = _env("STT_PROVIDER", "none").lower() or "none"
    if provider not in STT_PROVIDERS:
        raise ConfigError(
            f"STT_PROVIDER is not a supported provider: {provider!r} "
            f"(expected one of {', '.join(STT_PROVIDERS)})"
        )
    return SttConfig(
        provider=provider,
        base_url=_env("STT_BASE_URL", "https://api.openai.com/v1").rstrip("/"),
        api_key=_env("STT_API_KEY"),
        model=_env("STT_MODEL", "whisper-1"),
        languages=_split_languages(_env("STT_LANGUAGES", ",".join(DEFAULT_STT_LANGUAGES))),
    )


def _default_path(env_name: str, docker_path: str, repo_relative: str) -> Path:
    configured = _env(env_name)
    if configured:
        return Path(configured).expanduser()
    docker = Path(docker_path)
    if docker.exists():
        return docker
    return REPO_ROOT / repo_relative


def load_config(*, load_env_file: bool = True) -> Config:
    """Build the configuration, failing fast with an actionable message."""
    if load_env_file:
        env_file = REPO_ROOT / ".env"
        if env_file.exists():
            load_dotenv(env_file, override=False)

    raw_origin = _env("PUBLIC_ORIGIN")
    if not raw_origin:
        raise ConfigError(
            "PUBLIC_ORIGIN is required: set it to the exact origin apps use, "
            "for example https://rc.example.com"
        )
    public_origin = canonical_origin(raw_origin)
    if public_origin is None:
        raise ConfigError(
            f"PUBLIC_ORIGIN is not a valid origin: {raw_origin!r} "
            "(expected scheme://host[:port] with no path)"
        )

    password = _env("RC_PASSWORD")
    if not password:
        raise ConfigError("RC_PASSWORD is required: set the login password for the admin user")

    # Before DATA_DIR is touched: a typo in the provider name should not leave secrets behind.
    stt = _stt_config()

    data_dir = Path(_env("DATA_DIR", "/data") or "/data").expanduser()
    try:
        data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    except OSError as exc:
        raise ConfigError(f"DATA_DIR {data_dir} is not writable: {exc}") from exc

    secret = _env("RC_SECRET") or _read_or_create_secret(data_dir / "session_secret")
    vapid_private_pem = data_dir / "vapid_private.pem"
    web_push_contact = _env("WEB_PUSH_CONTACT")
    vapid_public_key = _ensure_vapid_keys(vapid_private_pem) if web_push_contact else ""

    return Config(
        public_origin=public_origin,
        password=password,
        secret=secret,
        data_dir=data_dir,
        host=_env("RC_HOST", "0.0.0.0"),
        port=int(_env("RC_PORT", "8787") or "8787"),
        log_level=_env("LOG_LEVEL", "info").lower() or "info",
        web_dist_dir=_default_path("WEB_DIST_DIR", "/app/web/dist", "web/dist"),
        client_install_script=_default_path(
            "CLIENT_INSTALL_SCRIPT", "/app/client/install.sh", "client/install.sh"
        ),
        client_dist_dir=_default_path("CLIENT_DIST_DIR", "/app/client/dist", "client/dist"),
        web_push_contact=web_push_contact,
        stt=stt,
        apns=ApnsConfig(
            team_id=_env("APNS_TEAM_ID"),
            key_id=_env("APNS_KEY_ID"),
            key_path=_env("APNS_KEY_PATH"),
            topic=_env("APNS_TOPIC"),
            environment=_env("APNS_ENVIRONMENT", "production").lower() or "production",
        ),
        vapid_private_pem=vapid_private_pem,
        vapid_public_key=vapid_public_key,
        trusted_proxy_networks=_trusted_proxies(_env("TRUSTED_PROXIES")),
    )


def _trusted_proxies(raw: str) -> tuple[str, ...]:
    """Parse ``TRUSTED_PROXIES``, ignoring entries that are not networks."""
    if not raw:
        return DEFAULT_TRUSTED_PROXIES
    networks: list[str] = []
    for item in raw.split(","):
        candidate = item.strip()
        if not candidate:
            continue
        try:
            ipaddress.ip_network(candidate, strict=False)
        except ValueError:
            continue
        networks.append(candidate)
    return tuple(networks) or DEFAULT_TRUSTED_PROXIES
