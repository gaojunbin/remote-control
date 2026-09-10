# The iPhone app

A native SwiftUI client with the same four screens and the same interaction model as the web app.
iOS 17 and later, no third-party dependencies. It speaks `protocol/PROTOCOL.md` and talks only to
the gateway.

## Structure

| Path | What it holds |
| --- | --- |
| `Package.swift` | swift-tools-version 6.0, iOS 17+, macOS 14+ |
| `Sources/RCCore/` | Foundation only: protocol types, transport, state, persistence, Markdown, demo data |
| `Sources/RCUI/` | SwiftUI: design tokens, screens, Markdown rendering, voice, push, security, attachments |
| `Sources/RCPreview/` | A macOS host that runs the demo, for iterating on a screen without a simulator |
| `App/` | `@main`, `Info.plist`, entitlements, privacy manifest, assets, `Localizable.xcstrings` |
| `Verification/`, `VerificationUI/` | `RCVerify` and `RCUIVerify`, the executable check suites |
| `Tests/`, `UITests/` | swift-testing suites and an XCUITest smoke against the demo |
| `project.yml`, `RemoteControl.xcodeproj` | The xcodegen spec and the committed project |

`RCCore` imports nothing but Foundation and Observation, which is what lets the whole core
regression suite run in seconds with only Command Line Tools installed.

Bundle id `com.junbingao.remotecontrol`, display name "Remote Control", URL scheme
`remotecontrol://`.

## Building and checking

```sh
cd ios

swift run RCVerify        # protocol fixtures and reducer rules; Command Line Tools are enough

export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
swift run RCUIVerify
swift test
xcodegen generate
xcodebuild -project RemoteControl.xcodeproj -scheme RemoteControl \
  -destination 'generic/platform=iOS Simulator' -configuration Debug \
  CODE_SIGNING_ALLOWED=NO build
```

Set `DEVELOPER_DIR` per process; never change the global selection with `sudo xcode-select`.
`RCVerify` reads `../protocol/fixtures` directly, so a contract change is checked on the next run
without copying anything.

UI tests need a booted simulator:

```sh
xcrun simctl bootstatus <udid> -b
xcodebuild -project RemoteControl.xcodeproj -scheme RemoteControl -configuration Debug \
  -destination "platform=iOS Simulator,id=<udid>" -parallel-testing-enabled NO \
  -only-testing:RemoteControlUITests CODE_SIGNING_ALLOWED=NO test
```

## Running against a gateway

The simulator shares the Mac's network, so sign in with `http://127.0.0.1:8787` against a gateway
running from source. Plain HTTP is accepted only for loopback, `.local` names and RFC 1918
addresses; anything else must be https, because a bearer token rides on every request. From a
physical iPhone, use the Mac's LAN address or the real deployed origin.

Deep link into a session:

```sh
xcrun simctl openurl booted "remotecontrol://session?device=<id>&id=<session_id>"
```

## Demo mode

`--demo`, or "Try the demo" on the login screen, installs an in-memory gateway that serves the
protocol from typed fixtures and scripts a live turn. It never constructs a transport, so the demo
cannot reach the network even by accident. Every SwiftUI preview and the XCUITest smoke run on it.

Other launch arguments: `--ui-testing`, `--reset-state`, and in debug builds `--voice-preview`,
which swaps in a scripted speech platform so a UI test never opens the microphone.

## Voice

Two backends, chosen in Settings:

- **On this iPhone** — `SFSpeechRecognizer` with on-device recognition. Audio never leaves the phone
  and is never written to disk. It needs a downloaded model for the chosen language and reports
  "unsupported" when there is none.
- **Gateway** — PCM16LE at 16 kHz over `WS /ws/stt`, converted from the hardware format with
  `AVAudioConverter`. Audio goes to your own gateway. The settings screen states the difference.

Either way dictation only fills the draft. Sending stays a separate, explicit tap.

## Connection lifecycle

| Close code | What the app does |
| --- | --- |
| 4401 | Forgets the keychain token, keeps the cached lists, returns to login with "Your session expired" |
| 4403 | The same teardown, with "This gateway refused the connection" |
| 4001 | Stops reconnecting and offers a manual Reconnect |
| anything else | Reconnects with a 1/2/4/8/15 s backoff |

A request in flight when the socket drops is reported as uncertain, never resent automatically; the
retry reuses the original request id.

The bearer token lives in the Keychain, device-only and never synchronised. It is never written to
defaults, a log, a diagnostic report or a URL. The diagnostic report is built from an explicit
allowlist rather than by serialising and redacting.

## TestFlight

`.github/workflows/ios-check.yml` runs on every push touching `ios/` or `protocol/`. It pins
`macos-26` with Xcode 26.6 and the iOS 26.5 simulator runtime, then runs the unsigned simulator
build, `swift test`, both verification executables and the UI test target.

`.github/workflows/ios-testflight.yml` is `workflow_dispatch` on `main` only. It reuses the check
workflow, then archives, signs and uploads. The signing script refuses to run outside that context,
creates a temporary keychain with a random password, validates the profile and key before importing
anything, and removes the keychain on exit. Signing cannot be exercised locally by design.

Required repository secrets:

| Name | What it is |
| --- | --- |
| `BUILD_CERTIFICATE_BASE64` | Apple Distribution certificate with its private key, exported as `.p12`, base64 |
| `P12_PASSWORD` | The export password for that `.p12` |
| `BUILD_PROVISION_PROFILE_BASE64` | App Store distribution `.mobileprovision` for the bundle id, base64 |
| `ASC_KEY_ID` | App Store Connect API key id |
| `ASC_ISSUER_ID` | App Store Connect issuer id |
| `ASC_PRIVATE_KEY_BASE64` | The API key `.p8`, base64. A different key from the APNs auth key |

Variables: `APPLE_TEAM_ID`, and optionally `BUNDLE_ID`.

## Validation

`UITests/RealGatewaySmokeTests.swift` drives the app in a simulator against a real gateway, device
daemon and CLIs. It skips unless the runner is given a gateway, so the default UI test run stays
offline. Four tests pass: signing in and driving a real Claude session end to end, the Devices tab
listing the enrolled machine with its detected agents, Settings naming the live gateway, and the
gateway address surviving a background, terminate and relaunch. Details in
`docs/VALIDATION-APPS.md`.

## Not verified

Everything beyond those four tests ran only against the offline demo: new session, add device, the
directory picker, voice and push. APNs delivery and gateway speech-to-text have never been
exercised, the app has never run on a physical device, and dark mode and VoiceOver have not been
reviewed — the palette defines dark values, but v1 is designed light. CI, signing and TestFlight
upload have never run.

Keychain restore is a harness limitation rather than an open question about the code.
`CODE_SIGNING_ALLOWED=NO` produces an ad-hoc, linker-signed app with no `application-identifier`
entitlement, so `SecItemAdd` cannot store the token and the relaunch test asserts only that the
gateway address survives. Nothing suggests the keychain path is broken on a signed build; it cannot
be exercised without one.
