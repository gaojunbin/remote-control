# The Windows app

A Compose Multiplatform client that is the Mac app drawn for Windows — and so, like the Mac app, the
web app drawn natively: the same routes, the same words in both languages, the same tokens, rows,
controls, popovers and breakpoints, and the same behaviour (`docs/DESIGN.md` § "The Windows app").
Windows 10 and 11, x64, with its own Java runtime inside the installer. It speaks
`protocol/PROTOCOL.md` through `android/core`, the Kotlin port of the iPhone app's protocol and state
layer that the Android app runs on too, and talks only to the gateway. The Mac app is the reference:
where the two differ, the Windows app is wrong unless DESIGN says otherwise, and where the Mac's
Swift leaves a question open the web answers it.

## Structure

| Path | What it holds |
| --- | --- |
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/` | Gradle 9.8; `android/core` included by path as `:core` and `android/gradle/libs.versions.toml` read as the catalog, never copied |
| `app/src/main/kotlin/…/win/app/` | `WinAppModel` (the core's stores, sign-in, the extension points), `Route`/`Router` (the web's routes and a browser-like history), `RootView`, `LayoutClass` (the web's breakpoints), the key map, `LaunchOptions`, `Main.kt` |
| `…/win/design/` | `tokens.css` as Kotlin, the web's line box, SwiftUI's stacks, every primitive of the Mac's `Design/`, the lucide icons (`icons/`), the in-window overlay layer (`overlay/`) |
| `…/win/strings/` | every group of the Mac's `Strings/`, one file per group, both languages, with `S.win` for Windows' own words |
| `…/win/shared/` | the web helpers two features read, as the Mac's `Shared/` |
| `…/win/layout/`, `login/`, `update/` | the topbar and page shell, the landing rule, sign-in, Update required (A46) |
| `…/win/chat/`, `chat/composer/`, `voice/` | the conversation page, its composer, dictation |
| `…/win/devices/`, `sessions/` | the device and session lists, a device's page, Add device, the New session drawer, the session sidebar |
| `…/win/settings/`, `users/`, `terminal/`, `notifications/` | Settings, the accounts screen, the terminal, Notify me |
| `…/win/platform/` | the token vault (DPAPI), toasts and the notification-area icon, the microphone, JediTerm, the Markdown engine (the Mac's bundle in QuickJS), Windows' settings |
| `app/src/test/` | JUnit 5, including the Mac's own test cases for everything ported |
| `app/packaging/` | `RemoteControl.ico` |
| `preview/` | the renderer: the Mac's scenarios drawn offscreen and written as PNG |
| `scripts/ci-launch.ps1` | what CI runs to open the built app on Windows and photograph it |

Package `com.junbingao.remotecontrol.win`, display name "Remote Control", installed per user.

## Download

Every version tag's GitHub release carries the installer as `Remote-Control-<version>.msi` with its
SHA-256 beside it, built from the tagged commit on a Windows runner. Run it; the app installs for the
current user under the Start menu's "Remote Control" and a later installer upgrades it in place. It is
not code-signed, so SmartScreen says "Windows protected your PC" the first time: choose **More info**
→ **Run anyway**.

## Building, checking and running

```sh
cd win
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home   # JDK 17+
./gradlew --no-daemon :app:test
./gradlew --no-daemon :preview:run --args="--demo --all --out /tmp/rc-win-previews"
./gradlew --no-daemon :preview:run --args="--demo --all --language zh-Hans --out /tmp/rc-win-previews-zh"
./gradlew --no-daemon :app:run --args="--demo --ephemeral"            # a window, on a desktop session
./gradlew --no-daemon :app:packageMsi                                 # the installer, on Windows only
```

The tests and every preview scenario are the component's gate before a commit; CI
(`.github/workflows/windows-check.yml`) runs them on Windows as well, renders every scenario there
in both languages, builds the installer, starts the built app on the runner's desktop and keeps a
picture of it. The app develops and renders on a Mac as on Windows: on a Mac the renderer sets the
Mac's system faces, so its pictures compare with the Mac renderer's pixel for pixel. One test needs a
gateway and runs only when `RC_MOCK_GATEWAY` names one, as the Mac's does: it dictates through the
composer's own socket. `:app:run` needs the logged-in desktop; from a background shell build the app
image with `:app:createDistributable` and open that instead.

## Running against a gateway

The login page is the Mac's: the gateway's address above the username. The web's mock gateway works
as a gateway: `cd web && npm run mock`, then sign in at `http://127.0.0.1:8787` as `admin` / `dev`.
The token is sealed with Windows' data protection (DPAPI) for the signed-in Windows user in
`%LOCALAPPDATA%\Remote Control\secrets`; the preferences, drafts and the transcript cache live beside
it.

## Launch arguments

The Mac's set: `--demo`, `--demo-account` (with `--registration-open`), `--demo-update-required`,
`--ephemeral` (nothing of the person's read or written; every automated run uses it),
`--reset-state`, `--language=en|zh-Hans`.

## The window

One window with Windows' own title bar, so snapping, resizing and the caption buttons are Windows';
below it the Mac's window content without the traffic-light inset. 1280 × 860 at first — or the
screen's work area less a margin where that is smaller, so the title bar is never off screen — never
narrower than 480, always light, and the web's breakpoints on its width. Closing it leaves the app
running and connected in the notification area, so notifications keep arriving; the icon there opens
it again and offers Quit. Keys: Ctrl+1, Ctrl+2, Ctrl+3 for Devices, Sessions and Settings, Ctrl+,
for Settings, Ctrl+N for New session, Alt+Left and Alt+Right and the mouse's back and forward buttons
for the history, Escape for the newest overlay.

## How it follows the Mac

Every Swift file of `macos/Sources/RCMac` has its Kotlin twin under the same name, and every
`macos/Tests/RCMacTests` case its JUnit twin; the renderer draws every scenario of `RCMacPreview`
under the same name and size. On a Mac, the 203 scenarios compare with the Mac renderer's own
pictures at a median mean difference of about half a level of 255 (the numbers of each round are in
`docs/VALIDATION.md`). What it takes is in `win/README.md` § "Matching the Mac's pictures": SF
as CoreText sets it (optical sizes, CoreText's weights and tracking), SwiftUI's fractional stack
layout, the browser's line box with each line on its primary face's baseline, and SwiftUI's
two-line break rule. On Windows the same layout is set in Segoe UI Variable, with Microsoft YaHei UI
for Chinese.

## Composer, dictation and the conversation

As the Mac's (`docs/MACOS.md`): Enter sends, Shift+Enter breaks the line, and Enter that confirms an
input method's composition never sends; attachments come from the system file dialog, a paste or a
drop; dictation is the gateway's, streamed at 16 kHz in 120 ms frames, with polish. The Markdown is
the web's own pipeline — the Mac's `markdown.bundle.js`, run in QuickJS — so a message parses and
highlights exactly as on the web and the Mac. A tool's JSON input keeps the device's key order, as
the web shows it.

## Notify me and the terminal

Notify me is this PC's switch and posts Windows notifications from the running app at the moments the
gateway pushes for, with the push's words; a click opens the conversation. The terminal is JediTerm
with the Mac's theme and font, and Windows Terminal's keys: Ctrl+Shift+C, or Ctrl+C with a selection,
copies; Ctrl+Shift+V pastes; a right click copies a selection or pastes; Ctrl+L, Ctrl+F and
Ctrl+Up/Down reach the shell.

## The red dot and the taskbar badge (A47)

As the Mac's, and through it the web's (`docs/DESIGN.md` § "A red dot for a session that stopped and
waits for you"): a session whose `unseen` is set carries an 8 px dot of the Danger red centred in its
row's leading gutter and on the title's line — the Sessions page's 20 px padding (16 px below 640),
and the chat sidebar's, whose rows all keep a 20 px leading padding so the dot moves nothing — and the
row's accessible value says "not yet opened". The core's `SeenReporter` sends `session.seen` for the
conversation open in the window that has focus (`conversationInFront`): when it opens there, when
the window gains focus, and when a mark arrives for it; that conversation's own row draws no dot and
is not counted while the request travels, as on the web.

The badge is the number of unarchived sessions with a dot, drawn as a red disc with white figures
("99+" past two): over the taskbar button through `Taskbar.setWindowIconBadge` (Windows' overlay
icon), and over the notification-area icon while the window is closed, when there is no taskbar
button to carry it. Nothing at zero, and nothing once the account signs out. The renderer draws its
scene as the window in front (`FrontmostWindow`, as the Mac renderer's window is), so a conversation
it draws counts as read; `sessions-unseen` and `chat-sidebar-unseen` are the dot's scenarios.

## Update required (A46)

Below `apps.windows.minimum_version` the app shows the Mac's blocking screen — its version, the
gateway's minimum, a button to `update_url` when there is one, and Sign out — and nothing else.

## Not verified

Everything that needs Windows itself and is not in CI's run: the DPAPI round trip on a real account,
a toast and a click on one, the notification-area icon and its badge, the taskbar button's badge,
the microphone, JediTerm's keys in a real window, an upgrade from one installer to the next.
