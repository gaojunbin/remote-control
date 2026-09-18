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

**The version and the privacy strings are checked, not remembered.** `RCUIVerify` reads
`project.yml` itself and fails when `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` are not the
ones this round ships, or when any of the six purpose strings is missing. Six, not five: the app
sets `NSAllowsLocalNetworking`, `GatewayEndpoint` accepts a gateway on loopback, an RFC 1918
address or a `.local` name, and this file documents signing in from a physical iPhone against the
Mac's LAN address — all of which need `NSLocalNetworkUsageDescription` on real hardware. The
simulator shares the Mac's network and is never asked, which is why nothing local would have
noticed. `NSBonjourServices` is not declared and is not needed: the app resolves an address the
reader typed and browses for nothing.

UI tests need a booted simulator:

```sh
xcrun simctl bootstatus <udid> -b
xcodebuild -project RemoteControl.xcodeproj -scheme RemoteControl -configuration Debug \
  -destination "platform=iOS Simulator,id=<udid>" -parallel-testing-enabled NO \
  -only-testing:RemoteControlUITests CODE_SIGNING_ALLOWED=NO test
```

**The simulator's language.** The UI-test simulator on the development Mac had been left with
`AppleLanguages` `zh-Hans-SG` (found in round 31), which gives it the Pinyin keyboard by default.
Tests that type still pass with an English keyboard only; before a full run, keep the simulator
English:

```sh
xcrun simctl spawn <udid> defaults write .GlobalPreferences AppleLanguages -array en
xcrun simctl spawn <udid> defaults write .GlobalPreferences AppleLocale -string en_US
xcrun simctl shutdown <udid> && xcrun simctl boot <udid>
```

The interface-language tests pass the app its own `-AppleLanguages`, so they do not need the
simulator to be Chinese. Separately, XCUITest's `typeText` has been seen to re-send letters when
the composer's layout changes under it — the slash-command panel appearing on the first `/` — so
the command tests type the slash first and the name once the panel is up.

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
It answers `/api/config` from memory too, so the served client the Devices screen measures against
is there in the demo — build `3f2b4a9c…`, and for the version whatever this app ships
(`DemoFixtures.servedClientVersion` is `AppBuild.version`, because a round ships all four components
on one number, so the demo cannot fall a round behind): two demo machines run 1.3.0 and the demo
gateway takes `device.update`, reports the device as updating and brings it back on the served
build and version a few seconds later (A22). It also claims one printed pairing token, which is
what the scan flow is driven with.

Other launch arguments: `--ui-testing`, `--reset-state`, `--demo-account`, `--demo-update-required`, `--registration-open`,
and in debug builds `--voice-preview`, which swaps in a scripted speech platform so a UI test never
opens the microphone. The listening state, the full-screen glow included, is screenshotted through
it. `--voice-level=<0…1>` pins that platform's input level, so the glow can be looked at at rest, at
conversational speech and at the top of its range without speaking into a simulator; the default is
0.65, ordinary speech. `--voice-transcript=long` swaps the one short sentence it speaks for a
dictation of about a minute, delivered in four partials, which is what the field following the words
is proved against; anything else is the short sentence. `--field-scroll-probe` puts the message
field's scroll position beside it as `composer.prompt.scroll`, for the same test. Both are read only
where the scripted platform is, so a release build honours neither.

`--demo-account` puts the same in-memory gateway *behind* the sign-in form instead of around it:
`ConnectionStore.offlineDemo(registrationOpen:)` builds the store with the demo as both its HTTP
client and its socket, so the form signs in for real against it. That is how every answer the form
has to tell apart — an unknown account, a disabled one, a member, an admin — is driven with no
gateway to reach. `--registration-open` starts that gateway taking registrations.

## Accounts

Every person on a gateway has an account (protocol A24), and the app shows one person what is
theirs. `docs/DESIGN.md` § "Accounts" is the contract; this is where it lives.

**Sign in.** `LoginView` asks for the gateway, the username and the password, in that order. The
username is prefilled from `SettingsStore.username(for:)`, which files one account per gateway
address, so the next sign-in on a gateway is the password alone and alternating between two of them
offers the right name on each.

Under the button, **Create an account** is drawn only when `GET /api/health` reports
`registration_open`. The form asks for that itself — `ConnectionStore.registrationOpen(origin:)`,
debounced 400 ms behind a `.task(id: origin)`, because the address is typed and asking on every
keystroke would be one request per character. This screen is the only place in the app that reads
the flag. Tapping the link swaps the card for the same three fields, a **Create account** button
and **Sign in instead**; `POST /api/register` answers exactly what login answers, so creating an
account is a sign-in.

**Every refusal is one sentence, and the sentence is the web's.** `AccountError` holds them, one
function per route, because a status means different things on different routes: a `409` is a taken
username on `POST /api/users` and an account refusing to be touched on `PATCH`. Signing in reads
"This account is disabled." on `403` and "Wrong username or password." on `401`, which never says
which half was wrong. The strings are copied from `web/src/strings.ts`: the one that read
differently on each app would be the bug.

**Account, in Settings.** `UserIdentity` carries the `role`, so the Settings header
(`Screens/Settings/SettingsIdentityHeader.swift`) says who is signed in and as what — the initials
(`Initials.of`), the username, then `role · connection dot · host` (`GatewayHost.of`,
`ConnectionTone.dot`; the dot's word is the accessibility label only, `IdentityLine.label`). The
Account group under it (`SettingsAccountGroup`) offers a member **Change password**
(`PasswordSheet`, two fields, `POST /api/password`; a `401` reads "That is not your current
password."). An admin does not: the operator's password is the gateway's own `RC_PASSWORD` and
there is nothing on a phone that could change it. An admin gets **Users** instead. **Sign out** is
last and asks first, with the row's own sentence as the dialog's message.

**Users** (`UsersView`) is the admin's screen. `ConnectionStore.usersStore()` returns nil for
anyone else, so a member has no way to build one. The registration switch sits at the top with its
caption, then one row per account: the username, `role · state`, the device count and the last
sign-in as a relative time or "never". Reset password, Disable or Enable, and Delete ride one
trailing swipe and the context menu, listed Delete · Disable · Reset so the row reads Reset ·
Disable · Delete from the inside out. The `admin` row offers none of them — `UserRecord.isOperator`
— and the gateway refuses them with `409` as well, which `AccountError.manage` words. **Add user**
is the bottom-bar primary button, drawn exactly as Add device and New session are.

Each alert and sheet shows the failure it caused, never the page: the add sheet keeps its own
error, and a refused password reset re-opens its alert with the sentence in the message.

**The app's own settings are per account.** `SettingsStore` keys every preference —
notifications, app lock, voice backend, dictation language, timeline detail, interface language —
under `<name>@<origin>|<username>`, and `adopt(origin:username:)` re-reads them on every sign-in.
The gateway address is the one global value, because there is nobody to scope it to until someone
has signed in; the username is filed under the gateway it signed in on. `--reset-state` calls
`reset()`, which removes every `preference.` and `gateway.` key rather than only the current
account's, and empties the draft store (`DraftStore.clearAll()`, first thing in the launch task,
ahead of the demo opening a session), so a run never inherits the shape — or the half-typed
words — an earlier one left. `--language=` pins the language
for the run through `pinLanguage(_:)`, so signing in as an account that stored another language does
not move the app out from under a test.

Nothing here is scoped by the app alone: the gateway sends one account's devices and sessions and
answers `not_found` for anyone else's, so `hello` is already the whole of what this person has.

## Agents: four names, four logos, and nothing drawn for what an agent lacks

`AgentLabel` in `Sources/RCCore/Protocol/AgentLabel.swift` holds the names: `name(_:)` gives Claude
Code, Codex, Grok Build and pi (amendments A25 and A26, which withdrew Cursor). An id this build has
never met renders as itself, so a fifth agent needs no release. `Session.agentLabel` is how a row
asks for the name.

What stands in for an agent is its own logo, which is a drawing rather than a string, so it lives in
`RCUI` instead: `AgentLogo(agent:)` in `Sources/RCUI/Design/AgentLogo.swift` draws the vector as a
template image from `Sources/RCUI/Resources/Agents.xcassets` — image sets `agent-claude`,
`agent-codex`, `agent-grok` and `agent-pi`, each one SVG with its vector representation preserved
and its rendering intent set to template, so a single asset serves both appearances and every size.
The view frames it square at `Theme.Mark.inline`, the cap height of the footnote line beside it, and
`@ScaledMetric` carries that box through Dynamic Type; `Theme.Mark.control` is the larger box the
agent control uses, where the logo stands alone with no word to match. The colour is `Theme.ink`
unless the caller passes `tint: nil` to inherit whatever it has already set. An agent with no vector
falls back to `AgentLabel.initial(_:)`, the first letter of its id, in the same box.

Four places draw it. The **session row's chip** puts the logo before the name. The **filter menu**,
whose active choice the toolbar button repeats with its own logo, does the same — except on the
chosen row, because a menu row carries one image and that one is spent on the checkmark. The
**device row's agent list** draws the logos alone at `Theme.Mark.control`, each labelled with its
agent's name for a reader who cannot see it (`docs/DESIGN.md` § "The device row"). The
**new-session sheet's** agent control is a segmented `Picker` where four names do not fit across a
phone, so each segment is the logo alone with
`.accessibilityLabel(info.displayName)`, and a screen reader reads "Grok Build" where the eye reads
the spiral. That control and the menu are drawn by UIKit, which takes an `Image` and a `Text` and
drops every other view, so both call `AgentLogo.image(_:)` for the bare vector rather than the view.
The line under the control names the agent that is chosen and then, in the monospace face, the
version the device detected and the model that agent would start on — the same line the web draws
under its own segmented control.

**What an agent does not have is not drawn.** The device's `AgentInfo` is the only authority, and an
empty list means the agent has no such setting rather than that the app could not read one:

| Empty list | What goes | Where |
| --- | --- | --- |
| `permission_modes` | the composer's `composer.permissions` chip, and the sheet's Permissions section | `Composer.permissionChip`, `NewSessionSheet.settingsSections` |
| `efforts` | the card's `StopSlider`, the sheet's Effort section, and the effort word after the model name | `ModelCard`, `NewSessionSheet.settingsSections` |

No shipped agent has an empty list today. pi's three modes arrived with A26 — they are the device's
own, enforced by the extension pi loads into every session — and the app draws them exactly as it
draws Codex's, with nothing changed in the app to do it.

Nothing is greyed out and no caption explains the gap. The same rule reaches the read-only chips a
terminal-held session draws (A17): `TerminalSetting.permissionText(for:agent:)` and
`effortText(for:agent:)` return nil when the agent lists none, so a mirrored Grok session — whose
update log carries a model and a level but never a permission mode — shows one chip where a Claude
session shows two. A session whose agent this build has never met keeps A17's fallback and shows the
raw ids, because with no `AgentInfo` at hand there is no list to say the setting does not exist.

The demo device `mac-studio-office` advertises all four agents, copied from
`protocol/fixtures/objects/agent.grok.json` and `agent.pi.json` to the letter — so its Grok Build
reports `attach: "leader"` and is ready (A28) — and the demo list carries one session of each new
agent: a Grok session a terminal holds inside Grok's leader, which the device has joined
(`control: "shared"`, a turn running), and the pi one the app's own. `macbook-air` carries a Grok
Build whose leader mode is off (`attachReady: false`) and the terminal-held, mirrored Grok session
under it, which is where the leader hint renders.

## The shell: three tabs, one order, one landing rule

`MainShell` in `RootView.swift` lists **Devices**, **Sessions**, **Settings**, in that order — the
same three the web app puts in its sidebar, in the same order, so neither app teaches a different
shape. `AppModel.Tab` keeps its cases; only the `TabView` does the ordering.

Which one opens is decided once per sign-in, from the first device list that arrives:
`AppModel.decideLandingTab()` waits for `ConnectionStore.hasSnapshot` — the flag the `hello`
raises — and then takes `AppModel.landingTab(hasDevices:)`, Sessions when the account has at
least one machine and Devices when it has none. A new account's first job is enrolling a machine;
everyone else's is the conversation. The shell asks for the decision with `.onChange(of:
connection.hasSnapshot, initial: true)`, which also covers a relaunch on a connection that had
already synced.

It is decided once and no more. The one-shot is spent only by a decision that actually ran, so a
call that arrives before the snapshot does not consume it; a later `device.updated` that empties or
fills the list moves nobody; and a tab chosen by hand afterwards is not bounced back. A
notification link settles it too — `handle(_ link:)` forces `.sessions` and marks the rule done, so
the first device list cannot pull the tab out from under an opening session. Signing out resets it.
`VerificationUI/main.swift` covers both branches of the rule, "not decided until the list arrives"
and "decided once"; `testTabsReadDevicesSessionsSettingsAndLandOnSessions` reads the tab order off
the screen.

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

The agent filter sits in the navigation bar as **All agents** and then every agent the list
actually contains, each row reading its logo and then its name (A26). Every row's agent chip is the
logo and the full name in the one quiet tint every agent shares; no agent has a colour of its own. It is a view of the list rather than a setting: it starts at
All on every launch and is never written to defaults. The device filter is a parameter of the
same function, for the callers that narrow to one machine.

A row is three lines (`docs/DESIGN.md` § "The session row"): the title and the relative time on
the first; the agent chip at the leading edge and, at the trailing edge of the second, the dot with
the session's **origin** — `SessionOriginLabel` (`Design/Controls.swift`) drawing `StatusDot` and
`Session.originLabel`, "Terminal" for `origin: terminal` and "Remote Control" for `origin: remote`,
in the secondary ink whatever the tone (`docs/DESIGN.md` § "The session row says where it came
from"); "Archived ·" still precedes it on a hand-archived row, and the chat header alone keeps
`Session.statusLabel` and the state vocabulary. Because "Remote Control" is now a catalogue key
with a zh-Hans value (远程启动), the sign-in and lock screens print the product's name with
`Text(verbatim:)` so it is never translated there. Above the first device section the list's first
row is the legend (`docs/DESIGN.md` § "A legend, once, and quiet"): `DotLegend.entries` in
`Sources/RCCore/State/DotLegend.swift` — Working (green), For you (still amber), Not running (grey),
Error (red) — drawn as four `StatusDot`s with caption words, `sessions.legend`, one accessibility
element, absent when the list is empty and never on the chat or Devices screens. `RCUIVerify`
checks the origin words for both origins and every demo row, and the legend's words and order;
`Tests/RCCoreTests/StatusDotTests.swift` covers the legend's tones; the UI test
`testSessionRowsNameTheirOriginAndTheDotsAreExplainedOnce` reads a remote and a terminal row and the
legend. The third line is the working directory alone, after a `FolderGlyph` (`Design/FolderGlyph.swift`: lucide's `Folder`
as a `FolderShape` of six rounded corners on `OutlineGlyph`'s 24-unit grid, stroked in the ink at
1.5 units with round caps and joins, 14 pt scaled with the footnote). The path truncates from the
head so the folder it ends in survives a tight row. `OutlineGlyph` (`Design/OutlineGlyph.swift`) is
the one rule the folder and the device row's laptop share; `RCUIVerify` checks the folder's bounds
on the grid and at twice the grid, the tab's rise, the rounded corner and the stroke's caps and
joins.

## The status dot

The dot is not the state. A turn that finished and a CLI that exited both report `idle`, so the
tone reads `state`, the `control` owner and the device's `online` flag together.
`DotTone.of(state:control:online:)` in `Sources/RCCore/State/DotTone.swift` is that rule, and
`Session.dotTone(online:)` is how a row asks for it. It lives in `RCCore` so `RCVerify` can run the
whole table without Xcode. `docs/DESIGN.md` states the table both apps implement; nothing here
decides anything it does not.

`StatusDot` takes a tone and nothing else, so a caller that has no device to ask cannot quietly get
a living dot for a machine that is gone. Every call site passes the real flag: a session row from its
device group, the chat header from the device it resolved, the line under the transcript from
`ChatStore.deviceOnline`.

| Tone | Colour | Motion |
| --- | --- | --- |
| `working` | `Theme.running` | none |
| `waiting` | `Theme.attention` | breathes between full and half opacity over 1.1 s, and nothing else does |
| `live` | `Theme.attention` | none |
| `failed` | `Theme.danger` | none |
| `off` | `Theme.resting` | none |

The colour decides on its own whether to look: green means working — leave it; amber means there is
something for you, a finished turn to read or a question to answer. `Theme.dotColor` holds that
mapping and `StatusDot.pulses(tone:reduceMotion:)` the motion, both read by `RCUIVerify` rather than
by a screenshot. Reduce Motion holds the `waiting` dot still at full opacity, where the amber alone
still says it. `Theme.attention` is `#B07C00`, the shared token: amber rather than orange, 3.67:1 on
the white of a row and 3.36:1 on the canvas behind the chat status line. `StatusLabel` tints its
word with it too, and only for `waiting`.

A device's own dot is not a session dot and does not follow this table, so `OnlineDot` draws it
instead of `StatusDot`: green when the device is online, grey when it is not, and breathing green
while it updates itself (A22). Both dots share one private `BreathingCircle`, so there is a single
animation to keep honest.

`Tests/RCCoreTests/StatusDotTests.swift` covers the table on hand-built values;
`Verification/StoreChecks.swift` covers it again and checks the demo list carries all five tones at
once.

`Tests/RCCoreTests/SessionGroupingTests.swift` covers the rule on hand-built sessions;
`Verification/StoreChecks.swift` covers it against the demo fixtures.

The list ends the way the Devices list ends: one primary button in the bottom bar, **New session**
(`sessions.new`), drawn exactly as `DevicesView` draws **Add device** — the same
`PrimaryButtonStyle`, the same paddings, the same `barBackground()` — and disabled while no device
is online. The inventory summary that used to hold that bar is now the list's last row, a centred
footnote in the secondary ink under the identifier it always had, `sessions.summary`.
`testSessionsListEndsWithTheNewSessionButtonInTheBottomBar` measures the two bars against each
other rather than trusting a screenshot.

## New session

Device, agent, model, effort, permissions, speed, working directory and git. The sheet does not ask
for a first message, so `session.create` goes out without `first_message`; the protocol keeps the
field. The four agent settings start at the agent's own defaults and change with the agent picker,
so choosing Codex where Claude was selected re-reads every list — and a section whose list comes
back empty is not drawn at all (A25); no agent ships an empty list today. The agent control itself
carries logos rather than names, as the Agents section above describes. Its section
headers use `FieldLabel`, the one label every form section in the app is headed with.

**The browser makes a folder (A37).** `DirectoryPicker` (`Screens/DirectoryPicker.swift`) lists a
device's directories through `device.dirs`; its toolbar now carries **New folder**
(`dirs.newFolder`, `folder.badge.plus`), which reveals an inline row at the head of the list — a
mono field (Folder name), Create, Cancel — rather than an alert: SwiftUI's alert text field was not
readable from the Create action two runs out of three on the simulator, and XCUITest treats a
second alert of the app as an interruption to dismiss. Create sends
`GatewayRequest.mkdir(deviceID:path:name:)` for the listing on screen; the reply is the new, empty
directory's listing and becomes the listing on screen, so Select picks it and the sheet's working
directory is the new path. A `conflict` is said in red under the field ("A folder with that name
already exists.", `DirectoryError`) and the name is kept; any other refusal shows the device's own
sentence. The demo's directories are a real in-memory tree (`Demo/DemoDirectoryTree.swift`) that
`device.dirs` navigates and `device.mkdir` makes, clashes and refuses in, so the flow can be driven
without a device; `testDirectoryPickerMakesAFolderAndPicksIt` drives clash → cancel → make → select
in one launch. Strings 新建文件夹 / 文件夹名称 / 创建 / 已存在同名文件夹。

## A device row opens a terminal (A38)

Tapping a device row opens `TerminalScreen` on that machine when the device is online and
`Device.terminal` is true; otherwise the row says why under itself ("This device is offline." /
"This device does not offer a terminal.") and goes nowhere. The swipe and the context menu read
**Rename · Retry update (only while failed) · Show quota · Revoke**; Show quota pushes
`DeviceDetailView`. `DeviceRoute`, `DeviceTap` and `DeviceRowAction` in RCUI are the pure rules the
row and its tests share; the row is a `Button` with `.contentShape(Rectangle())`, without which a tap
on its whitespace does nothing. When `settings.appLockEnabled`, the screen asks `LAContext` before
opening the shell, even inside an unlocked app; a refusal goes back.

The screen (`docs/DESIGN.md` § "The terminal"): the device's name as the title, **Close** at the
trailing edge, the status line under it — Connecting / Connected / Disconnected with Reconnect /
"Shell exited (code)" with New shell — and `TerminalHost` filling the rest: a `UIViewRepresentable`
around SwiftTerm's `TerminalView` (SwiftTerm pinned at **1.11.2** — 1.12 and later ship a Metal
shader that makes Xcode 26 demand a separate multi-gigabyte Metal toolchain, for a renderer that is
off by default; the package graph is SwiftTerm and swift-argument-parser, nothing else). It feeds
`terminal.output` bytes, reports typed bytes and size changes, copies a long-press selection through
the system menu, and changes its type size on a pinch (`TerminalTypeSize`, remembered in
`SettingsStore`). `TerminalSession` in RCCore is the whole protocol side — open, attach, input,
resize, close, `seq` gaps and repeats, inputs sent in order by one task, resizes debounced — so the
screen only decides when to open, what the status line says and what the key bar sends. A lost
channel shows Disconnected and attaches again by itself when the channel is back, scrollback fed
first; Close or back sends `terminal.close`; backgrounding sends nothing.

`TerminalKeyBar` sits above the keyboard as a `safeAreaInset(.bottom)` — reliable, screenshot-able,
readable by UI tests, and usable before the keyboard is up — in the design's order: Esc · Tab ·
Ctrl · ↑ · ↓ · ← · → · Ctrl-C · Ctrl-D · Ctrl-Z · Ctrl-R · Ctrl-L · | · / · - · ~ · Paste. Ctrl is
sticky and shows its armed state; `TerminalKeys` in RCCore is the pure table from key to bytes
(Esc `1b`, Tab `09`, ↑ `1b 5b 41`, Ctrl+c `03`, …). A gotcha worth keeping: an
`accessibilityIdentifier` on a SwiftUI container renames every element inside it — the one that was
on the terminal screen's root overrode the status line, the emulator and every key, and is gone.
`DemoShell` in RCCore is a fake shell (prompt, echo, `exit`) so the flow runs in the demo and the UI
tests; RCVerify decodes the A38 fixtures; RCUIVerify checks the request bodies, the key table, the
tap rule and the menu order; eleven UI tests drive the row, the menu, the screen and the key bar.

## Devices

One row per enrolled machine: name and latency, the dot with `online`/`offline` and the platform,
the agents it detected, and the client line. The toolbar's trailing button filters the list by
platform (`docs/DESIGN.md` § "Devices can be filtered by platform"): `DeviceFilter` in
`Sources/RCCore/State/DeviceFilter.swift` names the platforms present, first seen first, and narrows
the list to one; the menu is drawn like the Sessions screen's agent filter, identifiers
`devices.platformFilter` and `devices.platformFilter.<platform id|all>`, and an emptied list reads
"No Linux devices" (`devices.platformFilter.empty`). The choice is `@State` on the screen, not a
setting. `Tests/RCCoreTests/DeviceFilterTests.swift`, `RCUIVerify` over the demo's three machines,
and the UI test `testPlatformFilterNarrowsTheDevicesToOnePlatform` cover it. Every row offers the same three actions the web menu
offers, with the same words in the same order — **Rename**, **Update**, **Revoke** — from one
trailing swipe holding all three and from the context menu. Identifiers `device.rename`,
`device.update`, `device.revoke`. Each action opens the same alert whichever way it was reached.

**The row says less** (`docs/DESIGN.md` § "The device row", owner's ruling 2026-09-17). One
`LaptopGlyph` sits at the leading edge in the ink, sized to the title, with the four lines indented
past it: it is the anchor that keeps two machines apart now that no separator is drawn between
them, and it is the same glyph for every platform because the app cannot tell a laptop from a
desktop. The glyph is `Design/LaptopGlyph.swift`: `LaptopShape` places a rounded screen and one
base line on lucide's 24-unit grid and scales them to its rect, and the view strokes it in
`Theme.ink` at 1.5 grid units with round caps and joins, 20 pt scaled with the callout title, its
base line on the title's first baseline — the same drawing the web app gets from lucide's
`LaptopMinimal`, chosen over SF Symbols' `laptopcomputer` whose base is a trapezoid. `RCUIVerify`
checks the path's bounds on the grid and at twice the grid, the base below the screen, the rounded
corner and the stroke ratio. The online dot left the name and sits on the status line with the word it belongs
to, which now reads `online · macOS` — the platform as a word, never the raw id. The hostname, which
on a machine `install.sh` set up is the name, and the architecture are off the row entirely and are
drawn on the machine's page instead, in `DeviceFactsLine` under the navigation title. The agents are
their logos alone, spaced by `Theme.Space.small` and sized `Theme.Mark.control`, each carrying its
agent's name as its `accessibilityLabel`, so the row still reads aloud; their names and versions are
on the machine's page, beside the accounts.

Both lines are one pure function each, in `Sources/RCCore/State/DeviceLine.swift`: `status(_:)` for
the row and `facts(_:)` for the page, with `platformName(_:)` mapping `macos` and `linux` to macOS
and Linux and printing anything else as it arrived. `Tests/RCCoreTests/DeviceLineTests.swift` covers
them, `RCUIVerify` checks every demo device's two lines — no hostname, no architecture, no raw id
and no agent name on the row; hostname and architecture on the page — and
`testDeviceRowNamesTheMachineOnceAndDrawsItsAgentsAsLogos` reads the rendered row's accessibility
label on the simulator.

SwiftUI lays a trailing swipe out from the edge inwards, so the buttons are listed Revoke, Update,
Rename and the row reads Rename · Update · Revoke from left to right; Rename is grey, Update is the
accent and Revoke is the destructive red. There is no leading swipe any more: three actions on one
gesture beat two gestures to find them.

**Revoke, not Remove.** Taking a machine's token away is called the same thing on both apps: the
swipe and the menu say "Revoke", the alert is headed "Revoke device", its message is the web's
(`Revoke <name>? Its token stops working and its sessions leave this gateway. The machine keeps its
agents and transcripts.`), and the destructive confirm reads "Revoke device". `Localizable.xcstrings`
carries 吊销 and 吊销设备, the words `web/src/strings.zh-Hans.ts` uses. A queue row still says
Remove, because removing a held message is not revoking anything.

Every confirmation reads the row it acts on while the tap is still being handled, never inside the
task it starts: dismissing an alert clears the `@State` that holds the device, and it does so before
a task started from the button's action gets to run. Update was written the other way first and did
nothing at all.

**Devices keep themselves current (A22, A36).** `GET /api/config` names the wheel the gateway
serves (`client.version`, `client.build`, `client.url`) and every device reports the build it runs;
the gateway asks a device that is behind to update by itself, so this app shows no client version
and no build hash anywhere — not on the row, not on the machine's page (owner's rulings,
2026-09-18; `docs/DESIGN.md` § "A device keeps itself current"). `DeviceUpdate.Notice` in
`Sources/RCCore/State/DeviceUpdate.swift` has two cases, and `DeviceUpdateLine`
(`Screens/DeviceLines.swift`) draws one line, on the row and on the page alike, only when there is
one:

| `Device` says | The row and the page read | Actions |
| --- | --- | --- |
| `update_state: "updating"` | "Updating…", with the row's dot pulsing | Rename, Revoke |
| a refusal this app is holding, or `update_state: "failed"` | "Update failed · &lt;message&gt;" | Rename, **Retry update**, Revoke |
| anything else | nothing | Rename, Revoke |

**Retry update** (`device.retryUpdate`, zh 重试更新) sits in the trailing swipe and the context menu
only while the device is `failed`, and beside the notice on the machine's page; it is disabled, with
its reason on the accessibility hint, while the device is offline or the gateway serves no wheel
(`DeviceUpdate.Block`: `.offline`, `.noServedBuild`). Its alert still names what it would install —
"Update macbook-air to &lt;version&gt;? Its service restarts; sessions it drives are stopped.", from
`DeviceUpdateText.confirmation(name:servedVersion:)` in `Screens/DeviceLines.swift`, falling back to
"…to the gateway's client?" on a gateway whose config carries no version. Confirming sends
`device.update {device_id, build}` and says nothing on success — `device.updated` carries the state
the row draws from then on; a refusal (`conflict`, `unsupported`) never reaches the gateway's
record, so `AppModel.deviceUpdateErrors` holds it against the row that asked. The demo's `macbook-air`
is a device whose automatic update failed ("the device did not come back"), which is the Retry
story the UI tests drive and the screenshots show; the other two machines say nothing about their
client. `Tests/RCCoreTests/DeviceUpdateTests.swift` covers the rule on hand-built devices,
`Verification/ProtocolChecks.swift` against the fixtures, and `RCUIVerify` reads the row and page
lines off the demo.

**Add device asks nothing.** The sheet is one sentence, the one-liner with a Copy button, the code
with its countdown, the checklist and a Manual install link. There is no platform control: the
installer tells macOS from Linux itself (`uname`), so the command is the same on both, and a
question whose answer changes nothing is not asked. The gateway still hands out `install.macos` and
`install.linux` so a platform whose command really differs can be added without a wire change; the
app reads one of them through `InstallCommands.command`, which says in a comment why either key
does. `PairingFlow` holds no platform, and `Verification/StoreChecks.swift` checks the two keys
carry the same command.

**Cancel leaves at once.** The sheet's Cancel dismisses first and gives the code back behind the
closed sheet, and `PairingFlow.cancel()` keeps the code on screen until the gateway has taken it —
it used to blank the flow and then wait for the answer, which drew the "Requesting a code"
placeholder on a sheet that was already closing (owner's report, 2026-09-18). A scan's claim swaps
codes the same way, without a gap. `Tests/RCCoreTests/PairingFlowTests.swift` holds `cancel()` to
that with a gateway whose answer the test releases, and the pairing UI test ends by cancelling.

**Scan a code (A23).** The Add device sheet keeps the code flow first and adds **Scan a code**
beside it. The scanner is a full-screen camera with the two steps on a card over it — the one-liner
`curl -fsSL <origin>/install.sh | sh` with a Copy button, then "Point this camera at the QR code it
prints." — a Cancel button top right, and a status strip along the bottom. A payload is claimed only
when it parses as `<this gateway's origin>/pair#<token>` with a Crockford token
(`PairingClaimLink`); the origin is checked against both the address this app dials and the one the
gateway publishes, since a LAN sign-in reads a QR code carrying the public one. Anything else says
"That code belongs to a different gateway" in the strip and the camera keeps looking. A claim
cancels the code the sheet had minted, adopts the one the gateway minted for that host, and the
sheet shows the progress the code flow already shows. `404`/`410` read "This code has expired. Run
the command again on the host."; `409` reads "This code was already used."

The camera is behind `CodeScanning` (`Sources/RCUI/Screens/CodeScanner.swift`): `CameraCodeScanner`
prefers VisionKit's `DataScannerViewController` and falls back to an `AVCaptureMetadataOutput`
session where it is unavailable, and `StaticCodeScanner` hands over a printed payload on a tap. A
simulator has no camera, so the demo takes the stand-in and the UI test drives the whole flow
through it. The fallback session starts from `viewWillAppear` rather than `viewDidLoad`, because
`viewDidDisappear` stops it: a sheet presented over the scanner, or the app backgrounded and
restored, used to leave a frozen preview that read exactly like a camera that was looking.

**A camera the app may not use says so** (`docs/DESIGN.md` § "The three screens"). `CodeScanning`
asks for the camera before anything is drawn — `requestAccess()`, which `CameraCodeScanner` answers
through `Camera.requestAccess()` (`Sources/RCUI/Attachments/CameraAccess.swift`) and every scanner
with no camera behind it answers `.allowed` by default. `ScanPairingView` holds the answer and
draws the viewfinder only for `.allowed`; `.denied` — which is `denied` and `restricted` alike,
because they read the same to the reader — puts one line where the camera would be, "Allow camera
access in Settings, or type the code" (`scan.cameraRefused`), with an **Open iOS Settings** button
(`scan.openSettings`). The status strip is not drawn at all until there is something to say, so
"Hold steady — the QR code is detected automatically." is said only while a camera is actually
looking. Before this the `try?` around `startScanning()` and the `try?` around
`AVCaptureDeviceInput` both swallowed the refusal and the reader got a black frame under a strip
that claimed to be scanning.

**A device has a page (A33).** The row itself opens the machine; its swipe and its context menu
still act on it without going anywhere. `DevicesView` wraps each row in a `NavigationLink(value:)`
carrying the device id and answers it with `.navigationDestination(for: String.self)` on the Devices
`NavigationStack` — an id rather than a `Device`, because a device value changes on every latency
report and a destination keyed by the value would rebuild the page several times a minute. The three
row actions are not repeated on the page.

`DeviceDetailView` (`Sources/RCUI/Screens/DeviceDetailView.swift`) is the page: the name in the
navigation bar, then the machine as its row words it — `DeviceStatusLine` (`Screens/DeviceLines.swift`)
is the row's own line, lifted out so the page and the row cannot drift apart — then
`DeviceFactsLine`, which is the page's alone and carries the hostname and the architecture the row
dropped, then `DeviceClientLine`, the page's full client line where the row (`DeviceRowClientLine`)
says only the version or only the notice, and then one `AgentAccountCard` per available agent, in the device's
order.

A card names the agent by logo and name with its version, and under it one line per credential:
*Anthropic account · Max · Max 5x · me@example.com*, *OpenAI API key · api.relay.example*,
*Not signed in* for an agent that reported `accounts: []`, and nothing at all for an agent whose
`accounts` is absent, which is what an older client says. The wording is `AccountLine` in
`Sources/RCCore/State/AccountLine.swift`: a three-entry vendor table (`anthropic` → Anthropic,
`openai` → OpenAI, `xai` → xAI) with an unknown id printed as itself, the plan raised at its first
letter, and the tier, the email and the host printed exactly as the device sent them. Nothing on
the page reads an agent id beyond the logo lookup the row already does.

**The meters.** `QuotaWindow` (`Sources/RCCore/State/QuotaWindow.swift`) names a window from its
length — 300 → *5-hour*, 1440 → *24-hour*, 10080 → *7-day*, anything that is not whole hours in
minutes — appends the scope where the vendor confined it to one, and decides the fill's colour:
`Theme.ink` to 80 %, `Theme.attention` past it, `Theme.danger` at 100. No other colour appears on
the page. "resets 15:40" today and "resets Tue 22:00" otherwise, formatted in the interface
language's own locale, which the page reads from `\.locale`.

**Where the fresh figures live.** The credentials are already in the device list, so they are on
screen the moment the page opens; the windows are read on request. The page sends `device.agents`
from its `.task` and again from `.refreshable`, and keeps the reply in its own `@State` — never in
the device store, so the list behind it never redraws because a percentage moved, and
`agents.updated` keeps its quarter-hour silence. Until the reply lands each account line carries
*Checking…*; an offline machine is not asked at all and reads *Offline · quota unavailable*; a
`device_offline` refusal reads the same; an account carrying `limits_error` shows that line and no
meter; an account with neither windows nor error — Grok Build — shows its line alone. A key never
has a meter, not even a waiting one.

Identifiers: `device.page`, `device.agent.<id>`, `device.agent.<id>.signIn`, `device.quota.checking`,
`device.quota.offline`, `device.quota.error`, `device.quota.meters`, `device.quota.failure`. The
agent's is on its **name**, not on the card: an `accessibilityIdentifier` on a stack that is not
itself an accessibility element is copied onto every text inside it, and the card's identifier
swallowed the sign-in line's and the meters' the first time. The UI test caught it.

The demo spreads the shapes across its three machines: `mac-studio-office` has Claude on an
Anthropic account with a tier and three windows, Codex on `pro` with two, Grok Build on an account
whose vendor exposes no window at all, and pi with one credential per provider — an account and a
relayed key; `macbook-air` has a Claude account whose token expired (a `limits_error`) and a Grok
Build signed in nowhere; `ci-runner-01` is offline. `DemoFixtures.agentsWithQuota(deviceID:)` is the
reply, `DemoFixtures.devices` the list, and the two are deliberately different: nothing carrying a
window is ever stored.

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

**A box around a label is `@ScaledMetric`, never a constant.** `Theme.Touch.minimum` and
`Theme.Touch.primary` are the sizes at the default text size; a frame pinned to one of them around
a label clips long before the largest accessibility size. The composer's Send circle and control
row take `@ScaledMetric(relativeTo: .body)` from `Theme.Touch.primary`, and its attachment pills
`@ScaledMetric(relativeTo: .caption)`, the way `CommandPanel` and `AgentLogo` already do.
`testSendCircleGrowsWithAccessibilityText` launches with
`-UIPreferredContentSizeCategoryName UICTContentSizeCategoryAccessibilityExtraExtraExtraLarge` and
measures the circle.

Settings is `docs/DESIGN.md` § "The Settings screen", one file per part under
`Sources/RCUI/Screens/Settings/`: the header and the versions line sit on the canvas
(`canvasRow()`), and the groups — Account, While you're away, Voice, Reading, Security — are
`SettingsGroup`s. **A group is one `List` cell, not one cell per row.** `List` drew a separator
between the second and third rows of the Voice group whatever `listRowSeparator` was asked of
every row, of the section, and of every variation tried (row type, row order, a `.task`, a
`.disabled`, a trailing `if`, a `Menu` in place of a menu-styled `Picker` — six builds); a surface
that is a single cell has no boundary for it to draw on, so the rows are a `VStack` inside one
cell and carry their own insets (`settingsRowPadding()`). Rows are `SettingsRow` (a
`SettingsLabel` — title and one sentence — with the control at the trailing edge),
`SettingsMenuRow` (a `Menu` holding a `Picker`, whose label is the whole row, so the row is the
target), a `Toggle` whose own label is a `SettingsLabel`, or a `Button` labelled with a
`SettingsActionLabel` (Users, Change password with a chevron; Sign out in the danger ink). Users
is pushed with `navigationDestination(isPresented:)` from `SettingsView`, not by a `NavigationLink`
inside the cell, which would make the whole cell the link. No footer: a state — notifications
blocked (the row's tap then opens iOS Settings), no transcription or polish service, a failed model
list, a refused preference — replaces the row's sentence and disables its control. Two options are
`.segmented` inside the row (Language, Detail); the versions line (`VersionsLine.text`,
`settings.versions`) reads `Remote Control x · Gateway y · Protocol vN` with Diagnostics beside it,
stacked under it on a phone too narrow for both. The sentences are `String`s built with
`L10n.string`, so every group takes the interface language as a value and is rebuilt when it
changes. `ValueRow` (label left, monospace value right, middle truncation) and `settingsRowLayout()`
survive for the update-required screen, the accounts screen and the resume notice.

**Nothing is re-cased, and it takes saying so twice.** `docs/DESIGN.md` § "Surfaces, rows and
controls" allows no `text-transform` anywhere, and the app broke that rule in two ways at once: a
group caption called `.uppercased()` on the words itself, and a `Section` inside a `Form` re-cases
whatever it is handed as a header regardless. So `FieldLabel` — the one label every form section in
the app is headed with, Settings and the new-session sheet alike — spells
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
| `attach: "extension"`, `attach_ready: false` (A26) | Run rc-client pi setup on the device to attach its pi sessions |
| `attach: "leader"`, `attach_ready: false` (A28) | Run rc-client grok setup on the device, then restart Grok |
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
| `shared_settings` | the model card and the permission-mode picker | `allowsSettingsChanges` |
| `shared_attachments` | the attachment button, so photos and files go into the live thread | `allowsAttachments` |

Both are read straight off `AgentInfo`; nothing in the app branches on the agent id. Stop is
unaffected and still needs capability `interrupt` plus `shared_interrupt`. A `terminal` session
takes no input whatever it reports, so `allowsAttachments` stays false there. Both the card and the
permission picker are chips on the composer row and are drawn only where they are live, so neither
ever opens on a session it could not change.

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
| The scrollable range changes — rows arrive, the keyboard opens or closes — while nobody is scrolling | Never counts as the reader moving: someone at the bottom is pinned there, someone away is left where they are, and a range that shrank until nothing scrolls puts them back at the bottom |
| The range changes while the reader's finger is on the transcript, or a fling is still running | The reader wins: the numbers are theirs, following is read from where they are, and nothing scrolls under them; content that arrived meanwhile is caught up on when the finger lifts, if they stayed at the foot |
| The way back down is tapped | Scrolls to the tail, again from where it landed if it landed short, and follows once the numbers say it is there |
| A message is sent | `ChatStore.deliver` returns to the tail before the message lands |
| Earlier messages are paged in | Prepended above the anchor the reader was looking at |

Whenever the reader is not at the bottom, a round white button with a down arrow floats at the foot
of the timeline, centred above the message field: identifier `chat.jumpToLatest`, label "Jump to
latest". It fades in and out over 0.18 s, carries the count from `ScrollTail.badge(updates:)` when
something arrived while they were away ("3", and "99+" past a hundred), and tapping it returns to
the tail and resumes following. It is the one control on this screen with a shadow rather than an
edge, because it floats over the transcript.

Tapping it is a journey rather than a scroll. `proxy.scrollTo` puts the end of a `LazyVStack` where
the list guessed the rows it had not laid out would be, and measuring them on the way there moves
the end again, so one scroll from pages away can arrive short of the tail. `scrollToTail` is
therefore a small task: it scrolls, waits out the 0.2 s animation, reads the geometry back, and
while `ScrollTail.jump(attempt:atBottom:limit:)` says `.again` it scrolls again from where it
landed, up to `ScrollTail.jumpLimit` times — a bound, so a transcript growing faster than it is
scrolled cannot hold the view for ever. Following resumes only when the numbers say the tail is on
screen, never on the strength of having asked for it, and since following is also what takes the
button away, the button leaves only once the tail is really there, which is the DESIGN ruling. A
finger back on the transcript (`tracking`, `interacting`) ends the jump, because where the reader
takes it is where they want to be; leaving the screen ends it too. The landing short was not
reproducible in the demo: with the transcript grown to eight screens of very uneven rows and the
reader at the top of it, the old one-shot scroll still reached the tail (three runs of
`testJumpToLatestLandsAtTheTailFromFarUp`, round 26), so what is fixed here is the rule — arrival is
now read from the scroll view rather than assumed — rather than a recorded trace.

Who is moving is read from the scroll view itself: `onScrollPhaseChange` says whether a finger is
tracking or interacting, a fling is decelerating, a scroll the view started is animating, or nothing
is moving, and `ScrollTail.decide(rangeChanged:atBottom:following:motion:)` in RCCore is the whole
rule as one pure function over that and the three numbers. It exists because of a bug: after a turn
ended, rows kept settling — the thinking row folding, tool rows finalising, the status line going —
and a long transcript's `LazyVStack` re-measures rows as they scroll into view, so the scrollable
range moved on nearly every frame of an upward drag; the old rule read every range change as
"content grew" and, with following still on, scrolled back to the tail each time, which a finger on
the screen cannot win against. Now a range change during a drag or a fling is the reader's, a scroll
the view started can only confirm that it arrived (`scrollToTail` also arms a 0.45 s window, because
the proxy's scroll is reported as animating only once it is under way), and only a change while
nothing is moving pins a reader at the foot. The reproduction was by reasoning from the geometry
callbacks, not a recording; the rule is unit-tested over its table.

**The open transcript has a ceiling.** `Timeline` holds at most `Timeline.entryLimit` (3000) rows.
Live events past that drop the oldest and set `hasMoreHistory` back to true, so scrolling up pages
them from the gateway again. A page of history is not trimmed: it is older than everything held, so
trimming after one would throw away exactly what the reader scrolled up to see. `ChatStore.rows`
keeps its answer until the timeline's version or the detail level moves, because SwiftUI reads it
on every render pass and the filter runs over the whole transcript.

**"Open full output" cannot revert a block.** `session.block` answers with the seq of the version
it holds, so a block that streamed on while the request was out answers below the row's newest seq
and is refused; an answer at the same seq is the untruncated copy of what is on screen, which is
the point of the request. When the reply is the first event to carry `first_seq` for that block,
the row moves and the transcript is re-sorted, as a live replacement already was.

**A closed conversation stays closed.** `ChatStore.close()` cancels the resubscribe a `hello` or a
detected gap may have started and sets a flag `subscribe()` checks, so a conversation closed at the
moment the socket came back cannot leave the gateway streaming a transcript nobody is reading.

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
| Permission mode | the same, and `AgentInfo.permissionModes` is non-empty (A25) | `composer.permissions` |
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
- the effort slider, one stop per `AgentInfo.efforts` entry. It is `StopSlider`
  (`Sources/RCUI/Design/StopSlider.swift`), not `Slider`: a 28 pt pill track in `Theme.quietFill`
  filled to the thumb in `Theme.accent`, one 6 pt dot at every stop — `Theme.inkTertiary` on the
  unfilled part, white at 60 % on the filled part — and a 24 pt white disc with a soft shadow that
  snaps to the stops under a `DragGesture(minimumDistance: 0)` on a `GeometryReader`, so a tap on a
  stop moves there too. Nothing else is drawn: no numbers, no labels under the track. The word in
  the first row follows the thumb and `session.set {effort}` is sent on the drag's end, so dragging
  across four levels is one request rather than four. `.sensoryFeedback(.selection, trigger:)` on
  the stop index gives one selection haptic per stop the thumb crosses, which is what lets the
  levels be counted without looking. VoiceOver reaches it as one adjustable element with an
  `.accessibilityAdjustableAction` for increment and decrement. An agent with one effort level or
  none draws no slider: there is nothing to slide. An agent that lists none at all loses the effort
  word after the model name as well, so its card reads the model alone.

**A width that never changes.** The chip and the card's name row are as wide as the widest
model-and-effort combination the agent offers, so nothing beside them shifts while a level is
chosen or a model is picked. The width is measured, not guessed: `ModelCardSizing.pairs(for:model:)`
returns every `models × efforts` pair — the fallback name standing in where the agent lists no
model — and `ModelCardSizer` stacks one hidden label per pair behind the visible one in a `ZStack`,
with `.hidden()`, which keeps the layout and drops the drawing, and `.accessibilityHidden(true)`.
The chip measures `ModelCardLabel`, with the lightning's width reserved wherever the agent lists a
tier; the name row measures `ModelNameLabel`, the same two fonts and the same spacing it draws.
The card's own `frame` is `minWidth: 280` — the name row sizes it now, and it is never narrower
than it was.

**Drawn at once.** `ChatStore.set(...)` applies the patch to `session` before the request leaves,
so the lightning fills, the effort word changes and the model name switches on the tap rather than
on the reply. The device's reply confirms it; a refusal puts the previous value back with the
error, unless a newer session replaced the optimistic one meanwhile — a `sessionGeneration` counter
bumped by `session.updated`, by a `meta` event carrying settings and by every reply says which.
`Tests/RCCoreTests/SessionSetTests.swift` covers all three outcomes.

The accessible names are "Model", "Effort", "Speed" and "Permissions", with the value on each; the
identifiers are `composer.modelCard`, `composer.model`, `composer.effort`, `composer.speed` and
`composer.permissions`. A terminal-held session shows the same words as one static chip,
`composer.readonly.modelCard`, that opens nothing, with the tier's glyph on it and the tier's name
spelled out in its accessibility value — the glyph says "faster tier" to the eye and nothing at all
to a screen reader. `ModelCard.swift` holds all of it; `TerminalSetting.modelCardText` words the
session once, so the live chip and the read-only chip can never disagree.

**After the card, the permission picker.** `composer.permissions` is a `Menu` holding a `Picker` of
`AgentInfo.permissionModes` with the current one marked, built the way the dictation-language chip
beside it is; choosing one calls `chat.set(permissionMode:)` and is drawn at once. A plain list and
nothing else: what runs and how hard comes first, what it may do second.

Forms keep list pickers. The new-session sheet lists Model, Effort, Permissions in that order, with
a `SpeedPicker` after them where the agent offers a tier; it sends `model`, `permission_mode`,
`effort` and `speed` in `session.create`, starting from the agent's own defaults so a sheet sent
untouched asks for what the device would have chosen anyway. There is no session settings sheet:
model, effort and speed are the card's, permissions are the picker's, and the dictation language
has a picker of its own.


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

**While dictation runs, the field follows the words** (`docs/DESIGN.md` § "The composer"). The
composer asks for it with `followsTail:` while the dictation phase is `listening` or `finishing`,
and never otherwise. Typing needs nothing, because the caret keeps itself visible; dictation writes
with the keyboard down and no caret to follow, which is why the last line had to be asked for.
`TailFollowingTextView` takes the tail in `layoutSubviews` rather than where the text is written,
because that is the first moment
the view has both the height SwiftUI gave it and the size of the text now in it — a text that has
just grown past the cap is still one line shorter there, and a scroll aimed at that lands short. It
sets `contentOffset` rather than scrolling to the end of the text, so nothing in it has an opinion
about the selection the field gets when it is later tapped, and never animates: the words arrive in
bursts and an animation would still be running when the next one lands. Scrolling down is the only
move it makes, so a field already showing its last line is left alone, and so is one that is not
following. Tapping the field ends dictation through the takeover overlay and leaves the offset
where the words ended, since nothing moves it once the flag is off.

Two debug launch arguments prove it. `--voice-transcript=long` gives the scripted speech platform a
dictation of about a minute, which wraps to twice the eight lines the field grows to at any width,
delivered in four partials the way a long one really lands. `--field-scroll-probe` puts one more
element beside the field, `composer.prompt.scroll`, whose label is `<offset>/<end>` in whole points: a UI test cannot ask a text view where it is scrolled to, because `value` on one
reports the whole draft whether the field is showing its first line or its last, and equal numbers
are what "the last line is the one in view" looks like from outside.
`testALongDictationKeepsItsLastLineInView` types eight lines to measure what eight lines are worth,
takes them back out, dictates the long one and holds the field's height and its scroll position
against that — before and after Done.

**The `+` menu presents nothing itself.** Files, Camera and Photos are three buttons that set a
flag; every presenter — the file importer, the camera cover and `.photosPicker` — sits on the
composer beside the others. Photos used to be a `PhotosPicker` built inside the `Menu`, and on a
phone it did nothing at all while the other two worked: a menu item's view leaves the hierarchy the
moment the menu closes, so the picker it was asked to present never arrived. Nothing about
`ingest(_:)` changed. `testPhotosOpensThePickerFromTheAttachMenu` taps `+` → Photos and waits for
the system picker's own collection, which is identified rather than named because the picker is
titled in the phone's language and not the app's.

**Camera is offered only where there is one**, and only after the camera says yes.
`UIImagePickerController` documents that a source type must be checked with
`isSourceTypeAvailable(_:)` and raises otherwise, so `Camera.exists` gates the menu item and
`CameraCapture` checks it a second time before setting the source. The tap itself goes through
`Camera.requestAccess()`; a refusal writes the notice line the composer gives any denied
permission — "Allow camera access in Settings, or attach a photo instead." — and presents nothing.
`testAttachMenuOffersNoCameraWhereThereIsNone` reads the menu on the simulator, which has no
camera: Files and Photos, and nothing for a camera that is not there.

**Attachments are named for what they are** (`docs/DESIGN.md` § "The composer").
`AttachmentNaming` (`Sources/RCUI/Attachments/AttachmentNaming.swift`) owns the rule: a library
photo is `photo-1.jpg`, `photo-2.jpg`, … by the order it was attached in, a camera shot is
`photo.jpg`, and both carry the extension of what is actually sent, which is always JPEG because
every photo goes through `PhotoPreparation.jpeg`. The composer used to name a library photo with
`PhotosPickerItem.itemIdentifier`, which is a `PHAsset` local id — `B84E8479-…/L0/001`, a UUID with
slashes and no extension — so the pill, the bubble and the file the device wrote all read as a raw
identifier and the agent was handed a path with nothing to say it was an image.

**A picked file is read, not mapped.** `PickedFile.read(_:)`
(`Sources/RCUI/Attachments/PickedFile.swift`) is a plain `Data(contentsOf:)`. The security-scoped
access a picked URL carries is released by the ingest loop's `defer`, while the bytes are not
touched again until the message is base64-encoded on its way out; for a file outside the app
container, faulting a page of a mapping whose scoped access has ended is a `SIGBUS` rather than a
thrown error. The 6 MB cap `PickedFile.size(of:)` checks first is what makes the eager copy cheap.

Above the field the composer draws one line at most, and only while something is happening to it: an
attachment that was refused, what dictation is doing, or where the argument of a slash command
already named goes (A27). It never says who owns the session — the
header above the transcript already reads `terminal · attached`, and a control the app cannot drive
is absent rather than dimmed under a caption explaining why. So the composer is the field row plus
one control row, with a strip of attachment pills between them while a message carries files.

**A draft goes when its session does.** `DraftStore.retain(_:account:)` drops the drafts of
sessions the `hello` snapshot no longer lists. `AppModel.adoptSnapshot()` calls it once per
snapshot, from the same `onChange(of: connection.hasSnapshot)` the landing rule reads, and never
for the demo, whose scripted device resumes and archives sessions as the run goes on. `signOut()`
clears the account's draft file alongside its cache, and does it **before** `connection.signOut()`,
which empties `user` and drops the endpoint — after it, `connection.account` would name nobody. A session deleted on the device used to keep its text on disk for the life of the
install. A refused send returns the words through the store and the attachments through the view:
`ChatStore.send` answers `accepted`, `uncertain` or `refused`, and the composer puts its files back
on `refused` unless newer ones were attached while the request was out.

## Slash commands

Amendment A27: a developer at a Codex, Grok Build or pi terminal types `/` and gets a list; the
phone gives the same list for the same keystroke. `docs/DESIGN.md` § "The composer" is the contract
and the web app implements the same one.

The rule lives in `RCCore` so both the card and Send read one copy of it:
`SlashDraft.parse(_:)` in `Sources/RCCore/State/SlashDraft.swift` says whether a draft is a command
at all, `filter` and `match` say which rows are left and which command a finished word names, and
`CommandSection.build` groups them. `ChatStore` exposes the whole of it — `offersCommands`,
`commandRows`, `commandSections`, `commandHint`, `draftCommand`, `commandsWaitForTurn` — so the
panel, the hint line and the send button can never disagree about what will happen.

| What is typed | What is drawn |
| --- | --- |
| `/` on an agent with capability `commands` | the card, every command the session offers |
| `/rel` | the same card, filtered by prefix of the name, case ignored |
| a row is tapped | `/name ` when the command takes an argument, `/name` when it does not |
| `/release-notes ` | no card; the hint line under it, `/name` and where the argument goes |
| `/nonsense` | nothing; the words are an ordinary message |
| anything, on Claude | nothing, ever: `/` is a character and no caption explains it |

`CommandPanel` in `Sources/RCUI/Screens/CommandPanel.swift` is the card, above the message field
and over the keyboard. One row per command: `/name` in the monospace face, the description after it
in the secondary ink, and the argument placeholder at the trailing edge in the tertiary one, the
description being the part that gives way when the line is tight. Group headers are drawn only when
more than one group is on screen, because a single header over the whole list names nothing the list
does not already say. A tap takes a row, gives one selection haptic, and asks for the keyboard back
— the screen's background tap would otherwise lower it, since a row is not the message field. A
screen reader reads each row as "Command, /compact, Summarise the conversation", and the group
headers carry the header trait so it can jump between sources.

**It is as tall as its rows and no taller.** The cap is `SlashDraft.visibleRows`, eight, past which
the card scrolls; the frame is a *maximum* rather than a height, and `ChatView` gives the composer
`.layoutPriority(1)`, so the transcript is the view that gives way and the control row under the
field is never pushed off the screen by a long list.

**A command runs between turns.** While one is running every row is dimmed, the card carries one
footer, "Available when the turn finishes", and `canSend` is false for a command draft, so Send does
not act. The send-mode menu is not offered either: a command has no queue to join and no turn to
interrupt.

Sending routes on the first word. A draft that names a command goes out as `session.command
{name, argument}` with the request id as the block id, exactly as `session.send` does under A12, so
the row is in the transcript before the request leaves; a refusal removes it and puts the words back
in the field. Attachments are left where they are — a command carries none, and a pill the user
added is not ours to discard. The primary button names itself Run rather than Send while a command
draft stands.

The list is fetched when the conversation opens (`ChatStore.open`) and asked for again when `/` is
typed if the last answer is older than `ChatStore.commandsStaleAfter`, 60 seconds, or was empty; a
failure keeps the last list rather than raising a banner, because nobody asked for that request.

**What a command prints survives the Simple detail level.** A tool call whose `tool` begins with `/`
is the whole answer to something the reader asked for by name, so `TimelineEntry.isDrawn(at:)` keeps
it at every level. Simple hides the agent's own working, not the reply — and since A34 the working
it hides includes a `user_message` whose `source` is `agent`, which is another agent's report rather
than anything written to the reader (see "Messages from other agents").

In the demo, `mac-studio-office`'s pi session carries nine commands across Prompts, Skills,
Extensions and Built-in, so the card sections and scrolls; the Codex thread carries the eight-entry
table with no groups at all, and running `/usage` on it after Stop brings back the tool-call block
the protocol's own fixture shows. Grok Build's twelve advertised commands are served too, but its
demo session is held by a terminal, which takes nothing typed here, so no card opens on it.
`DemoFixtures.commands(for:)` holds all three lists.

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
| `control: "terminal"` | "Controlled by the terminal", plus " · take over to send" only where the agent lists `takeover` |
| A question is pending | "Waiting for your answer" (A20), which outranks the turn it interrupted because nothing is queued behind it |
| A turn is running | "Working · your message will steer the turn", or "· will be queued", or "· N messages queued" |
| `state: "error"` | Whatever the device put in `state_detail`, which the header has no room for |

An attached session takes the ordinary rules rather than a rule of its own, so a running shared
Codex thread reads "Working · your message will steer the turn" and an idle one reads nothing at
all. `docs/DESIGN.md` holds the same table for both apps.

**The take-over clause belongs to the status line alone.** `ChatStore.terminalControlNotice` builds
that line, and names the way out only where there is one: Codex and Grok Build advertise no
`takeover`, so a terminal-held session of either reads "Controlled by the terminal" and nothing
more, rather than inviting a tap that would be refused. The disabled field's placeholder
(`sendBlockReason`) is the short sentence on every agent — a placeholder that repeated the line
above it word for word, and then truncated, said less than half of it did.

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
| The reply is an error the gateway actually sent | The row goes, the message is shown in the composer, and the text returns to the draft if the user has not started another one — and the composer puts its attachment pills back beside them |
| The socket dropped, or the request timed out | The row stays, "Delivery unconfirmed" and Retry appear, and the retry reuses the id rather than sending a second copy. Retry is disabled while it is out (`OneAtATime`, `Sources/RCUI/Design/OneAtATime.swift`): the id is reused, so two overlapping retries would be two requests under one id |
| Nothing at all for 60 s | The row says "Delivery unconfirmed" itself, through `OptimisticMessage.isUnconfirmed(at:)` |

A resync keeps these rows — a message the user just typed must not vanish because the socket came
back — and the reloaded history reconciles them, so a reconnect neither drops nor duplicates one.
`Tests/RCCoreTests/OptimisticSendTests.swift` covers every line of that table.

**A refused send returns everything**, the words and the attachments alike (`docs/DESIGN.md` §
"The composer" → **A draft belongs to its session**). The composer clears its pills optimistically
with the field, and `ChatStore.send` says what became of them: `accepted` and `uncertain` keep them
cleared — the `PendingSend` holds the bytes and Retry re-sends the same message — while `refused`
and `empty` put them back, because nothing was ever on its way. Newer pills attached while the
request was out are the person's, so they are not overwritten. They used to be dropped before the
await and never put back, so a second tap sent the words without the files and nothing said they
had gone.

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

**Done becomes a spinner, and the spinner becomes Send** (`docs/DESIGN.md` § "The composer"). The
tap on Done is answered in the same pass: the capsule gives way to Send's circle holding a
`ProgressView` tinted `Theme.onAccent` at `Theme.Touch.primary` (`WorkingCircle`,
`Sources/RCUI/Design/WorkingCircle.swift`, identifier `composer.working`), in the same slot against
the trailing edge and at the same height. It is not a `Button`, and not a disabled one either:
nothing in it can be tapped, because a control that looks live and does nothing is what the earlier
form got wrong — `PrimaryButtonStyle` has no disabled look, so the Done that stood there through
`.finishing` read as tappable.

What the slot holds is one derivation and not a view's opinion: `ComposerPrimarySlot`
(`Sources/RCCore/State/ComposerPrimarySlot.swift`, pure, tested over all twenty-four pairs of
phases) answers `done` while the microphone is live or being asked for, `working` in
`VoiceInputPhase.finishing` and in `PolishPhase.polishing`, and `send` everywhere else. Both rows
read it from `Composer`, so the slot cannot disagree with itself when the voice row gives way to
the ordinary one. The spinner says in words what it is waiting for — "Finishing the transcript" in
the voice row, "Polishing…" in the ordinary one, the same strings the status line uses — because a
spinner alone says only that something is happening.

While the transcript is finishing the meter rests and the elapsed clock stops at the moment Done
was tapped: what it counted is how long the microphone was open. Once the transcript is final the
ordinary row (`+`, mic, chips) returns around the spinner, which keeps the slot until the words are
back. The capsule-to-circle change eases over 0.2 s, and not at all under Reduce Motion.

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

**How bright, and why (round 27).** `docs/DESIGN.md` § "The composer": the glow is meant to be
seen, not found. The first pass was faint at rest and swelled by about a third, which read as a
meter nobody noticed. `VoiceGlowField` draws three strokes on the same rounded rectangle, each a
width, a blur and an opacity that rise with the level `e` (0…1):

| Stroke | Width | Blur | Opacity | Was |
| --- | --- | --- | --- | --- |
| Halo | 34 + 30e | 22 + 10e | 0.22 + 0.20e | 30 + 16e, 20 + 6e, 0.13 + 0.09e |
| Skirt | 12 + 12e | 8 + 3e | 0.36 + 0.24e | 11 + 6e, 7 + 2e, 0.26 + 0.12e |
| Rim | 4 + 4e | 3 | 0.60 + 0.30e | 3.5 + 2e, 2.5, 0.44 + 0.16e |

The resting light is roughly doubled and the swing with it, so silence is plainly a band of light
along all four edges and a conversational voice is unmistakable; the halo is still the widest and
the faintest, so the page stays readable to the margin and the colour never lands on the words. The
breathing is unchanged — a 0.14 s rise and a 0.32 s fall — and so is the Reduce Motion path, which
holds `expansion` at 0.3 and drives nothing from the voice.

The other half was the scale itself. `InputLevel` (`Sources/RCUI/Voice/InputLevel.swift`) maps a
buffer's RMS through decibels, and both backends now go through it rather than each carrying the
same line. It ran from −55 dBFS to 0, which is the dynamic range of the format rather than the
range of a voice: ordinary speech sat between 0.4 and 0.6 and the glow only ever used the middle of
its swing. It now runs from −50 dBFS, a quiet room, to −12, conversational speech at arm's length,
so a voice spans the scale.

The figures were chosen by eye from before-and-after screenshots of the `--voice-preview` listening
state at `--voice-level=0`, `0.5` and `1`, which is what that argument exists for.

### No maximum duration

Listening ends when the user taps Done, when the app is **backgrounded**, or when the recognizer
fails. `VoiceInputController` arms no deadline at all; the only timer it owns is how long a backend
may take to answer `finish()`, and `isAwaitingFinalTranscript` says when that one is up.

Backgrounded, and nothing less. `SceneRule` (`Sources/RCUI/Screens/SceneRule.swift`) is the one
place that reads a `ScenePhase`, and it says three things: `.background` is leaving the app,
`.active` is the app being used, and the privacy shield alone follows `.inactive`, because that is
where the switcher's snapshot is taken. Control Centre, the app switcher's peek, an incoming-call
banner and a system permission alert all make the scene inactive and nothing more — dictation
listens through them, and the app lock stays down through them, exactly as its own footer promises
("when the app returns from the background"). Both handlers used to test `phase != .active`, so a
Control Centre pull mid-sentence ended the recording with no message — `suspend()` clears
`authorizedRun`, and coming back could not restart it — and cost a Face ID on the way back.

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


## The screen stays awake in a conversation

`ChatView` is the one screen that switches the idle timer off, through the single
`keepsScreenAwake()` modifier in `Sources/RCUI/Screens/ScreenAwake.swift`; the rule it applies is
the pure `ScreenAwakeRule.awake(chatOnScreen:sceneActive:)` in RCCore — awake only while a
conversation is on screen and the scene is active, restored the moment either stops being true. A
counter, not a flag, tracks how many conversations are on screen, because opening a second one from
a notification lays it over the first and their `onAppear`/`onDisappear` interleave; without it the
timer would be handed back while a chat is still showing. Dictation lives inside the conversation,
so a long dictation no longer ends with the screen locking on its own.

## Alerts while the app is open

The gateway's push says on a locked phone that a turn finished or an approval is waiting, when the
operator configured APNs. The app now says the same thing itself while it is in the foreground:
`TurnAlerts` (RCCore, pure) maps a session's transition to the gateway's own kinds — `idle` from
`running`/`needs_approval`/`needs_input` is a finished turn, `needs_approval`, `needs_input` and
`error` are themselves, a session seen for the first time is nothing — and `ConnectionStore` hands
every `.sessionUpdated` to it with the previous and the new session (`onSessionTransition`).
`TurnNotifier` (RCUI) posts a local notification through `SystemTurnAlerts` behind three gates: the
scene is active, the Notifications switch is on, and the system authorization is granted. The title
is the device's name, the body the status word — "Turn finished", "Needs your approval", "Waiting
for your answer", "Errored" — and `userInfo` is the gateway's own push payload, so tapping the
banner takes the existing `PushRoute` → `handle(link:)` path and opens the session.

**A notification opens its session in place** (`docs/DESIGN.md` § "Status vocabulary"). Tapping a
notification, or following a `remotecontrol://session?device=…&id=…` link, for session B while
session A is open replaces A with B: `AppModel.handle(_ link:)` calls `open(session, inPlace: true)`
and the navigation stack becomes `[B]` rather than `[A, B]`, so Back returns to the list and not to
the session that was left. Nothing of A is touched — `closeChat` writes its draft out on the way —
and B is streaming with its composer the moment it is on screen.

Closing is addressed to a named conversation, `closeChat(key:)`, and that is what makes the above
work. SwiftUI delivers the new view's `onAppear` and `.task` **before** the covered view's
`onDisappear`, the ordering `ScreenAwake` already exists because of; a close addressed to "the open
chat" therefore arrived after B was installed and closed B. What was left on screen was
`ChatView`'s third branch — a `ProgressView` with no composer under it — for good, because
`.task` had already run for that view identity and would not run again.
`VerificationUI/main.swift` plays the two callbacks in that order, and
`testALinkOpensItsSessionOverAnOpenOneAndKeepsItsComposer` drives the real link through
`XCUIDevice.shared.system.open` and looks for a composer.

A remote push that arrives while the app is active and connected is presented with no banner,
because the app has
already said it; local ones always show. The Notifications switch now works in the demo too, since a
local alert needs no gateway — and `PushController` no longer registers a device token when there is
no gateway to hand it to (its status reads "On, in this app only"), so the demo still sends nothing
anywhere. The switch is off by default: turning it on in Settings and granting the system permission
is what makes the banners appear.


## Dictation polish (A29)

The Voice group of Settings offers the polish switch, the model and the strength only when the
gateway says it can (`hello.polish.enabled`, also in `GET /api/config`; absent on an older gateway
means disabled, and the switch is then shown disabled with "This gateway has no polish model
configured" as the switch row's sentence). `SettingsVoiceGroup.swift` draws the three controls;
the model list comes from `GatewayAPI.polishModels()` when the switch appears, and a failed list
puts "The model list could not be loaded." in the Model row's own line; the three values are
per-account keys of `SettingsStore` (`polishEnabled`, `polishModel`, `polishStrength`). When a
dictation ends with polish on, the words land in the field at once as before, `ChatStore` enters its
polish phase — the status line reads "Polishing…" — and `polish(_:)` is sent the dictated span alone
(`VoiceDraftTarget` already knows where it starts), the model, the strength, the dictation language
and the open session's last twenty user and assistant text blocks, oldest first, each trimmed to
4000 characters (`State/DictationPolish.swift`, pure). The answer replaces only that span and
"Polished · Undo" appears under the field until the next edit or send; a failure leaves the words
and says "Polishing failed, your words are unchanged".

Send is not offered while the request is out. The slot holds the spinner Done turned into (§ "What
listening looks like") and becomes Send the moment the field holds what will be sent: the polished
words when the model answers, the dictated words when it fails or the answer is dropped. Typing
into the field ends the wait — the person's words win — so `forgetPolishOnEdit` cancels the request
on any draft that changes under it while `.polishing`, and Send is back at once. Nothing but the
person can write the draft then: `applyPolished` sets the phase before it writes, `send` cancels
the request itself before it clears the field, and the dictation that started the request stops
touching a field it did not leave (`InlineVoiceDraftSession.updateDraft` resets on a draft it does
not recognise). The demo gateway serves two models and a fake polish with a short delay, which is
what the screenshots and the checks drive; no real provider was called from the app.

## Messages from other agents (A30, A34)

`EventSource` decodes `agent` for `user_message.source` and `turn_started.trigger`. Such a message
is **not** the person's side of the conversation, so it is not drawn in their bubble: `TimelineRow`
routes it by its source to `AgentMessageRow` (`Sources/RCUI/Screens/AgentMessageRow.swift`), which
draws it on the leading side with the agent's own output — a muted block on the quiet surface with a
hairline edge, the caption "from another agent" above the text (zh-Hans "来自其他代理"), left-aligned,
at the width assistant text uses and in no bubble shape at all. VoiceOver reads "From another agent:
…", never "You said". The muted-bubble variant A30 shipped is gone, and `UserMessageRow` now knows
nothing about `agent` at all.

The Simple detail level does not draw it (A34): it is the agent's working, like thinking and tool
calls, so `TimelineEntry.isDrawn(at:)` reads a user message's `source` as well as its kind. The
jump-to-latest badge follows from the same rule, because `ChatStore` counts only rows the current
level draws. The status line never switched on `trigger`, so an `agent`-triggered turn already reads
as a terminal one; the demo's shared Claude session carries one such message and turn, which is what
the checks and both screenshots are driven from.

## Paused by the usage limit (A35)

A Claude Code or Codex turn that ran into the five-hour or weekly window ends as an `error` stop
carrying `limit {window_minutes, resets_at}`, and the device schedules a resume when the account
asked for one. Three surfaces read it.

**One switch, on the account.** The Sessions group of Settings holds "Resume after the limit
resets" (`SessionPreferences.swift`, identifier `settings.resumeAfterLimit`). It is not a
`SettingsStore` key: `PreferencesStore` (RCCore) is seeded from `hello.preferences`, replaced by
the `preferences.updated` frame whenever another app or device of the account changes it, and
written with `PATCH /api/preferences` through `GatewayAPI`. The write is applied before the round
trip and put back with the gateway's own reason if it is refused. `AppModel` owns the store and
feeds it every frame through one long-lived handler, so the value is right before Settings is ever
opened, and `attach(api: nil)` on sign-out forgets the previous account's. A gateway older than the
amendment sends no `preferences` at all, which is `nil`, and the switch is disabled under "Your
gateway does not offer this yet." (`ResumeText.settingsFooter(offered:)`).

**The notice, where the session is.** `ChatView` draws `ResumeNotice` between the subtitle bar and
the transcript while `Session.resume` is set, and it goes when the resume does. `NoticeBanner` grew
a second action rather than being forked, so the bar reads "Paused by the usage limit · resumes
3:50 PM" with **Change** and **Cancel** and nothing else (`notice.action`, `notice.secondaryAction`).
Change opens `ResumeTimeSheet`, one compact `DatePicker` for date and time prefilled with `at` and
bounded to `ResumeBounds` — at least a minute ahead, at most eight days out, the device's own rule
checked here so a time it would refuse never leaves the picker. Cancel sends
`session.resume_cancel` at once, with no confirmation. The status dot is untouched: the session is
idle and says so, and the notice carries the pause.

**The words are in one place.** `ResumeText` (RCCore) writes every sentence — the notice, "about"
for an estimated time, "second try" / "third try" from `attempts`, the turn's "Ended at the usage
limit · resets 3:50 PM", and the device's rows ("Resume scheduled for …", "Resume moved to …",
"Resume cancelled · …", "Not resumed · …", the app's word followed by the device's own one-line
reason). Every time is formatted with `Date.FormatStyle` in the viewer's zone, with the day in
front of it when the time is not today; the device sends a timestamp and nothing else. `RCCoreTests`
pins the composition and the today/other-day rule rather than one release's locale pattern.

**The timeline.** `TimelineEntry` knows the `resume` kind, and `isRenderable` returns false for
`fired`: the moment of resuming is the prompt in the person's bubble and the turn it starts, not a
row of its own. Simple keeps the limit end, the device's rows and the captioned prompt, as it keeps
notices and errors. A `source: "resume"` message is the person's own — `EventSource.resume` is not
`isElsewhere` — so it stays in their bubble, captioned "Sent for you after the limit reset", and a
`trigger: "resume"` turn already reads in the status line as a remote one because that line never
switched on the trigger.

**Alerts.** `PushKind` gained `limit_reached`, `resumed` and `resume_dropped`, whose words are
"Paused by the usage limit", "Resumed after the limit reset" and "Not resumed" — the gateway's own
three sentences, with no time in any of them. `TurnAlerts.kind(resume:)` maps the three statuses the
gateway pushes for and nothing else: a rescheduled resume is a detail of a pause already told, and a
cancelled one is usually the person's own doing. `AppModel` raises the banner from the `resume`
event under the same three gates as a finished turn, and the remote push for it is suppressed while
the app is in the foreground exactly as before.

**The demo.** `DemoFixtures.pausedSessionID` is an attached Claude session the five-hour window
stopped, with a resume three quarters of an hour out: its transcript carries the vendor's sentence
as an `error`, the turn's `limit` end and a `resume {scheduled}` row. The demo gateway keeps the
account's preferences, answers `GET`/`PATCH /api/preferences`, emits `preferences.updated`, takes
both resume requests with the real refusals, and cancels every pending resume when the switch goes
off.

Two UI tests drive it on the simulator: `testSessionsGroupOffersTheResumeSwitch` reads the group,
its sentence and the live switch in both directions, and `testPausedSessionShowsItsResumeAndCancelsIt`
opens the paused session, reads the notice and the two timeline rows, opens the picker from Change
and removes the resume with Cancel. Their screenshots are `ios-round33-resume-settings.png` and
`ios-round33-resume-banner.png`. A toggle in a settings row is wider than its control, so both the
`turnOn` and the new `turnOff` helper fall back to a tap on the trailing edge; and an accessibility
identifier on a `NoticeBanner` is inherited by the buttons inside it, so the identifier names the
banner's text and the actions keep `notice.action` and `notice.secondaryAction`.

## Update required (A31)

`AppsInfo` is decoded from `GET /api/health`, `GET /api/config` and `hello` alike — the health call
answers before sign-in, so a too-old app is stopped at the login screen — and `AppVersion` compares
`CFBundleShortVersionString` (`AppBuild.version`, falling back to "1.4.0" without a bundle, which
must match `MARKETING_VERSION` in `project.yml`) with `apps.ios.minimum_version` as
`major.minor.patch`. The first source to say "below" sets `ConnectionStore.updateRequired`, and
`UpdateRequiredView` then covers everything: "Update required", the app's version and the gateway's
minimum, "Open TestFlight" / "Open the App Store" when `update_url` is present, and Sign out, which
clears it. Equal, newer, or a gateway without `apps` changes nothing. `--demo-update-required`
starts the demo with a minimum above the app's version so the screen can be seen and is what the UI
test drives.

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

**A reply belongs to the connection that asked for it.** `ConnectionStore` holds a scope counter
that moves whenever it adopts a credential, enters the demo, ends a session or signs out.
`/api/session` and `/api/config` are confirmed behind screens the app has already drawn, so both
are held in a property, cancelled when the scope is left, and checked against the scope they were
issued in before anything they carry is applied. Every other assignment after an `await` in the
store — the cache paint, the two cache writes, an archive reply, a sign-in refusal, the
pre-credential `/api/health` answer — takes the same check. Without it, signing out and back in as
somebody else while `/api/session` was in flight left the app reporting the previous account and
its role, writing the new account's cache and drafts under the old account's name; and a slow
`/api/config` from a gateway the person had left could put amendment A31's blocking "Update
required" screen over a gateway that states no minimum at all.

**One frame the app cannot read is one frame, not a broken connection.** The receive loop decodes
in its own `do`/`catch`: a frame that fails to decode is counted, named once per kind in the log
(its `type` and, for a session event, its `kind` — never its content), and the loop reads on.
Nothing validates frames against the schema at runtime anywhere in the system, so a device adapter
that omits a required field reaches every app unfiltered; ending the connection over it failed
every request in flight as "delivery unconfirmed" and reconnected, for as long as that session ran.
`ProtocolFailure.unsupportedVersion` is raised while handling a `hello`, not while decoding, so an
incompatible gateway is still terminal.

**A request id is answered once.** Amendment A12 makes a retry reuse its request id, so two can be
outstanding at once — a double tap on Retry, or two taps while the socket is coming back and both
are held. The socket answers the first caller with `deliveryUncertain` before the second takes the
slot, and the Retry button is disabled while a retry is in flight. Previously the first
continuation was dropped, leaving its task suspended for the life of the process and its row on
the screen forever.

**Back-pressure costs the oldest frame.** Both transport streams are built by `EventBuffer`
(`bufferingNewest`, 1024 app frames and 256 dictation events). A stream of frames is worth reading
for its most recent element, so a burst that outruns the MainActor pump loses its beginning rather
than its end.

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

**A string built that way is computed, never kept.** `L10n.string` reads the table that was current
when it ran, so a store that assigns its words once goes on saying them in the language it was
first built in. `PushController` keeps a `PushStatus` and builds `statusText` from it at read time,
and `PushStatusRow` (`SettingsView.swift`) holds the language so that changing it rebuilds the row
where it stands — `SettingsRow` takes a `String` for its value, which no environment can redraw.
`testNotificationStatusFollowsAChangeOfLanguage` switches the segmented control and watches the
Status row change without leaving the screen. Anything else that stores an `L10n.string` result
rather than computing it has the same defect.

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
directory picker, voice, push, the in-app turn banners (seen by eye in the simulator; a banner is
SpringBoard's, not in the app's element tree, so no UI test asserts it) and slash commands — no command has been listed or run against a real
device, so what the three agents really offer is the client's word rather than this app's. The
device page is in the same position (A33): every account line, every meter and every one of its
four states was driven by the scripted device, and no real machine has yet reported a credential or
a rate-limit window to this app. Its pull-to-refresh is wired to the same request the page opens
with and no test asserts the gesture. The pairing camera is the one piece with no coverage at all: a
simulator has none, so the scan flow was driven through the injected stand-in and neither
VisionKit's data scanner nor the `AVCaptureMetadataOutput` fallback has read a real QR code. The
refusal path is in the same position: `Camera.requestAccess()` reads
`AVCaptureDevice.authorizationStatus(for: .video)`, which answers `.notDetermined` on a simulator
and is never actually refused there, so the line and the Settings button that replace the
viewfinder have been seen only by reading the code. The Local Network prompt of
`NSLocalNetworkUsageDescription` is the same: the simulator shares the Mac's network and is never
asked. `.inactive` is reasoned from code too — a headless simulator never reaches
`ScenePhase.active`, so no run has pulled Control Centre over a live dictation. Segment rollover is covered as a rule and against a fake backend,
never against a real microphone: no dictation has run past one recognition request on a device, and
neither Apple's own limit nor the gateway's has been reached in practice. APNs delivery and gateway speech-to-text have never been
exercised, the app has never run on a physical device, and dark mode and VoiceOver have not been
reviewed — the palette defines dark values, but v1 is designed light. CI, signing and TestFlight
upload have never run.

The account screens are proved against the offline gateway behind the form (`--demo-account`), so
signing in with a username, registering, the role gate and the whole Users screen have run end to
end, but never against a real gateway's accounts. Cross-account isolation is not among them: the
demo serves the same three machines to whichever account signs in, because scoping devices to their
owner is the gateway's work and is tested there.

The selection haptic on the effort slider cannot be asserted from a UI test — nothing in XCTest
observes `UIFeedbackGenerator` — so the test taps the last stop and asserts the word that follows
the thumb instead, and the haptic itself has been read only from the code. `StopSlider` is not a
`UISlider`, so the runner drives it by a coordinate tap rather than
`adjust(toNormalizedSliderPosition:)`; the drag path itself is exercised only by hand. The launch rule is
proved against the demo account rather than against a stored keychain token, for the reason below.

Keychain restore is a harness limitation rather than an open question about the code.
`CODE_SIGNING_ALLOWED=NO` produces an ad-hoc, linker-signed app with no `application-identifier`
entitlement, so `SecItemAdd` cannot store the token and the relaunch test asserts only that the
gateway address survives. Nothing suggests the keychain path is broken on a signed build; it cannot
be exercised without one.
