# App validation — web and iOS against a real gateway

End-to-end validation of the two client apps on 2026-09-10: the web UI in a real Chrome, and the
iOS app in an iPhone 17 simulator, both driving a gateway run from source, a device daemon enrolled
on this Mac, and the real `claude` and `codex` CLIs.

`docs/VALIDATION.md` covers the gateway and the device daemon and is the companion to this file.
Nothing here re-tests the backend for its own sake; the backend is the fixture the apps run against.

Sections 3 and 4 are later passes, added when amendments A10 and A11 landed. Both were driven against
the web mock gateway and the iOS demo rather than a live device, and say so; the live proof for A10 is
section 6 of `docs/VALIDATION.md`, and for A11 its "Codex on the shared daemon" section. Section 9 is
a later pass again, on the session grouping and the visual rules; it changed no protocol behaviour and
ran against the offline demo, so it sits after the closing sections rather than among the live ones.

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

Checks after the change in section 5:

```
cd web && npm run typecheck && npm run lint && npm test -- --run && npm run build
→ tsc clean, eslint clean, 12 files / 123 tests passed, built in 1.19 s
```

### Second UI pass — the session list, the icon and the drawer

2026-09-11, against the bundled mock gateway (`npm run dev:mock`) in the installed Google Chrome
driven by `playwright-core`. Nothing here touches the protocol; it is app-side only, and every rule
it implements reads fields the session object already carries. `docs/DESIGN.md` states the rule both
apps follow and `SCRATCH/plan/SESSION-LIST-2.md` was the frozen brief.

| Check | Result |
| --- | --- |
| Per-device grouping | Two device groups, `mac-studio-office` and `ci-runner-01`, each printed exactly as reported, active rows first |
| Collapse | Clicking a device header folds that device only; the other stays open, and the ids persist in `rc.settings` |
| Per-device Archive | `Archive · 3` under the Mac and `Archive · 2` under the CI box, both shut by default, opening independently |
| Archive contents | Both halves visible: exited sessions read `stopped`, hand-archived rows read `Archived` |
| Search into an Archive | Typing "pairing docs" opened the matching Archive without writing the stored ids |
| Agent filter | `All · Claude Code · Codex`, one line at 1280 px and at 400 px, narrowing both the page and the chat sidebar |
| Agent chips | Every row carries a tinted `Claude Code` or `Codex` chip on its meta line, no border |
| No "Show archived" | The toggle and its preference are gone |
| Device dropdown in the drawer | Opens **above** the drawer and selects; before the fix the portalled panel sat at `z-index: 40` under the overlay's `60`, so it opened behind the drawer and the drawer looked unresponsive |
| Drawer survives the menu | Clicking inside the portalled panel does not close the drawer |
| Form labels | Device, Agent, Working directory and Git are sentence case; Settings captions too |
| First message | The field is gone from the drawer, and the app never sends `first_message` |
| Icon | Topbar, login card and sidebar carry the three-dot mark; `icon.svg`, the two PNGs and the maskable PNG match the iOS `AppIcon` |

Screenshots, 1280 px and 400 px, under
`…/scratchpad/ui-pass2/web/`: `sessions-*.png`, `sessions-archive-*.png`, `sessions-collapsed-*.png`,
`new-session-*.png`, `new-session-menu-*.png`, `chat-*.png`, `login-*.png`, `settings-*.png`,
`devices-*.png`. They are run artefacts and are not checked into the repository.

```
cd web && npm test -- --run && npx tsc --noEmit && npm run lint && npm run build
→ 18 files / 214 tests passed, tsc clean, eslint clean, built in 1.58 s
```

Not verified in this pass: a device list long enough to scroll, an Archive with dozens of rows, and
the collapse state surviving a real browser restart (it was read back from the store, not from a
relaunched browser).

### Status dots — four tones became five

2026-09-11, against the bundled mock gateway (`npm run dev:mock`) in the installed Google Chrome
driven by `playwright-core`. App-side only, no protocol change: the tone now reads `state`, `control`
and the device's `online` flag together, so a finished turn on a live session no longer looks like a
session whose CLI exited. The rule is the table in `docs/DESIGN.md`; `SCRATCH/plan/STATUS-DOTS.md`
was the frozen brief.

| Check | Result |
| --- | --- |
| The rule | `dotTone(state, control, online)` in `src/components/dotTone.ts`, the one place a tone is decided; `StatusDot` renders it and passes `control` at both call sites, the Sessions rows and the chat sidebar |
| All five tones on screen | `working` on three running rows, `waiting` on the pending approval and the pending question, `live` on the idle and attached-terminal rows, `failed` on the errored Codex row, `off` on the three exited rows in the two Archives |
| `idle` split by `control` | "iOS push tokens" (`control: "remote"`) reads `live`; "Rewrite the pairing docs" (`control: "none"`) reads `off` |
| Amber, not orange | `--attention` is `#B07C00`: 3.67:1 on `--surface`, 3.36:1 on the row hover and 3.24:1 on the muted surface, against 2.76:1 for the old `#E0862B` |
| Motion | Only `working` animates. Under `prefers-reduced-motion: reduce` the computed `animation-name` on every dot is `none`, the tones and colours unchanged |
| Labels | Each dot's `aria-label` is still the raw state ("needs approval", "running", "idle", "error") while its tooltip names the tone ("Waiting for you", "Working", "Live", "Failed"), read off the rendered DOM |
| Device dots | `OnlineDot` is untouched, green when online and a grey ring when not |
| Fixtures | Two sessions added to `mock/fixtures.ts` — a `needs_input` one on the CI box and an errored Codex one on the Mac — so the mock shows every tone; sixteen sessions in total |

Screenshots, 1280 px and 400 px, under `…/scratchpad/dots-pass/web/`: `sessions-*.png`,
`sessions-all-tones-*.png` (both Archives open, every tone in one frame), `chat-*.png`, and the close
crops `zoom-active-rows.png`, `zoom-archive-rows.png`, `zoom-waiting-row.png`,
`zoom-waiting-row-hover.png`. Run artefacts, not checked into the repository.

```
cd web && npm test -- --run && npx tsc --noEmit && npm run lint && npm run build
→ 19 files / 220 tests passed, tsc clean, eslint clean, built in 1.59 s
```

Not verified in this pass: an offline device. Both mock devices are online, so the offline row of the
table rests on `tests/dotTone.test.ts` alone.

### Sending shows the message at once (A12)

2026-09-11, against the bundled mock gateway (`npm run dev:mock`) in the installed Google Chrome
driven by `playwright-core`. App-side only: amendment A12 makes the app's `session.send` request id
the `user_message` block id, so the bubble is rendered on the click and the device's event replaces
it under the ordinary replacement rule. The mock now behaves like the device — it echoes the
`user_message` under the request id 400 ms after the reply, and a queued message keeps that id
through to its `user_message`.

| Check | Result |
| --- | --- |
| The field clears on the click | The composer no longer awaits `session.send`: the text and attachments are cleared, the error area reset and the row inserted in the same tick. A send that never settles leaves the field empty and the button ready |
| The bubble appears at once | On an idle Codex session the dimmed bubble with a quiet "Sending…" chip was in the timeline in the screenshot taken with no wait after the click |
| The device's event replaces it | 400 ms later the chip and the dimming were gone and there was exactly one bubble for the message, in the same place — two `.user-bubble` rows in total, one of them from history |
| Steering | On the shared Codex thread (`accepted: "steered"`) the same two frames: "Sending…", then replaced in place, two bubbles, none pending |
| Queueing | A send during a running turn read `accepted: "queued"`: the bubble was taken away again and the queue row above the composer stood for the message alone, so the text was never on screen twice. When the turn ended the device dequeued it under the same id — the queue row went and the message landed as one ordinary bubble |
| A refusal | A definite refusal (`conflict`, `unsupported`, `bad_request`, …) takes the bubble away and shows the message in the composer, and the draft is handed back if nothing was typed since. Covered by `tests/chat.test.ts` and `tests/Composer.test.tsx` |
| Uncertain delivery | Unchanged: no automatic resend, the outbox keeps the request id, "Delivery unconfirmed" with Retry. The pending bubble now says the same thing itself once it is a minute old with no device event |
| Reloads and reconnects | A resync keeps the pending rows and starts the timeline again; the history page that carries the device's copy retires the row rather than duplicating it. `tests/chat.test.ts` walks both |
| Older devices | A device that still mints its own block id is reconciled by `text` and `source: "remote"`, one row per event, never against a `terminal` message. The mock's `first_message` turn mints its own id, so the dev server exercises it |

Screenshots under `…/scratchpad/send-pass/web/`: `web-send-01-pending.png` and
`web-send-02-confirmed.png` (idle session), `web-send-03-queued.png`,
`web-send-04-steer-pending.png` and `web-send-05-steer-confirmed.png` (shared Codex),
`web-send-06-queue-delivered.png`. Run artefacts, not checked into the repository.

```
cd web && npm test -- --run && npx tsc --noEmit && npm run lint && npm run build
→ 20 files / 245 tests passed, tsc clean, eslint clean, built in 1.75 s
```

Not verified in this pass: a real device and gateway — this ran against the mock, so the 400 ms echo
is the mock's, not a measured round trip. The unconfirmed-after-60-s chip was verified in
`tests/optimistic-send.test.tsx` with a fake clock rather than by waiting in the browser.

### A steered message waits where the agent will read it (A14)

2026-09-12, source and test pass only: no browser and no gateway ran for it, so nothing below is a
live observation. Amendment A14 moves the device's `user_message` for a steered send to the moment
the agent takes the message, so the optimistic row from A12 has to survive the whole running turn
and the device's block has to sort by its own `first_seq`.

| Check | Result |
| --- | --- |
| `steered` keeps the row | Already correct. `settle` in `src/stores/chat.ts` drops the optimistic row only on `accepted: "queued"` or a definite refusal; every other answer leaves it standing |
| The row stays at the bottom | Already correct. `selectView` appends pending rows after `order`, and `pendingItem` gives them `Number.MAX_SAFE_INTEGER`, so assistant deltas, tool calls and replacements of the running turn all draw above it |
| Nothing else drops it | Already correct. No timer removes a row; `turn_completed` folds into usage only; a resync goes through `keepOptimistic`; `dropQueued` fires only for ids a `queue` snapshot names |
| The device's block lands in terminal order | Already correct. `reconcile` retires the row on the `block_id` match, and `insertOrdered` places the block by `first_seq`, after the output that preceded it |
| Older devices | Unchanged: the `text` + `source: "remote"` fallback still retires one row per event |
| The 60 s chip | **Changed.** A steered message that the agent has not reached yet was labelled "Delivery unconfirmed" once it was a minute old, which §8 rule 7 reserves for a send whose delivery is in doubt. The optimistic block now records what `session.send` answered, an accepted send never becomes "unconfirmed", and a steered one reads "the agent will read it at its next step" |

Changed files: `src/stores/timeline.ts` (`OptimisticBlock.accepted`, `markAccepted`, `isUnconfirmed`),
`src/stores/chat.ts` (`settle`), `src/features/chat/blocks/UserMessageRow.tsx`, `src/strings.ts`.

`tests/steer-order.test.ts` is new and replays the session from the defect report: a steered send,
then the assistant text the agent was already streaming (`first_seq` 46), a `pwd` tool call
(`first_seq` 50), the answer to the previous question (`first_seq` 53), the device's `user_message`
under the request id (`first_seq` 57) and the next assistant text (`first_seq` 59). It asserts the
row is the last row at every step before the block arrives and that the final order is
`assistant_text · tool_call · assistant_text · user_message · assistant_text`, plus a second case
where a `turn_completed` and a resync leave the row alone.

```
cd web && npm test -- --run && npx tsc --noEmit && npm run lint && npm run build
→ 21 files / 249 tests passed, tsc clean, eslint clean, built in 1.38 s
```

Not verified in this pass: a real Codex daemon steering a live turn, and the chip text in a browser.
Both were exercised in the store and component tests only.

### Round 6 — voice from the mic button, the product name, and the lists

2026-09-12. The state machine and the composer wiring were driven by tests; the lists and the
listening composer were driven in a real Chrome against the bundled mock at 1280 px and 400 px.
Screenshots are run artefacts under `…/scratchpad/ui-pass3/web/`, `before-*` and `after-*`.

| Check | Result |
| --- | --- |
| Voice starts on the mic button | **Changed.** Clicking the mic starts listening. The hold-⌥-space chord, its hint on the composer's bottom row, its "Push to talk" setting and the `pushToTalk` state are gone, not disabled |
| The listening control row | **Changed.** The composer's control row becomes a waveform, an elapsed timer and exactly two buttons, Cancel and Done. "Stop & send" is gone; the quiet line above the field reads "Transcribing live · edit before sending" |
| Cancel and Done | **Changed.** Cancel restores the draft the mic was pressed on; Done leaves the transcript in the field and sends nothing. Sending stays the ordinary Send button |
| No time limit | **Changed.** Nothing stops the recording on a timer. The gateway caps one utterance at 120 s / 4 MiB (`gateway/rc_gateway/stt.py`), so a long dictation now chains `WS /ws/stt` sockets the way iOS `GatewaySpeechRecognizer` does: the replacement is taking audio before the outgoing one is told to transcribe, the cut waits for the first pause after 30 s and is forced at 45 s, and the segments are joined by position |
| A failure keeps its words | Already correct by construction: a failed dictation publishes what it recognised before it moves to the error banner |
| Live, in Chrome | With a fake microphone, the mock's transcript appended to the typed draft (`after lunch` → `after lunch also add a retry`) and the control row rendered at both widths |
| Product name | **Changed.** `strings.productName`, the login title, the `claude` shim hint, `index.html`, `public/manifest.webmanifest` and the service-worker notification title read "Remote Control". Paths, the shim binary name and code comments are untouched |
| Device and session lists | **Changed.** No hairline between rows anywhere in the device list, the Sessions page or the chat sidebar; every row a fixed height; hover is the new `--hover` token and the selected sidebar row `--hover-selected` (.07) instead of a white card with a shadow |
| Row heights | Measured, not guessed: content is 42 px (session) and 39 px (device) at 1280, 73 px once the status stacks below 640 px, and 87 px once the device meta stacks below 480 px. The tokens are 64 / 96 / 108 px |
| A15 in the app | Already correct. `session.updated` goes through `useSessions.upsert`, and `selectSessionLayout` re-buckets from the record, so a row whose `archived` turns false moves into that device's Active rows with no reload |

Changed files: `src/features/voice/useVoice.ts`, `src/features/voice/segments.ts` and
`src/features/voice/sttSocket.ts` (both new), `src/features/voice/VoiceControls.tsx` (new, replacing
`VoicePanel.tsx`), `src/features/voice/draft.ts` (new), `src/features/chat/Composer.tsx`,
`src/features/settings/SettingsPage.tsx`, `src/stores/settings.ts`, `src/strings.ts`,
`src/styles/tokens.css`, `src/features/chat/chat.css`, `src/features/sessions/sessions.css`,
`src/features/devices/devices.css`, `index.html`, `public/manifest.webmanifest`, `public/sw.js`.

`tests/voice.test.ts` is new and drives the controller against a fake socket and a fake recorder:
listening starts on `start`, Done keeps the transcript and publishes it as final, Cancel publishes
nothing and drops the utterance, five minutes of speech is still listening, two segments join in
spoken order across a rollover, a cut waits for the pause, and a mid-utterance failure keeps what it
recognised. `tests/voice-composer.test.tsx` is new and covers the wiring with the controller mocked:
Alt+Space does nothing, the control row holds exactly Cancel and Done, the transcript appends to the
draft, Cancel restores it, Done leaves it for the ordinary Send, and a keystroke takes the field
back. `tests/SessionsPage.test.tsx` gained the A15 move.

```
cd web && npm test -- --run && npx tsc --noEmit && npm run lint && npm run build
→ 23 files / 265 tests passed, tsc clean, eslint clean, built in 1.31 s
```

Not verified in this pass: dictation against a real gateway STT backend and a real microphone, and
a segment rollover in a browser (the mock's utterance is shorter than the 30 s cut).

### Round 8 — the way back down, whenever the reader is away

2026-09-12, in the installed Google Chrome driven by `playwright-core` against the bundled mock
(`npm run dev:mock`), at 1280 px and 400 px. App-side only, no protocol change: the jump-to-latest
control now appears as soon as the timeline stops following the tail instead of only when something
arrived while the reader was away, so paging up through history has a way back down too. It is the
phone's control — a 36 px round button in the bottom-right corner of the transcript above the
composer, lifted by `--shadow-pop` and carrying no border, which widens into an 81 px capsule
reading `1 new` or `2 new` once blocks land while the reader is away; "Back to latest" moved into
the `aria-label` and the `title`, the count joining the name when there is one. Measured in the
browser at both widths: 36 × 36 px round, 81 × 36 px with a count, 20 px in from the right edge.
`tests/Timeline.test.tsx` is new and covers five states: absent while following, on screen with no
count after the reader scrolls away, the count after a block lands, a click that returns to the tail
and clears both, and an older history page that keeps the control on screen without counting itself.
Screenshots under `…/scratchpad/web-jump/` as `before-*` and `after-*` (`away`, `counted`,
`counted-many` and the zoom crops); run artefacts, not checked into the repository.

```
cd web && npm test -- --run && npx tsc --noEmit && npm run lint && npm run build
→ 24 files / 270 tests passed, tsc clean, eslint clean, built in 2.37 s
```

Motion was read off the rendered element: the 150 ms opacity-and-scale entrance computes to
`back-to-latest-in` normally and to `none` under `prefers-reduced-motion: reduce`, the shadow and the
borderless white surface unchanged. Not verified in this pass: a real gateway and device, since this
ran against the mock.

### Round 10 — two levels of detail, and one kind of row that can be archived

2026-09-12, in the installed Google Chrome driven by `playwright-core` against the bundled mock
(`npm run dev:mock`) at 1280 px and 400 px. App-side only, nothing on the wire. The timeline has a
detail level, the rule in `docs/DESIGN.md` § "The timeline": `timelineDetail` sits beside the other
preferences in `stores/settings.ts` (persisted in `rc.settings`, default `simple`, no migration
needed), Settings gained a **Timeline** group with a **Detail** control and the explanation as a
footnote, and `selectView(state, detail)` is the one place the filter lives. Simple drops thinking
and every tool call along with what is nested under them, except an approval or a question, which
comes up to the top level because a card waiting on an answer is never hidden; `ChatHeader` hides
the Todos chip; `Timeline` passes the drawn rows to `useScrollFollow`, so the jump-to-latest count
counts only what the level draws, and a level change is a redraw that counts nothing and moves no
anchor. Counted on the rendered page at 1280 px, same transcript, same approval: Simple drew 0 tool
rows, 0 thinking rows and no Todos chip; Detailed drew 5, 1 and the chip. The end-of-turn rule
needed no change — the reducer already drops `turn_started` and a `turn_completed` that simply
finished, at both levels.

The same round moved the archive action to one kind of row (`docs/DESIGN.md` § "Session lists"):
`SessionRow` offers it only when `control === "remote"` and the row is not archived, and the
unarchive branch and its string are gone. Measured on the page, the rows offering it were exactly
the remote, unarchived ones; every row's meta column still ends in the same place, 60 px from the
row's right edge at 1280 px and 56 px at 400 px, because the row now reserves the gutter the action
sits in, and the row heights are unchanged at 64 px and 96 px.

New tests: `tests/SettingsPage.test.tsx` (the default is Simple before anything renders, the group
and its explanation, the level the reader picks reaches the store); five cases in `tests/timeline.test.ts`
for the selector at both levels, the promoted approval, the sub-agent text that goes with its tool
row, an unconfirmed send at both levels, and the end-of-turn rule; three in `tests/Timeline.test.tsx`
for a tool-call burst counting nothing at Simple, one per block at Detailed, and the count starting
again on a level change; two in `tests/useScrollFollow.test.tsx` for the redraw; four in
`tests/SessionsPage.test.tsx` for the three archive cases and the request the button sends.
Screenshots under `…/scratchpad/web-jump/`: `round10-chat-simple-1280.png`,
`round10-chat-detailed-1280.png`, `round10-settings-1280.png`, `round10-sessions-1280.png`,
`round10-sessions-400.png`. Run artefacts, not checked into the repository.

```
cd web && npm test -- --run && npx tsc --noEmit && npm run lint && npm run build
→ 25 files / 287 tests passed, tsc clean, eslint clean, built in 1.32 s
```

Not verified in this pass: a real gateway and device; and the level was switched from the Settings
page rather than under an open chat in the browser, because the web app puts Settings on its own
route — the live re-render of an open timeline rests on `tests/Timeline.test.tsx`.

### Round 11 — what the terminal chose, shown where the pickers would be (A17)

2026-09-12, in the installed Google Chrome driven by `playwright-core` against the bundled mock
(`npm run dev:mock`) at 1280 px and 400 px. App-side only, nothing on the wire: the composer's
bottom row draws the session's `model`, `permission_mode` and `effort` as static chips
(`.composer-chip.readonly`, `role="note"`, no chevron, `cursor: default`) on a session a terminal
holds — `control: "terminal"`, or `shared` on an agent without `shared_settings` — and keeps the
pickers everywhere else. The accessible name and the tooltip carry the whole sentence, "Model ·
Sonnet 4.5 · set in the terminal", because "auto" on its own says nothing about who set it. A value
the agent's lists do not know is shown by its id, and a null draws no chip. This also fixed a
`terminal` session, which until now drew live pickers whose `session.set` the device refuses
outright.

Read off the rendered page: the shared Claude session drew `Sonnet 4.5`, `auto` and `xhigh` (the
last two are ids the device does not advertise, shown verbatim), the terminal session drew
`Sonnet 4.5`, `Auto-accept edits` and `High`, and both kept only the voice-language picker beside
them; the driven session and the shared Codex session, whose daemon carries the settings, drew four
pickers and no chips. The chips are 26 px tall, the height of the trigger next to them, and the row
still fits on one line at 400 px. `mock/fixtures.ts` now gives the shared Claude session
`permission_mode: "auto"` and `effort: "xhigh"` so the unadvertised-id path is on screen.

Eight tests in `tests/Composer.test.tsx`: the three chips on a shared session, the same on a
terminal session whose field stays disabled, nothing for a null, the raw id for `auto` and for a
model id carrying a `[1m]` suffix, ids only when the agent is unknown, the pickers kept on a driven
session and on shared Codex, and a `meta` event folded through `foldSession` moving the chip from
`Sonnet 4.5` to `Haiku 4.5`. Screenshots under `…/scratchpad/web-jump/`:
`round11-{shared,terminal,driven,codex-shared}-{1280,400}.png` and `round11-zoom-chips-1280.png`.
Run artefacts, not checked into the repository.

```
cd web && npm test -- --run && npx tsc --noEmit && npm run lint && npm run build
→ 25 files / 295 tests passed, tsc clean, eslint clean, built in 1.25 s
```

Not verified in this pass: a real device filling those fields from a real transcript — every value
above came from the mock's fixtures, so the app was checked against the shape of `meta`, not against
what Claude Code writes.

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

Other iOS checks, all green after the change in section 5:

| Command | Result |
| --- | --- |
| `swift run RCVerify` | PASS, 677 checks over 125 fixtures |
| `swift run RCUIVerify` | PASS, 50 UI checks |
| `swift test` | 49 tests in 7 suites passed |
| `xcodebuild … -destination 'generic/platform=iOS Simulator' build` | BUILD SUCCEEDED |
| `xcodebuild test … -only-testing:RemoteControlUITests` with no gateway env | 3 demo tests passed, 4 real-gateway tests skipped |

### Second UI pass — the session list and the new session sheet

2026-09-11, against the offline demo in the iPhone 17 simulator (iOS 26), following the same frozen
brief as the web pass (`SCRATCH/plan/SESSION-LIST-2.md`, including its clarification that a search
also unfolds a collapsed device group). `docs/IOS.md` documents the list structure and the persisted
keys.

| Check | Result |
| --- | --- |
| Per-device grouping | One group per device, names exactly as reported, expanded by default; groups ordered live machine first, then by last activity |
| Collapse | Tapping a device header folds that device only; ids persist in `UserDefaults` `sessions.collapsedDevices` |
| Per-device Archive | `Archive · N` under each device, shut by default, expansion persisted in `sessions.archiveExpanded` as `[String]` |
| Archive contents | Exited sessions plus hand-archived rows, the latter marked `Archived` |
| Search | Opens the matching Archive and unfolds a folded device without writing either array; clearing restores the stored state |
| Agent filter | `All · Claude Code · Codex` in the toolbar Menu, only agents present, in memory, applied before grouping |
| Agent chips | Tinted `Claude Code` / `Codex` chip on every row, no border |
| No "Show archived" | The toggle, its state and its strings are gone |
| New session sheet | No first-message field, sentence-case form labels, the app never sends `first_message` |
| Top banner | The connection summary in the top safe-area inset now has the bar material behind it; before the fix (pre-existing, `RootView.swift`) scrolled rows showed through it |

```
cd ios && export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
swift run RCVerify      → PASS: 841 checks
swift run RCUIVerify    → PASS: 75 UI checks
swift test              → 106 tests in 10 suites passed
xcodebuild … -only-testing:RemoteControlUITests … test
                        → 9 passed, 4 skipped (the real-gateway smoke), 0 failures
```

`Tests/RCCoreTests/SessionGroupingTests.swift` holds 18 cases for `SessionListLayout.build`;
`Verification/StoreChecks.swift`, `VerificationUI/main.swift` and the UI tests
(`testDeviceArchiveOpensOnTapAndOnSearch`, `testDeviceGroupCollapses`,
`testAgentFilterNarrowsTheListToOneAgent`, the New session sheet without a first-message field)
cover the same rules end to end. Screenshots from the UI test run are under
`…/scratchpad/ui-pass2/ios/` (`sessions-groups.png`, `sessions-archive-open.png`,
`sessions-archive-search.png`, `sessions-device-collapsed.png`, `sessions-agent-filter.png`,
`new-session-sheet.png`); they are run artefacts, not checked in.

Not verified in this pass: a physical device, a real gateway, dark mode and VoiceOver on the new
headers and chips.

### Voice, reading position, status dots and sending — four passes on 2026-09-11

All in the iPhone 17 simulator (iOS 26) against the offline demo, with
`export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer` from `ios/`. Each pass ended
with the full set green; the counts below are the last run.

| Pass | What was checked | Result |
| --- | --- | --- |
| Voice | Siri-style glow around the whole display in its own window; only Cancel and Done while listening; transcript lands in the field and stays editable; no time limit (on-device recognizer swaps requests every 50 s on one audio tap, gateway STT opens a new socket every 30 s cut at a quiet moment); field on its own row growing to eight lines | RCVerify 841, RCUIVerify 98, swift test 117, UI 11 passed / 4 skipped |
| Reading position | Tap outside a field dismisses the keyboard without stealing the tap (chat, new session, login); "at the bottom" from `onScrollGeometryChange`; round jump-to-latest button whenever away from the tail, with the count of blocks that arrived; deployment target iOS 18 | RCVerify 852, RCUIVerify 98, swift test 123, UI 14 passed / 4 skipped |
| Status dots and composer | `DotTone.of(state:control:online:)` over the frozen five-tone table, amber `#B07C00` (3.67:1 on the row surface), pulse only while working, static under Reduce Motion; one control row under the field (`+`, mic, chips scrolling sideways, Send pinned right); the "Attached to the terminal" caption gone, undrivable controls hidden | RCVerify 895, RCUIVerify 105, swift test 130, UI 15 passed / 4 skipped |
| Sending (A12) | Optimistic `user_message` under the request id in the same frame as the tap, replaced by the device's echo by id (older devices: by text and source); `queued` hands the row to the queue; definitive rejection returns the text to the draft; 60 s unconfirmed → "Delivery unconfirmed"; Send allowed while the transport reconnects (request held up to 20 s for the hello); status line above the composer only for what the header cannot say | RCVerify 912, RCUIVerify 111, swift test 145, build succeeded, UI 16 passed / 4 skipped |

Screenshots, run artefacts under the session scratchpad and not checked in: `voice-pass/named/`
(`20-composer-one-line`, `21-composer-five-lines`, `24-composer-capped`, `22-voice-listening`,
`23-voice-done`), `chat-pass/` (`25-keyboard-up` … `30-jump-badge`), `dots-pass/ios/`
(`30-status-tones`, `12-codex-shared`, `20-composer-one-line`, `06-shared-idle`,
`22-voice-listening`), `send-pass/ios/` (`40-send-pending`, `41-send-confirmed`, `06-shared-idle`).

Three pre-existing defects were caught by the screenshots and fixed on the way: the top connection
banner had no background and scrolled rows showed through it; the composer field never grew and
text spilled past its background; and the first full-screen glow washed colour deep into the page
(strokes reduced to 30 / 11 / 3.5 pt at 0.13 / 0.26 / 0.44 alpha).

Not verified in these passes: a physical device (no real microphone, APNs or gateway STT), the
jump-to-latest badge count in a live run (the demo cannot leave the reader away from the tail while
blocks arrive; the screenshot forced the count), dark mode and VoiceOver on the new controls, and the
20 s reconnect hold against a real gateway.

### A steered message waits where the agent will read it (A14)

2026-09-12, source and test pass only: no simulator and no gateway ran for it, so nothing below is a
live observation. Amendment A14 moves the device's `user_message` for a steered send to the moment
the agent takes the message, so the optimistic row from A12 has to survive the rest of the running
turn and the device's block has to sort by its own `first_seq`.

| Check | Result |
| --- | --- |
| `steered` keeps the row | Already correct. `deliver` in `ios/Sources/RCCore/State/ChatStore.swift` removes the row only on `accepted: "queued"` or a definite refusal; every other answer leaves it standing |
| The row stays at the bottom | Already correct. `TimelineEntry(pending:)` takes `Int.max`, and `roots` appends the pending rows after the sorted entries, so assistant deltas, tool calls and replacements of the running turn all draw above it |
| Nothing else drops it | Already correct. No timer removes a row; `turn_completed` only clears the turn marker and folds in usage; `reset()` on a resync keeps the optimistic rows; `dropQueuedOptimistic` fires only for ids a `queue` snapshot names |
| The device's block lands in terminal order | Already correct. `reconcileOptimistic` retires the row on the `block_id` match, and the block sorts by `first_seq`, after the output that preceded it |
| Older devices | Unchanged: the `text` + `source: "remote"` fallback still retires one row per live event |
| A history page holding the same words | **Changed.** The text fallback also ran over `prependHistory`, so paging older events — or the reload a resync asks for — retired a steered row whenever the session already held a remote message with the same words ("continue", sent twice). History is older than the send by definition, so the fallback is now live-only; the `block_id` match still applies to history |
| The 60 s label | **Changed.** A steered message the agent had not reached yet was labelled "Delivery unconfirmed" once it was a minute old, which §8 rule 7 reserves for a send whose delivery is in doubt. The row now records that the device answered `steered`, such a row never becomes "unconfirmed", and it reads "the agent will read it at its next step" (the wording web uses) |
| The demo device | **Changed.** `DemoGateway` emitted the steered `user_message` at send time under an id of its own, which is the very order A14 forbids. It now answers `steered`, finishes the sentence the turn was on, and emits the block under the request id afterwards |

Changed files: `ios/Sources/RCCore/State/Timeline.swift` (`OptimisticMessage.isSteering`,
`markSteered`, history-only reconciliation), `ios/Sources/RCCore/State/ChatStore.swift`,
`ios/Sources/RCUI/Screens/ChatRows.swift`, `ios/Sources/RCCore/Demo/DemoGateway.swift`.

`ios/Tests/RCCoreTests/SteeredSendTests.swift` is new and replays the session from the defect
report: the optimistic row under the request id, the assistant text the agent was already streaming
(`first_seq` 46), a `pwd` tool call (`first_seq` 50), the answer to the previous question
(`first_seq` 53), the device's `user_message` under the request id (`first_seq` 59) and the next
assistant text (`first_seq` 60). It asserts the row is the last row at every step before the block
arrives, and that the final order is `user_message · assistant_text · tool_call ·
assistant_text · user_message · assistant_text`, with further cases for `turn_completed`, the
60 s label and the history fallback. `RCVerify` gained the same sequence end to end against the
demo device, and the A11 daemon test now asserts the row survives the `steered` acceptance.

```
cd ios && export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
swift run RCVerify && swift run RCUIVerify && swift test
→ RCVerify 917 checks, RCUIVerify 111 checks, 150 tests in 15 suites passed
```

Not verified in this pass: a real Codex daemon steering a live turn, and the caption in the
simulator. Both were exercised in the store checks and unit tests only.

### A session that comes back to life leaves the Archive (A15)

2026-09-12, source, store-check and unit-test pass only: no simulator and no gateway ran for it, so
nothing below is a live observation. Amendment A15 has the device clear `archived` when a turn
starts in the session or a terminal attaches to it, so the app's only job is to redraw the list from
the `session.updated` it gets.

| Check | Result |
| --- | --- |
| Nothing caches which half a row is in | Already correct. `SessionListLayout.isArchived` reads `archived` and `control` off the session it is handed, and `SessionListLayout.build` is a pure function of its arguments. The only state `SessionStore` writes down is per device id — `sessions.collapsedDevices` and `sessions.archiveExpanded` — never per session |
| The frame replaces the session outright | Already correct. `ConnectionStore.apply` assigns the decoded session over the one it matched by id rather than merging fields, so a stale `archived: true` cannot survive the update |
| The list redraws without being asked to | Already correct. `SessionsView` builds its groups inside `body`, off the observed `connection.sessions`, so the move is one rebuild after the frame. No reload, no `session.list` round trip |
| The row moves, and the counts follow | New check. `StoreChecks.revivedSession` takes the archived demo session out of the hello, emits one `session.updated` with `archived: false` and a terminal owner, and asserts the row is among that device's Active rows, gone from its Archive, the Archive count down one, the live count up one, and the recorded request count unchanged |
| The reverse folds it back | New check. The same session emitted again with `archived: true` returns to the Archive, and both counts return to what they were |
| The flag alone decides the half | New test. `archivedFlagAloneDecidesTheHalf` holds `control` at `remote` and changes only `archived`, so the move is not an artefact of the owner changing. It also pins that the last row leaving an Archive takes the `Archive · N` sub-header with it |
| The reader's own choices survive the move | New test. `revivedSessionLeavesTheArchive` opens a device's Archive, moves the row out and back, and asserts the stored expansion set still holds that device id |
| The demo device shows it | **Changed.** `DemoGateway` gained a small script: five seconds after the demo connects, a terminal attaches to `demo-session-changelog`, so the device clears `archived`, reports `control: "terminal"` and publishes the session. The row leaves `mac-studio-office`'s Archive while the reader is looking at the list. The session is `idle` rather than `running` afterwards, because the transcript it carries ends in a completed turn and the demo models A15's attach trigger rather than inventing output |
| The UI-testing launch holds still | **Changed.** `AppModel.enterDemo` passes `resumeDelay: nil` under `--ui-testing`, and `VerificationUI` reads the hello's session list into a snapshot before the grouping checks, so neither measures a list that is moving underneath it |

Changed files: `ios/Sources/RCCore/Demo/DemoFixtures.swift` (the archived session and its transcript),
`ios/Sources/RCCore/Demo/DemoGateway.swift`, `ios/Sources/RCUI/Screens/AppModel.swift`,
`ios/Tests/RCCoreTests/SessionGroupingTests.swift`, `ios/Verification/StoreChecks.swift`,
`ios/VerificationUI/main.swift`. No file under `ios/Sources/RCCore/State/` changed: the grouping and
the store were already right.

```
cd ios && export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
swift run RCVerify && swift run RCUIVerify && swift test
→ RCVerify 927 checks, RCUIVerify 112 checks, 152 tests in 15 suites passed
```

Not verified in this pass: the move on screen in the simulator, and the same move against a real
device that resumes an archived session. The demo carries the script for the first; nothing here
exercised a real client.

### Two levels of detail, and one kind of row that can be archived (round 10)

The timeline now has a detail level, the rule in `docs/DESIGN.md` § "The timeline". `TimelineDetail`
lives beside the other preferences in `SettingsStore` (key `preference.timelineDetail`, default
`.simple`), never on the wire, and Settings gained a **Timeline** group with a **Detail** picker
(`settings.timelineDetail`). The filter is pure: `Timeline.roots(at:)` and `children(of:at:)` drop
thinking, every tool call and everything nested under one, along with a turn that simply finished,
while a card waiting on an answer comes up to the top level rather than going with the tool row that
would have held it. `ChatStore` reads the level through `detailSource` rather than keeping a copy, so
the open transcript and its jump-to-latest count follow the preference without a reload, and
`showsTodos` hides the header chip at Simple. `RCVerify` covers the filter over a transcript with one
of everything, a tool-call burst counting nothing at Simple and one per block at Detailed, and the
persisted default; `RCUIVerify` drives the real demo conversation, switching the preference under an
open chat and back; five tests in `TimelineTests` and `ScrollTailTests` pin the same rules.

The same round moved the Archive action to one kind of row, the rule in `docs/DESIGN.md` § "Session
lists". `SessionListLayout.offersArchive` is `control == .remote && !archived`, and the swipe action
in `SessionsView` is built only under it: a row a terminal holds offers nothing, a row in the Archive
offers nothing, and the Unarchive label is gone, so nothing in the app clears the flag that A15 has
the device clear. `SessionGroupingTests` covers the three cases directly and `RCVerify` asserts them
over the whole demo list. Changed files:
`ios/Sources/RCCore/State/{SettingsStore,Timeline,ChatStore,SessionGrouping,ConnectionStore}.swift`,
`ios/Sources/RCUI/Screens/{AppModel,ChatView,ChatRows,SettingsView,SessionsView}.swift`,
`ios/Verification/StoreChecks.swift`, `ios/VerificationUI/main.swift`, and three test files.

```
cd ios && export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
swift run RCVerify && swift run RCUIVerify && swift test
→ RCVerify 950 checks, RCUIVerify 125 checks, 158 tests in 15 suites passed
```

Not verified in this pass: nothing ran in a simulator or on a device, so the picker was not tapped,
the redraw was not watched on screen, no row was swiped to see which action appears, and no VoiceOver
pass was made over a Simple transcript. Every result above is the SwiftUI-free harness driving the
same stores and pure rules the screens read.

### What the terminal chose is shown, not offered (A17, round 11)

A session a terminal holds now draws its model, permission mode and effort where the pickers would
be, the rule in `docs/DESIGN.md` § "The composer". `ChatStore.isTunedByTerminal` is `control ==
.terminal`, or `.shared` on an agent without `shared_settings`; `allowsSettingsChanges` is its
negation, so the two are exclusive by construction, and `terminalSettings` builds the chips through
`TerminalSetting.all(for:agent:)` — one per non-nil value, in picker order, labelled by the agent's
own lists and by the raw id where they do not know it. The composer draws them as `StaticChip`
(the `ChipPill` the menus wear, no chevron, no action) under `composer.readonly.model` /
`permissionMode` / `effort`, with the accessible reading "Model, Sonnet 4.5, set in the terminal".
The demo's terminal session carries all three, its attached Claude session carries `auto` as a
permission mode Claude never advertises, and the demo gateway publishes a `meta` switching that
session's model 700 ms after it is opened. `RCVerify` covers the rules and the `meta`, `RCUIVerify`
watches the demo chip go from Sonnet 4.5 to Opus 4.1 with no reload and asserts nothing is drawn on
a session the app drives or on the shared Codex thread, and nine tests in `TerminalSettingsTests` pin
the labelling, the nil cases, the unknown agent and effort without the capability. One existing test
had to change: `CodexDaemonTests` proved a refused `session.set` by the effort *not* becoming
`high`, which the new fixture value made vacuous, so it now asserts the value is unchanged. Changed
files: `ios/Sources/RCCore/State/{TerminalSettings,ChatStore}.swift`,
`ios/Sources/RCCore/Demo/{DemoFixtures,DemoGateway}.swift`,
`ios/Sources/RCUI/{Design/Controls,Screens/Composer}.swift`, `ios/Verification/StoreChecks.swift`,
`ios/VerificationUI/main.swift`, `ios/UITests/RemoteControlUITests.swift` and two test files.

```
cd ios && export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
swift run RCVerify && swift run RCUIVerify && swift test
→ RCVerify 962 checks, RCUIVerify 135 checks, 167 tests in 16 suites passed
```

Not verified in this pass: nothing ran in a simulator, so the chips were not seen beside the live
pickers, the row was not scrolled at phone width, and no VoiceOver pass read one out. The two
XCUITest assertions added for them (chips present on the attached session, absent on the shared
Codex thread) were written but not run.

### Round 12 — two defects the owner saw on the phone, settled by screenshot (2026-09-12)

Both were established in the iPhone 17 simulator on iOS 27 before anything was changed, and both
shots are kept. **Settings headers were capitals**: `before-settings-headers.png` shows ACCOUNT,
NOTIFICATIONS, VOICE against `docs/DESIGN.md` § "Surfaces, rows and controls", so the phone was not
running an old build — the app really did re-case them, in two ways at once, an explicit
`.uppercased()` in the label and the `Section` header transform SwiftUI applies inside a `Form`.
`after-settings-headers.png` shows Account, Notifications, Voice, Timeline.
`testSettingsSectionHeadersAreSentenceCase` reads the four headers back through their accessibility
labels, which carry the transformed text when a header is re-cased. **The composer field scrolled
with no indicator**: `before-composer-scroll.png` shows a capped eight-line draft after a slow drag
inside the field with nothing on its trailing edge. `.scrollIndicators(.visible)` changed nothing —
five screenshots taken in a burst straight after the drag were all pure white along that edge, while
the chat transcript dragged the same way in the same burst showed its indicator in all five, which
is what ruled the modifier out rather than a fade race. The field became a `UITextView`
(`GrowingTextField`); `after-composer-scroll.png` and `after-composer-capped.png` show the indicator,
the latter also showing that the cap is now a measured eight lines rather than eight times the
font's line height, which had been clipping the eighth. `testComposerFieldShowsItsScrollIndicator`
samples the pixels along the trailing edge after the drag, so it fails if the indicator goes away
again. One existing assertion had to change with the implementation: UIKit has no placeholder on a
text view and no placeholder value to report, so the composer's placeholder is now the field's
accessibility label and `testSharedCodexSessionKeepsEveryControl` reads `label` where it read
`placeholderValue`.

```
cd ios && export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
swift run RCVerify && swift run RCUIVerify && swift test
→ RCVerify 961 of 962 checks, RCUIVerify 135 checks, 167 tests in 16 suites passed
xcodebuild test-without-building … -only-testing:RemoteControlUITests
→ 18 run, 4 skipped (the real-gateway smoke tests), 4 failed
```

The one RCVerify check that fails is `events/user_message.pending.json`, a fixture removed from the
working tree by concurrent protocol work, not by anything here. Three of the four XCUITest failures
— `testJumpToLatestAppearsWhenTheReaderLeavesTheBottom`, `testSessionsListShowsEveryStatusTone` and
`testToolCardOpensOnTheFirstTapWhileTheKeyboardIsUp` — fail the same way on a build of the sources
at `HEAD`, so they came in before this round. The fourth,
`testDeviceArchiveOpensOnTapAndOnSearch`, passes on its own and failed twice while two other builds
were running on the same Mac: it scrolls a lazy list and taps what it finds, and a loaded machine
moves the row under the tap.

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

## 4. Shared Codex sessions (A11) in the apps

Amendment A11 landed after section 3. It gives a shared session two more optional agent booleans,
`shared_settings` and `shared_attachments`, and it gives Codex an attachment that carries far more
than the Claude channel does. Like section 3, this pass was driven against the web mock gateway and
the iOS demo rather than a live device, because the apps' side of A11 is entirely a matter of reading
those booleans and rendering what arrives.

The live proof that the two ends agree is on the device side, in section 7 of
`docs/VALIDATION.md`, where a scripted app drove a real bare `codex` TUI through the shared daemon.
Neither a browser nor a phone has typed into one.

Both apps read the booleans off `AgentInfo` and branch on nothing else. No surface in either app asks
which agent it is looking at.

### Web

The mock gateway gained two Codex agents and two Codex sessions so both shapes exist side by side: a
device whose Codex reports `attach: "daemon"` with `attach_ready: true` and all three booleans true,
and a device whose Codex has no daemon running and reports `attach_ready: false`.

What the app does:

- The model, permission-mode and effort pickers are enabled on a `shared` session when the agent
  reports `shared_settings`, and the attachment button when it reports `shared_attachments`. Each
  keeps its own tooltip when the boolean is false.
- The attached bar names only what the attachment cannot do. With both booleans true nothing is left
  to the terminal alone, so it reads "Attached to the terminal".
- A block resolved with `{option_id: "elsewhere", by: "terminal"}` reads **"Answered in the
  terminal"** rather than falling back to the raw option id, and `elsewhere` is never offered as a
  button.
- A four-option approval — Allow, Allow for this session, Always allow commands like this, Deny —
  renders in the order the block gives, with `primary` and `danger` placed as always, and wraps
  inside the card at 390 px.
- All three send modes were driven on a running shared Codex turn: `auto` came back
  `accepted: "steered"`, `queue` came back `accepted: "queued"` with the message held, and
  `interrupt` ended the terminal's turn before starting its own. Stop and "Interrupt & send" are both
  gated on capability `interrupt` together with `shared_interrupt`.
- A `terminal` Codex session on the no-daemon device shows the daemon hint rather than the shim one.

| Check | Result |
| --- | --- |
| `npm run typecheck && npm run lint && npm test -- --run && npm run build` | tsc clean, eslint clean, 173 vitest tests passed |
| Headless-Chrome pass over the mock | 39 checks, all passed |

Screenshots `a11-01` through `a11-10` are run artefacts under the session scratch directory
`…/scratchpad/web-codex/shots/`. They are not checked into the repository.

### iOS

The demo gateway gained a Codex agent behind a running daemon and one with no daemon, so the
composer's hint has a home in both states. `DemoGateway` routes a send by the attachment's own
capabilities rather than by agent name, and refuses an `option_id` the block never offered with
`bad_request`, which is what keeps the app honest about never sending `elsewhere`.

What the app does:

- `ChatStore.allowsSettingsChanges` and `allowsAttachments` come straight from the two booleans, and
  they drive the chips, the attachment button and the session settings sheet alike.
- The composer status line shrinks as the attachment grows: "Attached to the terminal · settings and
  attachments are changed there", then one clause, then just "Attached to the terminal".
- `ChatStore.steersRunningTurn` decides both the status line and the placeholder, so a running shared
  Codex turn reads "your message will steer the turn" while Claude keeps "will be queued".
- `ApprovalPayload.resolvedOptionLabel` returns nil for `elsewhere`, so the card reads "answered in
  the terminal" with no option named, and renders any other unrecognised id verbatim rather than
  blanking the card.

| Command | Result |
| --- | --- |
| `swift test` | 88 tests passed |
| `swift run RCVerify` | PASS, 822 checks against the real `protocol/fixtures`, 137 fixtures |
| `swift run RCUIVerify` | PASS, 68 UI checks |
| `xcodegen generate` then `xcodebuild … -destination 'generic/platform=iOS Simulator' build` | BUILD SUCCEEDED |
| UI tests on the iPhone 17 simulator | 10 executed, 0 failures, 4 real-gateway tests skipped |

Screenshot: `12-codex-shared.png`, under the run scratch directory `…/scratchpad/ios-codex/shots/`.

Not verified in this pass, on either app: nothing was driven against a real Codex daemon or a real
`codex` TUI, and no attachment was actually delivered into a live thread.

## 5. Defects found in the apps, and fixed

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

## 6. Defects found in other components, reported and since fixed

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

## 7. Observations, not defects

- `strings.status.idle`, `strings.chat.outputTruncated` and `strings.chat.inputTruncated` are in the
  web catalog but never rendered: idle shows no status line by design, and a truncated block shows
  "Open full output" instead of a label. Dead strings, no user impact.
- The composer's Effort pill renders with no value for Claude sessions, because the device leaves
  `Session.effort` null until it is set explicitly.
- Signing out once failed to reach the login screen inside 15 s, in a run that had just restarted
  the device daemon and the gateway. Two targeted attempts to reproduce it, including one
  immediately after a gateway restart, both signed out instantly. Recorded, not explained.
- `ENGINE-FACTS.md` records Claude Code 2.1.266; this machine now runs 2.1.267.

## 8. Not verified

- **`allow_session`, the middle approval option.** Allow and Deny were both driven end to end; the
  session-scoped grant was never chosen, so nothing checked that a second write goes through
  unattended afterwards.
- **Approval for a command rather than an edit.** Both approvals answered here were `Write`.
- **Codex approvals.** As in `docs/VALIDATION.md`: this machine's Codex sandbox never escalates, and
  `permission_mode: "untrusted"` with `echo hi` still produced no approval.
- **Attachments** on either app, **Web Push** and **APNs** delivery, and **speech to text** on either
  app: the gateway ran with `STT_PROVIDER=none` and no `WEB_PUSH_CONTACT`, so the mic is hidden on
  web by design and nothing exercised `/ws/stt`.
- **iOS keychain restore**, for the signing reason in section 5.
- **`session.takeover` from an app.** The read-only state was verified; taking over needs an idle
  terminal session, which this run never held.
- **iOS on a physical device**, dark mode, VoiceOver, and any iOS flow beyond the four tests above:
  new session, add device, directory picker, voice and push were exercised only by the offline demo
  suite the iOS owner wrote.
- **`session.delete`** has no entry point in either app.
- **Attached sessions against a real device.** Both apps were driven against fixtures for A10 and
  A11: the web mock gateway and the iOS demo. The live proof that the two ends agree is on the device
  side, in section 6 of `docs/VALIDATION.md`, where a scripted app drove a real attached CLI. No
  browser and no phone has yet typed into one.
- **A shared Codex session against a real daemon.** Section 4 exercised every A11 surface against
  mock and demo data. Nothing in either app has spoken to a real `codex` app-server daemon, so the
  four-option approval, the steered send and the "answered in the terminal" card have not been seen
  end to end from a browser or a phone.

## 9. Session grouping and the visual pass (iOS)

2026-09-11, iPhone 17 simulator (`32BBA636-AC71-4804-84E2-ED98992C86B6`, iOS 27) against the offline
demo. No gateway, no device, no CLI: the change is app-side and reads only fields the session object
already carries. `docs/DESIGN.md` states the rule both apps implement.

The four suites `docs/IOS.md` prescribes, all green:

| Suite | Command | Result |
| --- | --- | --- |
| Core regressions | `swift run RCVerify` | 839 checks, was 822 |
| SwiftUI layer | `swift run RCUIVerify` | 69 checks, was 68 |
| Unit tests | `swift test` | 98 tests in 10 suites, was 88 in 9 |
| Simulator build | `xcodebuild … -destination 'generic/platform=iOS Simulator' build` | BUILD SUCCEEDED |
| UI tests | `xcodebuild … -only-testing:RemoteControlUITests test` | 8 tests, 0 failures, was 6 |

New checks, and what each one proves:

- **Active membership.** `remote`, `terminal` and `shared` are Active; `control: "none"` is not.
  Covered for every value rather than for the ones the demo happens to hold.
- **The archive toggle.** A hand-archived session is hidden with the toggle off and joins the
  Archive with it on. It never returns to Active, whatever holds it.
- **Order inside a device.** `needs_input`, `needs_approval`, `starting`, `running`, `idle`, in that
  order, with `updated_at` breaking ties. `starting` counts as working.
- **The Archive is flat.** Newest first across devices, each row carrying its own device name.
- **A device with nothing open.** It keeps its caption and its place in the list, and the caption
  sits on the canvas rather than in an empty surface. Driven in the simulator against
  `ci-runner-01`, whose only session is archived: `testDeviceWithNoOpenSessionsSaysSo`.
- **Device order is stable.** A device whose session needs approval does not jump above one that
  does not; the order comes from the device list.
- **Search.** Title, working directory and agent match; whitespace and case are ignored; a device
  with no match drops out instead of showing an empty caption.
- **A search reaches into the Archive.** A match inside opens the group without touching the stored
  preference, and a search that misses leaves it shut. Driven in the simulator as well:
  `testArchiveGroupOpensOnTapAndOnSearch` taps the header open, taps it shut, then finds the same
  row by searching for "OTLP".
- **The open or collapsed choice survives a relaunch.** A second `SessionStore` on the same
  `UserDefaults` reads it back.
- **A session on a device the gateway never listed** still gets a group rather than disappearing.

The six existing UI tests passed unchanged, so the visual pass moved no accessibility identifier and
broke no behaviour: the shared-session delivery chips, the four-option Codex approval, the attach
hint, the pairing sheet and the new-session sheet are all where they were.

Screenshots before and after — Sessions, the Archive collapsed and open, chat, Devices and Settings —
were taken from the same simulator on the demo data and handed to the review.

### Not verified in this pass

- **Dark mode.** The tokens define dark values and the new ones follow them, but no screen was
  reviewed in dark mode; v1 is still designed light.
- **Dynamic Type.** The new type scale uses text styles rather than fixed sizes, so it scales, but
  no size above the default was exercised.
- **VoiceOver.** Row labels were kept and the Archive header carries a label and a hint, which the
  accessibility tree shows, but nothing was driven with the screen reader on.
- **A long device list or a long Archive.** The demo carries three devices and one archived session.

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
9. Shared Codex (A11), in the same mock and demo: open the Codex session on the device whose daemon
   is running and confirm the pickers and the attachment button are live, the bar reads "Attached to
   the terminal", Stop is offered, a send during the running turn steers it, the four-option approval
   renders all four, and the card the terminal answered reads "Answered in the terminal". Open the
   Codex session on the device with no daemon and confirm the daemon hint.
10. Stop the daemon and the gateway. Delete the scratch `DATA_DIR` and `RC_CLIENT_HOME`.
