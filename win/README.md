# win/

The Windows client for remote-control. It is the Mac app drawn for Windows (`docs/DESIGN.md`
§ "The Windows app"), which is itself the web app drawn natively: the same routes, the same words
in both languages, the same tokens, rows, controls, popovers and breakpoints, in Compose
Multiplatform Desktop on the Android app's Kotlin core (`android/core`). The Mac app (`macos/`) is
the reference for every pixel, word and behaviour; where the two differ, this app is wrong unless
the ruling says otherwise, and where the Mac's Swift leaves a question open the web answers it.

What is here today is the foundation: the design system, the strings, the router and the window
shell, the platform services and the renderer. The app model on the core is stage 2 and the
screens are stage 3; until they arrive the window shows the design system's gallery.

## Layout

| Path | What it holds |
| --- | --- |
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/` | Gradle 9.8; the Android app's version catalog; `android/core` included by path as `:core`, never copied |
| `app/` | the app, `com.junbingao.remotecontrol.win`, and its packaging (`app/packaging/RemoteControl.ico`) |
| `app/src/main/kotlin/…/win/app/` | `Route`, `Router`, `LayoutClass`, `AppCommands` (the key map), `LaunchOptions`, `ShellState`, `RootView`, `AppServices`, `MainWindow`; `Main.kt` is `main` |
| `…/win/design/` | `tokens.css` as Kotlin, the web's type, SwiftUI's stacks, every primitive of the Mac's `Design/`; `icons/` (lucide) and `overlay/` (the overlay layer) |
| `…/win/strings/` | every group of the Mac's `Strings/`, one file per group, with `S` and the Windows-only groups |
| `…/win/layout/` | the topbar and its tabs, the page head, the page shell, the landing rule, the window strip |
| `…/win/platform/` | the services: the token vault, notifications and the tray, the microphone, the terminal emulator, the Markdown engine, Windows' settings |
| `…/win/shared/` | the web helpers more than one feature reads (`Identity`) |
| `…/win/standin/` | `InterfaceLanguage`, `TimelineDetail`, `DotTone`: stand-ins for the core's types, which stage 2 deletes |
| `…/win/gallery/` | the gallery pages the window and the renderer draw |
| `app/src/test/` | JUnit 5; `resources/markdown/corpus.json` and `resources/text/linebreaks.json` are the Mac's own results the tests compare with |
| `preview/` | the renderer: scenarios drawn offscreen and written as PNG |

## Building and checking

```
cd win
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew --no-daemon :app:test
./gradlew --no-daemon :preview:run --args="--all --out /tmp/rc-win-previews"
./gradlew --no-daemon :preview:run --args="--all --language zh-Hans --out /tmp/rc-win-previews-zh"
./gradlew --no-daemon :app:run --args="--ephemeral"
```

Always `--no-daemon`. `:app:run` needs the window server: from a shell outside the logged-in
session (`launchctl managername` prints `Background`) AWT cannot reach it and the run stops with a
`HeadlessException`. Build the app image there instead and start that, then end it by its PID:

```
./gradlew --no-daemon -Pcompose.desktop.packaging.checkJdkVendor=false :app:createDistributable
open -n "app/build/compose/binaries/main/app/Remote Control.app" --args --ephemeral
```

(The property lets jpackage use Homebrew's JDK on a Mac; a Windows build needs none.)

### Packaging

`./gradlew --no-daemon :app:packageMsi` and `:app:packageExe` build the installer on a Windows host —
jpackage makes installers for the system it runs on only. Either installs "Remote Control" per
user, upgrades the previous install in place (a fixed upgrade UUID), and carries
`app/packaging/RemoteControl.ico`.

## Launch arguments

`LaunchOptions` parses the Mac's set; the app model (stage 2) is what acts on most of them.

| Argument | What it does |
| --- | --- |
| `--demo` | The core's offline demo around the app: an account, two devices and their sessions, nothing on the network (stage 2) |
| `--demo-account` | The offline demo behind the sign-in form instead; `--registration-open` opens its registrations (stage 2) |
| `--demo-update-required` | The demo states a minimum above this build, which is how Update required is reached (A46, stage 2) |
| `--ephemeral` | Nothing of the person's is read or written: the token is kept in memory (`MemorySecretVault`). Every automated run uses it |
| `--reset-state` | Start as a fresh install (stage 2) |
| `--language=en\|zh-Hans` | The interface language for the run. On a gateway the account's own preference (A41) arrives with `hello` and wins (stage 2) |

Without `--ephemeral`, on Windows, the token is kept with Windows' data protection for the current
user in `%LOCALAPPDATA%\Remote Control\secrets`.

## The window

One Compose `Window` with the system title bar, so snapping, resizing and the caption buttons are
Windows' own, titled "Remote Control": 1280 × 860 at first, never narrower than 480 (or shorter
than 560), always light. There are no traffic lights, so `LocalTrafficLightInset` is 0 and a strip
starts its content at the web's padding. There is no menu bar; the Mac's menu commands are keys
(`AppCommands`): Ctrl+1, Ctrl+2 and Ctrl+3 for the tabs, Ctrl+, for Settings, Ctrl+N for New
session, Alt+Left and Alt+Right and the mouse's back and forward buttons for the history, and
Escape closes the newest overlay. Closing the window leaves the app running in the notification
area (`AppTray`), whose icon opens the window again and offers Open and Quit; a click on one of the
app's notifications opens the window too. Where there is no notification area, closing quits.

## What the features build on

**The model** is stage 2's: the app model on the Kotlin core, in the place of `MacAppModel`. What
the shell reads of it today is `ShellState` (`router`, `origin`, `username`, `connectionIsOpen`,
`hasSnapshot`, `hasDevices`), provided as `LocalShellState`.

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
each line box of height L, and a line box lands on a whole point. Without a style a text takes the
environment's font (`WithFont`) on the line SwiftUI gives the Mac's text. It takes a `String` or an
`AnnotatedString`, whose spans' weight, size and `FontFamily.Monospace` pick their faces. The face is
the platform's: Segoe UI Variable Text (Segoe UI before Windows 11), Consolas, and Microsoft YaHei
UI for Chinese on Windows; on a Mac the Mac's own — SF with its size-specific tracking, SF Mono,
PingFang — so this renderer's pictures match the Mac renderer's to the pixel. Two of the Mac's text
system's habits are kept: a paragraph of two lines never ends on one short word (`PushOut`: the
word before it comes down, a frame after the text is first laid out), and a line drawn where the
exact layout puts it (below).

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
size, label)` and `iconBtn`, `pill` and `quietPill`, `MenuTriggerStyle` (the row menus' three dots,
lit by `LocalRowIsHovered`), `Badge(text, tone)`, `AgentChip(agent)`, `AgentLogo(agent, size)`,
`Mark(size)`, `Dot(style, pulses)`, `StatusDot(tone, state)`, `OnlineDot(online, pulses)`,
`Switch(isOn, label, onChange)`, `Segmented(value, options, ariaLabel)` with `SegmentOption`, `Spinner(size)`,
`DeviceGroupHeader`, `ArchiveGroupHeader`, `Modifier.surface()`, `Modifier.card()`, `GroupTitle`,
`FieldLabel`, `Hint`, `FormError`, `EmptyState(text, title)`, `PageHead(title, hint) { actions }`,
`FieldText` with `Modifier.fieldChrome(focused)`, `WebField`, `SearchField`, `MenuList`,
`MenuItemRow`, `SpaceBetween`, `Help(text) { }` (the web's `title` tooltip), and `Icon(LucideIcon.x,
size, strokeWidth, modifier, color)` for every lucide icon the web imports, named as it imports them. The
environment is `LocalContentColor` (`WithForeground`), `LocalFont` (`WithFont`), `LocalIsEnabled`
(`Disabled`) and `LocalReduceMotion` (Windows' animation setting in the window). `./gradlew
--no-daemon :preview:run --args="--scenario gallery,gallery-tokens,gallery-menu --out <dir>"` draws
all of them.

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
anywhere but the panel and its trigger for a popover — or their own control. A dialog moves focus
as the web's does, to its first field (`Modifier.dialogField(requester)` marks one), or to nothing
when a close button or a button comes first. A modal or the drawer blurs everything under it, the
drawer included; popovers are placed by `PopoverPlacement`, the web's rule, above everything. A
modal's and a drawer's content is a `VStackScope`. **An overlay's content is drawn in the overlay
layer and reads that layer's composition locals**, not those where it was asked for: pass in
anything a feature keeps in its own locals.

**Words.** `S.<group>.<key>` in the current interface language (`InterfaceLanguageSource.current`),
read through snapshot state, so a change redraws every screen; nothing may read a string at
static-init time. The helpers at the foot of `web/src/strings.ts` are on `S` too (`agentLabel`,
`platformLabel`, `stateLabel`, `dotToneLabel`, `timelineDetailLabel`, `sessionOriginLabel`,
`sessionTitle`, …). `S.win` holds Windows' own words where the Mac names the Mac; `S.winComposer`
and `S.winSettings` are the composer's and Settings' Windows-only words. A feature's Windows-only
words go in a strings file of its own built the same way: one class with an `en` and a `zhHans`
table and `of(language)`, and an accessor on `S`. The group files are generated from the Mac's
tables, word for word; when the Mac adds a word, regenerate rather than edit.

**Previews.** `LocalPreviewStage.current` is the scenario's stage and null in the app: a view reads
it to show, for a render, a state that takes a click. `LocalShowsCaret` is false in a render.

## The platform services

Each is a small API of its own; stage 2 adapts the core's interfaces to them.

| Service | API | On Windows | Elsewhere |
| --- | --- | --- | --- |
| Token vault | `SecretVault`: `read(key)`, `write(data, key)`, `remove(key)`, all `suspend`; `SecureStorageUnavailable` when it cannot | `DpapiSecretVault`: DPAPI for the current user, one `<key>.secret` file per key in `%LOCALAPPDATA%\Remote Control\secrets`, written atomically | `MemorySecretVault`, also for `--ephemeral` and the renderer |
| Notifications | `Toasts`: `post(ToastNotice(title, body, target))`, `removeDelivered()`, `onOpen` with the clicked notice's `ToastTarget(deviceId, sessionId)` | `TrayToasts`: Windows' toasts from the app's notification-area icon (`AppTray`); a click arrives as the icon's action (`ToastClicks`) | `InertToasts`: kept in the process, never shown |
| Microphone | `VoiceRecorder`: `start()`, `stop()`; `RecorderHandlers(onFrame, onLevel, onError)` with `RecorderError` | `MicRecorder`: `javax.sound.sampled`, 16 kHz PCM16LE mono in frames of 1920 samples (120 ms, as the Mac's dictation sends them), resampled when the device cannot do 16 kHz (`Downsample`, `PcmChunker`); Windows' privacy setting read first (`MicrophoneAccess`) | the same, but nothing here ever opens it |
| Terminal | `TerminalEmulator(feed, onSize, onInput, modifier)` with `TerminalFeed` (`write(bytes)`, `reset()`) | JediTerm in the window (`SwingPanel`), the Mac's terminal font and colours (`TerminalTheme`) | the same; a render, which has no window, draws none |
| Markdown | `MarkdownEngine.shared.hast(text)`: the hast as JSON (`[tag, properties, children]`, text a string), or null without the pipeline | the Mac app's `markdown.bundle.js` in QuickJS, served from `../macos/Sources/RCMac/Resources/Highlight/` by the build | the same |
| Settings | `ReduceMotion.current`, `AppData.directory` and `AppData.secrets`, `Host.isWindows` | Windows' animation setting; `%LOCALAPPDATA%\Remote Control` | no reduced motion; the same folder under the home directory |

## The renderer

```
./gradlew --no-daemon :preview:run --args="[--demo | --gateway <url> --username <u> --password <p>]
    (--scenario <name>[,<name>…] | --all | --list)
    [--width W --height H] [--scale 1|2] [--language en|zh-Hans] --out <dir>"
```

The Mac renderer's arguments, scenarios and sizes, so the two pictures of a scenario compare
directly. Each scenario is drawn in an `ImageComposeScene` with no window
(`java.awt.headless=true`) through the real `RootView`, left to settle — its `settle` and then
until nothing is left to draw — and written as `<out>/<name>.png`, 1280 × 860 at 2× unless it says
otherwise. A scenario is `PreviewScenario(name, route, width, height, stage, account, language,
settle, setup, prepare, content)`, the Mac's fields; `content` draws one view where the route would
be. The registry is `PreviewScenarios.all`: `FoundationScenarios` today, then one file per feature
beside it. `--demo` and `--gateway` need the app model: until stage 2 signs the renderer in, a
scenario without `content` stops with "a route needs the app model, which stage 2 brings".

What a render cannot show: the pointer's hover states, a text field's caret, and the terminal
emulator, which is a Swing component.

## Matching the Mac's pictures

On a Mac the renderer draws with the Mac's own faces, so its pictures and the Mac renderer's
compare pixel for pixel. What it takes, measured against the Mac renderer and CoreText:

- **SF as CoreText sets it**: the variable face cloned at its optical size and CoreText's weights
  (510 for medium, 590 for semibold), with the size-specific tracking CoreText applies from the
  face's `trak` table and Skia leaves out; widths agree to a thousandth of a pixel.
- **The Mac's line**: SwiftUI's line heights for its natural text, the browser's baseline rule for
  a line box, text widths rounded up to the device pixel.
- **Ink**: Skia's glyph coverage depends on the colour; text is drawn in a neutral grey and
  recoloured, which brings its weight of ink to the Mac's.
- **Exact layout** (above), and the Mac's text system's two-line rule, checked against SwiftUI's
  own line breaks of 257 paragraphs (`PushOutTests`).
- **Overlays**: the backdrop's blur is a Gaussian of 0.94 px per CSS px, the Mac's `.blur(radius: 1)`.

What still differs: the backdrop is one level of 255 lighter (Skia blends an 8-bit premultiplied
colour, Core Animation a float one); a few pixels in a thousand differ by more than a level or two,
at glyph edges and in the blur; and the spinner's phase is the moment the picture is taken, on both.

## Not verified on this Mac

Everything that needs Windows: the DPAPI round trip (its test runs on Windows only), the toasts and
a click on one on Windows 10 and 11 (the click rule is unit-tested; the toasts were never posted
here), the tray, Segoe UI Variable and Microsoft YaHei UI on screen, the microphone (never opened),
JediTerm in a window, and `packageMsi`/`packageExe`.

## Icon

`app/packaging/RemoteControl.ico` holds the Mac's `AppIcon` drawing at 16, 20, 24, 32, 40, 48, 64, 128
and 256 px, each drawn from the vectors rather than scaled; `app/src/main/resources/icon/` holds them
but the 128 for the window and the notification area.

## What stage 2 wires

- The core's types in place of `standin/` (`InterfaceLanguage`, `TimelineDetail`, `DotTone`), which
  goes.
- The app model on the core: it implements `ShellState` and provides `LocalShellState`, acts on
  `LaunchOptions`, and lets `AppCommands` navigate once someone is signed in (`canNavigate`).
- `AppServices.vault` as the core's `SecretStore`; `toasts.onOpen` to `router.go(Route.Chat(…))`.
- `StatusDot`, `S.sessionTitle` and `S.sessionOriginLabel` overloads that take the core's session.
- `RootView` drawing the routed screens in place of the gallery.
- The renderer signing in for `--demo` and `--gateway`, a model per scenario, and the scenarios'
  `PreviewContext` reaching it.
