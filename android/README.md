# android/

The Android client for remote-control. It is the iPhone app, drawn on Android
(`docs/DESIGN.md` § "The Android app"): the same three tabs, the same screens, words, colours,
type and behaviour, in Jetpack Compose, on top of the Kotlin port of the iPhone's protocol and
state layer (`android/core`). The iPhone app (`ios/Sources/RCUI`, `ios/App`) is the reference
for every pixel; where the two differ, this app is wrong unless the ruling says otherwise.

The system pieces are drawn in the iPhone's shapes, not Material's: the floating glass tab bar,
the navigation bar with its large title, inset grouped lists, the switch, the segmented control,
sheets, alerts, confirmation dialogs, menus and swipe actions are this module's own composables.

## Layout

| Path | What it holds |
| --- | --- |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle/` | the build; the catalogue in `gradle/libs.versions.toml` |
| `core/` | the Kotlin core (`com.junbingao.remotecontrol.core`): protocol, transport, persistence, Markdown, the stores and the offline demo, shared with the Windows app; its own README |
| `app/build.gradle.kts` | the app, plus two build tasks: the string catalogue and the Markdown assets (below) |
| `app/src/main/kotlin/…/android/shell/` | RCUI's root: `AppModel`, `RootView`, `LoginView`, `UpdateRequiredView`, `ConnectionSummary`, the tab shell, and `AppEnvironment`, where the core's stores meet the phone |
| `…/android/screens/` | the screens, a package per feature; the phase-3 agents replace the placeholders in them (below) |
| `…/android/design/` | RCUI `Design/`: `Theme`, the type ramp, every primitive |
| `…/android/system/` | the iPhone's system pieces: bars, lists, switch, segmented control, search field, sheets, alerts, dialogs, menus, swipe actions, the presentation stack |
| `…/android/navigation/` | `Navigator` (a stack per tab), `NavigationStack`, `NavigationScreen`, the tab bar's visibility |
| `…/android/icons/` | `Sf`, every SF Symbol RCUI draws, as lucide glyphs (`Lucide.kt` is generated) |
| `…/android/strings/` | `L10n` and `IosFormat`; the table itself is generated |
| `…/android/launch/` | `LaunchOptions`, the iPhone's launch arguments, and the link an intent carries |
| `…/android/security/`, `persistence/`, `attachments/`, `voice/`, `push/`, `markdown/`, `scanner/`, `terminal/`, `awake/`, `haptics/`, `permissions/` | the platform services, each behind a small API of its own; the core's seams are filled here |
| `…/android/gallery/` | the primitives' gallery, a debug build's Settings → Diagnostics row |
| `app/src/main/java/com/termux/` | Termux's `terminal-emulator` and `terminal-view`, vendored (Apache 2.0, `LICENSE` and `NOTICE` beside them) |
| `app/src/main/strings/` | `overlay.json` (Android's words for the iPhone's Apple names) and `system.json` (the words UIKit supplies) |
| `app/src/main/res/` | the theme behind the system bars, the adaptive icon, the notification icon, the network, file-provider and backup configuration |
| `app/src/test/kotlin/…/android/` | JUnit and Robolectric tests; `harness/` draws pictures and drives the demo, `gallery/` and `compare/` picture the system pieces, `demo/` runs the demo end to end |

## Building and checking

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
cd android
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:recordRoborazziDebug
```

Everything is checked on the JVM: the unit tests and the pictures run on Robolectric with the
real graphics stack (`graphicsMode=NATIVE`), so no emulator is needed. `recordRoborazziDebug`
runs the same tests and draws every picture into `app/build/outputs/roborazzi/`. The pictures are
evidence to lay beside the iPhone's, not baselines: none is kept in the repository, and nothing
compares a run against an earlier one. A plain `testDebugUnitTest` draws none.

**Generated at build time.** `generateStringCatalog` reads `../ios/App/Localizable.xcstrings`,
lays `app/src/main/strings/overlay.json` over it and adds `system.json`, and writes
`Catalog.kt` into the build directory: the Android app follows the iPhone's words as they change,
and the build fails if a catalogue key lacks its Chinese, an overlay key is not a catalogue key, or
a system word is one. `copyMarkdownAssets` serves `../ios/Sources/RCUI/Resources/Markdown` (the
renderer, KaTeX, Mermaid, their licences) to the app's assets under `markdown/`, so both apps draw
a diagram with the same files.

## Running

```sh
./gradlew --no-daemon :app:installDebug
adb shell am start -n com.junbingao.remotecontrol/.android.MainActivity --esa args --demo,--reset-state
adb shell am start -a android.intent.action.VIEW -d 'remotecontrol://session?device=<id>&id=<session_id>'
```

The arguments are the iPhone's (`docs/IOS.md` § "Demo mode"), read by `LaunchOptions` from the
`args` extra — `--esa args a,b` or `--es args "a b"`: `--demo` (the offline demo, signed in),
`--demo-account` (the offline gateway behind the sign-in form), `--demo-queue`,
`--demo-update-required`, `--demo-preference-change`, `--registration-open`, `--reset-state`,
`--ui-testing`, `--language=zh-Hans`, and in a debug build only `--voice-preview`,
`--voice-level=0.5`, `--voice-transcript=long` and `--field-scroll-probe`. `--gallery` (debug)
opens the primitives' gallery at once; it is also Settings → Diagnostics → Primitive gallery.
A `remotecontrol://session` link — from `adb`, a notification's tap or a browser — opens the
conversation in place: the Sessions tab, with it as the only screen over the list.

## What the features build on

### The app model

`AppModel` (`shell/AppModel.kt`) is the port of RCUI's `AppModel.swift`, under its names. The
process builds one (`RemoteControlApplication.model(options)` → `AppEnvironment.model(context,
options, tasks)`), never a view; a screen reads it as `LocalAppModel.current`, which `RootView`
provides. What it holds is snapshot state, so a screen that reads it redraws when it changes; it
lives on the main thread.

- **The core's stores** (`android/core/README.md`): `connection: ConnectionStore` (the account,
  `devices`, `sessions`, `phase`, `channel`, `api`, `config`, `isAdmin`, `updateRequired`, the
  demo), `settings: SettingsStore`, `sessions: SessionStore` (the list's grouping and its state),
  `preferences: PreferencesStore` (A35), `preferenceSync` (A41); and the app's `push:
  PushController` and `turns: TurnNotifier`.
- **Where the app is**: `tab` (`AppModel.Tab.devices`, `sessions`, `settings`), `path:
  List<String>` (the Sessions stack: session keys), `devicePath: List<DeviceRoute>`, `navigation:
  ShellNavigation` (a `Navigator` per tab, the tab bar's visibility).
- **The conversation**: `chat: ChatStore?` (the open one), `open(session, inPlace = false)`,
  `closeChat()`, `closeChat(key)`, `saveDraft()`; `session(key)`, `device(session)`,
  `agent(session)`. The drafts, the queued edit (A43) and the cached transcript are kept and
  restored by `open` and `closeChat` as the iPhone's are.
- **The account**: `isSignedIn`, `isDemo`, `isResuming`, `signIn(origin, username, password)`,
  `register(origin, username, password)`, `signOut()`, `enterDemo()`, `restoreOrPrompt()`,
  `attachPush()`, `adoptSnapshot()`, `decideLandingTab()` and the rule itself,
  `AppModel.landingTab(hasDevices)`.
- **The rest**: `isLocked`, `lockIfNeeded()`, `isSceneActive`, `toast`, `handle(link)`,
  `handle(url)`, `deviceUpdateErrors`, `deviceUpdateError(deviceID)`, `updateDevice(device)` (A22),
  `pairingFlow()`, `codeScanner` (read in a composable: the camera, or the demo's printed code,
  A23), `voiceBackendInEffect` (A44), `accessibilityMode`, `options` (the launch's arguments, for
  a piece that reads one of its own, as the voice preview does).
- `perform { … }` starts what a person asked for in the app's scope —
  `model.perform { signOut() }` — so leaving the screen that asked does not cancel it halfway, as
  the iPhone's `Task { await model.… }` outlives its view. Work that belongs to a screen (a
  refresh while it is open) runs in the screen's own `rememberCoroutineScope()` or
  `LaunchedEffect`.

Every launch argument the iPhone's model reads is read here: `--demo` (and its scripted delays
under `--ui-testing`), `--demo-queue`, `--demo-update-required`, `--demo-preference-change`,
`--reset-state` (settings, the list's state and the drafts), `--language=` (pinned after the
reset). `--demo-account` and `--registration-open` choose the connection in `AppEnvironment`.

### The root and the shell

`MainActivity` draws `AppRoot(presenter, over = { AppLockWindow(model.isLocked) { … } }) {
RootView(model) }`. `RootView(model)` is the iPhone's: the tab shell when signed in, the page
colour alone while the account is being restored, `LoginView(model)` otherwise; the A31/A46
`UpdateRequiredView` over all of it (`AppUpdateRequirement.of(apps, app = InstalledApp.android,
current = AppBuild.version)`, which the core's connection computes), with nothing under it
reachable; the toast; the restore at launch; the scene's state (`isSceneActive` in the
foreground; the lock and the save when the app goes to the background). Over the presentations,
`AppRoot` draws the lock and the privacy cover, so an open sheet cannot show a transcript over
them.

The shell (`TabShell`) draws each tab's `NavigationStack` under the floating tab bar, in the
iPhone's order (Devices, Sessions, Settings) and keeps each tab's saved state; a second tap on the
open tab returns it to its root. Each tab's stack is drawn by its feature's destination, which
receives every route on that stack, the root being `TabRoot(tab)`:

```kotlin
when (tab) {
    AppModel.Tab.devices -> DevicesDestination(route)     // screens.devices
    AppModel.Tab.sessions -> SessionsDestination(route)   // screens.sessions
    AppModel.Tab.settings -> SettingsDestination(route)   // screens.settings
}
```

The landing rule (`docs/DESIGN.md` § "Three tabs, one order, one landing rule") runs once per
sign-in, when the first device list arrives: Sessions with a device, Devices without one.

### The placeholders the features replace

Each is a file the feature agent owns and replaces, keeping the signature the shell calls; what
it does now is the least the shell and the demo run need.

| Agent | Package | Entry point (signature kept) | Called by | Now |
| --- | --- | --- | --- | --- |
| `android-lists` | `screens.devices` | `DevicesDestination(route: Any)` | the shell, for the Devices stack | `TabRoot` → `DevicesView()`, `DeviceRoute.Page` → `DeviceDetailView(deviceID)`, `DeviceRoute.Terminal` → `TerminalScreen(deviceID)` |
| | | `DevicesView()` | `DevicesDestination` | the machines; a tap follows `DeviceTap.outcome` (rule 20) |
| | | `DeviceDetailView(deviceID: String)` | `DevicesDestination` | the name and the facts line |
| | | `DeviceRoute` (`Page`, `Terminal`), `DeviceTap`, `DeviceRowAction` | the model's `devicePath`, the rows | the whole port of `DeviceRoute.swift` |
| | `screens.sessions` | `SessionsDestination(route: Any)` | the shell, for the Sessions stack | `TabRoot` → `SessionsView()`, a `String` key → `ChatView(sessionKey)` |
| | | `SessionsView()` | `SessionsDestination` | the sessions by device, rows tagged `session.<session_id>`; a tap is `model.perform { open(session) }` |
| | `screens.terminal` | `TerminalScreen(deviceID: String)` | `DevicesDestination` | an empty screen under the machine's name |
| `android-chat` | `screens.chat` | `ChatView(sessionKey: String)` | `SessionsDestination` | hides the tab bar, holds the screen awake, lists the open store's rows, `closeChat(sessionKey)` when it leaves; tagged `chat.<key>` |
| `android-settings` | `screens.settings` | `SettingsDestination(route: Any)` | the shell, for the Settings stack | `TabRoot` → `SettingsView()`, `UsersRoute` → `UsersView()` |
| | | `SettingsView()` | `SettingsDestination` | Users (an admin) and Sign out |
| | `screens.users` | `UsersRoute`, `UsersView()` | `SettingsDestination` | an empty screen titled Users |
| | `screens.alerts` | `PushController(context)`: `authorization`, `attach(api: GatewayAPI?, enabled: Boolean)`, `detach()` | the model, on every sign-in and sign-out | reads the system's permission on each call |
| | | `TurnNotifier(context)`: `lastAlert`, `announce(previous, current, deviceName, onScreen, enabled, authorization)`, `announce(kind, session, deviceName, identifier, onScreen, enabled, authorization)`; `TurnAlert` | the model, on every session transition and `resume` event | the port of the iPhone's notifier as local notifications, skipped while the conversation is on screen |
| | `screens.lock` | `AppLockWindow(locked: Boolean, onUnlock: () -> Unit)`, `AppLockView(onUnlock: () -> Unit)`, `PrivacyShield(visible: Boolean)` | `MainActivity` (the lock), `AppRoot` (the cover) | the lock and the cover, ported |

`android-chat` puts the voice UI (`InlineVoiceInput`, `InlineVoiceDraftSession`, `VoiceEdgeGlow`,
`VoiceGlowWindow`, `VoiceInputController`) and the native Markdown views (`MarkdownBlocks`,
`MarkdownParseModel`, `MarkdownText`) in subpackages of `screens.chat`. Two of the pieces its
brief and `android-lists`' name are already the foundation's and are used, not ported again:
`voice/InputLevel.kt` (with the recognisers and the microphone) and `terminal/TerminalHost.kt`
with `TerminalFeed` (the emulator under `TerminalScreen`).

### How a screen does what the iPhone's does

- **Opens a conversation**: `model.perform { open(session) }`. The model builds the `ChatStore`,
  restores the draft and the queued edit, shows the cached transcript, subscribes, and pushes the
  session's key on the Sessions stack, where `SessionsDestination` draws `ChatView(sessionKey)`.
  The conversation tells the model when it leaves with `closeChat(sessionKey)`, which saves the
  draft and the transcript. A notification or a link goes through `model.handle(link)`, which
  opens it in place once there is an account.
- **Presents a sheet, an alert, a dialog or a menu**: a flag the screen owns and a dismissal it
  answers, anywhere in its composition, as SwiftUI's modifiers are: `Sheet(isPresented = adding,
  onDismiss = { adding = false }, detents = listOf(SheetDetent.medium)) { AddDeviceSheet(…) }`.
  The presentation is drawn above the shell, keeps the presenting screen's composition locals
  (the model, the navigator) and is closed first by Back.
- **Pushes a page**: `LocalNavigator.current?.push(route)` with a route of the feature's own — a
  `data class` or `data object`, as `UsersRoute` is — drawn by that tab's destination, which its
  owner extends. A `String` on the Sessions stack is a conversation; anything else pushed there is
  a type of its own. `HidesTabBar()` in a pushed screen is `.toolbar(.hidden, for: .tabBar)`.
- **Reads a word**: `L10n.string("…")` where it is drawn, never kept; a sentence the core built
  (an error) through `L10n.platform(sentence)`, which says it in Android's words.
- **Tags what a test finds** with the iPhone's accessibility identifier: `Modifier.testTag("sessions.new")`,
  or `tag =` on a list row, so a ported UI test asks for the same names.

### Driving the app in a test

A test ported from `ios/UITests/` drives the whole app in its demo with `DemoApp`
(`harness/DemoApp.kt`): the iPhone's launch arguments (`DemoApp.launchArguments`, or
`DemoApp.signedOut` for the sign-in form with the offline gateway behind it) plus the test's own,
the model built as the process builds it, `RootView` and the lock in an activity at the iPhone 17's
size and safe area, in a language and an appearance, on real time:

```kotlin
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class SessionsUITest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun sessionsAndChat() = DemoApp(compose, "testSessionsAndChat").use { app ->
        app.waitFor("sessions.new")
        app.attach("01-sessions")               // the iPhone's attach(name:)
        app.tap("session.demo-session-auth")
        app.waitFor("composer.prompt")
        app.node("composer.prompt").performTextInput("run the suite again")
        app.attach("02-chat")
    }
}
```

`waitFor(tag)`, `waitForAbsence(tag)`, `exists(tag)`, `tap(tag)`, `node(tag)` (the first match,
for Compose's own assertions and gestures), `await(what) { condition }`, `back()` (the system's
Back), `model` (the stores, to assert on), and `attach(name)`, which draws
`build/outputs/roborazzi/<test>/<name>-<language>-<appearance>.png` to lay beside the iPhone's
`<test>__<name>.png`. `DemoApp(compose, test, arguments, variant = Variant(L10n.chinese, dark =
true))` runs it in Chinese and dark; `IPhone.variants` is all four. `demo/DemoRunTest.kt` launches
`MainActivity` itself with `--demo`; `shell/SignInFlowTest.kt` does the same behind the sign-in
form.

A test of the model alone runs it on the test's clock: `AppModelHarness(context,
root).model(scope, arguments)` gives a model on the core's stores with nothing written outside
`root`, the demo on the same clock, and `settle { condition }` lets the clock run until the
condition holds (`shell/AppModelTest.kt`).

A screen drawn from values is pictured with `compose.picture(group, name, variant) { … }` and
stood under the real bars with `ShellAt(tab, path) { route -> … }` (below).

### Navigation, Back and presentations

**Navigation.** `LocalNavigator.current` is the stack a screen belongs to: `push(route)`,
`pop()`, `popToRoot()`, `setPath(routes)` (the iPhone's path assignment: a link replaces what
was over the root), `routes`, `canPop`. A route is any value. `NavigationStack` plays UIKit's push
and keeps each covered screen's saved state. `NavigationScreen(title, displayMode = large |
inline, leading, trailing, showsBack, onBack, listState, top, bottomBar, background) { insets ->
… }` is a screen: the bar under the status bar with the back button when there is somewhere to
go, glass bar items, the large title folding into the bar's 17-point title as the content
scrolls (pass the `LazyListState` so it follows exactly), `top` and `bottomBar` as
`.safeAreaInset(edge:)`. The content scrolls under the bars and takes `insets.padding()`.
`ConnectionSummary(phase, isDemo, reconnect)` is the lists' line under the bar.

**Back.** `BackRouter` is the only `BackHandler`: the system's back gesture closes the topmost
menu, sheet, dialog or alert first (`Presenter.dismissTop()`), then pops the stack, and at a
tab's root leaves the app (`docs/DESIGN.md` § "The Android app"). An alert or sheet that is not
`dismissible` takes Back without closing.

**Presentations** are drawn in the window above everything, never as Android dialogs, so a
picture of a screen carries them and Back reaches them first:
`Sheet(isPresented, onDismiss, detents = listOf(SheetDetent.large) | medium | height(dp),
dismissible, background) { … }` (iOS 26's card: from just under the status bar at the large
detent over a dimmed screen, the grabber when there is more than one detent, a drag, a tap on the
dimming or Back to dismiss; `LocalInSheet` tells a `NavigationScreen` it is in one),
`FullScreenCover(isPresented, onDismiss) { … }`, `Alert(isPresented, title, onDismiss, message,
actions = listOf(AlertAction(title, ActionRole.normal | cancel | destructive, enabled, tag) {
… }), textField = AlertTextField(…))` (iOS 26's alert: two buttons side by side when they fit,
stacked otherwise, cancel last), `ConfirmationDialog(isPresented, title, onDismiss, titleVisible,
message, actions)` (the action sheet, from the foot), `Menu(items) { label }` (a pull-down menu
that grows out of its button, `MenuPlacement`), `Modifier.contextMenu(items, onClick)` (long press,
over a dimmed screen). `MenuItem.Action(title, symbol, image, role, checked, enabled, tag) { … }`,
`MenuItem.Section(title, items)`, `MenuItem.Divider`; a picker is actions with one `checked`.
Anything else can be presented with `Present(kind, isPresented, onDismiss, options) { … }`.

### The design system

**Lists.** `InsetGroupedList(modifier, state, contentPadding) { section(key, header, footer) {
row(key, style, onClick, swipeActions, contextMenu, tag) { … } }; item(key) { … } }` is
`List.listStyle(.insetGrouped)` on iOS 26: white cards 16 points in with continuous corners, the
section gaps and header and footer positions measured on the iPhone, separators only between rows
and inset to the content (`RowStyle(insets, background, separator, separatorInset)`), the
pressed row's highlight, swipe actions (`SwipeAction(title, symbol, tint, tag) { … }`, the first
nearest the edge, as SwiftUI lays a trailing swipe out) and the context menu. A list is built
outside composition, so a colour a row needs is read before the list (`val danger = Theme.danger`).
`item` is something on the canvas between sections. `FieldLabel(key)` is a section header;
`SettingsFooter(text)` a footer; `ValueRow(key, value, mono)` a label and its value. The iPhone's
`settingsRowLayout()` is two halves here: `Modifier.settingsRowLayout()` (at least 28 tall, the
content centred) on the row's content everywhere, and `row(style = RowStyle.settings)` (12 above
and below, 16 at the sides, no separator) for a row in a list.

**Bars and controls.** `TopBar { … }` and `BottomBar { … }` (the bar material, painted to the
edge of the screen behind the system bars), `BarButton`, `BarIconButton(symbol, description)`,
`BarTextButton(title, prominent)`, `BarLabelButton(title) { leading }`, `BackButton`;
`TabBar(items, selected, onSelect)` is the shell's. `Toggle(title, isOn, onChange)` and
`Switch(isOn, onChange)` (iOS 26's 63 × 28 switch), `SegmentedControl(segments, selected,
onSelect, fill)` with `Segment(title, image, tag)`, `SearchField(text, onTextChange, prompt,
isActive, onActiveChange, onCancel)` (iOS 26's glass capsule with its magnifying glass and clear
button, and while it is in use a round close button beside it), `ActivityIndicator(size = mini |
regular | large, tint)` (UIKit's eight-spoke spinner), `TextField(placeholder, text,
onTextChange, style, secure, keyboard, submit, onSubmit, enabled, minHeight, tag)`.
`Button(onClick, style) { label }` with `PlainButtonStyle`, `BorderlessButtonStyle`,
`PrimaryButtonStyle(fullWidth)`, `ChipButtonStyle` — a `ButtonStyle` is SwiftUI's, drawn from the
pressed and enabled state. `Label(title, symbol, font)` sets the words a measured gap after the
symbol's ink.

**Type.** `Text(text, modifier, style, color, lineLimit, truncation = head | middle | tail,
alignment)` is SwiftUI's `Text`: the iPhone's styles (`SystemFont.largeTitle` … `caption2`,
`SystemFont.system(size, weight)`, `.weight(…)`, `.monospaced()`, `.monospacedDigit()`) in the
system sans at the iPhone's sizes as sp, each line the style's leading tall, and the text box
trimmed to SwiftUI's — the first baseline one SF ascent (0.952 em) under the top and the bottom
one SF descent (0.241 em) under the last baseline — so a row is as tall as the iPhone's in either
language. Latin text is tracked towards SF's widths by size and weight (`SystemFont.tracking`,
measured on the reference pictures); Chinese is set untracked in the interface language's locale,
so it takes the simplified forms whatever the phone is set to. A colour or font set above a view
reaches the text and symbols in it: `Foreground(color, font) { … }`, `FontScope(font) { … }`
(SwiftUI's `.foregroundStyle` and `.font`).

**Tokens.** `Theme`: `canvas`, `surface`, `surfaceSunken`, `border`, `ink`, `inkSecondary`,
`inkTertiary`, `accent`, `onAccent`, `hairline`, `quietFill`, `running`, `attention`, `resting`,
`danger`, `added`, `removed` (each light and dark, from `Theme.swift`), `Theme.dotColor(tone)`
for the core's `DotTone`, `Radius`, `Space`, `Mark`, `Touch`, `Theme.Text` (`title`, `label`,
`meta`, `caption`, `metaMono`), `mono`, `monoBody`. `SystemColor` holds UIKit's (`label`,
`secondaryLabel`, `separator`, the fills, `dimming`, …), `Glass` the iOS 26 glass the bars, menus
and alerts are made of (`Modifier.glass(shape)`, `glassPanel(shape)`), `ContinuousShape(radius)`
and `CapsuleShape` Apple's continuous corners. `Modifier.card()`, `softSurface()`,
`pageBackground()`, `barBackground()`; `scaledMetric(value, relativeTo)` is `@ScaledMetric`.

**Primitives** (RCUI `Design/`, same names): `StatusDot(tone, size)` (the core's `DotTone`;
`StatusDot.pulses(tone, reduceMotion)`), `OnlineDot(online, updating)`, `StatusLabel(tone, text)`,
`SessionOriginLabel(tone, origin)`, `AgentChip(agent)` (the agent's name from the core's
`AgentLabel`), `AgentLogo(agent, size, tint)` and `AgentLogo.vector(agent)` (the four agents'
marks, null for another), `ChipPill`, `CodeText`, `EmptyStateView(symbol, title, message)`,
`AppMark(size)`, `NoticeBanner`, `Divider`, `LaptopGlyph`/`LaptopShape`,
`FolderGlyph`/`FolderShape`, `OutlineGlyph`, `EffortGauge(position, isFast)`, `PromptShield`,
`WorkingCircle(label)`, `StopSlider(stops, index, value, onIndex, onCommit)`,
`GrowingTextField(placeholder, text, onTextChange, isFocused, onFocusChange, identifier,
followsTail)` (grows with the draft to `ComposerLayout.maximumLines`, then scrolls with the
iPhone's indicator), `Modifier.dismissesKeyboardOnBackgroundTap()` with `textInputRegion()`,
`ComposerLayout`, `OneAtATime`, `FieldScrollProbe`.

**Symbols.** `Icon(Sf.gearshape)` is `Image(systemName: "gearshape")`: the symbol sized and
weighted by the font it is set in (the nearest `LocalFont` unless given one) or `Icon(symbol,
side, weight)` in a box of its own; `Sf.named("…")` finds one by the iPhone's spelling. Every SF
Symbol RCUI names is in `Sf` (a test scans RCUI to keep it so), drawn as the nearest lucide glyph
(ISC; `Lucide.kt` is generated from `web/node_modules`, regenerate rather than edit) or, where
SF's own drawing differs too much — the tab bar's filled symbols, the checkmark, the photo, the
filter, the app mark's connected nodes — as a drawing of its own in `Glyphs.kt`, measured on the
reference pictures.

**Words.** `L10n.string("Sign out")` is the iPhone's `L10n.string`: the catalogue's English key,
the current language's words, `L10n.string(key, values…)` with the iPhone's specifiers (`%@`,
`%lld`, `%.1f`, `%%`, positional `%2$@`, `IosFormat`). The language is the account's
(`settings.language`, which the model follows with `L10n.follow`; the core's own words follow the
same setting): English until Chinese is picked, whatever the phone is set to, and a change reaches
every open screen at once without restarting anything. Look a word up where it is drawn, never
keep it. `L10n.platform(sentence)` says a sentence the core built in Android's words (the
Keystore where RCCore says the keychain). An Android-only word for a feature goes in its own
overlay file; a word UIKit supplies on the iPhone (Back, Search, OK) is in `system.json`.

### The platform services

The core declares its seams and the app fills them in `AppEnvironment`:

- **Secrets**: `KeystoreSecretStore(context)` is the core's `SecretStore` — AES-256-GCM under a
  key the Android Keystore generates and never lets out, usable while the phone is unlocked, the
  entry's name bound into the seal; it fails with the core's
  `TransportError.SecureStorageUnavailable`.
- **Defaults and files**: `SharedPreferencesDefaults.standard(context)` is the core's
  `UserDefaults`; `AppDirectories.drafts(context)` and `cache(context)` hold the `DraftStore` and
  the `LocalCache` in the no-backup directory, and the manifest backs nothing up.
- **The build**: `AppBuild.version = BuildConfig.VERSION_NAME`, set once in
  `RemoteControlApplication`; the connection measures it against `apps.android` (A46).
- **The lock and the cover**: `BiometricLock.canAuthenticate(context)`,
  `authenticate(activity, reason)` (biometric or the screen lock, `BIOMETRIC_WEAK |
  DEVICE_CREDENTIAL`); `RecentsShield.apply(activity)` (blank in recents); `SceneRule`
  (`isForeground`, `isBackground`, `shields` from a lifecycle state; `currentSceneState()`).
- **Attachments**: `rememberPhotoPicker(max) { uris }` (the system photo picker: no photo
  permission), `rememberDocumentPicker { uris }`, `rememberCameraCapture(onCapture, onFailure)`,
  `rememberCameraAccessRequest()` and `Camera.exists`, `PickedFile.read/size/name`,
  `PhotoPreparation.jpeg(bytes)` (oriented, fitted, JPEG), `AttachmentNaming`.
- **Dictation**: `SpeechInputPlatform`, `SpeechInputEvent`, `SpeechInputFailure` as the iPhone's
  `VoiceInputController` declares them, and both backends behind them:
  `SystemSpeechRecognizer(context, localeIdentifier, microphone)` (Android's recogniser, on-device
  where the phone has the language, `SpeechLanguages`; its runs joined through the core's
  `TranscriptSegments`) and `GatewaySpeechRecognizer(client, microphone, capture)` (the gateway's
  `WS /ws/stt` through the core's `STTSocket`, cut into segments under the 120-second cap, A44);
  `MicrophoneCapture(context)` gives the protocol's 16 kHz mono PCM16LE in 100 ms chunks;
  `InputLevel.from(rms)`. `microphone` is the screen's `rememberPermissionRequest(RECORD_AUDIO)`.
- **Notifications**: `LocalNotifications.post(context, kind, identifier, title, body, thread,
  deepLink)` and `removeAll`, on `NotificationChannels` (one channel per kind, the gateway's
  words); a tap opens the session link, which `MainActivity` hands to `model.handle(link)`.
  `NotificationAuthorization.status(context)`, `openSettings(context)` and
  `rememberNotificationPermissionRequest()`; `ForegroundBanner.shows`.
- **Pairing**: `SystemCodeScanner.make()`, a `CodeScanning` with `requestAccess()` and
  `Viewfinder(onCode)` (CameraX and ML Kit, on the phone); `StaticCodeScanner(payload)` for tests
  and the demo — `model.codeScanner` picks.
- **Terminal**: `TerminalHost(feed, fontSize, onSize: (TerminalSize) -> Unit, onInput,
  onFontSize, modifier)`: Termux's terminal view, fed by `TerminalFeed.write(bytes)` from any
  thread, the bytes the person types out, the core's `TerminalSize` on every layout, a pinch scaled
  by `TerminalTypeSize`, the iPhone's font and colours; `TerminalPasteboard.bytes(context,
  maxBytes = TerminalLimits.maxInputBytes)`.
- **Markdown**: `MarkdownVisualView(kind, source, modifier, inlineRuns, maximumHeight, textScale,
  onLink)`: the iPhone's Markdown renderer and Mermaid in a `WebView`, served from the app's
  assets at `https://appassets.androidplatform.net`, reporting its height back to Compose;
  `MarkdownVisualParts.of(source, runs)` cuts an `inlineMath` paragraph into its formulas (the
  core's `MarkdownMath.spans`) and the runs of text between them (`runs`, the conversation's
  inline Markdown reading; one plain run on its own).
- `Modifier.keepsScreenAwake()` (the core's `ScreenAwakeRule`, only in the foreground),
  `Haptics.selection(view)` and `Modifier.selectionFeedback(trigger)`,
  `rememberPermissionRequest(permission)`, `AppSettings.open(context)`.

## Pictures

The harness draws a composable as the iPhone 17 shows a screen — 402 × 874 points at three
pixels a point, 1206 × 2622 like every picture in the iPhone's reference set — with the iPhone's
safe area (62 at the top, 34 at the bottom) and the app's root around it, in English or Chinese,
light or dark:

```kotlin
class DevicesScreenshots : IPhoneScreenshotTest() {      // the screen, the graphics, the rule
    @Test fun list() = compose.picture("devices", "list", Variant(L10n.chinese, dark = true)) {
        ShellAt(AppModel.Tab.devices) { route -> if (route is TabRoot) DevicesScreen() }
    }
}
```

`picture(group, name, variant)` and `capture(group, name, variant)` (what is on screen now, after
a test has driven it) draw `app/build/outputs/roborazzi/<group>/<name>-<zh|en>-<light|dark>.png`
when the run is `recordRoborazziDebug`. `ShellAt(tab, path)` opens the real shell on a tab with
routes pushed over its root, so a screen stands under the real bars; drive it with Compose's test
API (`onNodeWithTag(…).performClick()`, swipes) before taking the picture, as
`compare/CompareScreenshots.kt` does for an open menu and a swipe. A picture of the whole app in
its demo is `DemoApp.attach(name)` (above). To compare with the iPhone, lay the picture beside
the one in the reference set with the same test and step (mask the iPhone's status bar, which
this harness does not draw).

`gallery/` pictures every page of the gallery in both languages and appearances; `compare/`
pictures the system pieces in the arrangements the iPhone's own screenshots show (the devices
list, the users list, the settings groups, an alert, a stacked alert, a sheet, a swipe, the
composer's three menus, a conversation's bar, the archive's search), which is what they were
measured against; `shell/RootScreenshots.kt` the sign-in form and the update screen;
`demo/DemoPicturesTest.kt` the demo's first screen and its live conversation, as the iPhone's
`testSessionsAndChat` takes them.

## Where it differs from the iPhone

- **The face.** Roboto (the system sans) for SF, tracked to SF's widths by size and weight;
  Chinese in the system's CJK face. Robolectric has no 600 instance of Roboto and draws semibold
  as medium, so a picture's semibold is a little narrower and lighter than a phone's.
- **Glass** is a translucent fill with a rim and a shadow, without the blur of what is behind it.
- **Symbols** are lucide's line glyphs where SF's are not drawn here; their sizes are matched,
  their drawings are lucide's.
- **Words** that name Apple's places say Android's (`app/src/main/strings/overlay.json`): the
  update screen's button opens the update page rather than TestFlight or the App Store, the lock
  unlocks with a fingerprint, a face or the screen lock, and a token is kept in the Android
  Keystore.
- **No push channel yet**: notifications are the app's own, raised while it reads the stream, so
  there is no gateway registration and nothing to drop while the conversation is open.
- **Not measured on the iPhone**, for want of a reference picture: the confirmation dialog, the
  context menu (iOS lifts the element; this dims the screen and stands the menu beside it), a
  menu of words alone, and the dark appearance's glass. They follow iOS 26's conventions.
- **Haptics** are Android's nearest constants; the speech recogniser, the camera, the Keystore,
  the web view's JavaScript and notifications are exercised by their pure parts on the JVM and
  need a phone for the rest.

## Licences

Termux's terminal emulator and view (`app/src/main/java/com/termux/`) are Apache 2.0, vendored
with their `LICENSE` and `NOTICE`. lucide's path data (`icons/Lucide.kt`) is ISC; its licence
travels with it in the file.
