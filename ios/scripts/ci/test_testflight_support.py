"""Zero-SDK signing checks. All material and macOS command responses are fixtures."""
from __future__ import annotations

import base64
import contextlib
import copy
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import plistlib
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import testflight_support as support


TEAM = "TESTTEAM01"
BUNDLE = "com.example.testflight"
PROFILE_ID = "11111111-2222-3333-4444-555555555555"
CERTIFICATE = b"not-a-real-certificate-fixture"
FINGERPRINT = hashlib.sha1(CERTIFICATE).hexdigest().upper()
PRIVATE_MARKER = "never-print-private-fixture-material"


def profile_fixture() -> dict:
    return {
        "UUID": PROFILE_ID,
        "TeamIdentifier": [TEAM],
        "ApplicationIdentifierPrefix": [TEAM],
        "ExpirationDate": dt.datetime(2090, 1, 1),
        "Entitlements": {
            "com.apple.developer.team-identifier": TEAM,
            "application-identifier": TEAM + "." + BUNDLE,
            "aps-environment": "production",
            "get-task-allow": False,
        },
        "DeveloperCertificates": [CERTIFICATE],
    }


def environment(folder: Path) -> dict[str, str]:
    encode = lambda data: base64.b64encode(data).decode()
    return {
        "BUILD_CERTIFICATE_BASE64": encode(CERTIFICATE),
        "P12_PASSWORD": PRIVATE_MARKER,
        "BUILD_PROVISION_PROFILE_BASE64": encode(plistlib.dumps(profile_fixture())),
        "ASC_KEY_ID": "TESTKEY001",
        "ASC_ISSUER_ID": PROFILE_ID,
        "ASC_PRIVATE_KEY_BASE64": encode(b"-----BEGIN PRIVATE KEY-----\n" + PRIVATE_MARKER.encode() + b"\n-----END PRIVATE KEY-----"),
        "APPLE_TEAM_ID": TEAM,
        "BUNDLE_ID": BUNDLE,
        "GITHUB_RUN_NUMBER": "234",
        "GITHUB_RUN_ATTEMPT": "1",
        "GITHUB_RUN_ID": "12345",
        "GITHUB_JOB": "upload",
        "RUNNER_TEMP": str(folder),
    }


class HelperTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="remotecontrol-signing-checks.")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.env = environment(self.root)
        self.patch = mock.patch.dict(os.environ, self.env, clear=True)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def prepare(self) -> Path:
        with contextlib.redirect_stdout(io.StringIO()):
            support.prepare()
        _, state = support.load_state()
        folder = Path(state["directory"])
        (folder / "profile.plist").write_bytes(plistlib.dumps(profile_fixture()))
        support.profile()
        (folder / "identities.txt").write_text(f'1) {FINGERPRINT} "Apple Distribution: Example ({TEAM})"\n')
        support.identity()
        return folder

    def test_preflight_and_monotonic_build_numbers(self) -> None:
        self.assertEqual(support.preflight(), "3.34.1")
        numbers = [support.build_number(str(run), str(attempt)) for run, attempt in [(1, 1), (1, 2), (99, 99), (100, 1), (999899, 99)]]
        self.assertEqual(numbers, sorted(numbers, key=lambda number: tuple(map(int, number.split(".")))))
        self.assertEqual(numbers[-1], "9999.99.99")
        for run, attempt in [("0", "1"), ("999900", "1"), ("1", "100"), ("1.0", "1")]:
            with self.subTest(run=run, attempt=attempt), self.assertRaises(support.PreflightError):
                support.build_number(run, attempt)

    def test_missing_secret_reports_only_parameter_name(self) -> None:
        del os.environ["ASC_KEY_ID"]
        with self.assertRaises(support.PreflightError) as caught:
            support.preflight()
        self.assertEqual(str(caught.exception), "Missing required parameters: ASC_KEY_ID")
        self.assertNotIn(PRIVATE_MARKER, str(caught.exception))

    def test_invalid_base64_and_wildcard_rejected(self) -> None:
        for name, value in [("BUILD_CERTIFICATE_BASE64", PRIVATE_MARKER), ("BUNDLE_ID", "com.example.*")]:
            with self.subTest(name=name), mock.patch.dict(os.environ, {name: value}), self.assertRaises(support.PreflightError):
                support.preflight()

    def test_profile_exact_binding_and_app_store_distribution(self) -> None:
        good = profile_fixture()
        self.assertEqual(support.validate_profile(good, TEAM, BUNDLE)["certificates"], [FINGERPRINT])
        changes = [
            ("TeamIdentifier", ["OTHERTEAM0"]),
            ("ExpirationDate", dt.datetime(2000, 1, 1)),
            ("ProvisionedDevices", ["device"]),
            ("ProvisionsAllDevices", True),
            ("DeveloperCertificates", []),
        ]
        for key, value in changes:
            changed = copy.deepcopy(good)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(support.PreflightError):
                support.validate_profile(changed, TEAM, BUNDLE)
        for key, value in [("aps-environment", "development"), ("application-identifier", TEAM + ".com.example.*"), ("get-task-allow", True)]:
            changed = copy.deepcopy(good)
            changed["Entitlements"][key] = value
            with self.subTest(key=key), self.assertRaises(support.PreflightError):
                support.validate_profile(changed, TEAM, BUNDLE)

    def test_profile_preserves_legacy_application_identifier_prefix(self) -> None:
        value = profile_fixture()
        value["ApplicationIdentifierPrefix"] = ["LEGACYPFX0"]
        value["Entitlements"]["application-identifier"] = "LEGACYPFX0." + BUNDLE
        self.assertEqual(support.validate_profile(value, TEAM, BUNDLE)["application_id"], "LEGACYPFX0." + BUNDLE)

    def test_material_permissions_and_identity_membership(self) -> None:
        folder = self.prepare()
        self.assertEqual(stat.S_IMODE(folder.stat().st_mode), 0o700)
        for name in ("certificate.p12", "AuthKey.p8", "keychain.password", "profile.mobileprovision"):
            self.assertEqual(stat.S_IMODE((folder / name).stat().st_mode), 0o600)
        (folder / "identities.txt").write_text('1) ' + "A" * 40 + f' "Apple Distribution: Example ({TEAM})"\n')
        with self.assertRaises(support.PreflightError):
            support.identity()

    def test_target_only_overlay_and_manual_upload_options(self) -> None:
        folder = self.prepare()
        support.project_spec(str(self.root))
        spec = json.loads((folder / "project-signing.json").read_text())
        self.assertEqual(set(spec), {"include", "targets"})
        self.assertEqual(set(spec["targets"]), {"RemoteControl"})
        configs = spec["targets"]["RemoteControl"]["settings"]["configs"]
        self.assertEqual(set(configs), {"Release"})
        self.assertEqual(configs["Release"]["PROVISIONING_PROFILE_SPECIFIER"], PROFILE_ID)
        self.assertEqual(configs["Release"]["PRODUCT_BUNDLE_IDENTIFIER"], BUNDLE)
        support.export_options()
        options = plistlib.loads((folder / "ExportOptions.plist").read_bytes())
        self.assertEqual(options["destination"], "upload")
        self.assertEqual(options["method"], "app-store-connect")
        self.assertEqual(options["signingStyle"], "manual")
        self.assertEqual(options["signingCertificate"], FINGERPRINT)
        self.assertEqual(options["provisioningProfiles"], {BUNDLE: PROFILE_ID})
        self.assertFalse(options["manageAppVersionAndBuildNumber"])
        self.assertTrue(options["testFlightInternalTestingOnly"])

    def test_cleanup_restores_profiles_and_search_list(self) -> None:
        folder = self.prepare()
        fake_home = self.root / "fixture-user"
        old_profile = fake_home / "Library/MobileDevice/Provisioning Profiles" / (PROFILE_ID + ".mobileprovision")
        old_profile.parent.mkdir(parents=True)
        old_profile.write_bytes(b"existing-profile")
        old_profile.chmod(0o640)
        (folder / "signing.keychain-db").write_bytes(b"keychain-fixture")
        with mock.patch.object(Path, "home", return_value=fake_home), mock.patch.object(support.subprocess, "check_output", return_value='"/fixture/login.keychain-db"\n'), mock.patch.object(support.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as commands:
            support.activate()
            support.install_profile()
            self.assertNotEqual(old_profile.read_bytes(), b"existing-profile")
            support.cleanup()
        self.assertEqual(old_profile.read_bytes(), b"existing-profile")
        self.assertEqual(stat.S_IMODE(old_profile.stat().st_mode), 0o640)
        self.assertFalse(folder.exists())
        self.assertFalse(support.state_path().exists())
        argv = [call.args[0] for call in commands.call_args_list]
        self.assertIn(["security", "list-keychains", "-d", "user", "-s", "/fixture/login.keychain-db"], argv)
        self.assertFalse(any("default-keychain" in args for args in argv))
        support.cleanup()  # --cleanup remains safe after an EXIT trap succeeded.

    def test_cleanup_can_retry_without_rejecting_already_restored_profile(self) -> None:
        folder = self.prepare()
        with mock.patch.object(Path, "home", return_value=self.root / "fixture-user"):
            support.install_profile()
        (folder / "signing.keychain-db").write_bytes(b"keychain-fixture")
        with mock.patch.object(support.subprocess, "run", return_value=subprocess.CompletedProcess([], 1)):
            with self.assertRaises(support.PreflightError):
                support.cleanup()
        self.assertTrue(support.state_path().exists())
        self.assertFalse((folder / "AuthKey.p8").exists())
        with mock.patch.object(support.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)):
            support.cleanup()
        self.assertFalse(folder.exists())

    def test_invalid_job_and_symlink_state_rejected(self) -> None:
        with mock.patch.dict(os.environ, {"GITHUB_JOB": ""}), self.assertRaises(support.PreflightError):
            support.state_path()
        external = self.root / "unrelated"
        external.write_text("{}")
        support.state_path().symlink_to(external)
        with self.assertRaises(support.PreflightError):
            support.cleanup()
        self.assertEqual(external.read_text(), "{}")

    def test_diagnostics_do_not_echo_tool_or_private_content(self) -> None:
        source = self.root / "App/Fixture.swift"
        source.parent.mkdir()
        source.touch()
        messages = support.safe_diagnostics(
            f"{source}:12:9: error: cannot find '{PRIVATE_MARKER}' in scope\n"
            f"error: authentication {PRIVATE_MARKER} ITMS-90189\n"
            f"error: <server body>{PRIVATE_MARKER}</server body>\n", self.root)
        self.assertEqual(messages, ["App/Fixture.swift:12:9: unresolved symbol or type", "App Store Connect authentication failure", "ITMS-90189"])
        self.assertNotIn(PRIVATE_MARKER, str(messages))


# This single fixture executable stands in for every macOS tool used by the
# shell script. Tests never invoke the installed security/Xcode/SDK binaries.
STUB_SOURCE = r'''
import base64, hashlib, json, os, pathlib, plistlib, sys
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
root = pathlib.Path(os.environ["TEST_FIXTURE_ROOT"])
def argument(flag): return args[args.index(flag) + 1]
if name == "security":
    command = args[0]
    if command == "cms":
        sys.stdout.buffer.write(pathlib.Path(argument("-i")).read_bytes())
        print("fixture cms warning", file=sys.stderr)
    elif command == "create-keychain":
        pathlib.Path(args[-1]).touch()
    elif command == "delete-keychain":
        pathlib.Path(args[-1]).unlink()
    elif command == "find-identity":
        fingerprint = hashlib.sha1(b"not-a-real-certificate-fixture").hexdigest().upper()
        print(f'1) {fingerprint} "Apple Distribution: Example (TESTTEAM01)"')
    elif command == "list-keychains":
        if "-s" in args:
            (root / "search-list.json").write_text(json.dumps(args[args.index("-s") + 1:]))
        else:
            print('"/fixture/original.keychain-db"')
elif name == "xcodegen":
    spec = json.loads(pathlib.Path(argument("--spec")).read_text())
    assert set(spec["targets"]) == {"RemoteControl"}
    assert argument("--project-root") == str(root / "repository")
    (root / "overlay.json").write_text(json.dumps(spec))
elif name == "xcodebuild":
    if "-help" in args:
        print("app-store-connect -authenticationKeyPath -authenticationKeyID -authenticationKeyIssuerID")
    elif "archive" in args:
        assert not any(value.startswith(("PROVISIONING_PROFILE_SPECIFIER=", "PRODUCT_BUNDLE_IDENTIFIER=", "CODE_SIGN_IDENTITY=", "CODE_SIGN_STYLE=")) for value in args), "Global settings could leak into package bundles"
        spec = json.loads((root / "overlay.json").read_text())
        settings = spec["targets"]["RemoteControl"]["settings"]["configs"]["Release"]
        app = pathlib.Path(argument("-archivePath")) / "Products/Applications/RemoteControl.app"
        app.mkdir(parents=True)
        (app / "Info.plist").write_bytes(plistlib.dumps({"CFBundleIdentifier": settings["PRODUCT_BUNDLE_IDENTIFIER"], "CFBundleVersion": settings["CURRENT_PROJECT_VERSION"]}))
    elif "-exportArchive" in args:
        options = plistlib.loads(pathlib.Path(argument("-exportOptionsPlist")).read_bytes())
        assert options["method"] == "app-store-connect" and options["destination"] == "upload"
        assert options["signingStyle"] == "manual"
        assert pathlib.Path(argument("-authenticationKeyPath")).read_bytes().startswith(b"-----BEGIN PRIVATE KEY-----")
        assert argument("-authenticationKeyID") == "TESTKEY001"
        assert argument("-authenticationKeyIssuerID") == "11111111-2222-3333-4444-555555555555"
        if os.environ.get("TEST_FAIL_UPLOAD"):
            print("error: authentication never-print-private-fixture-material ITMS-90189")
            sys.exit(65)
        (root / "upload-called").touch()
elif name == "codesign" and "--entitlements" in args:
    sys.stdout.buffer.write(plistlib.dumps({"aps-environment": "production", "com.apple.developer.team-identifier": "TESTTEAM01", "application-identifier": "TESTTEAM01.com.example.testflight", "get-task-allow": False}))
elif name == "openssl":
    pass
'''


class ShellStubTests(unittest.TestCase):
    def run_shell(self, failure: bool = False, event: str = "workflow_dispatch") -> tuple[subprocess.CompletedProcess, Path, tempfile.TemporaryDirectory]:
        temporary = tempfile.TemporaryDirectory(prefix="remotecontrol-signing-shell.")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        repository = root / "repository"
        (repository / "scripts/ci").mkdir(parents=True)
        source = Path(__file__).resolve().parents[1]
        shutil.copyfile(source / "ci-testflight.sh", repository / "scripts/ci-testflight.sh")
        helper = (source / "ci/testflight_support.py").read_text()
        # Redirect only the copied helper's Path.home(), never the real HOME or
        # the production script. This makes profile install/restore a fixture.
        helper = helper.replace('if __name__ == "__main__":', 'if __name__ == "__main__":\n    Path.home = staticmethod(lambda: Path(os.environ["TEST_FIXTURE_ROOT"]) / "fixture-user")')
        (repository / "scripts/ci/testflight_support.py").write_text(helper)
        runner = root / "runner"
        runner.mkdir()
        binaries = root / "bin"
        binaries.mkdir()
        (binaries / "python3").symlink_to(sys.executable)
        for name in ("security", "xcodebuild", "xcodegen", "codesign", "openssl"):
            path = binaries / name
            path.write_text("#!" + sys.executable + "\n" + STUB_SOURCE)
            path.chmod(0o700)
        env = environment(runner)
        env.update({"PATH": str(binaries) + ":/usr/bin:/bin", "GITHUB_ACTIONS": "true", "RUNNER_OS": "macOS", "GITHUB_EVENT_NAME": event, "GITHUB_REF": "refs/heads/main", "GITHUB_OUTPUT": str(root / "output"), "TEST_FIXTURE_ROOT": str(root)})
        if failure:
            env["TEST_FAIL_UPLOAD"] = "1"
        result = subprocess.run(["/bin/bash", str(repository / "scripts/ci-testflight.sh")], env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        return result, root, temporary

    def test_shell_success_uses_manual_upload_and_cleans_all_private_state(self) -> None:
        result, root, _ = self.run_shell()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((root / "upload-called").exists())
        self.assertIn("build_number=3.34.1", (root / "output").read_text())
        self.assertEqual(list((root / "runner").iterdir()), [])
        self.assertEqual(json.loads((root / "search-list.json").read_text()), ["/fixture/original.keychain-db"])
        self.assertNotIn(PRIVATE_MARKER, result.stdout + result.stderr)

    def test_shell_failed_upload_is_safely_diagnosed_and_cleaned(self) -> None:
        result, root, _ = self.run_shell(failure=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ITMS-90189", result.stderr)
        self.assertNotIn(PRIVATE_MARKER, result.stdout + result.stderr)
        self.assertEqual(list((root / "runner").iterdir()), [])
        self.assertFalse((root / "upload-called").exists())

    def test_non_manual_event_is_rejected_before_tools_or_private_files(self) -> None:
        result, root, _ = self.run_shell(event="pull_request")
        self.assertEqual(result.returncode, 2)
        self.assertIn("workflow_dispatch on main", result.stderr)
        self.assertEqual(list((root / "runner").iterdir()), [])
        self.assertFalse((root / "overlay.json").exists())


if __name__ == "__main__":
    unittest.main()
