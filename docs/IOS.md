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

swift run RCVerify        # protocol fixtures, reducer rules and the string catalogue;
                          # Command Line Tools are enough

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

## The status dot

The dot is not the state. A turn that finished and a CLI that exited both report `idle`, so the
tone reads `state`, the `control` owner and the device's `online` flag together.
`DotTone.of(state:control:online:)` in `Sources/RCCore/State/DotTone.swift` is that rule, and
`Session.dotTone(online:)` is how a row asks for it. It lives in `RCCore` so `RCVerify` can run the
whole table without Xcode. `docs/DESIGN.md` states the table both apps implement; nothing here
decides anything it does not.

`StatusDot` takes a tone and nothing else, so a caller that has no device to ask cannot quietly get
a green dot for a machine that is gone. Every call site passes the real flag: a session row from its
device group, the chat header from the device it resolved, the line under the transcript from
`ChatStore.deviceOnline`.

| Tone | Colour | Motion |
| --- | --- | --- |
| `working` | `Theme.running` | breathes between full and half opacity over 1.1 s, and nothing else does |
| `live` | `Theme.running` | none |
| `waiting` | `Theme.attention` | none |
| `failed` | `Theme.danger` | none |
| `off` | `Theme.resting` | none |

Reduce Motion holds the `working` dot still at full opacity. `Theme.attention` is `#B07C00`, the
shared token: amber rather than orange, 3.67:1 on the white of a row and 3.36:1 on the canvas
behind the chat status line. `StatusLabel` tints its word with it too, and only for `waiting`.

A device's own dot is not a session dot and does not follow this table: green when the device is
online, grey when it is not.

`Tests/RCCoreTests/StatusDotTests.swift` covers the table on hand-built values;
`Verification/StoreChecks.swift` covers it again and checks the demo list carries all five tones at
once.

`Tests/RCCoreTests/SessionGroupingTests.swift` covers the rule on hand-built sessions;
`Verification/StoreChecks.swift` covers it against the demo fixtures.

## New session

Device, agent, model, effort, permissions, speed, working directory and git. The sheet does not ask
for a first message, so `session.create` goes out without `first_message`; the protocol keeps the
field. The four agent settings start at the agent's own defaults and change with the agent picker,
so choosing Codex where Claude was selected re-reads every list. Its section headers use
`FieldLabel`, the one label every form section in the app is headed with.

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
| `Theme.Text.metaMono` | `.caption` monospaced | a path or a branch inside a row |

`softSurface()` is one rounded fill with no border and no shadow; `card()` keeps a hairline border
for the surfaces that still need an edge, an approval card among them, and no longer carries a
shadow as well. `ChipButtonStyle` is tinted rather than outlined, so the Todos chip, the composer's
model and permission chips and "Take over" all lost their borders in one place.
`sessionRowLayout()` and `settingsRowLayout()` hold the row insets, so Sessions and Devices share
one rhythm and Settings shares another.

Settings reads like iOS grouped settings: a quiet caption, one soft surface, label left and control
right, and the explanation as a footnote under the group rather than inside it. A monospace value
truncates in the middle, so a gateway origin keeps its scheme and its host.

**Nothing is re-cased, and it takes saying so twice.** `docs/DESIGN.md` § "Surfaces, rows and
controls" allows no `text-transform` anywhere, and the app broke that rule in two ways at once: a
group caption called `.uppercased()` on the words itself, and a `Section` inside a `Form` re-cases
whatever it is handed as a header regardless. So `FieldLabel` — the one label every form section in
the app is headed with, Settings, the session settings sheet and the new-session sheet alike — spells
the words as they were written and carries `.textCase(nil)` on its own outer `HStack`, which is the
view the `Section` re-cases. One place, no modifier at any call site.
`testSettingsSectionHeadersAreSentenceCase` reads the headers back: a re-cased header carries the
transformed text in its accessibility label, so the assertion is on what the screen really says.

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
`ChatStore.isAttached` mirrors it. The header above the transcript reads `terminal · attached`, and
so does the session list, through `Session.statusLabel`. Nothing else on the screen repeats it: see
the status line below.

Two controls can be beyond a `shared` session's reach, and the app hides them rather than dimming
them under a caption: no `+` without `shared_attachments`, no settings chips without
`shared_settings`. Nothing is printed in their place. The header already reads `terminal ·
attached`, which is the one thing the reader needs, and a second line saying it again cost the
transcript a row on every attached session.

Whether either is reachable is the device's call, not the app's — see the two booleans below.

A message sent into a `shared` session may be held by the device until the terminal-driven turn
ends. Amendment A19: while it waits it is **a queue entry and nothing else**. The device answers
`queued`, publishes the queue, and emits no block at all, so the optimistic row from sending retires
into "Up next · N" exactly as it does on a session this app drives. The bubble appears only when the
CLI takes the message, with `delivery: "delivered"` and a `first_seq` that places it after the
output of the turn it waited for — which is where the terminal draws it too. A bubble pinned at the
moment of sending sat in the middle of an answer that was still arriving, before the words it was
replying to.

One delivery chip is left: `absorbed` reads "will be re-sent", for a message the CLI took as
mid-turn data rather than as a prompt. The device replaces the same `block_id` when it goes in
again, so the chip disappears on its own. The chip lives inside a combined accessibility element,
so its words are appended to the bubble's label as well.

Amendment A20: a question the attached Claude Code asks is answered here or in the terminal,
whichever comes first. The device raises the `question` block from a `PermissionRequest` hook it
runs beside the CLI's own dialog, so the card is live on a `shared` session — `ChatStore.allowsAnswers`
now follows `control != "terminal"` alone. A question resolved elsewhere carries `by`, and the card
prints the same words the approval card does: "answered in the terminal". Nothing is queued behind
a question; see "The composer" for what the message field becomes while one is open.

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

`mac-studio-office` also carries `demo-session-toolchain`, a session whose agent stopped on an error,
so the list shows all five dot tones at once rather than four.

The demo carries both cases: `demo-session-shared` on `mac-studio-office`, whose Claude reports
`attach: "channel"`, `attach_ready: true`, `shared_interrupt: false`, and `demo-session-rename` on
`macbook-air`, whose Claude has no shim installed. Opening the shared session shows a question its
terminal answered earlier and a live one this phone can answer; sending into it afterwards shows the
message queued, then delivered, then a relayed permission request with exactly Allow and Deny.

## What a shared attachment carries

Amendment A11 adds two more optional booleans to the agent object, both defaulting to false, so a
device that never heard of them grants nothing:

| Field | What it opens on a `shared` session | `ChatStore` |
| --- | --- | --- |
| `shared_settings` | the model, permission and effort chips, and the session settings sheet behind them | `allowsSettingsChanges` |
| `shared_attachments` | the attachment button, so photos and files go into the live thread | `allowsAttachments` |

Both are read straight off `AgentInfo`; nothing in the app branches on the agent id. Stop is
unaffected and still needs capability `interrupt` plus `shared_interrupt`. A `terminal` session
takes no input whatever it reports, so `allowsAttachments` stays false there. The session settings
sheet is reached only from the chips, so it never opens on a session it could not change and carries
no locked state of its own.

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
started and the daemon shares, running, with Stop in the navigation bar, a live model card and
permission chip, and a four-option request in the transcript. Changing the effort there goes
through `session.set` and is applied; the same request on the attached Claude session is still
refused. That agent is also the one with a speed tier (A21), so the card's lightning toggle has a
home in the demo. `ci-runner-01` keeps a
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

Two rows, and never three. The message field takes the first one to itself. Everything else shares
the second, in one order: the `+` attachment button and the microphone against the leading edge,
then the session's chips, then Send against the trailing edge. The chips scroll sideways when they
do not fit and never wrap, so the transcript loses no height when a chip is added. Stop is not among
them — it stays in the navigation bar, so no one ends a turn while reaching for Send.

| Chip | Shown when | Identifier |
| --- | --- | --- |
| Model card | `ChatStore.allowsSettingsChanges` | `composer.modelCard` |
| Permission mode | the same | `composer.permissions` |
| Dictation language | always; it belongs to the microphone beside it | `composer.language` |
| Up next · N | `session.queued > 0` | `composer.queue` |

While dictation runs the level meter, the elapsed time and Done replace that whole row.

**The model card: model, effort and speed are one control (A21).** The row does not spend three
chips on what runs and how hard. One chip reads the model label with the effort word after it
("Opus 4.1 High"), with a small `bolt.fill` before them while `session.speed` is set; a tap opens a
popover anchored above it — `.presentationCompactAdaptation(.popover)`, so it is a card on the
phone and not a sheet — with two rows:

- a `bolt` toggle at the leading edge, drawn only when `AgentInfo.speeds` is non-empty, filled and
  tinted while a tier is on, cycling standard → each tier → standard through `ChatStore.nextSpeed`
  and one `session.set {speed}`; then the model name with the effort word after it and a chevron
  that discloses the model list, one row per `AgentInfo.models` entry with the current one ticked;
- the effort slider, one stop per `AgentInfo.efforts` entry, the track tinted to the thumb in
  `Theme.accent` and nothing else on it. The word in the first row follows the thumb and
  `session.set {effort}` is sent on release, so dragging across four levels is one request rather
  than four. `.sensoryFeedback(.selection, trigger:)` on the stop index gives one selection haptic
  per stop the thumb crosses, which is what lets the levels be counted without looking. An agent
  with one effort level or none draws no slider: there is nothing to slide.

The accessible names are "Model", "Effort" and "Speed", with the value on each; the identifiers are
`composer.modelCard`, `composer.model`, `composer.effort` and `composer.speed`. A terminal-held
session shows the same words as one static chip, `composer.readonly.modelCard`, that opens nothing,
with the tier's glyph on it and the tier's name spelled out in its accessibility value — the glyph
says "faster tier" to the eye and nothing at all to a screen reader. The permission-mode chip
follows the card in the row. `ModelCard.swift` holds all of it; `TerminalSetting.modelCardText`
words the session once, so the live chip and the read-only chip can never disagree.

Forms keep list pickers. The session settings sheet and the new-session sheet both list Model,
Effort, Permissions in that order, with a Speed picker after them where the agent offers a tier;
the new-session sheet sends `model`, `permission_mode`, `effort` and `speed` in `session.create`,
starting from the agent's own defaults so a sheet sent untouched asks for what the device would
have chosen anyway.


Amendment A20: while the transcript holds a question nobody has answered yet, the one primary in the
row answers it instead of sending. Its glyph does not change — it is still the button that takes
what was typed — but it names itself "Answer" to assistive technology, the field's placeholder reads
"Your answer", and the status line reads "Waiting for your answer". Submitting sends `session.answer`
for that block with whatever was chosen on the card, plus the draft as the free-text answer to the
first question on it that nothing has been chosen or typed for. A draft with nowhere to go — every
question already answered on the card, or the one still waiting takes options and no words — is left
in the field: the card's own Submit is the way to send that. A **secret** question is never answered
from the message field either, however it is configured: the composer's draft is written to disk and
restored on the next launch, and the card promises a secret value is neither stored nor logged, so
its own masked field is the only way in. Nothing is queued while a question is
open, and no optimistic row is drawn for an answer, because an answer is not a message and the card
resolving is what says it arrived. The card and the composer read one copy of the choices, a
`QuestionDraft` in `ChatStore`, so the two can never submit different things.

The field grows with the draft from one line to eight, then stops growing and scrolls inside
itself. `ComposerLayout` in `Sources/RCUI/Design/ComposerLayout.swift` holds that range, and the
answer field on an agent's question uses the same one so the two never disagree.
`.fixedSize(horizontal: false, vertical: true)` on the field is load-bearing: without it the bar
takes its height from whatever is left over and squeezes the field back to one scrolling line. The
transcript above is the view that should give way, not the thing being written.

**The field is a `UITextView`, for the scroll indicator.** Past the cap a long draft has more above
and below it, and the only cue `docs/DESIGN.md` allows is the system indicator down the trailing
edge. SwiftUI's `TextField(axis: .vertical)` draws none, and `.scrollIndicators(.visible)` does not
reach the text view it keeps inside — measured on iOS 27, five screenshots taken straight after a
slow drag inside the field, against a `ScrollView` dragged the same way in the same burst that
showed its indicator in all five. So `GrowingTextField` in `Sources/RCUI/Design/GrowingTextField.swift`
owns a `UITextView`: scrolling switches on only once the text passes eight lines, and the indicator
flashes once at that moment. Nothing was added around it — no expand button, no permanent bar, no
line count. Two things follow from owning the view. The cap is measured rather than multiplied,
because a text view sets its lines further apart than the font's own line height and eight times
that clips the eighth line. And the placeholder is the field's accessibility label, because UIKit
has neither a placeholder on a text view nor a placeholder value to report: VoiceOver reads
"Message · will steer the turn" before whatever has been typed, and
`testComposerFieldShowsItsScrollIndicator` samples the pixels along the trailing edge after a drag
rather than trusting a screenshot to be read by hand. Focus is a `Bool` binding rather than
`@FocusState`, since a represented view is not a focus target SwiftUI can move to; it reads both
ways, so dictation still hands the cursor back to the field and a background tap still ends editing
through `endEditing(true)`, which the field reports back.

Above the field the composer draws one line at most, and only while something is happening to it: an
attachment that was refused, or what dictation is doing. It never says who owns the session — the
header above the transcript already reads `terminal · attached`, and a control the app cannot drive
is absent rather than dimmed under a caption explaining why. So the composer is the field row plus
one control row, with a strip of attachment pills between them while a message carries files.

## The status line

One line between the transcript and the composer, and it is drawn only when it says something the
header does not. The header already carries the dot and the state word, so `ChatStore.statusLine`
returns nil for every state the header names — `idle`, `starting`, `stopped`, `needs_approval`,
`needs_input` — and nil for an attached session that is simply sitting there. The one exception is a
question in the transcript: the line then says what the composer has become, which is a fact about
the message field rather than a repeat of the header (A20). It keys off the block, never off
`needs_input`, so a device that reports the state without raising a block changes nothing here.

| When | What it says |
| --- | --- |
| The device is offline | "Device offline" |
| `control: "terminal"` | "Controlled by the terminal", plus "· Take over to send" where the agent lists `takeover` |
| A question is pending | "Waiting for your answer" (A20), which outranks the turn it interrupted because nothing is queued behind it |
| A turn is running | "Working · your message will steer the turn", or "· will be queued", or "· N messages queued" |
| `state: "error"` | Whatever the device put in `state_detail`, which the header has no room for |

An attached session takes the ordinary rules rather than a rule of its own, so a running shared
Codex thread reads "Working · your message will steer the turn" and an idle one reads nothing at
all. `docs/DESIGN.md` holds the same table for both apps.

## Sending

Amendment A12: the app mints the `session.send` request id and the device returns it as the
`user_message` block id, so nothing about sending waits for a round trip.

Tapping Send clears the field and puts the message in the transcript in the same turn of the run
loop, as an `OptimisticMessage` in `Sources/RCCore/State/Timeline.swift`. Those rows are kept apart
from `entries`: they hold no `seq`, so they can neither move the replay cursor nor become a history
boundary, and `Timeline.roots` appends them after everything the device has sent. The bubble is
drawn at 55 % with a "Sending…" caption under it, and no spinner — the message is already on screen,
and a turning wheel would claim the app was busy when it is not.

| What happens next | What the row does |
| --- | --- |
| The device's `user_message` arrives under the same `block_id` | Replaced in place by the ordinary replacement rule |
| An older device sends its own id, `source: "remote"`, identical text | Reconciled by text, one row per event |
| The reply is `sent` or `steered` | Nothing; the row waits for the event |
| The reply is `queued` | The row goes, and the queue row above the composer stands for the message until the device dequeues it and emits the `user_message` under the same id |
| The reply is an error the gateway actually sent | The row goes, the message is shown in the composer, and the text returns to the draft if the user has not started another one |
| The socket dropped, or the request timed out | The row stays, "Delivery unconfirmed" and Retry appear, and the retry reuses the id rather than sending a second copy |
| Nothing at all for 60 s | The row says "Delivery unconfirmed" itself, through `OptimisticMessage.isUnconfirmed(at:)` |

A resync keeps these rows — a message the user just typed must not vanish because the socket came
back — and the reloaded history reconciles them, so a reconnect neither drops nor duplicates one.
`Tests/RCCoreTests/OptimisticSendTests.swift` covers every line of that table.

The demo device holds its echo back by `DemoGateway.defaultEchoDelay`, 400 ms, and reports the
message before it reports what the agent said about it, which is the order a real device uses. Under
`--ui-testing` the delay is three seconds, so a test can look at the state between the tap and the
echo rather than race it.

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

The control row holds the level meter and the elapsed time on the left, and one control on the
right: **Done**, which stops listening and keeps the transcript in the field. It stands where Send
stands, at Send's size and in Send's style, because while listening it is the one primary action in
the row. There is no Cancel — a dictation nobody wants is Done and then edited or cleared like any
other draft, and a second button of a different size beside the primary only made the row look
unfinished.

There is no "stop and send" either. Sending a dictated message is the ordinary Send button,
afterwards. One quiet line above the field says what dictation is doing — "Transcribing live · edit
before sending", or the gateway wording when the gateway is transcribing — and it is where a failure
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

Listening ends when the user taps Done, when the app is backgrounded, or when the recognizer fails.
`VoiceInputController` arms no deadline at all; the only timer it owns is how long a backend may take
to answer `finish()`, and `isAwaitingFinalTranscript` says when that one is up.

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

**A slot is not one sentence.** A recognizer may also start its transcription over *inside* one
request: the speaker paused, and the next partial is a new sentence rather than a longer version of
the old one. Replacing the slot's text then threw away everything said before the pause, which is
what the phone was doing. So `TranscriptSegments.update` takes a result as a **fresh start** —
settling what the slot held as a finished sub-segment and carrying on in a new one — when all three
of these hold, and as an ordinary replacement otherwise:

1. The slot already holds at least twelve letters or digits, so the first few words of an utterance
   are never settled behind a correction to them.
2. The result is shorter than what the slot holds. A recognizer extending or revising an utterance
   keeps roughly what it had; the first partial after a restart is a word or two.
3. The two share no real beginning: the run of characters they agree on, ignoring case, spacing and
   punctuation, covers less than half the new result.

Rule 3 is what keeps a revision a revision. The recognizer rewriting its last few words produces a
result that agrees with the old one for almost all of its length, so it replaces, as it always did.
The three together can never lose a word; the worst they can do to an unusually heavy revision of a
whole sentence is keep both versions. `TranscriptSegmentsTests` covers the restart, the revision and
the correction of a first word.

This is a fix from the symptom, not from a measurement: the diagnosis is that Apple's on-device
recognizer restarts `bestTranscription` after a silence within one request, and the experiment that
would have shown it — feeding two `say` clips joined by three seconds of silence to
`SFSpeechURLRecognitionRequest` with `requiresOnDeviceRecognition` — could not run here.
`SFSpeechRecognizer.requestAuthorization` answers `.notDetermined` for a process nobody is sitting
in front of, from a plain command-line tool and from a signed bundle carrying
`NSSpeechRecognitionUsageDescription` alike, and the permission cannot be granted without somebody
at the machine. The rule is written so that it holds the transcript together whatever the recognizer
is doing, because every path through it either extends, replaces or appends, and none discards.

## Connection lifecycle

**Launch shows the app, never the sign-in form, when there is an account.** `AppModel.isResuming` is
true from the first frame whenever a gateway is stored (the demo counts as one), and `RootView`
draws the page colour and nothing else while it holds — never the form, which is an answer and not
a waiting room. `ConnectionStore.restore` adopts the endpoint and the user the moment the keychain
answers and opens the socket behind them, so the main screens appear in their connecting state
rather than a gateway form for the length of a round trip. `/api/session` then runs behind the
screens it already unlocked: a 401 is the one answer that ends the session and brings the form back,
saying "Your session expired. Sign in again."; anything else is a link problem the socket is
already reconnecting through, and nothing signs the user out over it.
`testLaunchWithAnAccountNeverShowsTheSignInForm` and `testLaunchWithNothingStoredShowsTheSignInForm`
hold both halves of the rule.

| Close code | What the app does |
| --- | --- |
| 4401 | Forgets the keychain token, keeps the cached lists, returns to login with "Your session expired" |
| 4403 | The same teardown, with "This gateway refused the connection" |
| 4001 | Stops reconnecting and offers a manual Reconnect |
| anything else | Reconnects with a 1/2/4/8/15 s backoff |

A request in flight when the socket drops is reported as uncertain, never resent automatically; the
retry reuses the original request id.

A request issued while the socket is coming back is held rather than refused. The gateway closes a
silent socket after 25 s, so an app returning to the foreground usually finds one to rebuild, and
failing the send for the length of a TLS handshake, a hello and a subscribe reads as a dead Send
button. `GatewaySocket.request` waits for the hello under `GatewaySocket.readyWait`, 20 s, then
reports the request unconfirmed and leaves the retry to the user. A request with no connection
attempt under way still fails at once with `notConnected`.

Send is therefore disabled only for reasons a wait cannot fix: the terminal owns the session, the
device is offline, there is an unconfirmed send to settle first, or the app is no longer signed in.
`ConnectionPhase.canReachGateway` is what says which of those a phase is, and the composer copies it
into `ChatStore.canReachGateway`.

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

## Language

**English is the default whatever the phone is set to**, and Settings offers English and 中文 as a
segmented control under a **Language** caption above Timeline (`settings.language`). The Voice
group's own picker is called "Dictation language", because two rows one screen apart both reading
"Language" is a riddle rather than a preference. `InterfaceLanguage` in `RCCore` holds the two
cases, persisted as `preference.language`; the choice applies at once on every open screen, because
there is nothing to reload — it changes where words are read from, not what is on screen.

Words reach the reader two ways, and both are one setting.

- **Inside a view.** `RootView` sets `.environment(\.locale, settings.language.locale)` on the
  whole shell, so every `Text(LocalizedStringKey)`, `Button`, `Label`, `Picker` and
  `navigationTitle` literal resolves through `App/Localizable.xcstrings` in that language. Nothing
  at a call site changes. Two components had to stop taking `String`: `FieldLabel` and
  `SettingsRow` now take a `LocalizedStringKey`, because `Text(someString)` is verbatim and a
  caption typed as a `String` would have been the one word on the screen that never moved.
- **Outside a view.** A status line in `ChatStore`, a `ConnectionStore` phase message, a
  `TransportError`, a push status, the state word beside a dot, a relative time — none of them has
  an environment to read. They go through `L10n.string(_:)`, which looks the key up in the chosen
  language's `.lproj` table. `SettingsStore` points it at that table on launch and on every change,
  so the two paths can never disagree.

The keys are the English text, so a key with no translation reads as English rather than as a
placeholder. Relative times and durations carry words ("12m" → "12 分钟前", "yesterday" → "昨天")
and are formatted through the same table. Never translated: what the agent wrote, what the device
reported (a device name, a path, a branch, a model, permission or effort id), anything the reader
typed, the product name "Remote Control", "Claude Code", "Codex", and the two language names, which
are always shown in their own script.

`RCCore` keeps no catalogue of its own. `Bundle.module` would give the package target its own
table and a second place for a word to live, so `L10n` reads the app's, and a process with no
catalogue at all — a check runner, a unit test — reads every key as the English it is written in.
There is therefore one file of strings, `App/Localizable.xcstrings`, whatever target wrote the word.

`Verification/LocalizationChecks.swift` is the gate. It reads the catalogue and fails on any key
with no `zh-Hans` translation, on any translation whose `%@`/`%lld` placeholders do not match the
key's — reordering them without numbering (`%2$@`) hands an integer to `%@` and crashes the app the
first time the line is drawn — and then walks `Sources/` and fails on any word a view writes, a
literal in a `Text`, `Button`, `Label`, `navigationTitle` or any other `LocalizedStringKey`
position, or a key handed to `L10n.string`, that the catalogue does not hold, naming the file that
writes it. It runs inside `swift run RCVerify`.

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

The selection haptic on the effort slider cannot be asserted from a UI test — nothing in XCTest
observes `UIFeedbackGenerator` — so the test drags the thumb one stop and asserts the word that
follows it instead, and the haptic itself has been read only from the code. The launch rule is
proved against the demo account rather than against a stored keychain token, for the reason below.

Keychain restore is a harness limitation rather than an open question about the code.
`CODE_SIGNING_ALLOWED=NO` produces an ad-hoc, linker-signed app with no `application-identifier`
entitlement, so `SecItemAdd` cannot store the token and the relaunch test asserts only that the
gateway address survives. Nothing suggests the keychain path is broken on a signed build; it cannot
be exercised without one.
