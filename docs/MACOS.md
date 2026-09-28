# The Mac app

A native SwiftUI client that is the web app drawn natively: the same routes, the same words in both
languages, the same tokens, rows, controls, popovers and breakpoints, and the same behaviour
(`docs/DESIGN.md` § "The Mac app"). macOS 15 and later, sandboxed. It speaks
`protocol/PROTOCOL.md` through the iPhone app's protocol and state layer (`ios/Sources/RCCore`) and
talks only to the gateway. The web app is the reference: where the two differ, the Mac app is wrong
unless DESIGN says otherwise.

## Structure

| Path | What it holds |
| --- | --- |
| `Package.swift` | swift-tools-version 6.0, macOS 15+, package `RemoteControlMac`; RCCore from `../ios` (never RCUI) and SwiftTerm 1.11.2 exact |
| `Sources/RCMac/App/` | `MacAppModel` (the RCCore stores, sign-in, the extension points), `Route`/`Router` (the web's routes and a browser-like history), `RootView`, `LayoutClass` (the web's breakpoints), the window's chrome, the menu commands, `Features` |
| `Sources/RCMac/Design/` | `tokens.css` as Swift, `SystemFace`, the CSS line box, the primitives of `web/src/components/` and `ui.css`, the in-window overlay layer, every lucide icon the web imports |
| `Sources/RCMac/Strings/` | every group of `web/src/strings.ts` and its Chinese table, one file per group, plus the Mac's own words |
| `Sources/RCMac/Shared/` | the web helpers two features read: format, identity, errors, sessionOptions, modelLabels, attach, attachments, answering, commands |
| `Sources/RCMac/Layout/`, `Login/`, `Update/` | the topbar and page shell, the landing rule, sign-in, Update required (A45) |
| `Sources/RCMac/Chat/`, `Chat/Composer/`, `Voice/` | the conversation page, its composer, dictation |
| `Sources/RCMac/Devices/`, `Sessions/` | the device and session lists, a device's page, Add device, the New session drawer, the session sidebar |
| `Sources/RCMac/Settings/`, `Users/`, `Terminal/`, `Notifications/` | Settings, the accounts screen, the terminal, Notify me |
| `Sources/RCMac/Resources/` | `Assets.xcassets`, and `Highlight/` for the timeline's code highlighter |
| `Sources/RCMacPreview/` | the renderer: scenarios drawn offscreen and written as PNG |
| `Tests/RCMacTests/` | swift-testing suites, including the web's own test cases for everything ported |
| `App/` | `@main`, `Info.plist`, the entitlements, the icon in every size, `AccentColor` |
| `project.yml`, `RemoteControl.xcodeproj` | the XcodeGen spec and the committed project with its shared scheme |
| `scripts/ci-check-macos.sh` | what CI runs |

Bundle id `com.junbingao.remotecontrol.mac`, display name "Remote Control". Sandboxed with the
network client, audio input and user-selected read-only file entitlements, and the hardened runtime.

## Building, checking and running

```sh
cd macos
# Set the toolchain per process; never change the global one with xcode-select.
export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
swift build && swift test
swift run RCMacPreview --demo --all --out /tmp/rc-previews

xcodegen generate
xcodebuild -project RemoteControl.xcodeproj -scheme RemoteControl -configuration Release \
  -derivedDataPath build/DerivedData build
open "build/DerivedData/Build/Products/Release/Remote Control.app"
```

Copy `Remote Control.app` to `/Applications` to keep it. The toolchain above (`swift build`,
`swift test`, every preview scenario, the app build) is the component's gate before a commit; CI runs
the same through `scripts/ci-check-macos.sh`.

Without a team the app is signed to run locally (`CODE_SIGN_IDENTITY = -`), so it builds and runs on
any Mac, and a build made on this Mac opens without a Gatekeeper prompt. A team id, when you want
one, goes only in the ignored `macos/Signing.local.xcconfig`. The token lives in the Keychain under
the service `com.junbingao.remotecontrol.mac.gateway`; an ad-hoc signature changes with every build,
so after a rebuild macOS may ask once whether the new build may read it.

## Running against a gateway

The login page is the web's with the gateway's address above the username (DESIGN). `https://` for
a real gateway; `http://` only for loopback, `.local` and private-network hosts, as RCCore allows.
The web's mock gateway works as a gateway: `cd web && npm run mock`, then sign in at
`http://127.0.0.1:8787` as `admin` / `dev` (member `alice` / `devdevdev`).

## Launch arguments

| Argument | What it does |
| --- | --- |
| `--demo` | RCCore's offline demo gateway around the app, as the iPhone's `--demo` |
| `--demo-account` | The offline demo behind the sign-in form; `--registration-open` opens its registrations |
| `--demo-update-required` | The demo states a minimum above this build (A45) |
| `--ephemeral` | Nothing of the person's is read or written: the token in memory, defaults in memory, caches and drafts in a scratch directory. Every automated run uses it |
| `--reset-state` | Start as a fresh install |
| `--language=en\|zh-Hans` | The interface language for the run; on a gateway the account's preference (A41) arrives with `hello` and wins |

## Screens

| Route | What it does on the Mac |
| --- | --- |
| landing | The web's landing rule decides where an open lands |
| login | The web's sign-in with the gateway's address above the username, and **Create an account** when the gateway takes registrations (A24) |
| devices | The web's device list: rows with their status line and update state, Rename · Retry update · Show quota · Revoke, a row that opens the terminal or says why it cannot, and **Add device** with the one-liner, the pairing code and its countdown, the live handshake and Manual install. Its "From your phone" line says a host's pairing link opens in the phone app or a browser: the Mac has no pairing-link screen (DESIGN) |
| device | One device (A33): the machine's facts, a card per agent, how it is signed in, a meter per rate-limit window |
| terminal | A shell on the device (A38), the whole window |
| sessions | Every session by device with each group's own Archive, search, the agent and device filters, the legend, Close (A39), and **New session** in the right-hand drawer with the folder picker and New folder (A37); ⌘N opens it |
| chat | The conversation: the session sidebar at 1024 pt and wider, the timeline, the status line, the composer |
| settings | The identity header, Account, While you're away, Voice (Transcribe · Gateway, polish) and Reading, and a caption that starts with the Mac app's own version |
| users | The admin's accounts screen; a member who reaches it lands on Sessions |

## The window

One `Window` scene without a title bar: the topbar is the window's top strip with the traffic lights
set into its leading edge, and the strip drags the window (a double click zooms it). The chat page
has no topbar: at 1024 pt and wider the session sidebar's head carries the traffic lights, with the
brand after them (the mark alone when the wordmark has no room); below that the chat header does.
The window narrows to 480 pt and follows the web's breakpoints on its width (1023, 760, 640, 480).
Closing it leaves the app running and connected, so notifications keep arriving; the Dock icon, a
notification or a menu command brings it back. The menu bar has ⌘1 ⌘2 ⌘3 for Devices, Sessions and
Settings, ⌘, for Settings, ⌘N for New session, ⌘[ ⌘] for the history, and the standard Edit menu.

## How it follows the web

- **Type.** SF is the web's `-apple-system` and SF Mono its `ui-monospace`; 1 CSS px is 1 pt. Text
  sits on the browser's baselines in the web's line boxes (`CSSLineBox`, measured against Chrome).
  The app turns macOS's font smoothing off for its own windows, because the web draws with
  `-webkit-font-smoothing: antialiased`.
- **The system face.** macOS hands out the system font by the person's preferred languages; with
  Chinese first, the middle dot of every meta line fell to the CJK symbols font, 2.4 pt wider than
  Chrome's. `SystemFace` uses the system UI font made for English, which has SF's own dot and the
  same metrics. In the Chinese interface Han falls back to PingFang SC at optical size 0 and text is
  typeset without CoreText's Chinese punctuation rules, so widths and line breaks match Chrome's.
- **Overlays** — popovers, menus, modals, the drawer — are drawn in the window with the web's
  surfaces and placement, close on Escape or a press outside, blur what is under them (an open
  drawer included), and focus what the web's do: the first focusable element, so the drawer and a
  closable modal focus their close button and a confirm its Cancel. An overlay's content is built
  from values the presenting view's body reads (macos/README.md § Overlays).
- **Scroll bars** are the system's, as both browsers draw the web's `scroll-thin`: its
  `scrollbar-width: thin` wins over its `::-webkit-scrollbar` rule in Chrome and Safari alike, so
  while the system shows scroll bars automatically they are thin overlays that take no room, and
  when it always shows them the small legacy scroller takes its gutter beside content that
  overflows. The renderer always draws the overlay case (`-AppleShowScrollBars Always` draws the
  other).

## The composer and dictation

The field is an `NSTextView`, because only AppKit says whether an input method holds a
composition: Enter sends and Shift+Enter breaks the line as on the web, and **Enter that confirms a
Pinyin (or any) composition never sends** — the rule the web's `useImeGuard` exists for. It grows
with its text to 220 pt and then scrolls. The paperclip opens the system's file panel; pasting or
dropping images and files attaches them, with the web's limits, chips and errors.

Slash commands use the web's parsing rules (`Shared/SlashCommands`), which differ from RCCore's
`SlashDraft` — the iPhone app's — on the characters a command name takes and on what separates it.
An answer composed in the composer follows the web's rule too (`Shared/Answering`). The controls
row is the web's — Up next · N (A43), the model card with speed and effort, permissions — with a
terminal's values read-only where the terminal holds them (A17, A40); a queued message is edited
as A43 rules, text only.

Dictation is the web's: the gateway transcribes and detects the language (A44), so there is no
language to choose. `AVAudioEngine` captures, resampled as the web's recorder sends, through RCCore's
`STTSocket`; partials land in the field as the web merges them; Done, Cancel, the level, and
polish with its undo (A29). The system asks for the microphone the first time dictation starts.
Previews and the demo use scripted speech and never open the microphone.

## The conversation

At 1024 pt and wider the page is two panes — the session sidebar with its own New session
button and drawer, then the chat column — and one column with a back button below. The page owns
the conversation's `ChatStore`: it opens it when the page appears and closes it when it goes,
loads and saves the draft, keeps an unfinished queued edit for the next open, and caches the
transcript, as the iPhone app's model does; the composer sits at the column's foot.

The timeline draws every block kind at the Simple or Detailed level: messages, messages from
another agent (A30, A34), thinking, tool rows that expand, output boxes, diffs, approval and
question cards, notices, and the resume notice above it all (A35). Load earlier keeps the reading
position; at the bottom it follows the tail, and away from it the jump-to-latest control appears.
Unconfirmed sends show with Retry, and a refused action shows in the page's error bar.

Markdown and highlighting run the web's own pipeline — react-markdown 10's processor with
remark-gfm and rehype-highlight on lowlight's highlight.js 11.11.2 (the copy the web highlights
with) — bundled into `Resources/Highlight/markdown.bundle.js` and run in JavaScriptCore. It returns
the hast, which the page draws natively in the colours of the web's `github.css`. The README beside
the bundle says how it was built; rebuild it whenever the web's Markdown packages change.

## Notify me

The web's switch subscribes a browser to the gateway's push; the Mac has no push channel of its own
(the gateway's APNs topic is the iPhone app's), so the running app posts a local notification at the
moments the gateway pushes for — a session that needs you, a turn that ends, a usage-limit resume —
with the push's words (`gateway/rc_gateway/push.py`). A notification is skipped while that
conversation is open in the frontmost window; clicking one brings the window forward and opens it;
signing out clears the ones posted. The switch belongs to this Mac, not the account, and turning it
on is the only thing that asks macOS for permission. A resume that fires or is dropped is reported
by an event only a subscribed connection receives, so it is announced only while that conversation
is open; a pause shows on every session through its `resume` field.

## The terminal

SwiftTerm 1.11.2's macOS view, fed by RCCore's terminal session with the web's open, reconnect and
exit rules (`useTerminal.ts`): the shell opens once the emulator has measured itself, a lost
connection or a device that comes back reattaches and redraws the scrollback, and the page says
Disconnected, Shell exited or why it cannot open, as the web does. The web's theme and 13 pt size;
⌘C and ⌘V. Rows are 16 pt, where xterm.js draws 18.5 px: SwiftTerm 1.11.2 has no line height.

## Update required (A45)

Below the gateway's `apps.macos.minimum_version` the app shows PROTOCOL 8.16's blocking screen in the
web's visual language: its own version, the gateway's minimum, a button to `apps.macos.update_url`
when there is one, and Sign out. The gateway's floor is `MACOS_MINIMUM_APP_VERSION` in
`gateway/rc_gateway/compat.py` (`MACOS_MIN_APP_VERSION` overrides it, `MACOS_UPDATE_URL` names the
button's page, `docs/DEPLOY.md`), raised only in a release an older Mac app cannot follow.

## The renderer

```sh
swift run RCMacPreview [--demo | --gateway <url> --username <u> --password <p>]
                       (--scenario <name>[,<name>…] | --all | --list)
                       [--width W --height H] [--scale 1|2] [--language en|zh-Hans] --out <dir>
```

Each scenario gets a fresh ephemeral model and the real root view in an offscreen window, signed in
through RCCore, taken to its route and prepared, and is written as a PNG rendered through
`CARenderer` — the compositor the window server runs — so shadows, blurs, text and scroll content
come out as a live window draws them. Traffic lights, hover states and the caret are the only things
it cannot show. Every feature keeps a scenario per state it draws; comparing with the web means the
web app and its mock on ports of your own, a playwright-core screenshot at the same size and scale,
and the same screen rendered with `--gateway`. Chrome falls back to Menlo for `ui-monospace`, so mono
text is wider in a Chrome screenshot than in Safari or this app.

## Differences from the web that remain

- **Mono text** is SF Mono, as Safari draws the web's `ui-monospace`; a Chrome screenshot shows Menlo.
- **A tool's JSON input** lists its keys alphabetically: RCCore's `JSONValue` keeps an object as a
  dictionary (the iPhone app shows the same), where the web keeps the device's order.
- **Load earlier** on a long conversation leaves the reading position about a line (≈45 pt) lower
  than the web's: `LazyVStack` measures the new page by estimate first.
- **The terminal's rows** are 16 pt where xterm.js draws 18.5 px; SwiftTerm 1.11.2 has no line height.
- **A refused send or command** shows the gateway's own message: RCCore keeps the error text and not
  its code, where the web chooses its words by code.
- **The resume time field** has no calendar icon, which is Chrome's own control.
- **Adjacent full-width punctuation** is not trimmed as Chrome trims it; one string has it
  (`composer.textTooLong`'s "）。").
- **An agent that reports no `attach_ready`** gets the channel hint, because RCCore reads the field as
  false; the web shows none. Only a device older than A10 sends that.
- **Notify me** posts only while the app runs, and a resume that fires or is dropped is announced
  only while that conversation is open (DESIGN, § Notify me above).

## Not verified

- A real gateway with a real device and real agents, end to end in the Mac app: every check ran
  against the web's mock gateway or RCCore's offline demo.
- The microphone (the permission prompt, capture, a real STT provider), system notifications (the
  permission prompt, posting, clicking one) and real Pinyin typing in the composer: automated runs
  never raise a system prompt, so these are the person's first use.
- The Keychain: every automated run was `--ephemeral`.
- VoiceOver, and keyboard navigation beyond the menu shortcuts.
- A Mac whose scroll bars are set to always show.
- Distribution: the build is signed to run locally, not notarized, and there is no TestFlight or
  App Store channel for the Mac yet.
