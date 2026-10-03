# The Android app

A Jetpack Compose client that is the iPhone app drawn for Android: the same three tabs, the same
screens, sheets, rows and controls, the same words in both languages, the same tokens and the same
behaviour (`docs/DESIGN.md` § "The Android app"). Android 10 (API 29) and later. It speaks
`protocol/PROTOCOL.md` through `android/core`, the Kotlin port of the iPhone app's protocol and
state layer (`ios/Sources/RCCore`), which the Windows app runs on too, and talks only to the
gateway. The iPhone app is the reference: where the two differ, the Android app is wrong unless
DESIGN says otherwise.

## Structure

| Path | What it holds |
| --- | --- |
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/` | Gradle 9.8, AGP 9.4, Kotlin 2.4; the version catalog the Windows build reads too |
| `core/` | the Kotlin core (`com.junbingao.remotecontrol.core`): protocol, transport, persistence, Markdown, every store and the offline demo, one Kotlin file per RCCore Swift file; `core/README.md` |
| `app/src/main/kotlin/…/android/shell/` | RCUI's root: `AppModel`, `RootView`, `LoginView`, `UpdateRequiredView` (A46), the tab shell |
| `…/android/screens/` | the screens, a package per feature: `chat` (the conversation, the composer, voice, the native Markdown views), `devices`, `sessions`, `terminal`, `settings`, `users`, `alerts`, `lock` |
| `…/android/design/`, `system/`, `navigation/`, `icons/`, `strings/` | RCUI's `Design/`; the iPhone's system pieces in its own shapes (bars, lists, switch, segmented control, sheets, alerts, menus, swipe actions); a navigation stack per tab; every SF Symbol as a lucide glyph; `L10n` |
| `…/android/security/`, `persistence/`, `attachments/`, `voice/`, `push/`, `markdown/`, `scanner/`, `terminal/`, `awake/`, `haptics/` | the platform services: the Keystore secret store, biometric lock and privacy shield, photo picker and camera, the speech recogniser and 16 kHz capture, notifications, the Markdown web view, the QR scanner (CameraX and ML Kit), the Termux terminal view, keep-awake, haptics |
| `app/src/main/java/com/termux/` | Termux's terminal emulator and view, vendored under Apache 2.0 |
| `app/src/main/strings/` | Android's words laid over the iPhone's catalog (`overlay.json`), UIKit's words the app draws itself (`system.json`), and the words only Android shows (`android.json`) |
| `app/src/test/` | JUnit and Robolectric: the iPhone's UI tests ported as Compose tests, the harness that drives the demo and draws pictures |

Application id `com.junbingao.remotecontrol`, app name "Remote Control".

**The iPhone's words and Markdown are read, not copied.** The build generates the string table from
`ios/App/Localizable.xcstrings` with `app/src/main/strings/overlay.json` laid over it (the words that
name Apple's places), and serves `ios/Sources/RCUI/Resources/Markdown` (the renderer, KaTeX,
Mermaid) as the app's assets, so the Android app follows the iPhone's words and diagrams as they
change. The words only Android shows — the launcher badge's notification (A47) — are in
`app/src/main/strings/android.json`, laid in beside the catalogue rather than over it, so the
iPhone's catalogue keeps only the iPhone's words: the build fails when one of its keys is also a
catalogue key, as it fails when an overlay key is not one.

## Building, checking and running

```sh
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home   # JDK 17+
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew --no-daemon :app:recordRoborazziDebug        # every picture, into app/build/outputs/roborazzi/
./gradlew --no-daemon :app:installDebug
adb shell am start -n com.junbingao.remotecontrol/.android.MainActivity --esa args --demo,--reset-state
```

That toolchain is the component's gate before a commit; CI (`.github/workflows/android-check.yml`)
runs it on Linux. Everything is checked on the JVM: the core's tests (every RCCoreTests case and
`ios/Verification` check has its JUnit twin), and the app's on Robolectric with the real graphics
stack — the iPhone's UI tests, each ported under its own name with its steps, assertions and
screenshots, and the pictures, drawn at the iPhone 17's size (402 × 874 at 3×) so each lays beside
the iPhone's screenshot of the same test and step. The pictures are evidence, not baselines: none
is kept in the repository. `--demo` and the iPhone's other launch arguments are read from the `args`
extra (`android/README.md` § "Running").

## Running against a gateway

The sign-in form is the iPhone's, with the gateway's address. `https://` for a real gateway; plain
`http://` only for loopback and private hosts, as the iPhone allows. From the emulator the web's mock
gateway is `http://10.0.2.2:8787` (`cd web && npm run mock`, `admin` / `dev`). The token is kept in
the Android Keystore (AES-GCM, the key never leaves it); drafts and the transcript cache stay out of
backups.

## What Android changes

As DESIGN rules: the system back gesture closes a menu, sheet or dialog first and then leaves the
screen; the app draws edge to edge; type is Roboto (and the system's CJK face) at the iPhone's sizes,
tracked to SF's widths; SF Symbols are lucide's glyphs; Face ID is the device's biometric unlock with
the screen lock behind it; the privacy shield keeps the content out of recents; a pairing code is
read with CameraX and the on-device ML Kit scanner; dictation offers both of the iPhone's
recognisers — Android's speech recogniser and the gateway's; notifications are the app's own,
posted from its live connection, because Android has no push channel yet (DESIGN); below
`apps.android.minimum_version` (A46) only the blocking Update required screen shows.

## The red dot and the launcher badge (A47)

As the iPhone's (`docs/DESIGN.md` § "A red dot for a session that stopped and waits for you"): a
session whose `unseen` is set carries an 8 pt dot of the Danger red in its row's 16 pt leading
gutter, centred on the title's line and drawn rather than laid out, so it moves nothing; the row's
spoken label says "not yet opened" after the title. The core's `SeenReporter` sends `session.seen`
for the conversation in front of the person — open on screen, the app resumed and not behind its
lock — when it opens, when the app comes back to it, and when a mark arrives for it; the store asks
only while its copy says `unseen`, once per mark.

Android draws a launcher badge from an app's notifications, so the badge is one quiet notification:
its own channel ("Sessions", low importance — no sound, no heads-up, a badge), `setNumber(count)`,
"1 session is waiting for you" / "%lld sessions are waiting for you", and a tap that opens Sessions.
It is posted while the count of unarchived sessions with a dot is above zero, replaced as it changes,
and removed at zero and on sign-out. Like the iPhone's it counts only while Notify me is on, and it
needs the notification permission Notify me asks for: with no push channel the number changes only
while the app runs, so the app leaves none behind that nothing could keep true. Coming back to the
front puts it up again, for a notification swiped away or a permission granted since. The offline
demo marks a session as the gateway does, and opens with one marked (the approval).

## Not verified

On the JVM only the pure parts of these run; they need a phone: the Keystore round trip, the
biometric prompt, the camera and the scanner, the speech recogniser and the microphone, posting a
notification, how a launcher draws the badge's notification (a number or a dot), the Markdown web
view's JavaScript, haptics. The badge's notification is checked on Robolectric's notification
manager. The emulator run of each round is recorded in `docs/VALIDATION-APPS.md`.
