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
| `core/` | the Kotlin core (`com.junbingao.remotecontrol.core`), shared with the Windows app; its own README |
| `app/build.gradle.kts` | the app, plus two build tasks: the string catalogue and the Markdown assets (below) |
| `app/src/main/kotlin/…/android/design/` | RCUI `Design/`: `Theme`, the type ramp, every primitive and glyph |
| `…/android/system/` | the iPhone's system pieces: bars, lists, switch, segmented control, search field, sheets, alerts, dialogs, menus, swipe actions, the presentation stack |
| `…/android/navigation/` | `Navigator` (a stack per tab), `NavigationStack`, `NavigationScreen`, deep links, the tab bar's visibility |
| `…/android/icons/` | `Sf`, every SF Symbol RCUI draws, as lucide glyphs (`Lucide.kt` is generated) |
| `…/android/strings/` | `L10n` and `IosFormat`; the table itself is generated |
| `…/android/shell/` | `AppRoot`, `MainShell`, `ShellState`, `BackRouter`, the placeholder destinations |
| `…/android/launch/` | `LaunchOptions`, the iPhone's launch arguments |
| `…/android/security/`, `attachments/`, `voice/`, `push/`, `markdown/`, `scanner/`, `terminal/`, `awake/`, `haptics/`, `permissions/` | the platform services, each behind a small API of its own |
| `…/android/gallery/` | the primitives' gallery, a debug build's Settings → Diagnostics row |
| `app/src/main/java/com/termux/` | Termux's `terminal-emulator` and `terminal-view`, vendored (Apache 2.0, `LICENSE` and `NOTICE` beside them) |
| `app/src/main/strings/` | `overlay.json` (Android's words for the iPhone's Apple names) and `system.json` (the words UIKit supplies) |
| `app/src/main/res/` | the theme behind the system bars, the adaptive icon, the notification icon, the network and file-provider configuration |
| `app/src/test/kotlin/…/android/` | JUnit and Robolectric tests; `harness/` is the screenshot harness, `gallery/` and `compare/` its pictures |
| `app/src/test/screenshots/` | the recorded pictures `verifyRoborazziDebug` compares against |

## Building and checking

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
cd android
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew --no-daemon :app:verifyRoborazziDebug   # the pictures still match the recorded ones
./gradlew --no-daemon :app:recordRoborazziDebug   # after a deliberate visual change: record them again
```

Everything is checked on the JVM: the unit tests and the pictures run on Robolectric with the
real graphics stack (`graphicsMode=NATIVE`), so no emulator is needed. A full run of the unit
tests with the pictures takes about two minutes.

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
`args` extra — `--esa args a,b` or `--es args "a b"`: `--demo`, `--demo-account`, `--demo-queue`,
`--demo-update-required`, `--demo-preference-change`, `--registration-open`, `--reset-state`,
`--ui-testing`, `--language=zh-Hans`, and in a debug build only `--voice-preview`,
`--voice-level=0.5`, `--voice-transcript=long` and `--field-scroll-probe`. `--gallery` (debug)
opens the primitives' gallery at once; it is also Settings → Diagnostics → Primitive gallery.
A `remotecontrol://session` link — from `adb`, a notification's tap or a browser — opens the
conversation in place: the Sessions tab, with it as the only screen over the list.

## What the features build on

**The shell.** `MainActivity` is the one activity: edge to edge, the page colour behind the
system bars (`res/values*/themes.xml`), configuration changes handled in place, recents kept
blank (`PrivacyShield`). `AppRoot(presenter, appearance, shielded) { … }` provides the
appearance (light or dark from the system, reduced motion from the animator scale), the
presentation stack and the privacy cover. `ShellState` holds the open `tab` (`AppTab.devices`,
`sessions`, `settings`, the iPhone's order), a `Navigator` per tab, the tab bar's visibility and
the `Presenter`; `open(SessionLink)` and `openGallery()` are its two jumps. `MainShell(state) {
route -> … }` draws each tab's stack under the floating tab bar; a second tap on the open tab
returns to its root. Each tab's root route is `TabRoot(tab)`; stage 3 replaces
`ShellDestination` with the screens.

**Navigation.** `LocalNavigator.current` is the stack a screen belongs to: `push(route)`,
`pop()`, `popToRoot()`, `setPath(routes)` (the iPhone's path assignment: a link replaces what
was over the root), `routes`, `canPop`. A route is any value. `NavigationStack` plays UIKit's push
and keeps each covered screen's saved state. `NavigationScreen(title, displayMode = large |
inline, leading, trailing, showsBack, onBack, listState, top, bottomBar, background) { insets ->
… }` is a screen: the bar under the status bar with the back button when there is somewhere to
go, glass bar items, the large title folding into the bar's 17-point title as the content
scrolls (pass the `LazyListState` so it follows exactly), `top` and `bottomBar` as
`.safeAreaInset(edge:)`. The content scrolls under the bars and takes `insets.padding()`.
`HidesTabBar()` inside a screen is `.toolbar(.hidden, for: .tabBar)` — the conversation calls it.

**Back.** `BackRouter` is the only `BackHandler`: the system's back gesture closes the topmost
menu, sheet, dialog or alert first (`Presenter.dismissTop()`), then pops the stack, and at a
tab's root leaves the app (`docs/DESIGN.md` § "The Android app"). An alert or sheet that is not
`dismissible` takes Back without closing.

**Presentations** are drawn in the window above everything, never as Android dialogs, so a
picture of a screen carries them and Back reaches them first. Each is a flag the screen owns
and a dismissal it answers, as SwiftUI's modifiers are:
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
Anything else can be presented with `Present(kind, isPresented, onDismiss, options) { … }`; the
presented content keeps the composition locals of the place that presented it.

**Lists.** `InsetGroupedList(modifier, state, contentPadding) { section(key, header, footer) {
row(key, style, onClick, swipeActions, contextMenu, tag) { … } }; item(key) { … } }` is
`List.listStyle(.insetGrouped)` on iOS 26: white cards 16 points in with continuous corners, the
section gaps and header and footer positions measured on the iPhone, separators only between rows
and inset to the content (`RowStyle(insets, background, separator, separatorInset)`), the
pressed row's highlight, swipe actions (`SwipeAction(title, symbol, tint, tag) { … }`, the first
nearest the edge, as SwiftUI lays a trailing swipe out) and the context menu. `item` is something
on the canvas between sections. `FieldLabel(key)` is a section header; `SettingsFooter(text)` a
footer; `ValueRow(key, value, mono)` a label and its value; `Modifier.settingsRowLayout()` the
settings rows' insets.

**Bars and controls.** `TopBar { … }` and `BottomBar { … }` (the bar material, painted to the
edge of the screen behind the system bars), `BarButton`, `BarIconButton(symbol, description)`,
`BarTextButton(title, prominent)`, `BarLabelButton(title) { leading }`, `BackButton`;
`TabBar(items, selected, onSelect)` is the shell's. `Toggle(title, isOn, onChange)` and
`Switch(isOn, onChange)` (iOS 26's 63 × 28 switch), `SegmentedControl(segments, selected,
onSelect, fill)` with `Segment(title, image, tag)`, `SearchField(text, onTextChange, prompt,
isActive, onActiveChange, onCancel)` (iOS 26's glass capsule with its magnifying glass and clear
button, and while it is in use a round close button beside it), `ActivityIndicator(size = mini | regular | large, tint)` (UIKit's eight-spoke spinner).
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
`danger`, `added`, `removed` (each light and dark, from `Theme.swift`), `Radius`, `Space`, `Mark`,
`Touch`, `Theme.Text` (`title`, `label`, `meta`, `caption`, `metaMono`), `mono`, `monoBody`.
`SystemColor` holds UIKit's (`label`, `secondaryLabel`, `separator`, the fills, `dimming`, …),
`Glass` the iOS 26 glass the bars, menus and alerts are made of (`Modifier.glass(shape)`,
`glassPanel(shape)`), `ContinuousShape(radius)` and `CapsuleShape` Apple's continuous corners.
`Modifier.card()`, `softSurface()`, `pageBackground()`, `barBackground()`; `scaledMetric(value,
relativeTo)` is `@ScaledMetric`.

**Primitives** (RCUI `Design/`, same names): `StatusDot(color, pulses)`, `OnlineDot(online,
updating)`, `StatusLabel`, `SessionOriginLabel`, `AgentChip(agent, name)`, `AgentLogo(agent, size,
tint)` and `AgentLogo.vector(agent)` (the four agents' marks, null for another), `ChipPill`,
`CodeText`, `EmptyStateView(symbol, title, message)`, `AppMark`, `NoticeBanner`, `Divider`,
`LaptopGlyph`/`LaptopShape`, `FolderGlyph`/`FolderShape`, `OutlineGlyph`, `EffortGauge(position,
isFast)`, `PromptShield`, `WorkingCircle(label)`, `StopSlider(stops, index, value, onIndex,
onCommit)`, `GrowingTextField(placeholder, text, onTextChange, isFocused, onFocusChange,
identifier, followsTail)` (grows with the draft to `ComposerLayout.maximumLines`, then scrolls with
the iPhone's indicator), `Modifier.dismissesKeyboardOnBackgroundTap()` with `textInputRegion()`,
`ComposerLayout`, `OneAtATime`, `FieldScrollProbe`.

**Symbols.** `Icon(Sf.gearshape)` is `Image(systemName: "gearshape")`: the symbol sized and
weighted by the font it is set in (the nearest `LocalFont` unless given one) or `Icon(symbol,
side, weight)` in a box of its own; `Sf.named("…")` finds one by the iPhone's spelling. Every SF
Symbol RCUI names is in `Sf` (a test scans RCUI to keep it so), drawn as the nearest lucide glyph
(ISC; `Lucide.kt` is generated from `web/node_modules`, regenerate rather than edit) or, where
SF's own drawing differs too much — the tab bar's filled symbols, the checkmark, the photo, the
filter — as a drawing of its own in `Glyphs.kt`, measured on the reference pictures.

**Words.** `L10n.string("Sign out")` is the iPhone's `L10n.string`: the catalogue's English key,
the current language's words, `L10n.string(key, values…)` with the iPhone's specifiers (`%@`,
`%lld`, `%.1f`, `%%`, positional `%2$@`, `IosFormat`). The language is English until Chinese is
picked, whatever the phone is set to, and a change reaches every open screen at once without
restarting anything: `L10n.use(L10n.chinese)`, or `L10n.follow { settings.language }` to read it
from the account's settings. Look a word up where it is drawn, never keep it. An Android-only
word for a feature goes in its own overlay file; a word UIKit supplies on the iPhone (Back,
Search, OK) is in `system.json`.

**Services**, each a small API the core's interfaces are laid over in stage 2:

- `KeystoreSecretStore(context)`: `read(key)`, `write(data, key)`, `remove(key)` — AES-256-GCM
  under a key the Android Keystore generates and never lets out, usable while the phone is
  unlocked, the entry's name bound into the seal; `SecureStorageUnavailable` when it cannot be
  reached.
- `BiometricLock.canAuthenticate(context)`, `authenticate(activity, reason)` (biometric or the
  screen lock, `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`); `AppLockView(onUnlock)`,
  `AppPrivacyCover()`, `PrivacyShield.apply(activity)` (blank in recents), `SceneRule`
  (`isForeground`, `isBackground`, `shields` from a lifecycle state; `currentSceneState()`).
- `rememberPhotoPicker(max) { uris }` (the system photo picker: no photo permission),
  `rememberDocumentPicker { uris }`, `rememberCameraCapture(onCapture, onFailure)`,
  `rememberCameraAccessRequest()` and `Camera.exists`, `PickedFile.read/size/name`,
  `PhotoPreparation.jpeg(bytes)` (oriented, fitted, JPEG), `AttachmentNaming`.
- `SystemSpeechRecognizer(context, locale)`: `start { RecognitionEvent }` (`Began`, `Partial`,
  `Final`, `Ended`, `Level`, `Failed`, `Finished`), `finish()`, `cancel()`, restarting a run as
  Android's recogniser ends one, on-device where the phone has the language (`SpeechLanguages`);
  `MicrophoneCapture(context).start(onChunk = { pcm16, level -> }, onFailure)` — 16 kHz mono
  little-endian PCM16 in 100 ms chunks for the gateway recogniser; `InputLevel.from(rms)`;
  `SpeechInputPlatform`, `SpeechInputEvent`, `SpeechInputFailure` as the iPhone's.
- `LocalNotifications.post(context, kind, identifier, title, body, thread, deepLink)` and
  `removeAll`, on `NotificationChannels` (one channel per kind, the gateway's words);
  `NotificationAuthorization.status` and `rememberNotificationPermissionRequest()`;
  `ForegroundBanner.shows`.
- `SystemCodeScanner.make()`: a `CodeScanning` with `requestAccess()` and `Viewfinder(onCode)`
  (CameraX and ML Kit, on the phone); `StaticCodeScanner(payload)` for tests and the demo.
- `TerminalHost(feed, fontSize, onSize, onInput, onFontSize, scaledFontSize)`: Termux's terminal
  view, fed by `TerminalFeed.write(bytes)` from any thread, the bytes the person types out,
  `onSize(cols, rows)` on every layout, pinch to resize, the iPhone's font and colours;
  `TerminalPasteboard.bytes(context, maxBytes)`.
- `MarkdownVisualView(kind, source, parts, maximumHeight, textScale, onLink)`: the iPhone's
  Markdown renderer and Mermaid in a `WebView`, served from the app's assets at
  `https://appassets.androidplatform.net`, reporting its height back to Compose.
- `Modifier.keepsScreenAwake(rule)`, `Haptics.selection(view)` and
  `Modifier.selectionFeedback(trigger)`, `rememberPermissionRequest(permission)`,
  `AppSettings.open(context)`.

## Pictures

The harness draws a composable as the iPhone 17 shows a screen — 402 × 874 points at three
pixels a point, 1206 × 2622 like every picture in the iPhone's reference set — with the iPhone's
safe area (62 at the top, 34 at the bottom) and the app's root around it, in English or Chinese,
light or dark:

```kotlin
class DevicesScreenshots : IPhoneScreenshotTest() {      // the screen, the graphics, the rule
    @Test fun list() = compose.picture("devices", "list", Variant(L10n.chinese, dark = true)) {
        ShellAt(AppTab.devices) { route -> if (route is TabRoot) DevicesScreen() }
    }
}
```

`picture(group, name, variant)` writes `app/src/test/screenshots/<group>/<name>-<zh|en>-<light|dark>.png`
(`IPhone.variants` is all four). `ShellAt(tab, path)` opens the real shell on a tab with
routes pushed over its root, so a screen stands under the real bars; drive it with Compose's test
API (`onNodeWithTag(…).performClick()`, swipes) before taking the picture with
`onRoot().captureRoboImage(…)`, as `compare/CompareScreenshots.kt` does for an open menu and a
swipe. To compare with the iPhone, lay the picture beside the one in the reference set with the
same test and step (mask the iPhone's status bar, which this harness does not draw).

`gallery/` pictures every page of the gallery in both languages and appearances; `compare/`
pictures the system pieces in the arrangements the iPhone's own screenshots show (the devices
list, the users list, the settings groups, an alert, a stacked alert, a sheet, a swipe, the
composer's three menus, a conversation's bar, the archive's search), which is what they were
measured against.

## Where it differs from the iPhone

- **The face.** Roboto (the system sans) for SF, tracked to SF's widths by size and weight;
  Chinese in the system's CJK face. Robolectric has no 600 instance of Roboto and draws semibold
  as medium, so a picture's semibold is a little narrower and lighter than a phone's.
- **Glass** is a translucent fill with a rim and a shadow, without the blur of what is behind it.
- **Symbols** are lucide's line glyphs where SF's are not drawn here; their sizes are matched,
  their drawings are lucide's.
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
