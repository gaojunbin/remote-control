# ios/

The native iPhone client for remote-control. It speaks the same wire protocol as
the web app (`protocol/PROTOCOL.md`), talks only to the gateway, and never
reaches a device directly.

## Layout

| Path | What it holds |
| --- | --- |
| `Package.swift` | swift-tools-version 6.0, iOS 17+/macOS 14+, no third-party dependencies |
| `Sources/RCCore/` | Foundation only: `Protocol/`, `Transport/`, `State/`, `Persistence/`, `Markdown/`, `Demo/` |
| `Sources/RCUI/` | SwiftUI: `Design/`, `Screens/`, `Markdown/`, `Voice/`, `Push/`, `Security/`, `Attachments/`, `Demo/`, `Resources/Markdown/` |
| `Sources/RCPreview/` | A macOS host that runs the demo, for iterating on a screen without a simulator |
| `App/` | `@main`, Info.plist, entitlements, `PrivacyInfo.xcprivacy`, assets, `Localizable.xcstrings` |
| `Verification/` | `RCVerify`: fixture decoding, reducer rules, endpoint rules, cache versioning, Markdown |
| `VerificationUI/` | `RCUIVerify`: app model, navigation, push reconciliation, dictation, resources |
| `Tests/RCCoreTests/` | swift-testing suites over the same rules |
| `UITests/` | XCUITest smoke against the offline demo |
| `scripts/` | Icon generator and the CI/signing scripts |
| `project.yml`, `RemoteControl.xcodeproj` | xcodegen spec and the committed project with a shared scheme |

`RCCore` deliberately imports nothing but Foundation and Observation. That is
what lets the whole core regression suite run in seconds with only Command Line
Tools installed, no Xcode and no simulator.

## Building and checking

```
cd ios

# Core suite. Works with plain Command Line Tools.
swift run RCVerify

# Everything else needs a real Xcode toolchain. Set it per process; never
# change the global selection with sudo xcode-select.
export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
swift run RCUIVerify
swift test

xcodegen generate
xcodebuild -project RemoteControl.xcodeproj -scheme RemoteControl \
  -destination 'generic/platform=iOS Simulator' -configuration Debug \
  CODE_SIGNING_ALLOWED=NO build
```

The UI test target runs against a booted simulator:

```
xcrun simctl bootstatus <udid> -b
xcodebuild -project RemoteControl.xcodeproj -scheme RemoteControl -configuration Debug \
  -destination "platform=iOS Simulator,id=<udid>" -parallel-testing-enabled NO \
  -only-testing:RemoteControlUITests CODE_SIGNING_ALLOWED=NO test
```

`RCVerify` reads `../protocol/fixtures` directly, so a fixture the contract
agent adds is checked on the next run without copying anything into this
directory.

## Running against a local gateway

The simulator shares the Mac's network stack, so `127.0.0.1` reaches a gateway
running on the same machine.

1. Start the gateway: `cd gateway && uv sync && uv run rc-gateway` (port 8787).
2. Launch the app in the simulator and sign in with `http://127.0.0.1:8787`.
   Plain http is accepted only for loopback, `.local` names and RFC 1918
   addresses; every other host must be https, because a bearer token rides on
   every request.
3. From a physical iPhone, use the Mac's LAN address (`http://192.168.x.x:8787`)
   with the Mac and the phone on the same network, or the real
   `https://` origin once the gateway is deployed.

Deep link into a session with
`xcrun simctl openurl booted "remotecontrol://session?device=<id>&id=<session_id>"`.

## Connection lifecycle

The app reads the WebSocket close code and decides what to do with it
(amendment A4):

| Code | What the app does |
| --- | --- |
| 4401 | Forgets the keychain token, keeps the cached lists, returns to the login screen with "Your session expired" |
| 4403 | Same teardown, with "This gateway refused the connection" |
| 4001 | Stops reconnecting and shows "Another app took over this connection" with a Reconnect action. Retrying on our own would just fight whatever replaced us |
| anything else | Reconnects with a 1/2/4/8/15 s backoff |

A request that was in flight when the socket dropped is reported as
`deliveryUncertain`, never resent automatically. The retry reuses the original
request id so the device recognises the duplicate.

## Demo mode

`--demo` (or "Try the demo" on the login screen) installs `DemoGateway`, an
in-memory gateway that serves the protocol from typed fixtures and scripts a
live turn. It never constructs a transport, so the demo cannot reach the network
even by accident. Every `#Preview` and the XCUITest smoke run on it.

Launch arguments: `--demo`, `--ui-testing`, `--reset-state`, and (debug builds
only) `--voice-preview`, which swaps a scripted speech platform in so the UI
test never opens the microphone.

## Voice

Two backends, chosen in Settings:

- **On this iPhone** — `SFSpeechRecognizer` with on-device recognition. Audio
  never leaves the phone and is never written to disk. Needs a downloaded model
  for the chosen language, and reports "unsupported" when there is none.
- **Gateway** — PCM16LE, 16 kHz mono frames over `WS /ws/stt`, converted from
  the hardware format with `AVAudioConverter`. Audio is uploaded to your own
  gateway. This is a different privacy story and the settings screen says so.

Either way dictation only ever fills the draft. Sending stays a separate,
explicit tap.

## Icon

```
swift scripts/make-icon.swift App/Assets.xcassets/AppIcon.appiconset/AppIcon.png
```

The script asserts the drawing is fully opaque and that the encoder produced an
RGB PNG: App Store icons must not carry an alpha channel.

## Continuous integration

`.github/workflows/ios-check.yml` runs on every push and pull request that
touches `ios/` or `protocol/`. It pins `macos-26` with Xcode 26.6 and the iOS
26.5 simulator runtime, then runs the signing-helper unit tests and
`scripts/ci-check-ios.sh`, which asserts the pins, picks an available
`iPhone 17` (failing rather than silently choosing another OS), and runs the
unsigned simulator build, `swift test`, `RCVerify`, `RCUIVerify` and the whole
`RemoteControlUITests` target. Logs and `.xcresult` bundles are uploaded as an
artifact.

`.github/workflows/ios-testflight.yml` is `workflow_dispatch` on `main` only. It
reuses the check workflow, then archives, signs and uploads.

**Secrets** (repository settings → Secrets and variables → Actions):

| Name | What it is |
| --- | --- |
| `BUILD_CERTIFICATE_BASE64` | Apple Distribution certificate **with its private key**, exported as `.p12` and base64 encoded |
| `P12_PASSWORD` | The password used when exporting that `.p12` |
| `BUILD_PROVISION_PROFILE_BASE64` | App Store distribution `.mobileprovision` for the bundle id, base64 encoded |
| `ASC_KEY_ID` | App Store Connect API key id |
| `ASC_ISSUER_ID` | App Store Connect issuer id |
| `ASC_PRIVATE_KEY_BASE64` | The API key `.p8`, base64 encoded. This is a different key from the APNs auth key |

**Variables**: `APPLE_TEAM_ID`, and optionally `BUNDLE_ID` (defaults to
`com.junbingao.remotecontrol`).

The signing script refuses to run outside a `workflow_dispatch` GitHub Actions
macOS job on `main`, creates a temporary keychain with a random password,
validates the profile and key before importing anything, unsets every secret
environment variable before invoking `xcodegen`, and removes the keychain on
exit. Signing and upload cannot be exercised locally by design.

## Notes for whoever picks this up

- The composer is gated on `Session.control`, never on `Session.state`
  (amendment A7). A terminal session reports `running` while its turn runs and
  `readonly` only when idle; both are locked to this app, and both show
  "Controlled by the terminal · Take over".
- A block's position in the transcript is `first_seq ?? seq` (amendment A8), so
  a streaming answer holds its place while its `seq` rises. History cursors use
  the raw event seq instead, so paging cannot skip an event.
- The cache schema version (`LocalCache.schemaVersion`) is deliberately separate
  from the wire protocol version. A protocol bump must not throw away every
  cached transcript and draft.
- The bearer token lives in the keychain, device-only and never synchronised. It
  is never written to defaults, a log, a diagnostic report or a URL.
- The diagnostic report is built from an explicit allowlist. Do not switch it to
  "serialize the store and redact afterwards".
- The transcript keeps its scroll anchor across a "Load earlier messages" tap,
  but not across a resync or a relaunch. A true reading anchor needs per-row
  visibility tracking (`onScrollGeometryChange`, iOS 18, or a preference-key
  sweep); this build does neither, and the unused `ReadingRestoration` state
  machine was removed rather than left as dead code.
- `Localizable.xcstrings` lives in the **app** target on purpose: SwiftUI
  resolves `Text("…")` against `Bundle.main`, so strings inside the `RCUI`
  package are localizable without touching every call site.
