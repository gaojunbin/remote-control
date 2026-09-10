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

## The session list

Two groups, and the rule that splits them lives in `RCCore` as
`SessionListLayout.build(sessions:devices:query:showsArchived:)`, a pure function of its arguments.
`SessionsView` only renders what comes back. `docs/DESIGN.md` states the rule; the web app
implements the same one.

**Active** is what a CLI or the device still holds: `archived` is false and `control` is `remote`,
`terminal` or `shared`. **Archive** is everything else. `control: "none"` means the CLI exited and
nothing owns the session, so it lands there whatever its state, and a hand-archived session joins it
only while the archive toggle in the navigation bar is on. Nothing is removed from the protocol:
`session.archive` and the swipe action stay where they were.

| Rule | Where |
| --- | --- |
| Active keeps its device grouping, in the order the gateway lists devices | `SessionListLayout.build` |
| Inside a device: `needs_approval` and `needs_input`, then `running` and `starting`, then the rest, each by `updated_at` descending | `SessionListLayout.urgency` |
| Archive is flat, `updated_at` descending, the device carried on the row | `ArchivedSession` |
| A device with nothing open keeps its caption and a muted "No open sessions" line | `DeviceSessionGroup` with no sessions |
| Open or collapsed persists in `UserDefaults` under `sessions.archiveExpanded` | `SessionStore.isArchiveExpanded` |
| A search whose match is inside the Archive opens it without touching that preference | `SessionList.forcesArchiveOpen` |
| Search reads the title, the working directory and the agent, and drops a device with no match | `SessionListLayout.matches` |

A row is two lines: the title and the relative time on the first, then a dot, the status word, and
the working directory on the second. An archived row adds the device between the two. Nothing is
right-aligned into a second column, because a column of statuses reads as a table.

`Tests/RCCoreTests/SessionGroupingTests.swift` covers the rule on hand-built sessions;
`Verification/StoreChecks.swift` covers it against the demo fixtures.

## Surfaces and type

Tokens first, screens second. Everything visual comes from `Sources/RCUI/Design/Theme.swift`:

| Token | What it is | Used for |
| --- | --- | --- |
| `Theme.hairline` | ink at 9 % | the only line allowed inside a surface, never after its last row |
| `Theme.quietFill` | ink at 6 % | the fill behind every chip and quiet button |
| `Theme.Text.title` | `.callout` semibold | a session or device name |
| `Theme.Text.label` | `.callout` | a settings label, where the control beside it is the point |
| `Theme.Text.meta` | `.footnote` | the status word and the line under a title |
| `Theme.Text.caption` | `.caption` | a time, a value, a supporting line |
| `Theme.Text.groupHeader` | `.caption` semibold, kerned, uppercased | the caption above a group |
| `Theme.Text.metaMono` | `.caption` monospaced | a path or a branch inside a row |

`softSurface()` is one rounded fill with no border and no shadow; `card()` keeps a hairline border
for the surfaces that still need an edge, an approval card among them, and no longer carries a
shadow as well. `ChipButtonStyle` is tinted rather than outlined, so the Todos chip, the composer's
model and permission chips, "Take over" and "Back to latest" all lost their borders in one place.
`sessionRowLayout()` and `settingsRowLayout()` hold the row insets, so Sessions and Devices share
one rhythm and Settings shares another.

Settings reads like iOS grouped settings: an uppercase caption, one soft surface, label left and
control right, and the explanation as a footnote under the group rather than inside it. A monospace
value truncates in the middle, so a gateway origin keeps its scheme and its host.

## Attached terminal sessions

Amendment A10 adds `control: "shared"`: a live CLI owns the session and the device is attached to
it, so the app types into the same conversation instead of taking it over.

| Session | Composer | Take over | Stop | Model, permissions, effort | Attachments |
| --- | --- | --- | --- | --- | --- |
| `remote` | enabled | no | when a turn runs | yes | yes |
| `shared` | enabled | never | only with capability `interrupt` and `shared_interrupt: true` | only with `shared_settings: true` | only with `shared_attachments: true` |
| `terminal` | disabled | when the agent has `takeover` | no | yes | no |
| `none` | enabled, the next send resumes the session | no | no | yes | yes |

`Session.isControlledByTerminal` stays false for `shared`; `Session.isAttached` is the new flag, and
`ChatStore.isAttached` mirrors it. The chat status line reads `terminal · attached`, plus
`· working` or `· N messages waiting` while the terminal's turn runs, and the session list shows the
same words through `Session.statusLabel` with the dot colour a remote session would get.

Two controls can be inert on a `shared` session, and one quiet line above the message field says who
owns them: "Attached to the terminal · settings and attachments are changed there". The attachment
button and the model and permission chips are dimmed; reaching for one swaps that line for its own
sentence for four seconds ("Attachments cannot be delivered to a terminal session", "Change it in
the terminal") rather than swallowing the tap. The same sentences are the accessibility hints on
those controls and the footers of the session settings sheet.

Whether either is inert is the device's call, not the app's — see the two booleans below. The line
names only what this attachment cannot do, so it shrinks to "Attached to the terminal · settings are
changed there", "…· attachments are added there", or just "Attached to the terminal" when the
attachment carries both.

A message sent into a `shared` session may be held by the device until the terminal-driven turn
ends. `user_message` then carries `delivery`, and the bubble shows a chip: `pending` reads "waiting
for the terminal" and `absorbed` reads "will be re-sent". The device replaces the same `block_id`
when the message goes in, so the chip disappears on its own. The chip lives inside a combined
accessibility element, so its words are appended to the bubble's label as well.

A `terminal` session whose agent reports an `attach` method gets one line under the takeover bar,
driven by `ChatStore.attachHint`:

| Case | Line |
| --- | --- |
| `attach: "channel"`, `attach_ready: false` | Start claude through the remote-control shim to control it from here |
| `attach: "daemon"`, `attach_ready: false` | Start the Codex app-server daemon on this device to control it from here |
| `attach_ready: true` | This terminal session was started without the attachment; restart it to control it from here |

Nothing is offered that the device cannot do: `session.takeover` is never shown on a `shared`
session, Stop is hidden unless the agent's capabilities include `interrupt` *and* the device reports
`shared_interrupt: true` (a Claude channel cannot interrupt a running turn, so it reports false even
though Claude lists `interrupt`), and "Take over" appears on a `terminal` session only when the
agent's capabilities include `takeover`.

The demo carries both cases: `demo-session-shared` on `mac-studio-office`, whose Claude reports
`attach: "channel"`, `attach_ready: true`, `shared_interrupt: false`, and `demo-session-rename` on
`macbook-air`, whose Claude has no shim installed. Sending into the shared session shows the message
held, then delivered, then a relayed permission request with exactly Allow and Deny.

## What a shared attachment carries

Amendment A11 adds two more optional booleans to the agent object, both defaulting to false, so a
device that never heard of them grants nothing:

| Field | What it opens on a `shared` session | `ChatStore` |
| --- | --- | --- |
| `shared_settings` | the model, permission and effort pickers, and the session settings sheet with them | `allowsSettingsChanges` |
| `shared_attachments` | the attachment button, so photos and files go into the live thread | `allowsAttachments` |

Both are read straight off `AgentInfo`; nothing in the app branches on the agent id. Stop is
unaffected and still needs capability `interrupt` plus `shared_interrupt`. A `terminal` session
takes no input whatever it reports, so `allowsAttachments` stays false there.

Codex behind a running app-server daemon reports `attach: "daemon"`, `attach_ready: true` and all
three booleans true; a Claude channel reports all three false. A device whose daemon is not running
reports `attach_ready: false`, and the composer falls back to the `startDaemon` hint above.

Approval cards render whatever `options` arrive, so the daemon's four decisions (Allow, Allow for
this session, Always allow commands like this, Deny) stack between the primary and the danger
button with no extra work. When someone else answers a shared request first, the block resolves
with the reserved option id `elsewhere`. There is no option to name, so the card reads "answered in
the terminal" alone. `ApprovalPayload.resolvedOptionLabel` is what decides that: it returns nil for
`elsewhere` and renders any other unrecognised id verbatim rather than blanking the card. The app
never offers or sends `elsewhere`.

A message sent into a running turn is queued behind it, unless the agent lists capability `steer`,
in which case it joins the turn already running. `ChatStore.steersRunningTurn` decides both the
status line ("Working · your message will steer the turn" rather than "· will be queued") and the
composer placeholder ("Message · will steer the turn"), on a `remote` session and a `shared` one
alike. Claude does not list `steer`, so its wording is unchanged. The send modes stay the protocol's
`auto | queue | interrupt`: on a running shared thread `auto` comes back `steered`, `queue` comes
back `queued` with the message held, and `interrupt` ends the turn and starts a new one.

`session.approve` sends back only an option the block offered. An id it did not offer, `elsewhere`
included, is `bad_request`; the app never renders `elsewhere` as a choice.

The demo carries `demo-session-typecheck` on `mac-studio-office`: a Codex thread the terminal
started and the daemon shares, running, with Stop in the navigation bar, all three chips live and a
four-option request in the transcript. Changing the effort there goes through `session.set` and is
applied; the same request on the attached Claude session is still refused. `ci-runner-01` keeps a
Codex with no daemon running, so the daemon hint has a home too.

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
