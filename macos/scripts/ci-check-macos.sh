#!/usr/bin/env bash
# Cloud-only checks for the Mac app. Local machines run the same steps by hand
# (macos/README.md § "Building and checking").
set -euo pipefail

if [[ "${GITHUB_ACTIONS:-}" != true || "${RUNNER_OS:-}" != macOS ]]; then
  echo 'Cloud macOS checks require a GitHub Actions macOS runner; no local SDK commands were run.' >&2
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
    echo "::error::macOS checks failed during $stage (exit $status). Download the macos-check artifact for logs and previews."
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
expected_developer_dir="/Applications/Xcode_${expected_xcode}.app/Contents/Developer"
[[ "${DEVELOPER_DIR:-}" == "$expected_developer_dir" ]] || fail "Set DEVELOPER_DIR to $expected_developer_dir."
[[ -x "$DEVELOPER_DIR/usr/bin/xcodebuild" ]] || fail "Pinned Xcode $expected_xcode is missing from this runner. Check the official runner-images inventory before updating the pins."

xcode_version="$(/usr/bin/xcodebuild -version)"
[[ "${xcode_version%%$'\n'*}" == "Xcode $expected_xcode" ]] || fail "Selected Xcode does not match the pinned version $expected_xcode."
{
  printf '%s\n' "$xcode_version"
  printf 'DEVELOPER_DIR=%s\nmacOS SDK=%s\n' "$DEVELOPER_DIR" "$(/usr/bin/xcrun --sdk macosx --show-sdk-version)"
  printf 'Runner image=%s\nRunner image version=%s\n' "${ImageOS:-unknown}" "${ImageVersion:-unknown}"
  /usr/bin/sw_vers
  /usr/bin/uname -m
  /usr/bin/xcrun swift --version
} | tee "$evidence_dir/toolchain.log"

run_logged swift-build /usr/bin/xcrun swift build
run_logged swift-testing /usr/bin/xcrun swift test
# Every scenario must render; the pictures are evidence, not a comparison.
run_logged previews /usr/bin/xcrun swift run RCMacPreview --demo --all --out "$evidence_dir/previews"

# The project is generated from project.yml, as the release is.
command -v xcodegen >/dev/null || brew install xcodegen
run_logged xcodegen xcodegen generate
run_logged app-build /usr/bin/xcodebuild \
  -project RemoteControl.xcodeproj -scheme RemoteControl -configuration Debug \
  -derivedDataPath build/DerivedData \
  -resultBundlePath "$evidence_dir/AppBuild.xcresult" \
  CODE_SIGNING_ALLOWED=NO build

stage=complete
echo 'Swift build, Swift Testing, every preview scenario and the unsigned app build passed.'
echo "Evidence: $evidence_dir"
