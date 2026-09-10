#!/usr/bin/env bash
# Official references (checked 2026-09-07):
# https://docs.github.com/en/actions/how-tos/deploy/deploy-to-third-party-platforms/sign-xcode-applications
# https://developer.apple.com/documentation/xcode-release-notes/xcode-15-release-notes
# https://developer.apple.com/videos/play/wwdc2021/10204/
# Run only on the protected, manually dispatched GitHub Actions macOS job.
set +x
set -euo pipefail
umask 077

script_root="$(cd "$(dirname "$0")/.." && pwd)"
helper="$script_root/scripts/ci/testflight_support.py"
export BUNDLE_ID="${BUNDLE_ID:-com.junbingao.remotecontrol}"
export GITHUB_RUN_ATTEMPT="${GITHUB_RUN_ATTEMPT:-1}"

fail() { printf '::error::%s\n' "$1" >&2; exit 2; }

case "${1:-}" in
  --preflight) python3 "$helper" preflight; exit 0 ;;
  --cleanup) python3 "$helper" cleanup; exit 0 ;;
  '') ;;
  *) fail 'Usage: scripts/ci-testflight.sh [--preflight|--cleanup]' ;;
esac

# Missing/invalid values are reported by name, never by their secret contents.
build_number="$(python3 "$helper" preflight)"
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == macOS ]] || fail 'Signing/upload requires a GitHub Actions macOS runner.'
[[ "${GITHUB_EVENT_NAME:-}" == workflow_dispatch && "${GITHUB_REF:-}" == refs/heads/main ]] || fail 'Signing/upload is restricted to workflow_dispatch on main.'
for tool in security xcodebuild xcodegen codesign openssl python3; do
  command -v "$tool" >/dev/null || fail "Missing required runner tool: $tool"
done

cleanup_signing() {
  local original_status=$?
  trap - EXIT INT TERM
  if ! python3 "$helper" cleanup; then
    printf '::error::Temporary signing cleanup requires attention; run --cleanup in an always step.\n' >&2
    original_status=1
  fi
  exit "$original_status"
}
trap cleanup_signing EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

signing_dir="$(python3 "$helper" prepare)"
private_step() {
  local label=$1
  shift
  if ! "$@" > "$signing_dir/last-step.log" 2>&1; then
    python3 "$helper" diagnostics "$script_root" || true
    fail "$label failed; raw private signing/upload diagnostics are not published."
  fi
}

# This inspection happens in the cloud only. A tool lacking the current upload
# contract fails before importing credentials into the temporary keychain.
private_step 'Xcode capability inspection' xcodebuild -help
python3 - "$signing_dir/last-step.log" <<'PY'
import pathlib, sys
help_text = pathlib.Path(sys.argv[1]).read_text()
required = ['app-store-connect', '-authenticationKeyPath', '-authenticationKeyID', '-authenticationKeyIssuerID']
if not all(value in help_text for value in required):
    sys.exit('Selected Xcode does not expose the required App Store Connect upload contract.')
PY
if ! security cms -D -i "$signing_dir/profile.mobileprovision" > "$signing_dir/profile.plist" 2> "$signing_dir/last-step.log"; then
  fail 'Provisioning profile decoding failed.'
fi
python3 "$helper" profile
private_step 'ASC signing key validation' openssl pkey -in "$signing_dir/AuthKey.p8" -noout

keychain_path="$signing_dir/signing.keychain-db"
keychain_password="$(cat "$signing_dir/keychain.password")"
private_step 'Temporary keychain creation' security create-keychain -p "$keychain_password" "$keychain_path"
private_step 'Temporary keychain settings' security set-keychain-settings -lut 21600 "$keychain_path"
private_step 'Temporary keychain unlock' security unlock-keychain -p "$keychain_password" "$keychain_path"
private_step 'Apple Distribution identity import' security import "$signing_dir/certificate.p12" -k "$keychain_path" -P "$P12_PASSWORD" -t cert -f pkcs12 -T /usr/bin/codesign -T /usr/bin/security
private_step 'Code signing key access' security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$keychain_password" "$keychain_path"
private_step 'Distribution identity inspection' security find-identity -v -p codesigning "$keychain_path"
cp "$signing_dir/last-step.log" "$signing_dir/identities.txt"
python3 "$helper" identity
python3 "$helper" activate
python3 "$helper" install-profile
python3 "$helper" export-options
python3 "$helper" project-spec "$script_root"
unset BUILD_CERTIFICATE_BASE64 BUILD_PROVISION_PROFILE_BASE64 ASC_PRIVATE_KEY_BASE64 P12_PASSWORD keychain_password

cd "$script_root"
# The include preserves the checked-in project. Only the RemoteControl Release target
# settings are overlaid: package resource bundles never inherit an app profile.
private_step 'Project generation' xcodegen generate --spec "$signing_dir/project-signing.json" \
  --project "$script_root" --project-root "$script_root"
archive_path="$signing_dir/RemoteControl.xcarchive"
private_step 'Signed Release archive' xcodebuild -quiet -project RemoteControl.xcodeproj -scheme RemoteControl \
  -configuration Release -destination 'generic/platform=iOS' \
  -archivePath "$archive_path" -derivedDataPath "$signing_dir/DerivedData" archive

applications=("$archive_path/Products/Applications/"*.app)
[[ ${#applications[@]} -eq 1 && -d "${applications[0]}" ]] || fail 'Archive must contain exactly one iOS app.'
private_step 'Archived code signature verification' codesign --verify --deep --strict "${applications[0]}"
if ! codesign -d --entitlements :- "${applications[0]}" > "$signing_dir/signed-entitlements.plist" 2>/dev/null; then
  fail 'Could not inspect archived app entitlements.'
fi
python3 "$helper" archive "${applications[0]}"

# Xcode 15+ officially supports API-key-authenticated upload. Provisioning stays
# manual and pinned to the installed identity/profile in ExportOptions.plist.
private_step 'App Store Connect upload' xcodebuild -exportArchive \
  -archivePath "$archive_path" -exportPath "$signing_dir/Export" \
  -exportOptionsPlist "$signing_dir/ExportOptions.plist" \
  -authenticationKeyPath "$signing_dir/AuthKey.p8" \
  -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID" \
  -allowProvisioningUpdates

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf 'build_number=%s\nupload_status=uploaded\n' "$build_number" >> "$GITHUB_OUTPUT"
fi
printf 'Uploaded build %s to App Store Connect for TestFlight processing.\n' "$build_number"
