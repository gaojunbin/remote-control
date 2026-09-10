#!/usr/bin/env bash
# Cloud-only checks. Local machines do not need to install or invoke Xcode.
set -euo pipefail

if [[ "${GITHUB_ACTIONS:-}" != true || "${RUNNER_OS:-}" != macOS ]]; then
  echo 'Cloud iOS checks require a GitHub Actions macOS runner; no local SDK commands were run.' >&2
  exit 2
fi

cd "$(dirname "$0")/.."
evidence_dir="$PWD/build/CI"
mkdir -p "$evidence_dir"
exec > >(tee "$evidence_dir/ci.log") 2>&1

stage=preflight
finish() {
  local status=$?
  printf 'stage=%s\nexit_code=%s\n' "$stage" "$status" > "$evidence_dir/status.txt"
  if [[ "$status" -ne 0 ]]; then
    echo "::error::iOS checks failed during $stage (exit $status). Download the ios-check artifact for logs and available xcresult bundles."
  fi
}
trap finish EXIT

fail() {
  echo "::error::$*"
  exit 2
}

run_logged() {
  stage="$1"
  shift
  echo "::group::$stage"
  "$@" 2>&1 | tee "$evidence_dir/$stage.log"
  echo '::endgroup::'
}

expected_xcode="${RC_XCODE_VERSION:-26.6}"
expected_runtime="${RC_IOS_RUNTIME:-26.5}"
simulator_name="${RC_SIMULATOR_NAME:-iPhone 17}"
expected_developer_dir="/Applications/Xcode_${expected_xcode}.app/Contents/Developer"
[[ "${DEVELOPER_DIR:-}" == "$expected_developer_dir" ]] || fail "Set DEVELOPER_DIR to $expected_developer_dir."
[[ -x "$DEVELOPER_DIR/usr/bin/xcodebuild" ]] || fail "Pinned Xcode $expected_xcode is missing from this runner. Check the official runner-images inventory before updating the pins."
[[ -f RemoteControl.xcodeproj/xcshareddata/xcschemes/RemoteControl.xcscheme ]] || fail 'The committed shared RemoteControl scheme is missing.'

# Use the committed project: no mutable Homebrew/XcodeGen installation is needed.
xcode_version="$(/usr/bin/xcodebuild -version)"
[[ "${xcode_version%%$'\n'*}" == "Xcode $expected_xcode" ]] || fail "Selected Xcode does not match the pinned version $expected_xcode."
simulator_sdk="$(/usr/bin/xcrun --sdk iphonesimulator --show-sdk-version)"
[[ "$simulator_sdk" == "$expected_runtime" ]] || fail "Expected iOS Simulator SDK $expected_runtime; found $simulator_sdk. Check runtime and Xcode pins together."
{
  printf '%s\n' "$xcode_version"
  printf 'DEVELOPER_DIR=%s\niOS Simulator SDK=%s\n' "$DEVELOPER_DIR" "$simulator_sdk"
  printf 'Runner image=%s\nRunner image version=%s\n' "${ImageOS:-unknown}" "${ImageVersion:-unknown}"
  /usr/bin/sw_vers
  /usr/bin/uname -m
  /usr/bin/xcrun swift --version
} | tee "$evidence_dir/toolchain.log"

/usr/bin/xcrun simctl list runtimes --json > "$evidence_dir/runtimes.json"
/usr/bin/xcrun simctl list devices available --json > "$evidence_dir/devices.json"
device_id="$(python3 - "$evidence_dir/runtimes.json" "$evidence_dir/devices.json" "$expected_runtime" "$simulator_name" <<'PY'
import json
import sys

runtime_path, device_path, version, name = sys.argv[1:]
with open(runtime_path, encoding="utf-8") as source:
    runtimes = json.load(source)["runtimes"]
with open(device_path, encoding="utf-8") as source:
    devices = json.load(source)["devices"]
runtime_ids = {
    runtime["identifier"] for runtime in runtimes
    if runtime.get("isAvailable")
    and runtime.get("version") == version
    and runtime.get("identifier", "").startswith("com.apple.CoreSimulator.SimRuntime.iOS-")
}
matches = [
    device for runtime_id, rows in devices.items() if runtime_id in runtime_ids
    for device in rows if device.get("isAvailable") and device.get("name") == name
]
if not matches:
    sys.exit(f"Required available simulator {name} / iOS {version} was not found. See runtimes.json and devices.json; update the verified runner pins instead of silently selecting another OS.")
matches.sort(key=lambda device: (device.get("state") != "Booted", device["udid"]))
print(matches[0]["udid"])
PY
)"
printf 'name=%s\niOS=%s\nudid=%s\n' "$simulator_name" "$expected_runtime" "$device_id" | tee "$evidence_dir/destination.txt"

run_logged ios-simulator-build /usr/bin/xcodebuild \
  -project RemoteControl.xcodeproj -scheme RemoteControl -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/DerivedData \
  -resultBundlePath "$evidence_dir/SimulatorBuild.xcresult" \
  CODE_SIGNING_ALLOWED=NO build

# Swift Testing runs on the macOS host; the next stage runs the actual iOS UI tests.
run_logged swift-testing /usr/bin/xcrun swift test
run_logged core-verification /usr/bin/xcrun swift run RCVerify
run_logged ui-state-verification /usr/bin/xcrun swift run RCUIVerify

# bootstatus boots the chosen device if necessary and waits until it is usable.
run_logged simulator-boot /usr/bin/xcrun simctl bootstatus "$device_id" -b
run_logged ios-ui-tests /usr/bin/xcodebuild \
  -project RemoteControl.xcodeproj -scheme RemoteControl -configuration Debug \
  -destination "platform=iOS Simulator,id=$device_id" \
  -destination-timeout 120 \
  -parallel-testing-enabled NO \
  -derivedDataPath build/DerivedData \
  -resultBundlePath "$evidence_dir/UITests.xcresult" \
  -only-testing:RemoteControlUITests \
  CODE_SIGNING_ALLOWED=NO test

stage=complete
echo 'iOS simulator build, Swift Testing, core/UI-state verification, and the complete RemoteControlUITests target passed.'
echo "Evidence: $evidence_dir"
