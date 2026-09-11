# The iPhone app

A native SwiftUI client with the same four screens and the same interaction model as the web app.
iOS 18 and later, no third-party dependencies. It speaks `protocol/PROTOCOL.md` and talks only to
the gateway.

## Structure

| Path | What it holds |
| --- | --- |
| `Package.swift` | swift-tools-version 6.0, iOS 18+, macOS 15+ |
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
which swaps in a scripted speech platform so a UI test never opens the microphone. The listening
state, the full-screen glow included, is screenshotted through it.

## The session list

One group per device, and the rule that builds them lives in `RCCore` as
`SessionListLayout.build(sessions:devices:deviceFilter:agentFilter:query:collapsedDevices:archiveExpanded:)`,
a pure function of its arguments that returns `[DeviceGroup]`. `SessionsView` only renders what
comes back. `docs/DESIGN.md` states the rule; the web app implements the same one.

A device is listed when at least one of its sessions passes the filters, and is not rendered at
all otherwise. Inside the group come the sessions something still holds, then that device's own
**Archive**: `control: "none"` means the CLI exited and nothing owns the session, so it lands
there whatever its state, and a hand-archived session joins it whatever still owns it, with a
small "Archived" mark so the two are told apart. Nothing is removed from the protocol:
`session.archive` and the swipe action stay where they were.

| Rule | Where |
| --- | --- |
| A machine with a live session leads; the rest follow, each by its most recent activity | `SessionListLayout.build` |
| Inside a device: `needs_approval` and `needs_input`, then `running` and `starting`, then the rest, each by `updated_at` descending | `SessionListLayout.urgency` |
| The Archive is flat, `updated_at` descending, and its header is not rendered when it is empty | `DeviceGroup.archive` |
| A whole group folds away on a tap, and stays folded: `UserDefaults` `sessions.collapsedDevices`, `[String]` of device ids | `SessionStore.toggleCollapsed` |
| Each Archive opens on a tap, and stays open: `sessions.archiveExpanded`, `[String]` of device ids | `SessionStore.toggleArchive` |
| A search unfolds every group it matched, and opens an Archive whose row matched, without touching either preference | `SessionListLayout.build` |
| Search reads the title, the working directory and the agent, by id and by label, and drops a machine with no match | `SessionListLayout.matches` |
| An agent filter applies before the grouping, so a machine whose sessions all drop out disappears | `SessionStore.agentFilter` |

The agent filter sits in the navigation bar as `All · Claude Code · Codex`, offering only the
agents the list actually contains. It is a view of the list rather than a setting: it starts at
All on every launch and is never written to defaults. The device filter is a parameter of the
same function, for the callers that narrow to one machine.

A row is two lines: the title and the relative time on the first, then the agent chip, a dot, the
status word and the working directory on the second. The path is the only part that gives way
when the line is tight, and it truncates from the head so the folder survives. Nothing is
right-aligned into a second column, because a column of statuses reads as a table.

`Tests/RCCoreTests/SessionGroupingTests.swift` covers the rule on hand-built sessions;
`Verification/StoreChecks.swift` covers it against the demo fixtures.

## New session

Device, agent, working directory and git, and nothing else: the sheet does not ask for a first
message, so `session.create` goes out without `first_message`. The protocol keeps the field.
Field labels there are sentence case through `FormLabel`, because a form label is read as a word;
`FieldLabel` stays the uppercase eyebrow above a settings group.

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
model and permission chips and "Take over" all lost their borders in one place.
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

## Reading position

The transcript follows the newest content only while the reader is at the foot of it, and
`ChatStore.isFollowingTail` means exactly that and nothing else. `onScrollGeometryChange` reports
the scroll view's numbers, `ScrollTail.isAtBottom(contentHeight:containerHeight:offset:threshold:)`
in `RCCore` turns them into the answer, and 40 pt of slack is what keeps a row settling a couple of
points short from counting as leaving.

| What happens | What the timeline does |
| --- | --- |
| A block arrives while at the bottom | Scrolls to the tail, animated |
| A block arrives while away | Stays put and counts it in `updatesWhileAway` |
| The scrollable range changes — rows arrive, the keyboard opens or closes | Never counts as the reader moving: someone at the bottom is pinned there, someone away is left where they are, and a range that shrank until nothing scrolls puts them back at the bottom |
| A message is sent | `ChatStore.deliver` returns to the tail before the message lands |
| Earlier messages are paged in | Prepended above the anchor the reader was looking at |

Whenever the reader is not at the bottom, a round white button with a down arrow floats in the
bottom-trailing corner of the timeline, above the message field: identifier `chat.jumpToLatest`,
label "Jump to latest". It fades in and out over 0.18 s, carries the count from
`ScrollTail.badge(updates:)` when something arrived while they were away ("3", and "99+" past a
hundred), and tapping it scrolls to the tail and resumes following. It is the one control on this
screen with a shadow rather than an edge, because it floats over the transcript.

Geometry a scroll this view started is not the reader either: `scrollToTail` arms a 0.45 s window
in which the animation's own numbers can only confirm that it arrived, so a tall row landing at the
tail never flashes the button on its way down.

## Dismissing the keyboard

`dismissesKeyboardOnBackgroundTap()` in `Sources/RCUI/Design/KeyboardDismiss.swift` puts a
`UITapGestureRecognizer` on the screen's own view controller, with `cancelsTouchesInView` off and
`shouldRecognizeSimultaneouslyWith` on. A tap that does not land in a `UITextField` or `UITextView`
calls `endEditing(true)`; everything else is untouched, so a button, a menu, a tool-card disclosure
or an approval option still acts on the first tap and puts the keyboard away at the same time. A
transparent overlay would have had to guess what to let through and would have cost a second tap on
whatever it guessed wrong.

The chat screen, the new-session sheet and the login screen carry it. The chat transcript and the
login screen also keep `.scrollDismissesKeyboard(.interactively)`, so dragging the transcript still
lowers the keyboard with the drag.

## The composer

Two rows. The message field takes the first one to itself, and the controls sit on the second:
the `+` attachment button and the microphone on the left, Send on the right. Stop is not among
them — it stays in the navigation bar, so no one ends a turn while reaching for Send. The model,
permission and dictation-language chips keep their own row underneath.

The field grows with the draft from one line to eight, then stops growing and scrolls inside
itself. `ComposerLayout` in `Sources/RCUI/Design/ComposerLayout.swift` holds that range, and the
answer field on an agent's question uses the same one so the two never disagree.
`.fixedSize(horizontal: false, vertical: true)` on the field is load-bearing: without it the bar
takes its height from whatever is left over and squeezes the field back to one scrolling line. The
transcript above is the view that should give way, not the thing being written.

Every capability rule is unchanged: the attachment button only where the agent and the attachment
carry bytes, the microphone only where a backend exists, the quiet line above the field naming what
an attached terminal owns, the send-mode menu on a long press of Send, and the queue chip.

## Voice

Two backends, chosen in Settings:

- **On this iPhone** — `SFSpeechRecognizer` with on-device recognition. Audio never leaves the phone
  and is never written to disk. It needs a downloaded model for the chosen language and reports
  "unsupported" when there is none.
- **Gateway** — PCM16LE at 16 kHz over `WS /ws/stt`, converted from the hardware format with
  `AVAudioConverter`. Audio goes to your own gateway. The settings screen states the difference.

Either way dictation only fills the draft. Sending stays a separate, explicit tap.

### What listening looks like

The transcript arrives in the composer's own field, not in a panel of its own, and is fully
editable once dictation ends. Reaching for the field while it runs is a request to take over: the
tap ends the dictation, keeps every word and puts the cursor in the field.

The control row holds the level meter and the elapsed time on the left, and exactly two controls on
the right:

| Control | What it does |
| --- | --- |
| Cancel | Discards what this dictation added and restores the draft it started from |
| Done | Stops listening and keeps the transcript in the field |

There is no "stop and send". Sending a dictated message is the ordinary Send button, afterwards.
One quiet line above the field says what dictation is doing — "Transcribing live · edit before
sending", or the gateway wording when the gateway is transcribing — and it is where a failure
reports itself. A failed run keeps whatever was recognised; the message clears itself after six
seconds.

While listening, a soft multi-colour light runs around the edge of the display, following its
rounded corners and breathing with the input level. It is drawn in its own `UIWindow` at
`UIWindow.Level.normal + 1`, the way `PrivacyShield` and `AppLockWindow` are, so it sits above the
app and anything the app presents, ignores the safe area and takes no touches. The display's own
corner radius is private to UIKit, so `DisplayCorner` reads the window's bottom safe-area inset
instead: a home indicator means a round display. Reduce Motion gets the same ring at a fixed width
with a slow opacity pulse and nothing driven by the voice.

### No maximum duration

Listening ends when the user taps Cancel or Done, when the app is backgrounded, or when the
recognizer fails. `VoiceInputController` arms no deadline at all; the only timer it owns is how long
a backend may take to answer `finish()`, and `isAwaitingFinalTranscript` says when that one is up.

Neither backend can hold one request open indefinitely, so both roll over underneath while the
audio engine and its tap keep running. Each request owns one slot in `TranscriptSegments`
(`Sources/RCCore/State/TranscriptSegments.swift`), and the text is joined by position rather than by
arrival, because a new segment's first partial usually lands before the old segment's final does. A
transcript counts as final only once every slot has settled.

| Backend | How it rolls over | What it costs |
| --- | --- | --- |
| `SystemSpeechRecognizer` | A new `SFSpeechAudioBufferRecognitionRequest` every 50 s, inside Apple's own limit of about a minute. Recognition is running on the replacement before `RecognitionRoute` hands it the tap, and the outgoing request is flushed under the same lock, so no buffer is dropped or handed to a request that has already been closed | A word spoken exactly across the seam can be split between two segments, because neither request hears the whole of it |
| `GatewaySpeechRecognizer` | A new `WS /ws/stt` socket every 30 s, well inside the gateway's 120 s and 4 MiB budget for one utterance. The cut waits for the first moment the input level drops below `silenceLevel`, and is taken anyway at 45 s. The replacement is connected and taking audio before the outgoing socket is told to stop | The gateway API is unchanged; it sees ordinary utterances. The first fraction of a second of the *first* segment is dropped while that socket connects, which is how it already behaved. Punctuation and casing restart at each segment, because the gateway transcribes each one on its own |

A recognition request that ends mid-session is a restart, never a stop. An error is too, unless the
request failed within five seconds of starting three times running, which is a broken recognizer
rather than a stretch of silence. A segment that already handed the microphone on keeps whatever it
transcribed even if it later fails; only the live segment can end the dictation.

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
directory picker, voice and push. Segment rollover is covered as a rule and against a fake backend,
never against a real microphone: no dictation has run past one recognition request on a device, and
neither Apple's own limit nor the gateway's has been reached in practice. APNs delivery and gateway speech-to-text have never been
exercised, the app has never run on a physical device, and dark mode and VoiceOver have not been
reviewed — the palette defines dark values, but v1 is designed light. CI, signing and TestFlight
upload have never run.

Keychain restore is a harness limitation rather than an open question about the code.
`CODE_SIGNING_ALLOWED=NO` produces an ad-hoc, linker-signed app with no `application-identifier`
entitlement, so `SecItemAdd` cannot store the token and the relaunch test asserts only that the
gateway address survives. Nothing suggests the keychain path is broken on a signed build; it cannot
be exercised without one.
