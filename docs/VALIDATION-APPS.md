# App validation — web and iOS against a real gateway

End-to-end validation of the two client apps on 2026-09-10: the web UI in a real Chrome, and the
iOS app in an iPhone 17 simulator, both driving a gateway run from source, a device daemon enrolled
on this Mac, and the real `claude` and `codex` CLIs.

`docs/VALIDATION.md` covers the gateway and the device daemon and is the companion to this file.
Nothing here re-tests the backend for its own sake; the backend is the fixture the apps run against.

Section 3 is a later pass, added when amendment A10 landed. It was driven against the web mock
gateway and the iOS demo rather than a live device, and says so; the live proof for A10 is section 6
of `docs/VALIDATION.md`.

## Environment

| Component | Version |
| --- | --- |
| macOS | 27.0, arm64 |
| Chrome | system Google Chrome, driven by `playwright-core` 1.63 |
| Xcode | 27.0 (27A5252f) at `/Applications/Xcode-beta.app`, iOS 27 simulator |
| Simulator | iPhone 17, `32BBA636-AC71-4804-84E2-ED98992C86B6` |
| Node | 26.5.0 |
| uv | 0.9.21, Python 3.12 |
| Claude Code CLI | **2.1.267** (`~/.local/bin/claude`) |
| Codex CLI | 0.153.4 (`/opt/homebrew/bin/codex`) |

The gateway ran on `127.0.0.1:8793` (8787 and 8791 were taken by a concurrent run) with
`PUBLIC_ORIGIN=http://127.0.0.1:8793` and a scratch `DATA_DIR`. It served the production build of
`web/dist`, so every browser result below is the shipped bundle, not the dev server, and not the
bundled mock.

```sh
cd web && npm ci && npm run build
cd ../gateway && uv sync
RC_HOST=127.0.0.1 RC_PORT=8793 PUBLIC_ORIGIN=http://127.0.0.1:8793 \
  RC_PASSWORD=… DATA_DIR=<scratch> uv run rc-gateway

# a bearer token, then a pairing code
curl -s -X POST http://127.0.0.1:8793/api/login -H 'content-type: application/json' -d '{"password":"…"}'
curl -s -X POST http://127.0.0.1:8793/api/devices/pairing -H "Authorization: Bearer $TOKEN"

cd ../client && uv sync
RC_CLIENT_HOME=<scratch> uv run rc-client enroll --gateway http://127.0.0.1:8793 \
  --pair RC-XXXX-XXXX --name integrate-apps-mac
RC_CLIENT_HOME=<scratch> uv run rc-client run
```

The daemon reported both agents. `rc-client service install` was never run, so this machine gained
no launchd agent. Screenshots named below are run artefacts under the session scratch directory
`…/scratchpad/integrate-apps/shots/`.

## 1. Web, in Chrome, against the real gateway

Every step below is a real browser interaction against the built app. Console output was captured
throughout; the only browser-logged errors were 401s on the pre-login `GET /api/session` probe,
which Chrome logs itself and which is the correct response to an unauthenticated probe.

| Flow | Result | Evidence |
| --- | --- | --- |
| Login | Password sign-in reaches Sessions | `web-01-login.png` |
| Devices | `integrate-apps-mac` online, `Claude Code 2.1.267`, `Codex 0.153.4`, hostname/platform/arch from the daemon | `web-02-devices.png` |
| Add device | Code `RC-XXXX-XXXX`, the install one-liner carrying this gateway's origin, and a countdown that ticks (10:00 → 9:58 over two seconds) | `web-03-add-device.png` |
| Pairing, live | A second `rc-client` in another `RC_CLIENT_HOME` redeemed the code from the open dialog: "Device handshake" then "Detect installed agents" completed, the agent chip read `claude · codex`, Continue enabled, the header read "… connected" | `web-04-add-device-connected.png` |
| Device list, live | The new device appeared without a reload, and revoking it removed the row live | `web-05-devices-two.png` |
| New session drawer | Device picker, agent segmented control (`claude 2.1.267 · default`), directory picker browsed into a scratch git repo, git row `main` / `clean · 0 ahead`, worktree toggle offered because Claude advertises `worktree` | `web-07-new-session.png` |
| Start session | `session.create` with a first message; the chat opened on the returned id and streamed the answer `OK` in about 5 s | `web-08-chat-streaming.png` |
| Usage chip | Rendered live as elapsed time during the turn and as `26.1k` after it, then the composer returned to "Message the agent…" with no Stop button | `web-09-chat-idle.png` |
| Approval card | "Create a file called hello.txt containing hi" raised a real `approval` (`tool: Write`, `tool_kind: write`, options Allow \[primary] / Allow for this session / Deny \[danger]) and the status line read "Needs your approval" | `web-36-approval-pending.png` |
| Approval, Allow | The card was answered from the browser: `session.approve` with the `primary` option, the card resolved to "Allow · decided by remote", `hello.txt` was written with `hi`, and the turn completed | `web-37-approval-allowed.png` |
| Approval, Deny | "Create a file called denied.txt containing no" raised a second approval; the `danger` option resolved it to "Deny · decided by remote", `denied.txt` was never created, and the turn completed | `web-38-approval-denied.png` |
| Approval, plan mode | An `ExitPlanMode` approval was answered the same way | `web-24-approval-pending.png`, `web-25-approval-allowed.png` |
| Question card | A real `AskUserQuestion` with two questions, six options and two free-text fields; Submit stayed disabled until both were answered, then `session.answer` resolved the card and the turn continued | `web-22-question.png`, `web-23-question-resolved.png` |
| Stop | "Count from 1 to 300, one number per line" then Stop → `turn_completed` `stop_reason: "interrupted"` → the "Turn interrupted" notice row | `web-27-streaming.png` (mid-turn), `web-13-after-reload.png` (the notice) |
| Reload (A8) | After a reload the timeline was rebuilt from `session.history` with the same rows in the same order, user messages included | `web-13-after-reload.png` |
| Truncated output | A `Bash` run of `seq 1 5000` came back truncated at 16 382 bytes; expanding the row offered "Open full output", and `session.block` replaced it with the full block (`Show more (3497 lines)` → `Show more (5000 lines)`) | `web-26-full-output.png` |
| Codex session | Created from the drawer (`codex 0.153.4 · gpt-6-astra`), streamed `OK`, usage chip `23.0k`, and one Stop on a long task produced `interrupted` | `web-14`…`web-17`, `web-35-codex-chat.png` |
| Terminal mirroring | A session the daemon discovered from a live CLI rendered read-only: composer disabled, no Stop, status "Controlled by the terminal · a turn is running there", Take over offered | `web-18-terminal-mirrored.png` |
| Sessions list | Search filtered the list; mirrored sessions are labelled `terminal` in the sidebar | `web-19-sessions-search.png` |
| Settings | Account, Notifications, Voice and About render, and About names this gateway's origin | `web-20-settings.png` |
| Device offline | Killing `rc-client run` disabled the composer with "Device is offline"; restarting it re-enabled the composer with no reload | `web-28-device-offline.png`, `web-29-device-back.png` |
| Gateway restart | Killing and restarting the gateway under an open chat: the socket reconnected on its own, the timeline was intact and the composer came back live | `web-30-after-gateway-restart.png` |
| Sign out | Returns to the login screen | — |
| Mobile | At 390 px the sidebar collapses, the chat has a back button, and the page has no horizontal overflow (0 px) | `web-33-mobile-sessions.png`, `web-34-mobile-chat.png` |

Checks after the change in section 4:

```
cd web && npm run typecheck && npm run lint && npm test -- --run && npm run build
→ tsc clean, eslint clean, 12 files / 123 tests passed, built in 1.19 s
```

## 2. iOS, in the simulator, against the same gateway

`ios/UITests/RealGatewaySmokeTests.swift` is new. It skips unless the runner is given a gateway, so
the default `RemoteControlUITests` run stays offline; `xcodebuild` strips the `TEST_RUNNER_` prefix
when it hands the variables to the runner, and the test forwards them to the app under test through
`launchEnvironment`.

```sh
cd ios && xcodegen generate
export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
xcodebuild build-for-testing -project RemoteControl.xcodeproj -scheme RemoteControl \
  -destination "platform=iOS Simulator,id=<udid>" -configuration Debug CODE_SIGNING_ALLOWED=NO

TEST_RUNNER_RC_E2E_GATEWAY=http://127.0.0.1:8793 \
TEST_RUNNER_RC_E2E_PASSWORD=… \
TEST_RUNNER_RC_E2E_SESSION=<claude session id> \
xcodebuild test-without-building -project RemoteControl.xcodeproj -scheme RemoteControl \
  -destination "platform=iOS Simulator,id=<udid>" -parallel-testing-enabled NO \
  -only-testing:RemoteControlUITests/RealGatewaySmokeTests CODE_SIGNING_ALLOWED=NO
```

| Test | What it proves | Result |
| --- | --- | --- |
| `testSignsInAndDrivesARealSession` | Types `http://127.0.0.1:8793` and the password on Login, signs in, finds the session created from the browser, reads the real assistant answer `OK`, sends "Reply with exactly DONE" through the composer and waits for `DONE` | passed, 40.7 s |
| `testDevicesTabListsTheEnrolledMachine` | The Devices tab lists `integrate-apps-mac` with the agents the daemon detected | passed, 17.5 s |
| `testSettingsTabRendersAgainstTheLiveGateway` | Settings renders and names the gateway this app is signed in to | passed, 16.7 s |
| `testRemembersTheGatewayAcrossALaunch` | Signing in, backgrounding, terminating and relaunching keeps the gateway address | passed, 40.5 s |

Screenshots: `shots/ios/ios-01-sessions.png`, `ios-02-chat.png`, `ios-03-answered.png`,
`ios-04-devices.png`, `ios-05-settings.png`, `ios-06-relaunch.png`.

The message the app sent is on the device, not only on the screen:

```json
{"seq": 80, "kind": "user_message", "text": "Reply with exactly DONE", "source": "remote"}
{"seq": 85, "kind": "assistant_text", "text": "DONE", "done": true, "first_seq": 83}
```

Other iOS checks, all green after the change in section 4:

| Command | Result |
| --- | --- |
| `swift run RCVerify` | PASS, 677 checks over 125 fixtures |
| `swift run RCUIVerify` | PASS, 50 UI checks |
| `swift test` | 49 tests in 7 suites passed |
| `xcodebuild … -destination 'generic/platform=iOS Simulator' build` | BUILD SUCCEEDED |
| `xcodebuild test … -only-testing:RemoteControlUITests` with no gateway env | 3 demo tests passed, 4 real-gateway tests skipped |

## 3. Attached terminal sessions (A10) in the apps

Amendment A10 landed after the run above. This section records what each app does with
`control: "shared"`, `user_message.delivery` and the `attach` hints.

### Web

Driven against the bundled mock gateway (`npm run dev:mock`) in headless Chrome, not against a live
device: the mock was extended for this so both new shapes exist side by side — an attached session
with `control: "shared"` and a `terminal` session on a device whose agent reports `attach: "channel"`
with `attach_ready: false`.

What the app does:

- A `shared` session is treated as `remote` for the composer, the approval cards, the queue and the
  user-message rows. It gets a quiet "Attached to the terminal session" bar instead of the takeover
  bar, and the status label reads **terminal · attached**.
- "Take over" is never offered on a `shared` session. Stop is hidden unless the agent lists the
  `interrupt` capability **and** reports `shared_interrupt: true`, which Claude does not.
- The model, permission-mode and effort pickers and the attachment button are disabled, each with a
  tooltip saying to change it in the terminal. The disabled controls set `pointer-events: none`, so a
  disabled picker cannot swallow the hover that shows its own tooltip.
- A held message carries a "waiting for the terminal" chip and appears in the queue; an absorbed one
  reads "will be re-sent". The chip clears when the same `block_id` comes back as `delivered`, and no
  second bubble appears.
- A relayed approval offers exactly Allow and Deny, with no session-scoped middle option.
- A `terminal` session whose agent advertises an attach method shows the hint that matches
  `attach_ready`: install the shim when it is false, restart this session when it is true.

| Check | Result |
| --- | --- |
| `npm run typecheck && npm run lint && npm test -- --run && npm run build` | tsc clean, eslint clean, 155 vitest tests passed |
| Headless-Chrome pass over the mock | 26 assertions, all passed |

Screenshots, under the run scratch directory `…/scratchpad/web-attach/shots/`:

| File | What it shows |
| --- | --- |
| `a10-01-shared-idle.png` | A shared session idle: live composer, attached bar, no Take over, no Stop |
| `a10-02-shared-approval.png` | A relayed approval card with only Allow and Deny |
| `a10-03-pending-chip.png` | A held message with the "waiting for the terminal" chip and its queue row |
| `a10-04-delivered.png` | The same block after injection, chip gone, no duplicate bubble |
| `a10-05-terminal-restart-hint.png` | A `terminal` session with `attach_ready: true`: restart it to attach |
| `a10-06-terminal-shim-hint.png` | A `terminal` session with `attach_ready: false`: install the shim |
| `a10-07-shared-390.png` | The shared session at 390 px |
| `a10-08-after-takeover.png` | A `meta` control transition unlocking the composer live |

### iOS

Driven in demo mode on an iPhone 17 simulator running iOS 27.0, not against a live attached session:
the in-memory demo gateway carries a shared Claude session on `mac-studio-office` and a `terminal`
session on `macbook-air` whose Claude reports `attach_ready: false`, so both new shapes exist side
by side as they do on the web.

The models gained `SessionControl.shared`, `AgentAttach`, `MessageDelivery`, the three `AgentInfo`
fields `attach`, `attach_ready` and `shared_interrupt`, and `delivery` on `user_message`.

What the app does:

- A `shared` session enables the composer exactly as `remote` does. "Take over" never appears on it,
  and on a `terminal` session it appears only when the agent lists the `takeover` capability.
- Stop is hidden unless the agent reports `shared_interrupt`.
- The model, permission-mode and effort controls and attachments are disabled, each carrying the
  reason rather than only greying out.
- A `terminal` session whose agent advertises an attach method shows the hint that matches
  `attach_ready`.
- A held message carries the "waiting for the terminal" chip and an absorbed one "will be re-sent",
  and the chip clears when the same `block_id` returns as delivered.
- A `question` on a shared session is mirrored read-only, "Answer this in the terminal", because
  `session.answer` is refused there.

Two latent accessibility defects were found and fixed on the way: a container
`accessibilityIdentifier` was hiding the buttons inside a card, and the status line was masking the
attach hint.

| Command | Result |
| --- | --- |
| `swift run RCVerify` | PASS, 766 checks against the real `protocol/fixtures`, `objects/` included: 466 protocol, 104 timeline, 25 transport, 21 socket, 13 stt, 16 persistence, 29 markdown, 92 stores |
| `swift test` | 66 tests in 8 suites passed |
| `swift run RCUIVerify` | PASS, 61 UI checks |
| `xcodegen generate` then `xcodebuild … -destination 'generic/platform=iOS Simulator' build` | BUILD SUCCEEDED |
| UI tests on the iPhone 17 simulator | 9 executed, 0 failures, 4 real-gateway tests skipped |

The two new UI tests are `testSharedSessionDeliversAndApproves` and
`testTerminalSessionExplainsHowToAttach`. All of the above ran with
`DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer`.

Screenshots, under the run scratch directory `…/scratchpad/ios-attach/screens/`:

| File | What it shows |
| --- | --- |
| `06-shared-idle.png` | A shared session idle: live composer, no Take over, no Stop |
| `07-shared-pending.png` | A held message with the "waiting for the terminal" chip |
| `08-shared-delivered.png` | The same block after injection, chip gone |
| `09-shared-approval.png` | A relayed approval card with only Allow and Deny |
| `10-shared-answered.png` | The card after it was answered from the phone |
| `11-attach-hint.png` | A `terminal` session with `attach_ready: false`: install the shim |

Not verified on iOS: nothing was driven against a real attached session, only the demo; and the
delivery chips were asserted by accessibility label rather than by pixels.

## 4. Defects found in the apps, and fixed

### iOS — signing in was forgotten again as soon as the app was backgrounded

`App/RemoteControlApp.swift`, `Sources/RCUI/Screens/RootView.swift`.

`WindowGroup { RootView() }` relied on `RootView.init(model: AppModel = AppModel())`. A default
argument is evaluated at every call, and the scene body runs again on every scene update, so a new
`AppModel` was built each time. `AppModel.init` applies the launch arguments, so every one of those
throwaway models re-ran `--reset-state` and wiped `gateway.origin` and `gateway.username` — after
the sign-in had just stored them. It also built a fresh `PushController` each time.

Observed: sign in with `--reset-state`, press Home, terminate, relaunch → the login screen with an
empty Gateway field, and `Library/Preferences/com.junbingao.remotecontrol.plist` holding
`gateway.origin => ""`. The same run without `--reset-state` left `gateway.origin =>
"http://127.0.0.1:8793"`, which is what identified the cause.

Fix: the App owns the model in `@State` and passes it down, and `RootView.init` no longer takes a
default, so no code path can build a second one implicitly. Regression test:
`testRemembersTheGatewayAcrossALaunch`, which backgrounds the app on purpose because that is the
scene update that used to trigger the wipe.

The stronger promise — a relaunch needs no password because the keychain token is restored — cannot
be asserted from this build: `CODE_SIGNING_ALLOWED=NO` produces an ad-hoc, linker-signed app with no
`application-identifier` entitlement, so `SecItemAdd` cannot store the token. The test accepts
either outcome and requires the address. Nothing here says the keychain path is broken on a signed
build; it says this harness cannot exercise it.

### Web — a protected page mounted for one pass while signed out

`src/App.tsx`, `tests/App.test.tsx`.

Opening any protected path while signed out rendered that page once and redirected from an effect.
`SessionsPage` fires `session.load()` on mount, so a cold load of the login screen always issued
`GET /api/sessions` with no credential and logged a 401 in the console. Signed out, the router now
renders the login screen for every path, so no protected page mounts and no authenticated request
goes out. Test: "never issues an authenticated request while signed out", which fails on the old
router with `['/api/session', '/api/sessions']`.

## 5. Defects found in other components, reported and since fixed

`gateway/`, `client/` and `protocol/` belong to other owners, so these were reproduced and reported
rather than fixed here. The client owner fixed all four; each entry below records the original
finding and the re-verification against the fixed tree on a fresh gateway, a fresh `DATA_DIR` and a
newly enrolled device.

### C1 — every Claude tool approval expired ~10 ms after it was raised (client) — **fixed**

The device raises an `approval`, sets `needs_approval`, and the CLI cancels the permission request
about ten milliseconds later. `_wait_for` reads the cancellation as "no answer", emits `status:
"expired"` and returns `PermissionResultDeny`, and the tool then runs anyway. A remote user can
never answer, and the card flashes and dies.

Reproduce, with the daemon running:

```
session.create {agent: "claude", cwd: <a git repo>, permission_mode: "default"}
session.send  "Create a file called hello.txt containing hi"
```

```
{"seq": 15, "ts": 1788988064892, "kind": "approval", "status": "pending",  "tool": "Write"}
{"seq": 16, "ts": 1788988064893, "kind": "status",   "state": "needs_approval"}
{"seq": 17, "ts": 1788988064912, "kind": "approval", "status": "expired",  "tool": "Write"}
{"seq": 18, "ts": 1788988064912, "kind": "status",   "state": "running"}
{"seq": 19, "ts": 1788988064933, "kind": "tool_call", "tool": "Write", "status": "succeeded"}
```

Twenty milliseconds, and `hello.txt` exists. It reproduces with `Bash` (`rm -f scratch-target.txt`)
and with a project-level `.claude/settings.json` pinning `permissions.defaultMode` to `default`, so
it is not one tool and not one settings file. In the same session an `ExitPlanMode` approval waited
for the remote user and resolved correctly, which is how section 1 could exercise the Allow path at
all — so the mechanism works and something resolves *tool* permissions out from under it.

Cause, confirmed by the client owner: the daemon let the CLI load this machine's user-level
`~/.claude/settings.json`, which carries `permissions.defaultMode: "auto"` and third-party
permission hooks, so the machine answered on behalf of a user who was not looking. `setting_sources`
now defaults to `["project", "local"]`.

Re-verified in the browser against the fixed tree, in one Claude session with the composer showing
"Ask before edits", on a scratch repository holding nothing but a README and a git history:

| Step | Result |
| --- | --- |
| "Create a file called hello.txt containing hi" | `approval` for `Write hello.txt`, options `Allow [primary]`, `Allow for this session`, `Deny [danger]`, status line "Needs your approval", **still pending six seconds later** |
| Allow | card resolved to "Allow · decided by remote", `hello.txt` written containing `hi`, turn completed |
| "Create a file called denied.txt containing no" → Deny | card resolved to "Deny · decided by remote", `denied.txt` never created, turn completed |

The device's own record agrees:

```json
{"seq": 9,  "kind": "approval", "status": "resolved", "decision": {"option_id": "allow", "by": "remote"}, "tool": "Write", "title": "hello.txt"}
{"seq": 25, "kind": "approval", "status": "resolved", "decision": {"option_id": "deny",  "by": "remote"}, "tool": "Write", "title": "denied.txt"}
```

### C2 — restarting the daemon duplicated a remote session's whole transcript (client) — **fixed**

After `rc-client run` was restarted, a session that had been created remotely was re-imported from
its Claude transcript. The re-imported events got **new** `block_id`s and `source: "terminal"`, so
they did not replace the originals: every user message and answer appeared twice, the second labelled
"sent from the terminal", and a `resumed this session from its transcript` notice sits between them.
Both apps show it because both apply the contract correctly.

Reproduce: run one remote turn, `kill` the daemon, start it again, then read `session.history`.

```
{"seq": 68, "kind": "user_message", "text": "Count from 1 to 300, one number per line", "source": "terminal", "first_seq": 68}
{"seq": 71, "kind": "user_message", "text": "Reply with exactly OK",                    "source": "terminal", "first_seq": 71}
```

Counted in the browser afterwards: 14 user rows, five of them duplicates.

Fix: the mirror skips sessions already persisted with `origin: "remote"`. Re-verified by killing and
restarting `rc-client run` under a remote session with three user messages; `session.history`
returned three afterwards, all still `source: "remote"`, and no `resumed this session from its
transcript` notice appeared.

### C3 — an interrupted turn zeroed the session's usage (client) — **fixed**

`turn_completed` for an interrupted turn carries `usage` with `input_tokens`, `output_tokens` and
`total_tokens` all `0` while `cost_usd` and `context_used` keep their values. The device writes that
over `Session.usage`, so a Stop makes the token count disappear from both apps' usage chip until the
next completed turn. Seen at seq 38: `{"input_tokens": 0, "output_tokens": 0, "total_tokens": 0,
"cost_usd": 0.806402, "context_used": 25889}`, immediately after a completed turn had reported
`total_tokens: 24952`.

Fix: a zero-token usage no longer overwrites what the session already knows. Re-verified with a Stop
in the browser: the usage chip read `32.6k` before and after, and the interrupted `turn_completed`
carried `total_tokens: 32559`, the same figure the previous completed turn reported.

### C4 — a fresh device published every local transcript it could find (client) — **fixed**

Enrolling this Mac and starting the daemon published **101** sessions within seconds, rising to 114
during the run: every Claude and Codex transcript on the machine going back weeks, with titles taken
verbatim from the owner's past prompts, including unrelated private work. Both apps list them,
because the device sends them as sessions.

Fix: the initial import is bounded, 50 sessions per agent and 14 days by default, both configurable
in `config.toml`. Re-verified on a fresh enrolment: **95** sessions, 50 Claude and 45 Codex, against
101 unbounded before. The bound is per agent, so the worst case on a busy machine is 50 times the
number of installed agents rather than the whole history.

## 6. Observations, not defects

- `strings.status.idle`, `strings.chat.outputTruncated` and `strings.chat.inputTruncated` are in the
  web catalog but never rendered: idle shows no status line by design, and a truncated block shows
  "Open full output" instead of a label. Dead strings, no user impact.
- The composer's Effort pill renders with no value for Claude sessions, because the device leaves
  `Session.effort` null until it is set explicitly.
- Signing out once failed to reach the login screen inside 15 s, in a run that had just restarted
  the device daemon and the gateway. Two targeted attempts to reproduce it, including one
  immediately after a gateway restart, both signed out instantly. Recorded, not explained.
- `ENGINE-FACTS.md` records Claude Code 2.1.266; this machine now runs 2.1.267.

## 7. Not verified

- **`allow_session`, the middle approval option.** Allow and Deny were both driven end to end; the
  session-scoped grant was never chosen, so nothing checked that a second write goes through
  unattended afterwards.
- **Approval for a command rather than an edit.** Both approvals answered here were `Write`.
- **Codex approvals.** As in `docs/VALIDATION.md`: this machine's Codex sandbox never escalates, and
  `permission_mode: "untrusted"` with `echo hi` still produced no approval.
- **Attachments** on either app, **Web Push** and **APNs** delivery, and **speech to text** on either
  app: the gateway ran with `STT_PROVIDER=none` and no `WEB_PUSH_CONTACT`, so the mic is hidden on
  web by design and nothing exercised `/ws/stt`.
- **iOS keychain restore**, for the signing reason in section 4.
- **`session.takeover` from an app.** The read-only state was verified; taking over needs an idle
  terminal session, which this run never held.
- **iOS on a physical device**, dark mode, VoiceOver, and any iOS flow beyond the four tests above:
  new session, add device, directory picker, voice and push were exercised only by the offline demo
  suite the iOS owner wrote.
- **`session.delete`** has no entry point in either app.
- **Attached sessions against a real device.** Both apps were driven against fixtures for A10: the
  web mock gateway and the iOS demo. The live proof that the two ends agree is on the device side,
  in section 6 of `docs/VALIDATION.md`, where a scripted app drove a real attached CLI. No browser
  and no phone has yet typed into one.

## Smoke procedure

About ten minutes, four short agent turns.

1. Build and start the stack as in **Environment**, then enrol this machine and run the daemon.
2. Open the gateway origin in a browser, sign in, and check Devices lists the machine online with
   both agents and their versions.
3. New session → Claude → browse to a git repository → first message "Reply with exactly OK" →
   Start. The answer streams, the usage chip appears, the composer returns to "Message the agent…".
4. Send "Count from 1 to 300, one number per line", press Stop, and confirm the "Turn interrupted"
   row. Reload the page and confirm the timeline comes back in the same order.
5. Send "Create a file called hello.txt containing hi". An approval card appears and stays. Allow it,
   and check the file exists. Send the same prompt for `denied.txt` and Deny it: the file must not
   appear. For the question card, send "Plan how to add a LICENSE file. Do not write anything yet."
   with `permission_mode: plan` and answer it.
6. Repeat step 3 for Codex and confirm the usage chip has no cost.
7. `cd ios && xcodegen generate`, build for the simulator, then run
   `-only-testing:RemoteControlUITests/RealGatewaySmokeTests` with `TEST_RUNNER_RC_E2E_GATEWAY`,
   `TEST_RUNNER_RC_E2E_PASSWORD` and `TEST_RUNNER_RC_E2E_SESSION` set. Four tests, no skips.
8. Attached sessions (A10), against fixtures rather than a device: `cd web && npm run dev:mock`,
   open the shared session and confirm a live composer, no Take over, no Stop, disabled pickers, and
   a message sent during the terminal's turn showing "waiting for the terminal" and then clearing.
   Open the `terminal` session and confirm the attach hint. On iOS, run the app with `--demo` and
   check the same two sessions.
9. Stop the daemon and the gateway. Delete the scratch `DATA_DIR` and `RC_CLIENT_HOME`.
