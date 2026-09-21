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

run_logged ios-simulator-build /usr/bin/xcodebuild \
  -project RemoteControl.xcodeproj -scheme RemoteControl -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/DerivedData \
  -resultBundlePath "$evidence_dir/SimulatorBuild.xcresult" \
  CODE_SIGNING_ALLOWED=NO build

# Swift Testing and both verifiers run on the macOS host. The RemoteControlUITests
# target is not run here: its accessibility snapshots time out on GitHub's shared
# simulators (docs/IOS.md § "TestFlight"), so the whole target is the local gate of
# every round instead.
run_logged swift-testing /usr/bin/xcrun swift test
run_logged core-verification /usr/bin/xcrun swift run RCVerify
run_logged ui-state-verification /usr/bin/xcrun swift run RCUIVerify

stage=complete
echo 'iOS simulator build, Swift Testing and core/UI-state verification passed.'
echo "Evidence: $evidence_dir"
