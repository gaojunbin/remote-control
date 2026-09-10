"""CLI exit codes and the launchd / systemd service templates."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Any

import httpx
import pytest

from rc_client.cli import EXIT_FAILURE, EXIT_NOT_ENROLLED, EXIT_OK, build_parser, main
from rc_client.config import Config, config_path, load_config, save_config
from rc_client.enroll import device_facts, enroll
from rc_client.errors import RcError
from rc_client.service import launchd, systemd


def test_the_parser_exposes_every_documented_command() -> None:
    parser = build_parser()
    for argv in (
        ["enroll", "--gateway", "https://x.example", "--pair", "RC-AAAA-BBBB"],
        ["run"],
        ["status"],
        ["agents"],
        ["service", "install"],
        ["service", "status"],
        ["uninstall", "--purge"],
    ):
        assert parser.parse_args(argv).command == argv[0]


def test_an_unknown_command_is_a_usage_error() -> None:
    with pytest.raises(SystemExit) as caught:
        main(["teleport"])
    assert caught.value.code == 2


def test_status_reports_not_enrolled_with_its_own_exit_code(capsys: Any) -> None:
    assert main(["status"]) == EXIT_NOT_ENROLLED
    output = capsys.readouterr().out
    assert "not enrolled" in output
    assert "rc-client enroll" in output


def test_status_prints_the_device_identity_without_the_token(capsys: Any) -> None:
    save_config(
        Config(
            gateway_origin="https://rc.example.com",
            device_id="dev-42",
            device_token="super-secret-token",
            name="mac-studio",
        )
    )
    assert main(["status"]) == EXIT_OK
    output = capsys.readouterr().out
    assert "dev-42" in output
    assert "mac-studio" in output
    assert "super-secret-token" not in output


def test_agents_prints_decodable_json(capsys: Any) -> None:
    assert main(["agents"]) == EXIT_OK
    payload = json.loads(capsys.readouterr().out)
    assert {info["agent"] for info in payload} == {"claude", "codex"}
    for info in payload:
        assert isinstance(info["capabilities"], list)


def test_enroll_reports_a_failure_exit_code(capsys: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    async def refuse(self: Any, url: str, **kwargs: Any) -> httpx.Response:
        return httpx.Response(
            404, json={"error": {"code": "not_found"}}, request=httpx.Request("POST", url)
        )

    monkeypatch.setattr(httpx.AsyncClient, "post", refuse)
    code = main(["enroll", "--gateway", "https://rc.example.com", "--pair", "RC-AAAA-BBBB"])
    assert code == EXIT_FAILURE
    assert "expired" in capsys.readouterr().err


async def test_enroll_stores_the_returned_credentials(monkeypatch: pytest.MonkeyPatch) -> None:
    sent: dict[str, Any] = {}

    async def accept(self: Any, url: str, **kwargs: Any) -> httpx.Response:
        sent["url"] = url
        sent["json"] = kwargs.get("json")
        return httpx.Response(
            200,
            json={
                "device_id": "dev-9",
                "device_token": "tok-9",
                "gateway_ws_url": "wss://rc.example.com/ws/device",
            },
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx.AsyncClient, "post", accept)
    config = await enroll("https://rc.example.com", "rc-aaaa-bbbb", "laptop", [])
    assert sent["url"] == "https://rc.example.com/api/devices/enroll"
    assert sent["json"]["code"] == "RC-AAAA-BBBB"
    assert sent["json"]["client_version"]
    assert config.device_id == "dev-9"
    assert load_config().device_token == "tok-9"
    assert config_path().exists()


async def test_enroll_refuses_a_remote_plain_http_gateway() -> None:
    with pytest.raises(RcError) as caught:
        await enroll("http://rc.example.com", "RC-AAAA-BBBB", None, [])
    assert caught.value.code == "bad_request"


def test_device_facts_describe_this_machine() -> None:
    facts = device_facts(None)
    assert facts["platform"] in {"macos", "linux"}
    assert facts["arch"] in {"arm64", "x86_64"}
    assert facts["hostname"]
    assert device_facts("chosen")["name"] == "chosen"


def test_launchd_plist_uses_the_agreed_label_and_paths(client_home: Path) -> None:
    rendered = launchd.render("/opt/rc/bin/rc-client")
    assert "<string>dev.remote-control.client</string>" in rendered
    assert "<string>/opt/rc/bin/rc-client</string>" in rendered
    assert "<string>run</string>" in rendered
    assert "<key>RunAtLoad</key><true/>" in rendered
    assert str(client_home) in rendered
    assert launchd.plist_path().name == "dev.remote-control.client.plist"
    assert launchd.plist_path().parent.name == "LaunchAgents"


def test_systemd_unit_restarts_and_names_the_client_home(client_home: Path) -> None:
    rendered = systemd.render("/opt/rc/bin/rc-client")
    assert "ExecStart=/opt/rc/bin/rc-client run" in rendered
    assert "Restart=always" in rendered
    assert f"Environment=RC_CLIENT_HOME={client_home}" in rendered
    assert "WantedBy=default.target" in rendered
    assert systemd.unit_path().name == "rc-client.service"
    assert "loginctl enable-linger" in systemd.LINGER_HINT


def test_the_served_install_script_accepts_its_own_origin(tmp_path: Path) -> None:
    """The gateway replaces every placeholder, so no other line may carry the literal.

    A copy that still holds the placeholder must refuse, and a substituted copy must run.
    """
    source = (Path(__file__).resolve().parents[1] / "install.sh").read_text(encoding="utf-8")
    served = tmp_path / "served.sh"
    served.write_text(source.replace("__GATEWAY_ORIGIN__", "https://rc.example.com"), "utf-8")
    raw = tmp_path / "raw.sh"
    raw.write_text(source, encoding="utf-8")

    command = ["sh", "{}", "--manual", "--pair", "RC-7K42-QX9M"]
    ok = subprocess.run([part.format(served) for part in command], capture_output=True, text=True)
    assert ok.returncode == 0, ok.stderr
    assert "https://rc.example.com/dist/rc_client-latest.whl" in ok.stdout

    refused = subprocess.run([part.format(raw) for part in command], capture_output=True, text=True)
    assert refused.returncode == 1
    assert "no gateway origin" in refused.stderr


def test_the_install_script_keeps_the_gateway_placeholder_and_flags() -> None:
    script = (Path(__file__).resolve().parents[1] / "install.sh").read_text(encoding="utf-8")
    assert 'GATEWAY="__GATEWAY_ORIGIN__"' in script
    for flag in ("--pair", "--name", "--gateway", "--manual", "--uninstall"):
        assert flag in script
    assert "/dist/rc_client-latest.whl" in script
    assert "uv python install 3.12" in script
    # `pip install <url>` needs PEP 427 tags in the filename and the alias has none, so the
    # script must download first and install the saved file, never the alias URL.
    assert "curl -fsSL -O -J" in script
    assert 'pip install --python "$VENV/bin/python" --upgrade --quiet "$WHEEL"' in script
    assert "$PAIR" not in script.split("log ")[-1], "the pairing code must never be echoed"


def _run_installer(*args: str) -> subprocess.CompletedProcess[str]:
    script = Path(__file__).resolve().parents[1] / "install.sh"
    return subprocess.run(
        ["sh", str(script), *args], capture_output=True, text=True, check=False, timeout=60
    )


@pytest.mark.parametrize(
    "origin",
    [
        "http://attacker.example",
        "http://192.168.evil.com",
        "http://10.deploy.example.com",
        "http://127.evil.com",
    ],
)
def test_the_installer_refuses_plain_http_to_a_public_host(origin: str) -> None:
    """The wheel is downloaded then executed, so this must hold before any fetch."""
    result = _run_installer("--pair", "RC-AAAA-BBBB", "--gateway", origin)
    assert result.returncode == 1
    assert "refusing plain http" in result.stderr
    assert "Installing" not in result.stdout


@pytest.mark.parametrize(
    "origin",
    ["http://127.0.0.1:8787", "http://192.168.1.20:8787", "http://172.20.0.5", "https://rc.io"],
)
def test_the_installer_accepts_local_http_and_any_https(origin: str) -> None:
    result = _run_installer("--pair", "RC-AAAA-BBBB", "--gateway", origin, "--manual")
    assert result.returncode == 0
    assert "Manual installation" in result.stdout
