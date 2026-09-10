#!/usr/bin/env python3
"""Private signing state and validation for ci-testflight.sh (stdlib only)."""
from __future__ import annotations

import base64
import datetime as dt
import hashlib
import json
import os
import plistlib
import re
import secrets
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path


class PreflightError(Exception):
    pass


SECRET_NAMES = (
    "BUILD_CERTIFICATE_BASE64", "P12_PASSWORD", "BUILD_PROVISION_PROFILE_BASE64",
    "ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_PRIVATE_KEY_BASE64",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PreflightError(message)


def build_number(run: str, attempt: str) -> str:
    require(bool(re.fullmatch(r"[1-9][0-9]{0,5}", run)), "GITHUB_RUN_NUMBER must be a positive integer below 999900")
    require(bool(re.fullmatch(r"[1-9][0-9]?", attempt)), "GITHUB_RUN_ATTEMPT must be between 1 and 99")
    number = int(run)
    require(number < 999900, "GITHUB_RUN_NUMBER exceeds this build-number scheme")
    # Conservative 4.2.2 digit limits; a rerun gets a distinct final component.
    return f"{1 + number // 100}.{number % 100}.{int(attempt)}"


def decode_secret(name: str, maximum: int) -> bytes:
    try:
        encoded = re.sub(r"\s+", "", os.environ[name])
        require(len(encoded) <= maximum * 2, f"{name} is too large")
        data = base64.b64decode(encoded, validate=True)
    except (KeyError, ValueError):
        raise PreflightError(f"{name} must contain valid Base64") from None
    require(0 < len(data) <= maximum, f"{name} has an invalid decoded size")
    return data


def preflight() -> str:
    missing = [name for name in (*SECRET_NAMES, "APPLE_TEAM_ID", "GITHUB_RUN_NUMBER") if not os.environ.get(name)]
    require(not missing, "Missing required parameters: " + ", ".join(missing))
    require(bool(re.fullmatch(r"[A-Z0-9]{10}", os.environ["APPLE_TEAM_ID"])), "APPLE_TEAM_ID must be a 10-character Team ID")
    require(bool(re.fullmatch(r"[A-Z0-9]{10}", os.environ["ASC_KEY_ID"])), "ASC_KEY_ID must be a 10-character key ID")
    require(bool(re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", os.environ["ASC_ISSUER_ID"])), "ASC_ISSUER_ID must be a UUID")
    require(bool(re.fullmatch(r"[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+", os.environ.get("BUNDLE_ID", "com.junbingao.remotecontrol"))), "BUNDLE_ID must be an explicit bundle identifier")
    decode_secret("BUILD_CERTIFICATE_BASE64", 1024 * 1024)
    decode_secret("BUILD_PROVISION_PROFILE_BASE64", 256 * 1024)
    key = decode_secret("ASC_PRIVATE_KEY_BASE64", 16 * 1024)
    require(key.strip().startswith(b"-----BEGIN PRIVATE KEY-----") and key.strip().endswith(b"-----END PRIVATE KEY-----"), "ASC_PRIVATE_KEY_BASE64 must contain a PKCS#8 .p8 key")
    return build_number(os.environ["GITHUB_RUN_NUMBER"], os.environ.get("GITHUB_RUN_ATTEMPT", "1"))


def state_path() -> Path:
    base = Path(os.environ.get("RUNNER_TEMP", ""))
    require(base.is_absolute() and base.is_dir(), "RUNNER_TEMP must name the runner's existing temporary directory")
    parts = [os.environ.get(name, "") for name in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_JOB")]
    require(bool(re.fullmatch(r"[1-9][0-9]{0,24}", parts[0]))
            and bool(re.fullmatch(r"[1-9][0-9]?", parts[1]))
            and bool(re.fullmatch(r"[A-Za-z0-9_-]{1,100}", parts[2])), "Invalid GitHub job identity for signing cleanup")
    identity = "-".join(parts)
    return base / f"remotecontrol-testflight-{identity}.json"


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value), encoding="utf-8")
    path.chmod(0o600)


def private_file(path: Path, content: bytes) -> None:
    with path.open("xb") as stream:
        stream.write(content)
    path.chmod(0o600)


def load_state() -> tuple[Path, dict]:
    manifest = state_path()
    require(not manifest.is_symlink(), "Refusing a symlink signing-state manifest")
    state = json.loads(manifest.read_text())
    folder = Path(state["directory"])
    require(folder.parent.resolve() == manifest.parent.resolve() and folder.name.startswith("remotecontrol-signing.")
            and not folder.is_symlink() and folder.is_dir(), "Invalid private signing directory")
    require(manifest.stat().st_uid == os.getuid() and folder.stat().st_uid == os.getuid(), "Signing state has a different owner")
    return manifest, state


def prepare() -> None:
    number = preflight()
    manifest = state_path()
    require(not manifest.exists() and not manifest.is_symlink(), "Signing state already exists; run --cleanup before retrying")
    folder = Path(tempfile.mkdtemp(prefix="remotecontrol-signing.", dir=manifest.parent))
    folder.chmod(0o700)
    state = {"directory": str(folder), "search_list_changed": False, "profiles": []}
    write_json(manifest, state)  # Every subsequent step is recoverable by --cleanup.
    private_file(folder / "certificate.p12", decode_secret("BUILD_CERTIFICATE_BASE64", 1024 * 1024))
    private_file(folder / "profile.mobileprovision", decode_secret("BUILD_PROVISION_PROFILE_BASE64", 256 * 1024))
    private_file(folder / "AuthKey.p8", decode_secret("ASC_PRIVATE_KEY_BASE64", 16 * 1024))
    private_file(folder / "keychain.password", secrets.token_urlsafe(40).encode())
    private_file(folder / "build-number", number.encode())
    print(folder)


def validate_profile(profile: dict, team: str, bundle: str, now: dt.datetime | None = None) -> dict:
    now = now or dt.datetime.now(dt.timezone.utc)
    entitlements = profile.get("Entitlements", {})
    expiry = profile.get("ExpirationDate")
    if isinstance(expiry, dt.datetime) and expiry.tzinfo is None:
        expiry = expiry.replace(tzinfo=dt.timezone.utc)
    require(isinstance(expiry, dt.datetime) and expiry > now, "Distribution profile is expired or has no expiration")
    require(profile.get("TeamIdentifier") == [team] and entitlements.get("com.apple.developer.team-identifier") == team, "Distribution profile Team ID does not match APPLE_TEAM_ID")
    prefixes = profile.get("ApplicationIdentifierPrefix", [])
    identifiers = [prefix + "." + bundle for prefix in prefixes if isinstance(prefix, str)]
    application_id = entitlements.get("application-identifier")
    require(application_id in identifiers and "*" not in str(application_id), "Distribution profile does not match the exact BUNDLE_ID")
    require(entitlements.get("aps-environment") == "production", "Distribution profile must enable production APNs")
    require(entitlements.get("get-task-allow") is False and not profile.get("ProvisionsAllDevices") and "ProvisionedDevices" not in profile, "Use an App Store distribution profile, not development, Ad Hoc or enterprise")
    profile_id = profile.get("UUID", "")
    require(bool(re.fullmatch(r"[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}", profile_id)), "Distribution profile has an invalid UUID")
    certs = profile.get("DeveloperCertificates", [])
    require(bool(certs) and all(isinstance(cert, bytes) for cert in certs), "Distribution profile has no signing certificates")
    return {"uuid": profile_id, "application_id": application_id,
            "certificates": [hashlib.sha1(cert).hexdigest().upper() for cert in certs]}


def profile() -> None:
    _manifest, state = load_state()
    folder = Path(state["directory"])
    value = validate_profile(plistlib.loads((folder / "profile.plist").read_bytes()), os.environ["APPLE_TEAM_ID"], os.environ.get("BUNDLE_ID", "com.junbingao.remotecontrol"))
    write_json(folder / "profile.json", value)


def identity() -> None:
    _manifest, state = load_state()
    folder = Path(state["directory"])
    value = json.loads((folder / "profile.json").read_text())
    candidates = re.findall(r'\b([0-9A-Fa-f]{40})\s+"(Apple Distribution:[^"\n]+)"', (folder / "identities.txt").read_text())
    matching = [digest.upper() for digest, name in candidates if digest.upper() in value["certificates"] and name.endswith("(" + os.environ["APPLE_TEAM_ID"] + ")")]
    require(len(set(matching)) == 1, "The imported P12 must contain exactly one valid Apple Distribution identity allowed by the profile")
    private_file(folder / "signing-identity", matching[0].encode())


def activate() -> None:
    manifest, state = load_state()
    folder = Path(state["directory"])
    original = shlex.split(subprocess.check_output(["security", "list-keychains", "-d", "user"], text=True))
    state["original_search_list"] = original
    state["search_list_changed"] = True
    write_json(manifest, state)
    subprocess.run(["security", "list-keychains", "-d", "user", "-s", str(folder / "signing.keychain-db"), *original], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def install_profile() -> None:
    manifest, state = load_state()
    folder = Path(state["directory"])
    profile_id = json.loads((folder / "profile.json").read_text())["uuid"]
    source = (folder / "profile.mobileprovision").read_bytes()
    # Both locations are used by the supported Xcode generations. Each exact
    # UUID file is backed up/restored; no unrelated profiles are removed.
    directories = [Path.home() / "Library/MobileDevice/Provisioning Profiles",
                   Path.home() / "Library/Developer/Xcode/UserData/Provisioning Profiles"]
    for index, directory in enumerate(directories):
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        target = directory / (profile_id + ".mobileprovision")
        require(not target.is_symlink(), "Refusing to replace a symlink provisioning profile")
        old_mode = stat.S_IMODE(target.stat().st_mode) if target.exists() else None
        backup = folder / f"profile-backup-{index}"
        if target.exists():
            private_file(backup, target.read_bytes())
        state["profiles"].append({"path": str(target), "backup": str(backup) if backup.exists() else None,
                                  "old_mode": old_mode, "installed_hash": hashlib.sha256(source).hexdigest()})
        write_json(manifest, state)
        target.write_bytes(source)
        target.chmod(0o600)


def export_options() -> None:
    _manifest, state = load_state()
    folder = Path(state["directory"])
    value = json.loads((folder / "profile.json").read_text())
    options = {"method": "app-store-connect", "destination": "upload", "signingStyle": "manual",
               "teamID": os.environ["APPLE_TEAM_ID"], "signingCertificate": (folder / "signing-identity").read_text(),
               "provisioningProfiles": {os.environ.get("BUNDLE_ID", "com.junbingao.remotecontrol"): value["uuid"]},
               "manageAppVersionAndBuildNumber": False, "uploadSymbols": True,
               "stripSwiftSymbols": True, "testFlightInternalTestingOnly": True}
    private_file(folder / "ExportOptions.plist", plistlib.dumps(options))


def project_spec(root: str) -> None:
    _manifest, state = load_state()
    folder = Path(state["directory"])
    value = json.loads((folder / "profile.json").read_text())
    settings = {
        "DEVELOPMENT_TEAM": os.environ["APPLE_TEAM_ID"],
        "PRODUCT_BUNDLE_IDENTIFIER": os.environ.get("BUNDLE_ID", "com.junbingao.remotecontrol"),
        "CODE_SIGN_STYLE": "Manual",
        "CODE_SIGN_IDENTITY": (folder / "signing-identity").read_text(),
        "PROVISIONING_PROFILE_SPECIFIER": value["uuid"],
        "OTHER_CODE_SIGN_FLAGS": "$(inherited) --keychain " + shlex.quote(str(folder / "signing.keychain-db")),
        "CURRENT_PROJECT_VERSION": (folder / "build-number").read_text(),
        "APNS_ENVIRONMENT": "production",
    }
    # XcodeGen deep-merges the included, checked-in spec before this target-only
    # overlay. --project-root keeps sources, plist and local package paths intact.
    write_json(folder / "project-signing.json", {
        "include": [{"path": str(Path(root) / "project.yml"), "relativePaths": False}],
        "targets": {"RemoteControl": {"settings": {"configs": {"Release": settings}}}},
    })


def safe_diagnostics(text: str, root: Path) -> list[str]:
    """Extract locations/categories/codes only; never echo arbitrary tool text."""
    messages = []
    categories = (
        ("no such module", "module unavailable"),
        ("cannot find", "unresolved symbol or type"),
        ("cannot convert", "type conversion failure"),
        ("isolation", "Swift concurrency isolation failure"),
        ("actor-isolated", "Swift concurrency isolation failure"),
        ("sendable", "Swift concurrency Sendable failure"),
        ("ambiguous", "ambiguous expression"),
        ("unavailable", "API availability failure"),
        ("does not support provisioning profiles", "a non-app target received a provisioning profile"),
        ("no profiles for", "no matching provisioning profile"),
        ("no signing certificate", "no matching signing certificate"),
        ("authentication", "App Store Connect authentication failure"),
        ("unauthorized", "App Store Connect authorization failure"),
        ("bundle version", "App Store Connect build-number validation failure"),
    )
    for line in text.splitlines():
        if "error" not in line.lower():
            continue
        category = next((description for marker, description in categories if marker in line.lower()), "build/upload error")
        location = re.match(r"(.+\.(?:swift|m|mm|h)):(\d{1,7}):(\d{1,7}):\s*error:", line)
        if location:
            try:
                path = Path(location[1]).resolve().relative_to(root.resolve())
            except (ValueError, OSError):
                continue
            if not re.fullmatch(r"[A-Za-z0-9_./-]+", str(path)) or not (root / path).is_file():
                continue
            messages.append(f"{path}:{location[2]}:{location[3]}: {category}")
        elif category != "build/upload error":
            messages.append(category)
        messages.extend(sorted(set(re.findall(r"\bITMS-[0-9]{5}\b", line))))
    return list(dict.fromkeys(messages))[:12]


def diagnostics(root: str) -> None:
    _manifest, state = load_state()
    with (Path(state["directory"]) / "last-step.log").open("rb") as stream:
        stream.seek(0, 2)
        stream.seek(max(0, stream.tell() - 4 * 1024 * 1024))
        text = stream.read().decode("utf-8", errors="replace")
    messages = safe_diagnostics(text, Path(root))
    for message in messages:
        print("TestFlight diagnostic: " + message, file=sys.stderr)
    print("Reproduce compiler failures in the unsigned iOS checks for this same commit; "
          "for signing/upload failures verify the matching App Store profile, certificate, "
          "API-key role and unused build number.", file=sys.stderr)


def archive(app_path: str) -> None:
    _manifest, state = load_state()
    folder = Path(state["directory"])
    info = plistlib.loads((Path(app_path) / "Info.plist").read_bytes())
    entitlements = plistlib.loads((folder / "signed-entitlements.plist").read_bytes())
    profile_value = json.loads((folder / "profile.json").read_text())
    require(info.get("CFBundleIdentifier") == os.environ.get("BUNDLE_ID", "com.junbingao.remotecontrol") and info.get("CFBundleVersion") == (folder / "build-number").read_text(), "Archived app bundle/build number does not match the requested release")
    require(entitlements.get("aps-environment") == "production" and entitlements.get("com.apple.developer.team-identifier") == os.environ["APPLE_TEAM_ID"] and entitlements.get("application-identifier") == profile_value["application_id"] and entitlements.get("get-task-allow", False) is False, "Archived signature does not match production APNs/Team/Bundle requirements")


def cleanup() -> None:
    manifest = state_path()
    if not manifest.exists():
        return
    manifest, state = load_state()
    folder = Path(state["directory"])
    failures = False
    if state.get("search_list_changed"):
        restored = subprocess.run(["security", "list-keychains", "-d", "user", "-s", *state["original_search_list"]], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        failures |= restored.returncode != 0
        if restored.returncode == 0:
            state["search_list_changed"] = False
            write_json(manifest, state)
    keychain = folder / "signing.keychain-db"
    if keychain.exists():
        deleted = subprocess.run(["security", "delete-keychain", str(keychain)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        failures |= deleted.returncode != 0
    for entry in list(state["profiles"]):
        target = Path(entry["path"])
        if target.exists() and not target.is_symlink() and hashlib.sha256(target.read_bytes()).hexdigest() == entry["installed_hash"]:
            if entry["backup"]:
                target.write_bytes(Path(entry["backup"]).read_bytes())
                target.chmod(entry["old_mode"])
            else:
                target.unlink()
        elif target.exists():
            failures = True  # Preserve unexpected concurrent changes.
            continue
        state["profiles"].remove(entry)
        write_json(manifest, state)
    # A restoration retry does not need raw certificate/private-key material.
    for name in ("certificate.p12", "AuthKey.p8", "keychain.password", "last-step.log"):
        (folder / name).unlink(missing_ok=True)
    require(not failures, "Signing cleanup could not fully restore runner state; private recovery state is retained")
    shutil.rmtree(folder)
    manifest.unlink()


def main() -> None:
    command = sys.argv[1]
    if command == "preflight":
        print(preflight())
    elif command in ("archive", "project-spec", "diagnostics"):
        {"archive": archive, "project-spec": project_spec, "diagnostics": diagnostics}[command](sys.argv[2])
    else:
        {"prepare": prepare, "profile": profile, "identity": identity,
         "activate": activate, "install-profile": install_profile,
         "export-options": export_options, "cleanup": cleanup}[command]()


if __name__ == "__main__":
    try:
        main()
    except PreflightError as error:
        print("TestFlight preflight: " + str(error), file=sys.stderr)
        sys.exit(2)
    except Exception:
        # Never include subprocess argv, malformed private material or plist
        # contents in CI tracebacks. Each shell step identifies its safe phase.
        print("TestFlight signing helper failed; private details were not logged.", file=sys.stderr)
        sys.exit(2)
