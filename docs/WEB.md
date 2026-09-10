# The web app

A static single-page app the gateway serves from `web/dist`. React 19, Vite, TypeScript, and
hand-written CSS with no framework. It talks only to the gateway and follows
`protocol/PROTOCOL.md` exactly.

## Screens

| Route | What it does |
| --- | --- |
| `/login` | Password sign-in against the gateway |
| `/devices` | Device list with online state, agents and session counts; rename and revoke; **Add device** with the copyable one-liner, the pairing code, its expiry, and live handshake steps |
| `/sessions` | Every session across every device, with a status dot, the device and working directory, and **New session** in a right-hand drawer |
| `/sessions/:deviceId/:sessionId` | The chat: sidebar, timeline, composer, status line |
| `/settings` | Account and sign out, browser notifications, voice language and push-to-talk, and an About block with the gateway origin, both versions and the connection state |

## Commands

```sh
cd web && npm ci
```

| Command | What it does |
| --- | --- |
| `npm run dev` | Vite on 5173, proxying `/api` and `/ws` to `http://127.0.0.1:8787` |
| `npm run dev:mock` | The same server plus the bundled mock gateway. Sign in with `dev` |
| `npm run mock` | Only the mock gateway, on 8787 |
| `npm run build` | Typecheck, then build to `web/dist` |
| `npm test` | Vitest |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run lint` | ESLint, flat config |

`RC_GATEWAY=http://host:port npm run dev` points the proxy somewhere else.

## The mock gateway

`mock/server.ts` implements the app-facing half of the protocol — the HTTP API, `WS /ws/app` and
`WS /ws/stt` — so the whole UI can be developed with no gateway and no device. It ships two devices
and nine sessions covering running, needs-approval, idle, terminal-controlled, shared through the
Claude channel, shared through the Codex daemon, Codex, and terminal sessions on a device that has
neither the shim nor the Codex daemon. Opening the
running session plays a scripted turn: streamed thinking, streamed Markdown, tool rows with a live
output box, a failing shell run, two diffs, an approval and a question. Answering both drives the
turn to completion. The shared session plays the A10 path end to end: a send while the terminal is
idle is injected at once and answers with a relayed Allow/Deny approval, a send during that turn is
held and shows the "waiting for the terminal" chip until the turn ends, and `session.set`,
`session.stop` and `session.takeover` answer with the errors the contract specifies. The shared
Codex session plays the A11 path: it opens on a turn the terminal started, a send steers that turn
(`accepted: "steered"`), `session.set` applies, Stop interrupts, and a command approval offers all
four daemon decisions. Its history carries a request the TUI answered first, so the card reads
"Answered in the terminal". Pairing walks
`waiting → enrolled → online → agents` over about six seconds, and the speech socket returns
scripted partials and a final transcript. The mock and the tests share
`mock/fixtures.ts`, so a fixture change shows up in both.

## Structure

```
src/
  protocol/    wire types and frame shapes
  lib/         HTTP client, app socket, formatters
  stores/      zustand stores; timeline.ts is the pure event -> renderable-items reducer
  components/  buttons, modal, drawer, popover, segmented control, status dots
  features/    login, devices, sessions, chat, voice, settings
  push/        Web Push subscription and service worker registration
  styles/      tokens.css and base.css
mock/          the mock gateway
tests/         vitest suites
```

Every user-visible string lives in `src/strings.ts` so the app can be localised later.

## Voice

Holding <kbd>⌥</kbd>+<kbd>Space</kbd>, or tapping the mic, opens `WS /ws/stt`, captures the
microphone through an AudioWorklet with a ScriptProcessor fallback, downsamples to 16 kHz mono
PCM16LE, and sends roughly 120 ms binary frames. Partial transcripts stream into an editable field.
"Stop & send" sends `stt.stop`, waits for `stt.final`, and submits. The mic is hidden entirely when
the gateway reports `stt.enabled: false`.

## Push and the service worker

`public/sw.js` is registered in production builds only. It is network-first for navigations,
cache-first for hashed assets under `/assets/`, and bypasses `/api`, `/ws`, `/install.sh` and
`/dist/`. Only successful same-origin responses are cached, so a gateway error page never becomes
the offline shell. A push payload carries only a device name and a reason; clicking the notification
focuses an open tab or opens `/sessions/<device_id>/<session_id>`.

## Behaviour worth knowing

- **Reconnect** backs off exponentially to 5 s, replies to `ping`, and treats 60 s of silence as a
  half-open socket. Subscriptions are re-issued with the latest `since_seq`.
- **Close codes** 4401 and 4403 end the session and return to login; every other code reconnects.
- **Sending** is always `mode: "auto"`; the device decides between send, steer and queue, and the
  button label follows that decision. "Interrupt & send" is a separate, explicit action.
- **Uncertain delivery** is never resent automatically. The composer offers a Retry that reuses the
  original request id.
- **The composer is gated on `control`**, never on `state`.
- **`control: "shared"`** (amendments A10 and A11) is a live terminal session the device is
  attached to. It behaves like `remote`: the composer, the queue and approvals all work. What the
  attachment cannot carry is disabled, and "Take over" never appears, because there is nothing to
  take over. Three optional agent booleans say what it carries — `shared_interrupt`,
  `shared_settings` and `shared_attachments` — each defaulting to false.
- **Long output folds** beyond 20 lines, and `output_truncated` adds "Open full output", which
  fetches the untruncated block.
- **Reading position** holds: the timeline auto-follows until you scroll away, then counts new
  blocks behind a "Back to latest" button.
- **Failed actions** surface in a dismissible banner above the composer rather than failing silently.

## Shared terminal sessions

A session whose `control` is `shared` is driven by a live CLI the device is attached to, so the app
can inject prompts and answer permission prompts without killing the process. A Claude terminal is
attached through the channel shim; a bare `codex` TUI is attached through the shared app-server
daemon, which carries far more.

| Surface | Behaviour |
| --- | --- |
| Composer | Enabled, exactly as for `remote`. Send label and queue are unchanged |
| Take over | Never shown. The device is already attached |
| Stop | Shown only when the agent lists the `interrupt` capability **and** the device reports `shared_interrupt`. A Claude channel cannot interrupt, so Stop and "Interrupt & send" both disappear |
| Model / permission mode / effort | Enabled when the agent reports `shared_settings`; otherwise disabled with the tooltip "Change it in the terminal". The title is always editable |
| Attachments | Enabled when the agent reports `shared_attachments`; otherwise disabled with the tooltip "Attachments cannot be delivered to a terminal session", and pasted files are ignored |
| Attached bar | "Attached to the terminal" when the agent reports both booleans, because nothing is left to the terminal alone; "Attached to the terminal session" while something still is |
| Status label | "terminal · attached" in the sidebar and the Sessions list; the dot uses the session `state`, so it matches `remote` |
| Approvals | Whatever `options` the block carries. A Claude relay sends Allow and Deny; the Codex daemon sends up to four decisions |

The three booleans are independent and read straight off `AgentInfo`, so no surface needs
agent-specific logic:

| Agent | `attach` | `shared_interrupt` | `shared_settings` | `shared_attachments` |
| --- | --- | --- | --- | --- |
| Claude, through the channel shim | `channel` | false | false | false |
| Codex, through the app-server daemon | `daemon` | true | true | true |

Sending uses the protocol's own modes. On a running shared session `auto` steers when the agent
lists `steer` and is held otherwise, `queue` is always held, and `interrupt` ends the terminal turn
and starts a new one, so "Interrupt & send" needs the same `shared_interrupt` as Stop. The composer
then says "Message will steer the turn…" with a "Send" button when the agent can steer, and
"Message will be queued…" with a "Queue" button when it cannot, matching the status line either
way.

Control changes arrive as a `meta` event carrying `control`, with a `status` event only when the
session state moves as well, so the app never infers the owner from a status change. The transitions
are `terminal → shared` when the attachment registers, `shared → terminal` when it drops, and
`shared → none` when the CLI exits.

### Approvals answered elsewhere

Both sides of a shared session see the same permission request, and whoever answers first wins. When
the terminal answered, the block resolves with the reserved decision `{option_id: "elsewhere", by:
"terminal"}`, which matches none of the offered options on purpose. The card then reads "Answered in
the terminal" rather than falling back to the raw option id. Every other resolved card still names
the option and who decided it.

A message sent into a shared session carries a `delivery` field. The bubble shows a quiet chip for
the two states that are not yet final: "waiting for the terminal" while the device holds the message
until the running turn ends, and "will be re-sent" when the CLI read it as mid-turn data. The device
replaces the block under its original `block_id` once the message lands, and the chip disappears.

A `terminal` session whose agent reports `attach` adds one secondary line under "Controlled by the
terminal", saying why this session cannot be driven from here:

| `attach` | `attach_ready` | Hint |
| --- | --- | --- |
| `channel` | `false` | "Start claude through the remote-control shim to control it from here" |
| `daemon` | `false` | "Start the Codex app-server daemon on this device to control it from here" |
| either | `true` | "This terminal session was started without the attachment; restart it to control it from here" |

The "Take over" button beside the hint follows the agent's `takeover` capability, in the composer
bar and in the status line alike.

## Responsive

At 1024 px and above the chat is two panes with the session sidebar. Below that the sidebar
collapses into the Sessions page, the chat runs full width with a back button, and the composer
sticks above the keyboard.

## Validation

The app was driven in a real Chrome against a real gateway, a real device daemon and the real CLIs:
login, devices, live pairing, the new-session drawer, a streaming Claude turn, an approval card
answered both ways, an `AskUserQuestion` card answered from the UI, Stop, reload from history,
truncated output expanded through `session.block`, a Codex session, a mirrored terminal session,
device-offline and gateway-restart recovery, and the 390 px layout. Details and screenshots are in
`docs/VALIDATION-APPS.md`.

The A11 shared-Codex surfaces were driven in headless Chrome against the mock gateway: the pickers
and the attachment button enabled by the two booleans, Stop on a shared turn, a send that steers the
running turn, a four-option approval answered with "Always allow commands like this" at 1280 px and
wrapping inside the card at 390 px, the "Answered in the terminal" card, and the daemon hint on a
terminal Codex session. "Interrupt & send" was driven separately and ends the terminal turn before
starting its own. The three send modes and the `bad_request` for an option the block never offered
were checked over the socket against the mock. Screenshots are not checked into the repository.

The approval path was re-verified on the fixed device daemon: the card stays pending until it is
answered, Allow writes the file, Deny leaves it absent, and both decisions are recorded against the
remote user.

## Not verified

Real Web Push delivery, speech to text (the gateway ran with `STT_PROVIDER=none`, so the mic is
hidden by design), and attachments. Two corners of the approval card are untried: the session-scoped
middle option, and an approval for a command rather than an edit — both cards answered here were
`Write`. `session.delete` and renaming a session exist in the protocol and the device implements
both, but the web UI has no entry point for either.
