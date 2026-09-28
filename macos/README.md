# macos/

The native Mac client for remote-control. It is the web app, drawn natively
(`docs/DESIGN.md` § "The Mac app"): the same routes, the same words in both
languages, the same tokens, rows, controls, popovers and breakpoints, on top of
the iPhone app's protocol and state layer (`ios/Sources/RCCore`). The web app
(`web/`) is the reference for every pixel; where the two differ, this app is
wrong unless the ruling says otherwise.

## Layout

| Path | What it holds |
| --- | --- |
| `Package.swift` | swift-tools-version 6.0, macOS 15+, `RemoteControlMac`; RCCore from `../ios` (never RCUI) and SwiftTerm 1.11.2 exact, for the same reason `ios/Package.swift` pins it |
| `Sources/RCMac/App/` | `MacAppModel`, `Route` and `Router`, `RootView`, `LayoutClass`, the window's chrome, the menu commands, the scene, `Features` |
| `Sources/RCMac/Design/` | `tokens.css` as Swift, the primitives of `web/src/components/` and `ui.css`, the overlay layer, the icons |
| `Sources/RCMac/Strings/` | every group of `web/src/strings.ts` and its Chinese table, one file per group, plus `MacStrings` |
| `Sources/RCMac/Shared/` | the web helpers more than one feature reads: `format`, `identity`, `errors`, `accountErrors`, `sessionOptions`, `modelLabels`, `attach`, `attachments`, `answering`, `commands` |
| `Sources/RCMac/Layout/`, `Login/`, `Update/` | the topbar and page shell, the landing rule, sign-in, Update required (A45) |
| `Sources/RCMac/Chat/`, `Sessions/`, `Devices/`, `Settings/`, `Users/`, `Terminal/`, `Notifications/`, `Voice/` | the features' own directories |
| `Sources/RCMac/Resources/` | `Assets.xcassets`, and `Highlight/` for the chat's highlighter |
| `Sources/RCMacPreview/` | the renderer: scenarios drawn offscreen and written as PNG |
| `Tests/RCMacTests/` | swift-testing suites |
| `App/` | `@main`, `Info.plist`, the entitlements, `Assets.xcassets` (the icon in every size, `AccentColor`) |
| `project.yml`, `RemoteControl.xcodeproj` | the XcodeGen spec and the committed project with its shared scheme |
| `scripts/ci-check-macos.sh` | what CI runs |

## Building and checking

```
cd macos
# Set the toolchain per process; never change the global one with xcode-select.
export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
swift build
swift test
swift run RCMacPreview --demo --all --out /tmp/rc-previews

xcodegen generate
xcodebuild -project RemoteControl.xcodeproj -scheme RemoteControl -configuration Debug \
  -derivedDataPath /tmp/rc-mac-dd build
open -n "/tmp/rc-mac-dd/Build/Products/Debug/Remote Control.app" --args --demo --ephemeral
```

Everything compiles against the Xcode 26.6 SDK CI pins: the app targets macOS 15
and uses nothing newer than that SDK has.

### Signing

Without a team the app is signed to run locally (`CODE_SIGN_IDENTITY = -` in
`Signing.xcconfig`), so it builds and runs on any Mac. To distribute a build, put
`DEVELOPMENT_TEAM = XXXXXXXXXX` and the identity it signs with in
`Signing.local.xcconfig` beside it: that file is ignored by git and survives
`xcodegen generate`, so the project never carries anyone's team id. The app is
sandboxed with the network client, audio input and user-selected read-only
file entitlements, and the hardened runtime.

## Launch arguments

| Argument | What it does |
| --- | --- |
| `--demo` | RCCore's `DemoGateway` around the app, as the iPhone's `--demo`: an account, two devices and their sessions, nothing on the network |
| `--demo-account` | The offline demo behind the sign-in form instead; `--registration-open` opens its registrations |
| `--demo-update-required` | The demo states a minimum above this build, which is how Update required is reached |
| `--ephemeral` | Nothing of the person's is read or written: the token in a `MemorySecretStore`, the defaults in memory, caches and drafts in a scratch directory quitting removes. Every automated run uses it |
| `--reset-state` | Start as a fresh install |
| `--language=en\|zh-Hans` | The interface language for the run. On a gateway the account's own preference (A41) arrives with `hello` and wins |

Without `--ephemeral` the token goes to the Keychain under the service
`com.junbingao.remotecontrol.mac.gateway`. The app turns macOS's font smoothing
off for its own windows (`TextRendering.matchWeb()`), because the web draws its
type with `-webkit-font-smoothing: antialiased`; measured against the browser,
that is what makes the two carry the same weight of ink.

## The window

One `Window` scene, no title bar: the topbar is the window's top strip, the
traffic lights are centred in it 20 points from the edge, and the strip drags the
window (a double click zooms it). A page that draws its own strip — the chat
header — declares its height with `.windowStripHeight(_:)`, and every page reads
`\.trafficLightInset` to start its strip's content after the buttons. Closing the
window leaves the app running and connected; the Dock icon, a notification or a
menu command brings it back (`model.showWindow()`); Quit ends it. The menu bar has
⌘1 ⌘2 ⌘3 for the tabs, ⌘, for Settings, ⌘N for New session and ⌘[ ⌘] for the
history, and keeps the standard Edit menu.

## What the features build on

**The model.** `@Environment(MacAppModel.self)`: `connection` (RCCore's
`ConnectionStore`, measured against `apps.macos`), `sessions` (`SessionStore`:
search, filters, folded groups), `settings`, `preferences` (A35),
`preferenceSync` (A41), `drafts` (`DraftStore`), `router`, `origin` (the host the
topbar and Settings print), `deviceUpdateErrors` and `updateDevice(_:)` (A22),
`pairingFlow()`, `device(_:)`, `agent(for:)`, `isWindowActive`, `showWindow()`,
`signIn`, `register`, `signOut`. Extension points, so no feature edits the model:
`onSignOut(_:)` — every handler runs, in order, before the connection goes, and
empties what its feature holds of the account, as `web/src/stores/signOut.ts`
does — and `onSessionTransition(_:)`, both multicast. `connection.addFrameHandler`
is already multicast, keyed by a token of your own. Each feature's launch hook is
the `install(on:)` of its `…Feature` enum, which `App/Features.swift` calls once.

**The router.** `Route` is the web's routes; `router.go`, `replace`, `back`,
`forward`, `canGoBack`, `canGoForward`, `route.path` and `Route(path:)` for the web
path. Signing out lands on the login page and a sign-in returns to where the app
was; `.users` for a member resolves to `.sessions`; ⌘N is
`router.requestNewSession()`, which the Sessions page answers with
`router.takeNewSessionRequest()` when it appears.

**Breakpoints.** `@Environment(\.layoutClass)`: `maxWidth1023`, `maxWidth760`,
`maxWidth640`, `maxWidth480`, `maxWidth420` — the web's media queries, true at
their own width, read from the window's content width.

**Type.** `.css(size, weight:, lineHeight:, mono:, tracking:)` sets type as a CSS
rule does and puts the text on the browser's baselines (`CSSLineBox`): measured
against Chrome, the baseline sits ⌊(L − round(ascent) − round(descent)) ÷ 2⌋ +
round(ascent) into each line box of height L, and a centred box lands on a whole
point. Use it on text; a view with no text has no baseline to place.

**Tokens and primitives.** `Palette`, `FontSize`, `Space`, `Radius`, `RowHeight`,
`LayoutSize`, `Shadow` (`.boxShadow(_:in:)`, each layer drawn apart as the browser
draws it), `Motion`, `ZLayer`. `Btn` and `.buttonStyle(.btn(variant, size:))`,
`IconBtn`/`.iconBtn`, `.pill`/`.quietPill`, `MenuTriggerStyle` (the row menus'
three dots, lit by `\.rowIsHovered`), `Badge`, `AgentChip`, `AgentLogo`, `Mark`,
`Dot`, `StatusDot`, `OnlineDot`, `Switch`, `Segmented`, `Spinner`,
`DeviceGroupHeader`, `ArchiveGroupHeader`, `.surface()`, `.card()`, `GroupTitle`,
`FieldLabel`, `Hint`, `FormError`, `EmptyState`, `PageHead`, `FieldText` with
`.fieldChrome(focused:)`, `WebField`, `SearchField`, `MenuList`, `MenuItemRow`,
`ThinScrollView`/`.scrollThin()`, `SpaceBetween`, `Icon(.name, size:)` for every
lucide icon the web imports, named as it imports them. `swift run RCMacPreview
--demo --scenario gallery,gallery-tokens,gallery-menu` draws all of them.

**Overlays** are drawn in the window, never as system popovers, sheets, alerts or
menus. `.modal(isPresented:title:width:showClose:content:footer:)`,
`.drawer(isPresented:title:subtitle:content:footer:)`,
`.confirmDialog(isPresented:title:body:confirmLabel:danger:busy:onConfirm:)`,
`Popover(align:side:chevron:triggerStyle:ariaLabel:initiallyOpen:label:content:)`
(or with `isOpen:`), `SelectMenu` (the web's `Menu`), and
`.anchoredPanel(isPresented:align:side:content:)` for a panel something other
than a click opens. They close on Escape (the newest first), a press outside —
the backdrop for a modal or the drawer, anywhere but the panel and its trigger
for a popover — or their own control. A dialog moves focus as the web's does, to
the first focusable thing it holds: its first field, or no field when a close
button or a button comes first (the drawer, a modal with a close button, a
confirmation). A modal blurs everything under it, the drawer included; popovers
are placed by `PopoverPlacement`, the web's rule. An overlay's content is drawn
at the root and reads the root's environment, so pass in what a feature keeps in
its own; and a list whose rows open overlays is a `ScrollView`, never a `List`,
whose rows AppKit hosts apart.

**Build an overlay's content from what the asking view's body reads.** The content
comes from the closures that body last handed over, and a `Binding` read inside
them gives the value it held when the body last ran — SwiftUI refreshes a binding
only where it is a view's `@Binding`. So pass the form a dialog edits as a value
the body reads (`changePasswordModal(changingPassword, isPresented: …)`), or hand
the binding on to a child view that declares it `@Binding`; never unwrap
`form.wrappedValue` in the closure, or a modal opened by setting an optional the
body never reads opens empty. The order overlays are chained in changes nothing.
`swift run RCMacPreview --demo --scenario gallery-order-modal,gallery-order-modal-binding`
draws both ways.

**Words.** `S.<group>.<key>` in the current interface language, observed, so a
change redraws every open screen; nothing may read a string at static-init time.
`S.mac` holds the Mac's own words. A feature's Mac-only words go in a strings
file of its own built the same way: one struct, an English and a Chinese table
made with its memberwise initialiser, an accessor on `S`. The group files are
generated from the web's tables and carry their doc comments; when the web adds a
word, regenerate rather than edit.

**Previews.** `\.previewStage` is the scenario's stage and nil in the app: a view
reads it to show, for a render, a state that takes a click.

## The renderer

```
swift run RCMacPreview [--demo | --gateway <url> --username <u> --password <p>]
                       (--scenario <name>[,<name>…] | --all | --list)
                       [--width W --height H] [--scale 1|2] [--language en|zh-Hans] --out <dir>
```

Each scenario gets a fresh ephemeral `MacAppModel` and the real `RootView` in an
offscreen window of the app's own shape, signed in through RCCore (`--gateway`
against the web's mock gateway, whose `admin` / `dev` works; `--demo` on the
offline demo), taken to its route, prepared, left to settle, and written as
`<out>/<name>.png` (1280 × 860 at 2× unless it says otherwise). A scenario is
`PreviewScenario(name:route:width:height:stage:account:language:settle:setup:prepare:content:)`:
`account` is `.signedIn`, `.signedOut` (the form) or `.updateRequired`; `setup`
runs before the window exists and `prepare` after it shows the route; `content`
draws one view where the route would be. The registry is
`Scenarios/FoundationScenarios.swift` plus one file per feature.

The capture renders the window's layer tree through `CARenderer` into a Metal
texture — the compositor the window server runs — because `NSView.cacheDisplay`
and `CALayer.render(in:)` go through Core Graphics, which draws layer shadows at
half their spread and upside down and skips every filter. Checked against Chrome,
the shadows' falloff, text, SF Mono, radii, hairlines, blurs, scroll content, text
fields and overlays come out as a live window draws them. What it cannot show:
the traffic lights (the window's frame draws them, out of reach; their room is
kept), the pointer's hover states, and a text field's blinking caret. Text layers
are drawn at the backing scale of the screen the offscreen window counts as on, so
on a 1× display a `--scale 2` render is upsampled. A machine without Metal falls
back to Core Graphics, with the differences above.

Comparing with the web: run the web and its mock on ports of your own (the
round's plan names them), take the web's screenshot with playwright-core at the
same size and scale, and render the same screen with `--gateway`. Chrome renders
the web's `ui-monospace` stack in Menlo, which it falls back to because it does not
know `ui-monospace`; Safari and this app draw SF Mono, so mono text is wider in a
Chrome screenshot than in either.

## Icon

`App/Assets.xcassets/AppIcon.appiconset` holds `web/public/icon.svg` — the same
drawing as the iPhone's `AppIcon` — on the macOS icon grid: the 824-point squircle
inset 100 points in a 1024 canvas, with the template's shadow, each size drawn
from the vectors rather than scaled down from the largest. Only the PNGs are kept:
to change the icon, change `icon.svg` and draw every size again on the same grid.

## Continuous integration

`.github/workflows/macos-check.yml` runs on every push to `master` and every pull
request that touches `macos/`, `ios/Sources/RCCore/` or `protocol/` (branches
only, as the iOS workflow does). It pins `macos-26` with Xcode 26.6 and runs
`scripts/ci-check-macos.sh`: `swift build`, `swift test`, every preview scenario on
the demo, `xcodegen generate` and the unsigned app build, uploading
`macos/build/CI/` — logs, the `.xcresult` and the previews — as an artifact.
