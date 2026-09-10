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
and five sessions covering running, needs-approval, idle, terminal-controlled and Codex. Opening the
running session plays a scripted turn: streamed thinking, streamed Markdown, tool rows with a live
output box, a failing shell run, two diffs, an approval and a question. Answering both drives the
turn to completion. Pairing walks `waiting → enrolled → online → agents` over about six seconds, and
the speech socket returns scripted partials and a final transcript. The mock and the tests share
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
- **Long output folds** beyond 20 lines, and `output_truncated` adds "Open full output", which
  fetches the untruncated block.
- **Reading position** holds: the timeline auto-follows until you scroll away, then counts new
  blocks behind a "Back to latest" button.
- **Failed actions** surface in a dismissible banner above the composer rather than failing silently.

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

The approval path was re-verified on the fixed device daemon: the card stays pending until it is
answered, Allow writes the file, Deny leaves it absent, and both decisions are recorded against the
remote user.

## Not verified

Real Web Push delivery, speech to text (the gateway ran with `STT_PROVIDER=none`, so the mic is
hidden by design), and attachments. Two corners of the approval card are untried: the session-scoped
middle option, and an approval for a command rather than an edit — both cards answered here were
`Write`. `session.delete` and renaming a session exist in the protocol and the device implements
both, but the web UI has no entry point for either.
