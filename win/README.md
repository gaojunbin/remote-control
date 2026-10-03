# win/

The Windows client for remote-control. It is the Mac app drawn for Windows (`docs/DESIGN.md`
§ "The Windows app"), which is itself the web app drawn natively: the same routes, the same words
in both languages, the same tokens, rows, controls, popovers and breakpoints, in Compose
Multiplatform Desktop on the Android app's Kotlin core (`android/core`). The Mac app (`macos/`) is
the reference for every pixel, word and behaviour; where the two differ, this app is wrong unless
the ruling says otherwise, and where the Mac's Swift leaves a question open the web answers it.

Every file of the Mac's `RCMac` has its port here with the same logic, under the same name — the
Mac's own under Windows' (`MacAppModel` is `WinAppModel`, `MacStrings` is `WinStrings`), the Mac's
window and app delegate the Windows window — and every scenario of the Mac's renderer has its twin
in this one (201, at the same names and sizes): the sign-in page, Update required, the shell and
its topbar, the conversation, the composer and dictation, the device and session lists, Settings,
Users, the terminal and the notifications.

## Layout

| Path | What it holds |
| --- | --- |
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/` | Gradle 9.8; the Android app's version catalog; `android/core` included by path as `:core`, never copied |
| `app/` | the app, `com.junbingao.remotecontrol.win`, and its packaging (`app/packaging/RemoteControl.ico`) |
| `app/src/main/kotlin/…/win/app/` | `WinAppModel` (with its account, device and report extensions), `ConnectionFactory`, `Persistence`, `SignInRecorder`, `Features`, `Route`, `Router`, `LayoutClass`, `AppCommands` (the key map), `LaunchOptions`, `ShellState`, `RootView`, `MainWindow`, `WindowActivity`; `Main.kt` is `main` |
| `…/win/design/` | `tokens.css` as Kotlin, the web's type and its line box, SwiftUI's stacks, every primitive of the Mac's `Design/`; `icons/` (lucide) and `overlay/` (the overlay layer, popovers, modals, the drawer, Escape) |
| `…/win/strings/` | every group of the Mac's `Strings/`, one file per group, with `S`, Windows' own words (`WinStrings`) and the composer's and Settings' Windows-only words |
| `…/win/layout/` | the topbar and its tabs, the page head, the page shell, the landing rule, the window strip |
| `…/win/platform/` | the services: the token vault, notifications and the tray, the icon's badge (`BadgeSurface`, `BadgeImage`), the microphone, the terminal emulator and its keys, the Markdown engine, Windows' settings |
| `…/win/login/`, `…/win/update/` | the sign-in page and its errors; Update required (A46) |
| `…/win/shared/` | the web helpers two or more features read: `Format`, `Identity`, `ErrorText`, `AccountErrors`, `SessionOptions`, `LabelPair`, `Attach`, `AttachmentLimits`, `Answering`, `SlashCommands` |
| `…/win/chat/` | the conversation: `ChatPage` (over the whole window) and `ChatFeature`; `page/` the panes, banners and `ChatHost`, which opens and closes a conversation; `header/` the chat header with Todos and usage; `timeline/` the transcript, its exact layout (`TranscriptExact`), the follow rule and the status line; `blocks/` every block — messages, thinking, tools, diffs, output, JSON, approval and question cards, notices; `markdown/` the hast drawn as views, with highlighted, copyable code; `resume/` the usage-limit notice and its form; `support/` the chat's text (`ChatText`: selectable, inline images, each line on the browser's baselines) and its borders, boxes and button styles |
| `…/win/chat/composer/` | the composer: the field and its keys, drafts, attachments, slash commands, the controls row (Up next, the model card with speed and effort, permissions), the primary button, A43's queued edit; `ComposerFeature` |
| `…/win/voice/` | dictation: `VoiceController` on the gateway's STT socket, segments, polish, the working pill |
| `…/win/devices/` | the Devices page and a device's rows, menus, dialogs and update states; `adddevice/` Add device with its code, countdown and live handshake; `page/` a device's page with its agent cards and quotas; `ListsFeature` |
| `…/win/sessions/` | the Sessions page with its groups, archives, search, filters, legend and Close; `controls/` its fields and buttons; `drawer/` the New session drawer, the directory picker and New folder; `sidebar/` and `SessionSidebar`, the conversation page's session list |
| `…/win/settings/`, `…/win/users/` | Settings — the identity header, the four groups, change password, sign out, the versions line; the accounts screen with registration and the add, reset and delete dialogs (A24) |
| `…/win/terminal/` | the terminal page (A38): `TerminalScreen` (the web's open, resize, reconnect and exit rules on the core's `TerminalSession`), the head and status line, and `TerminalStandIn`, what a render draws in the emulator's place |
| `…/win/notifications/` | `SettingsFeature` and the notifier: Notify me's two halves and the moments it posts at |
| `…/win/unseen/` | A47, the Mac's `Unseen/`: `UnseenFeature` (the core's `SeenReporter` on `conversationInFront`, and the badge's keeper), `UnseenDot` (the red dot beside a row's title), `TaskbarBadge` (the keeper, the stand-in, and Windows' own over the taskbar button and the notification area) |
| `…/win/gallery/` | the gallery pages the window and the renderer draw |
| `app/src/test/` | JUnit 5, one directory per package; `resources/markdown/corpus.json` and `resources/text/linebreaks.json` are the Mac's own results the tests compare with |
| `preview/` | the renderer: scenarios drawn offscreen and written as PNG; `scenarios/` holds the foundation's and one file per feature |

## Building and checking

```
cd win
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew --no-daemon :app:test
./gradlew --no-daemon :preview:run --args="--demo --all --out /tmp/rc-win-previews"
./gradlew --no-daemon :preview:run --args="--demo --all --language zh-Hans --out /tmp/rc-win-previews-zh"
./gradlew --no-daemon :app:run --args="--demo --ephemeral"
```

Always `--no-daemon`. `:app:run` needs the window server: from a shell outside the logged-in
session (`launchctl managername` prints `Background`) AWT cannot reach it and the run stops with a
`HeadlessException`. Build the app image there instead and start that, then end it by its PID:

```
./gradlew --no-daemon -Pcompose.desktop.packaging.checkJdkVendor=false :app:createDistributable
open -n "app/build/compose/binaries/main/app/Remote Control.app" --args --demo --ephemeral
```

(The property lets jpackage use Homebrew's JDK on a Mac; a Windows build needs none.)

### Packaging

`./gradlew --no-daemon :app:packageMsi` and `:app:packageExe` build the installer on a Windows host —
jpackage makes installers for the system it runs on only. Either installs "Remote Control" per
user, upgrades the previous install in place (a fixed upgrade UUID), and carries
`app/packaging/RemoteControl.ico`.

## Launch arguments

`LaunchOptions` parses the Mac's set, and the model acts on them as the Mac's does.

| Argument | What it does |
| --- | --- |
| `--demo` | The core's `DemoGateway` around the app, as the Mac's `--demo`: an account, two devices and their sessions, nothing on the network |
| `--demo-account` | The offline demo behind the sign-in form instead; `--registration-open` opens its registrations |
| `--demo-update-required` | The demo states a minimum above this build, which is how Update required is reached (A46) |
| `--ephemeral` | Nothing of the person's is read or written: the token in the core's `MemorySecretStore`, the defaults in its `MemoryUserDefaults`, caches and drafts in a scratch directory quitting removes. Every automated run uses it |
| `--reset-state` | Start as a fresh install |
| `--language=en\|zh-Hans` | The interface language for the run, the login page included |

Without `--ephemeral`, on Windows, the app keeps what the Mac keeps in the Keychain, the standard
defaults and Application Support in `%LOCALAPPDATA%\Remote Control`: the token sealed with DPAPI for
the current user in `secrets\`, the preferences in `defaults.json`, the core's `Cache\` and
`Drafts\`. The version the app states (A46) is the packaged one, which the installer's launcher
passes as `jpackage.app-version`, and the core's `AppBuild.shipped` in a run from the build.

## The window

One Compose `Window` with the system title bar, so snapping, resizing and the caption buttons are
Windows' own, titled "Remote Control": 1280 × 860 at first, or the screen's work area less a
margin where that is smaller (`InitialWindow`: Windows would put a larger window's title bar above
the screen), never narrower than 480 (or shorter
than 560), always light. There are no traffic lights, so `LocalTrafficLightInset` is 0 and a strip
starts its content at the web's padding. There is no menu bar; the Mac's menu commands are keys
(`AppCommands`): Ctrl+1, Ctrl+2 and Ctrl+3 for the tabs, Ctrl+, for Settings, Ctrl+N for New
session, Alt+Left and Alt+Right and the mouse's back and forward buttons for the history, and
Escape closes the newest overlay. Closing the window leaves the app running and connected in the
notification area (`AppTray`), whose icon opens the window again and offers Open and Quit; a click
on one of the app's notifications opens the window on its conversation. Where there is no
notification area, closing quits; quitting, and a process ended from outside, take an ephemeral
run's files with it. `main` builds the one model and the window asks it to `restoreOrPrompt()`;
the window tells it whether it is in front of the person (`isWindowActive`).

## What the features build on

**The model.** `LocalAppModel.current`, a `WinAppModel` — the Mac's `MacAppModel` under the
Windows app's name — which every screen reads as snapshot state, as the core's stores are:
`connection` (the core's `ConnectionStore`, measured against `apps.windows`, A46), `sessions`
(`SessionStore`: search, filters, folded groups), `settings`, `preferences` (A35), `preferenceSync`
(A41), `drafts` (`DraftStore`), `router`, `origin` (the host the topbar and Settings print),
`account`, `isSignedIn`, `isDemo`, `isResuming`, `deviceUpdateErrors` and `updateDevice(device)`
(A22), `pairingFlow()`, `device(id)`, `device(session)`, `agent(session)`, `httpClient` (for the
sockets the core opens on the gateway itself, as the dictation socket does), `isWindowActive`,
`showWindow()`, `toasts`, `diagnosticReport()`, `signIn`, `register`, `signOut` and
`lastSignInError`. Extension points, so no feature edits the model: `onSignOut { }` — every handler
runs, in order, before the connection goes, and empties what its feature holds of the account, as
`web/src/stores/signOut.ts` does — and `onSessionTransition { previous, current -> }`, both
multicast; `connection.addFrameHandler(token) { frame -> }` is multicast, keyed by a token of your
own. Each feature's launch hook is the `install(on)` of its `…Feature` object (`ChatFeature`,
`ComposerFeature`, `ListsFeature`, `SettingsFeature`, `UnseenFeature`), which `Features` calls once
as the model is built. The shell reads its part of the model as `ShellState` through `LocalShellState`;
`WithAppModel(model) { }` provides both.

**Work a person asked for runs on `model.tasks`.** The core's stores take the scope their work runs
in, and a suspend call rethrows cancellation, so a send, an approval or a sign-in started from a
composable's own scope dies with the composable — the sign-in with the login page, the moment it
succeeds. Start it with `model.tasks.launch { }`, the window's main thread in the app and the
renderer's own in a render, as the Mac starts it in an unstructured `Task`. A composable's
`LaunchedEffect` is for what belongs to it alone.

**The router.** `Route` is the web's routes — `Landing`, `Login`, `Devices`, `Device(id)`,
`Terminal(deviceId)`, `Sessions`, `Chat(deviceId, sessionId)`, `Settings`, `Users` — with
`route.path` and `Route.of(path)` for the web path. `Router`: `route`, `go`, `replace`, `back`,
`forward`, `canGoBack`, `canGoForward`, all snapshot state. Signing out lands on the login page and
a sign-in returns to where the app was; `Users` for a member resolves to `Sessions`; Ctrl+N is
`router.requestNewSession()`, which the Sessions page answers with `router.takeNewSessionRequest()`.

**Breakpoints.** `LocalLayoutClass.current`: `maxWidth1023`, `maxWidth760`, `maxWidth640`,
`maxWidth480`, `maxWidth420` — the web's media queries, true at their own width, read from the
window's content width (`RootView`).

**Type.** `Text(text, css(size, weight, lineHeight, mono, tracking), modifier, color, textAlign,
lineLimit, softWrap)` sets type as a CSS rule does and puts it on the browser's baselines, the
Mac's `.css(…)`: the baseline sits ⌊(L − round(ascent) − round(descent)) ÷ 2⌋ + round(ascent) into
each line box of height L, and a line box lands on a whole point. The line box aligns the baseline
Skia draws, not the one Compose reports: `PrimaryBaseline` reads it once per style from the foot of
a Latin letter's stem, because Compose reports a fraction (32.17 for 15 px on a 1.4 line) that Skia
does not round as the report rounds (it draws that line at 33), and a quarter of the web's styles
would land a pixel off the Mac's. Without a style a text takes the environment's font
(`WithFont`) on the line SwiftUI gives the Mac's text. It takes a `String` or an `AnnotatedString`,
whose spans' weight, size and `FontFamily.Monospace` pick their faces. The face is the platform's:
Segoe UI Variable Text (Segoe UI before Windows 11), Consolas, and Microsoft YaHei UI for Chinese
on Windows; on a Mac the Mac's own — SF with its size-specific tracking, SF Mono, PingFang — so this
renderer's pictures match the Mac renderer's to the pixel. Two of the Mac's text system's habits
are kept: a paragraph of two lines never ends on one short word (`PushOut`: the word before it
comes down, a frame after the text is first laid out), and a line drawn where the exact layout puts
it (below). The Mac's `.css(…)` on a row that holds text rather than on a text is
`CSSLine(style) { HStack { … } }`: the texts inside take the style's font, and the row is set on the
browser's baselines as one line box, its first baseline the highest of what it holds. The chat's
text, which can be selected and holds inline images, is `ChatText` (`chat/support/`), on the same
line box.

**Stacks.** Port a SwiftUI `VStack`, `HStack` or `ZStack` as `VStack(modifier, spacing, alignment)`,
`HStack(…)`, `ZStack(…)`: SwiftUI's defaults (8 apart, centred across), and SwiftUI's layout. The
Mac lays views out in fractions of a point — a 12 px caption at `line-height: 1.4` is 16.8 tall —
and rounds each edge to the pixel only as it draws it; the stacks keep those fractions as they
stack, and a text inside any of their children rounds its line from its exact place, as the
Mac's does. A Compose `Column`, `Row` or `Box` rounds every child to the pixel, so a long page built
of them drifts a pixel every few captions from the Mac's: use them only where SwiftUI has no stack.
In a stack, `Modifier.weight(weight, fill)` shares out the room left (SwiftUI's `Spacer()` is
`Spacer(Modifier.weight(1f))`) and `Modifier.align(…)` places one child; `HStack(alignment =
Alignment.FirstTextBaseline)` lines the first baselines up, a view without text standing on its
bottom edge. A scroll view's content is laid out afresh from the scroll view's pixel and centred in
its height rounded to the pixel, as the Mac's is.

**Tokens and primitives.** `Palette`, `FontSize`, `Space`, `Radius`, `RowHeight`, `LayoutSize`,
`Shadow` (`Modifier.boxShadow(shadow, shape)`, each layer drawn apart as the browser draws it),
`Motion` (`Motion.ease(duration, reduceMotion)`), `ZLayer`. `Button(action, modifier, style,
accessibilityLabel) { label }` takes a `ButtonStyle` — a `fun interface` with `Body(configuration,
modifier)`, the Mac's `ButtonStyle` — and shows a focus ring for the keyboard only; `Disabled { }`
above it stops it. `Btn(title, icon, variant, size, busy)` and `btn(variant, size)`, `IconBtn(icon,
size, label)` and `iconBtn`, `pill` and `quietPill` (an `HStack` centred in its 28 px at its exact
height, as the Mac's frame centres it), `MenuTriggerStyle` (the row menus' three dots, lit by
`LocalRowIsHovered`), `Badge(text, tone)`, `AgentChip(agent)`, `AgentLogo(agent, size)`,
`Mark(size)`, `Dot(style, pulses)`, `StatusDot(state, control, online)`, `OnlineDot(online, pulses)`,
`Switch(isOn, label, onChange)`, `Segmented(value, options, ariaLabel)` with `SegmentOption`,
`Spinner(size)`, `DeviceGroupHeader`, `ArchiveGroupHeader`, `Modifier.surface()`, `Modifier.card()`,
`GroupTitle`, `FieldLabel`, `Hint`, `FormError`, `EmptyState(text, title)`, `PageHead(title, hint) {
actions }`, `FieldText` with `Modifier.fieldChrome(focused)`, `WebField`, `SearchField`, `MenuList`
(every row, never a scroll: the Mac's popover asks its menu for all the height it needs),
`MenuItemRow`, `SpaceBetween`, `Help(text) { }` (the web's `title` tooltip, which leaves the layout
alone: what it wraps is measured as it would be without it, a segment still fills its share), and
`Icon(LucideIcon.x, size, strokeWidth, modifier, color)` for every lucide icon the web imports,
named as it imports them. The environment is `LocalContentColor` (`WithForeground`), `LocalFont`
(`WithFont`), `LocalIsEnabled` (`Disabled`) and `LocalReduceMotion` (Windows' animation setting in
the window). `./gradlew --no-daemon :preview:run --args="--scenario
gallery,gallery-tokens,gallery-menu --out <dir>"` draws all of them.

**Thin scroll bars.** `ThinScrollView(axes, modifier, state) { }` is the web's `.scroll-thin`, drawn
as Windows 11 draws its own: over the content, taking no room, shown while the content scrolls or
the pointer is on it and gone a moment after. `ScrollThin.gutter` is the room it takes — none.

**Overlays** are drawn in the window, never as system popups, dialogs or menus, by the one overlay
layer `RootView` holds (`OverlayHost`). `Modal(isPresented, onDismiss, title, width, showClose,
footer) { }`, `Drawer(isPresented, onDismiss, title, subtitle, footer) { }`,
`ConfirmDialog(isPresented, onDismiss, title, body, confirmLabel, danger, busy, onConfirm)`,
`Popover(align, side, chevron, triggerStyle, ariaLabel, initiallyOpen, label) { close -> }` (or with
`isOpen` and `onOpenChange`), `SelectMenu(options, value, ariaLabel, …, onSelect) { label }` (the
web's `Menu`, options as `MenuOption`), and `Modifier.anchoredPanel(isPresented, onDismiss, align,
side) { }` for a panel something other than a click opens. They close on Escape (the newest first,
through the window's key handler), a press outside — the backdrop for a modal or the drawer,
anywhere but the panel and its trigger for a popover — or their own control. A field inside an
overlay that closes something of its own on Escape carries `Modifier.claimsEscape { }`: while it
has the focus it stands as the newest overlay, one with nothing to draw, so Escape is its own and
the overlay around it stays (the directory picker's New folder name). A dialog moves focus as the
web's does, to its first field (`Modifier.dialogField(requester)` marks one), or to nothing when a
close button or a button comes first. A modal or the drawer blurs everything under it, the drawer
included; popovers are placed by `PopoverPlacement`, the web's rule, above everything. A modal's
and a drawer's content is a `VStackScope`. **An overlay's content is drawn in the overlay layer and
reads that layer's composition locals**, not those where it was asked for: pass in anything a
feature keeps in its own locals.

**Words.** `S.<group>.<key>` in the current interface language (`InterfaceLanguageSource.current`),
read through snapshot state, so a change redraws every screen; nothing may read a string at
static-init time. The helpers at the foot of `web/src/strings.ts` are on `S` too (`agentLabel`,
`platformLabel`, `stateLabel`, `dotToneLabel`, `timelineDetailLabel`, `sessionOriginLabel(session)`,
`sessionTitle(session)`, …). `S.win` holds Windows' own words where the Mac names the Mac; `S.winComposer`
and `S.winSettings` are the composer's and Settings' Windows-only words. A feature's Windows-only
words go in a strings file of its own built the same way: one class with an `en` and a `zhHans`
table and `of(language)`, and an accessor on `S`. The group files are generated from the Mac's
tables, word for word; when the Mac adds a word, regenerate rather than edit.

**The web's helpers** two or more features read are in `shared/`, ported once from the Mac's
`Shared/`: `Format` (relative times, durations, clocks, counts, sizes, paths, folding — the web's
`format.ts`, words from `S.format`), `Identity`, `ErrorText` (`text`, `refusal`, `queueRemove`),
`AccountErrors`, `SessionOptions` (with `SpeedChange` for A21's standard tier), `LabelPair`,
`Attach`, `AttachmentLimits`, `Answering` and `SlashCommands`. The sign-in errors are `LoginErrorText`.

**Previews.** `LocalPreviewStage.current` is the scenario's stage and null in the app: a view reads
it to show, for a render, a state that takes a click. `LocalShowsCaret` is false in a render.

**Tests that drive the model** build it on `ModelHarness` (`app/src/test/…/app/`): the model on a
thread of its own with its work, the stores' state and the test's reads there, and a `waitFor` that
stands in for the frame clock — the model follows its stores with `snapshotFlow`, which hears of a
change only when something sends the snapshot's apply notifications, as every frame of a window or
a scene does.

## The features

Each is the Mac's feature ported file by file, its scenarios under the Mac's names and sizes in its
own file in `preview/…/scenarios/`, and the `macos/Tests/RCMacTests/` cases for its files as JUnit
tests under the same names — or, where the Mac's case is about the Mac (its pasteboard, its
notification centre), as Windows' own.

**The conversation** (`chat/`; `ChatScenarios`, 44). Two panes at 1024 px and wider — the session
sidebar and the conversation — and the conversation alone with a way back below; the header with
Todos, usage and Stop; the usage-limit notice and its resume form; every block the web draws, the
JSON input in the device's key order; Markdown from the QuickJS engine's hast with highlighted,
copyable code; the status line with Take over; Load earlier that keeps the reading position, the
follow rule and the jump to the latest. The transcript is a lazy list that puts its rows where the
Mac's lazy stack would (`TranscriptExact`): from the top while its first row shows and from the end
while its last does, the fraction of a pixel each row is off handed to its text.

**The composer and dictation** (`chat/composer/`, `voice/`; `ComposerScenarios`, 40). The field —
Enter sends, Shift+Enter breaks the line, the Enter that confirms an input method's composition
never sends, undo, the Mac's maximum height, a draft per session — attachments from the file
dialog, a paste or a drop, slash commands, the controls row (Up next, the model card with speed and
effort, permissions), the primary button that sends, queues, answers and interrupts, A43's queued
edit, and the gateway's dictation with polish on the core's `STTSocket`.

**The lists** (`devices/`, `sessions/`; `ListsScenarios`, 49). Device rows with their menus, rename
and revoke and the update states; Add device with the one-liner, the code and its countdown — the
gateway's clock, read again on every tick — and the live handshake, the request leaving as the
modal shows, as the Mac's does; a device's page with its agent cards and quotas; Sessions grouped by
device with folds, archives, search, the agent and device filters, the legend and Close; the New
session drawer with its directory picker and New folder; the conversation's session sidebar, whose
head shows the wordmark the Mac's traffic lights leave no room for; and the red dot (A47), on a
Sessions row whose turn ended unwatched and in the sidebar beside an open conversation that keeps
none.

**Settings, Users, the terminal and notifications** (`settings/`, `users/`, `terminal/`,
`notifications/`; `SettingsScenarios`, 35). The identity header, the four groups and their states,
change password, sign out and the versions line; the accounts screen; Notify me through Windows'
notifications, inert in every ephemeral run. The terminal page is JediTerm in the window with the
web's open, resize, reconnect and exit rules, and Windows Terminal's keys (`TerminalKeys`):
Ctrl+Shift+C copies, and Ctrl+C copies while text is selected and goes to the shell when nothing
is; Ctrl+Shift+V pastes; the keys JediTerm keeps for itself — Ctrl+L, Ctrl+F, Ctrl+Up and Ctrl+Down —
are the shell's, as they are on the web. A right click is Windows Terminal's (`RightClick`): it
copies what is selected and pastes when nothing is, and a program that asked for the mouse gets the
click unless Shift is held. There is no menu. A render has no window for the emulator, so
`TerminalStandIn` stands in for it: the same grid in the emulator's face and size, and what the
shell wrote drawn as lines of text with the unfocused cursor after them.

## The platform services

Each is a small API of its own; where the core declares the seam, the app's service is the core's
interface.

| Service | API | On Windows | Elsewhere |
| --- | --- | --- | --- |
| Token vault | the core's `SecretStore`: `read(key)`, `write(data, key)`, `remove(key)`, all `suspend`; `TransportError.SecureStorageUnavailable` when it cannot | `DpapiSecretVault`: DPAPI for the current user, one `<key>.secret` file per key in `%LOCALAPPDATA%\Remote Control\secrets`, written atomically | the core's `MemorySecretStore`, also for `--ephemeral` and the renderer |
| Preferences | the core's `UserDefaults` | `FileUserDefaults`: one JSON file, `defaults.json`, read at launch and written whole after every change | the core's `MemoryUserDefaults` for `--ephemeral`, the renderer and the tests |
| Notifications | `Toasts`: `post(ToastNotice(title, body, target))`, `removeDelivered()`, `onOpen` with the clicked notice's `ToastTarget(deviceId, sessionId)` — the model's `toasts` opens the conversation | `TrayToasts`: Windows' toasts from the app's notification-area icon (`AppTray`); a click arrives as the icon's action (`ToastClicks`) | `InertToasts`: kept in the process, never shown |
| Microphone | `VoiceRecorder`: `start()`, `stop()`; `RecorderHandlers(onFrame, onLevel, onError)` with `RecorderError` | `MicRecorder`: `javax.sound.sampled`, 16 kHz PCM16LE mono in frames of 1920 samples (120 ms, as the Mac's dictation sends them), resampled when the device cannot do 16 kHz (`Downsample`, `PcmChunker`); Windows' privacy setting read first (`MicrophoneAccess`) | the same, but nothing here ever opens it |
| Terminal | `TerminalEmulator(feed, onSize, onInput, modifier)` with `TerminalFeed` (`write(bytes)`, `reset()`); `TerminalKeys` and `RightClick` | JediTerm in the window (`SwingPanel`), the Mac's terminal font and colours (`TerminalTheme`), Windows Terminal's copy, paste and right click | the same, ⌘C and ⌘V on a Mac; a render draws `TerminalStandIn` |
| Markdown | `MarkdownEngine.shared.hast(text)`: the hast as JSON (`[tag, properties, children]`, text a string), or null without the pipeline | the Mac app's `markdown.bundle.js` in QuickJS, served from `../macos/Sources/RCMac/Resources/Highlight/` by the build | the same |
| Settings | `ReduceMotion.current`, `AppData.directory` (with `secrets`, `defaults`, `cache`, `drafts`), `Host.isWindows` | Windows' animation setting; `%LOCALAPPDATA%\Remote Control` | no reduced motion; the same folder under the home directory |

## The renderer

```
./gradlew --no-daemon :preview:run --args="[--demo | --gateway <url> --username <u> --password <p>]
    (--scenario <name>[,<name>…] | --all | --list)
    [--width W --height H] [--scale 1|2] [--language en|zh-Hans] --out <dir>"
```

The Mac renderer's arguments, scenarios and sizes, so the two pictures of a scenario compare
directly. Each scenario gets a fresh ephemeral `WinAppModel` on the renderer's one thread, signed
in through the core (`--gateway` against the web's mock gateway, whose `admin` / `dev` works;
`--demo` on the offline demo), taken to its route and drawn through the real `RootView` in an
`ImageComposeScene` with no window (`java.awt.headless=true`). A scenario is `PreviewScenario(name,
route, width, height, stage, account, language, settle, setup, prepare, content)`, the Mac's
fields: `account` is `signedIn`, `signedOut` (the form, on the demo's account form under `--demo`)
or `updateRequired`; `setup` runs before the scene exists and `prepare` after it shows the route;
`content` draws one view where the route would be. Both get a `PreviewContext`: the `model`, the
`gateway` the command line named, `openChat(deviceId, sessionId)` to open one conversation for a
scenario that draws a piece of it, `chat` to read it back, and `wait(timeout) { }`.

The scene draws a frame every 16 ms while the scenario prepares and while it settles, as the Mac
renderer's window keeps refreshing, so what a view starts when it is next composed — a staged
dialog, Add device's request for a code — happens during a `wait`. The picture is taken when the
Mac's is, `settle` after the preparation: later would show a later moment — a spinner never stops
asking for frames, and the demo's handshake and every clock on the page go on — and a scenario
about one step of a handshake would show the next. It is never taken before the scene has drawn six
frames, the Mac window's first tenth of a second, in which a staged dialog opens and rises there,
where one frame behind a modal's blur can take a tenth of a second here. Each picture is
`<out>/<name>.png`, 1280 × 860 at 2× unless the scenario says otherwise. The registry is `PreviewScenarios.all`:
`FoundationScenarios` (the sign-in form in its states, the landing rule, the topbar on each tab and
below its breakpoints, Update required, the gallery and the overlay checks) and one file per
feature.

What a render cannot show: the pointer's hover states and a text field's caret; the terminal is the
stand-in's text, not JediTerm's drawing.

## Matching the Mac's pictures

On a Mac the renderer draws with the Mac's own faces, so its pictures and the Mac renderer's
compare pixel for pixel. What it takes, measured against the Mac renderer and CoreText:

- **SF as CoreText sets it**: the variable face cloned at its optical size and CoreText's weights
  (510 for medium, 590 for semibold), with the size-specific tracking CoreText applies from the
  face's `trak` table and Skia leaves out; widths agree to a thousandth of a pixel.
- **The Mac's line**: SwiftUI's line heights for its natural text, the browser's baseline rule for
  a line box, text widths rounded up to the device pixel, and each line aligned by the baseline its
  primary face is drawn on (`PrimaryBaseline`, read from the pixels): SwiftUI centres Chinese,
  whose face is taller, about a Latin line, and Compose's own baseline for that line would move it.
- **Ink**: Skia's glyph coverage depends on the colour; text is drawn in a neutral grey and
  recoloured, which brings its weight of ink to the Mac's.
- **Exact layout** (above), and the Mac's text system's two-line rule, checked against SwiftUI's
  own line breaks of 257 paragraphs (`PushOutTests`).
- **Overlays**: the backdrop's blur is a Gaussian of 0.94 px per CSS px, the Mac's `.blur(radius: 1)`.

All 201 scenarios in both languages against the Mac renderer's pictures of the same names
(`--demo`, 1280 × 860 at 2× unless the scenario says otherwise), on 2026-10-02: the mean difference
is 0.57 of 255 per channel and 0.50 % of pixels are more than 24 levels off; per feature,
foundation 0.46 / 0.31 %, conversation 1.05 / 1.08 %, composer 0.06 / 0.09 %, lists 0.60 / 0.47 %,
Settings 0.61 / 0.47 %. What still differs, and why:

- **The ruling's.** Without traffic lights the strips start at the web's padding where the Mac's
  start after them: the topbar at 760 and narrower, the chat header below 1024, and the session
  sidebar's head, which has room for the wordmark. Update required's button opens the download page
  where the Mac's names TestFlight or the App Store. The versions line names this build (1.12.0)
  where the Mac's pictures name theirs.
- **The moment.** A device page's quota resets are times of day and differ with the hour of the
  render, and a pulsing dot and a spinner are wherever their phase is when the picture is taken, on
  both.
- **The Mac's stage.** `chat.jump` and `chat.tools.open` scroll the transcript to its top 1.2 s in,
  and the tail pin of a live conversation scrolls it back when a pin is still in flight: the Mac's
  pictures show it back at the tail (its `chat-jump` is its `chat-running`), and this renderer's
  show it at the top or at the tail as the pin's timing falls.
- **Half points.** In a transcript read from its end (`chat-question`, `chat-shared`,
  `chat-running`, `chat-todos`, `chat-codex-shared`) some lines whose exact place falls on half a
  point are drawn a point from where the Mac draws them: the Mac's rows there sit a fraction of a
  pixel off the exact layout, which its scroll view keeping its offset on the pixel grid would
  explain. And Chinese at some sizes sits a pixel from the Mac's (Settings' sentences, 13 px on a
  1.45 line, a pixel high), CoreText and Skia rounding the taller face's metrics differently.
- **Glyph edges.** A few pixels in a thousand differ by more than a level or two at glyph edges and
  in the blur, and the backdrop is one level of 255 lighter (Skia blends an 8-bit premultiplied
  colour, Core Animation a float one).

## Not verified on this Mac

Everything that needs Windows: the DPAPI round trip (its test runs on Windows only), the toasts and
a click on one on Windows 10 and 11 (the click rule is unit-tested; the toasts were never posted
here), the tray, Segoe UI Variable and Microsoft YaHei UI on screen, the microphone (never opened:
dictation is driven from scenario stages and unit tests), JediTerm in a window with Windows
Terminal's keys and right click (the key and click rules are unit-tested; renders draw the
stand-in), and `packageMsi`/`packageExe`. On this Mac the app image starts with `--demo
--ephemeral` and stays up, but nothing in it was driven by hand: a tooltip on hover, Escape in a
focused field, a file dialog, a drop or the clipboard in a live window.

## Icon

`app/packaging/RemoteControl.ico` holds the Mac's `AppIcon` drawing at 16, 20, 24, 32, 40, 48, 64, 128
and 256 px, each drawn from the vectors rather than scaled; `app/src/main/resources/icon/` holds them
but the 128 for the window and the notification area.
