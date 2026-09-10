# Interaction design

The product is for developers who live in the Claude Code and Codex terminal UIs and sometimes have
to leave the desk. The design target is an instant-messaging app, not a terminal in a browser: you
should be able to keep a session moving from a phone on a train, one thumb, no zooming.

Three prototype screens in `web-moke/` fixed the visual language before any code was written. The
web and iOS apps implement the same three, with the same vocabulary and the same rules.

## The three screens

**Add device.** A modal over the Devices list: one sentence of explanation, a macOS / Linux
segmented control, the full one-liner in a monospace block with a Copy button, and below it the
pairing code marked "single use" with its expiry countdown. A live checklist — gateway ready, device
handshake, detect installed agents — fills in as `pairing.progress` arrives, so the moment of "did
it work" needs no refresh. A "Manual install" link covers a host without `curl`.

The web app renders this as a centred modal; iOS renders it as a sheet (`AddDeviceSheet`). Both
drive the same four progress steps.

**New session.** A right-hand drawer on the web, a sheet on iOS. Fields in the order you decide
them: device (with its latency), agent as a segmented control showing the detected version and
default model, working directory with recent paths and a browser, git status with an "Isolate in
worktree" toggle, and an optional first message. One primary button, "Start session".

**Chat.** A sidebar of sessions grouped by device, a header with the title, `device:path · branch`,
a Todos chip, usage and elapsed time, and a Stop button. The timeline runs down the middle on the
page's own canvas. The composer sits at the bottom with model, permission mode and voice language
pickers on a row beneath it.

Below 1024 px the web sidebar collapses into the Sessions page and the chat runs full width, which
is the layout iOS uses natively.

## The timeline

The rendering rules follow the block model in `docs/ARCHITECTURE.md`. What matters visually:

- **User messages** sit in a light gray bubble aligned right on the web, full width on iOS.
- **Assistant text** is Markdown rendered directly onto the canvas, with no bubble. Code blocks get
  syntax highlighting and a copy button.
- **Thinking** collapses to one quiet row, "Thought for 12s", that expands.
- **Tool calls** are one line: an icon, the tool name in bold, a monospace one-line title such as
  `pytest -k refresh --count 20`, a result chip like `2 failed`, and a duration. Expanding shows
  input and output in monospace, folded beyond about twenty lines. A running tool shows a live
  output box instead of a duration. An edit shows `+12 −4` and expands to the diff.
- **Approvals and questions** are bordered cards with the agent's own options as buttons.
- **Todos** live in a header chip, `Todos 1/4`, with the list behind a popover, because a checklist
  that reprints itself in the transcript is noise.
- **Errors** are red, and a failed turn says so rather than simply stopping.

Nothing about this is agent-specific. A tool row for Codex and a tool row for Claude are the same
row.

## The composer

The composer never guesses. It always sends `mode: "auto"` and lets the device decide what that
means, then labels the button with the decision:

| Session state | Button | Status line |
| --- | --- | --- |
| Idle | Send | — |
| Running, agent supports steering | Send | "Codex is working · your message will steer the turn" |
| Running, agent does not | Queue | "Claude Code is working · your message will be queued" |
| Terminal-controlled | disabled | "Controlled by the terminal · take over to send" |
| Device offline | disabled | "Device offline" |

**Interrupt & send** is always a separate, explicit action, never the default. **Stop** is separate
from Send and lives in the header, so no one stops a turn while reaching for the send button.
Queued messages are listed and can be removed one at a time. A send whose outcome is unknown shows
"Delivery unconfirmed" with a Retry that reuses the original request id, because a silent automatic
resend is how an agent gets told twice.

**Voice** replaces the composer rather than sitting beside it. Tapping the mic, or holding
<kbd>⌥</kbd>+<kbd>Space</kbd> on a desktop, opens a panel with a waveform, an elapsed timer, the
live transcript, and two buttons: Cancel and "Stop & send". The label underneath says "Transcribing
live · edit before sending", and it means it — the transcript is a draft you can edit, and no
utterance is ever sent by the act of stopping the recording alone. The mic disappears entirely when
the gateway has no speech backend rather than failing when pressed.

## Approvals and questions

- The UI renders **exactly the options the agent supplied**, in the order given, with the agent's
  own labels. Option ids are opaque strings; nothing in the UI assumes `allow` or `deny` exists.
- Placement comes from `style`: `primary` reads as accept, `danger` as reject, `secondary` in
  between. Every approval carries at least one of the first two.
- A card whose status is `resolved` or `expired` becomes inactive and says who decided and what:
  "Auto-accept edits · decided by terminal". A decision made in the terminal shows up in the app,
  and the reverse.
- Questions support several questions at once, single or multiple choice, free text, and secret
  fields. A secret field says "Value is not stored or logged".

## Reading position

Nothing moves under your eyes. The timeline auto-follows the newest content only while you are at
the bottom. As soon as you scroll away it stops, and new content is counted behind a "Back to
latest" button — counted in blocks, not streaming deltas, so a long answer is one update rather than
two hundred. Loading an earlier page prepends above the current anchor. Content appended below never
drags the viewport.

## Status vocabulary

One word per state, the same word in both apps and in notifications.

| Session state | What the apps say | Dot |
| --- | --- | --- |
| `starting` | "Starting the agent…" | green |
| `running` | "<agent> is working" | green |
| `needs_approval` | "Needs your approval" | orange |
| `needs_input` | "Waiting for your answer" | orange |
| `idle` | "Idle" | gray |
| `stopped` | "Stopped" | gray |
| `readonly` | "Controlled by the terminal" | gray |
| `error` | "Errored" | red |
| device offline | "Device offline" | gray |

Colour is never the only signal: the dot always sits next to the word.

## Palette and type

Light theme only in v1. iOS defines dark values so the app stays legible when the system is dark,
but the design was not reviewed in dark mode.

| Token | Value | Used for |
| --- | --- | --- |
| Canvas | `#F5F5F4` | The page |
| Surface | `#FFFFFF` | Cards, sheets, rows |
| Line | `#E6E5E1` | Hairline borders |
| Ink | `#111111` | Primary text, and the primary button fill |
| Ink secondary | `#6B6B6B` | Metadata |
| Running | `#22A06B` | Green status |
| Attention | `#E0862B` | Needs approval or input |
| Idle | `#B5B5B0` | Resting status |
| Danger | `#D23F31` | Errors and destructive actions |
| Diff add / remove | `#1F7A4D` / `#C23A2C` | Diff counts and gutters |

Type is the system UI face — Inter-like on the web, SF on iOS — with `ui-monospace` for paths,
commands, tool titles, code and pairing codes. Radii are 12–16 px on cards and sheets and 999 px on
pills. Shadows stay quiet: `0 1px 2px rgba(0,0,0,.06)` for a raised surface, `0 24px 60px
rgba(0,0,0,.12)` for a modal. Motion is 120–200 ms on a single easing curve, and disabled entirely
under `prefers-reduced-motion`.

Every user-visible string lives in one catalog per app — `web/src/strings.ts` and
`ios/App/Localizable.xcstrings` — so a second language never means touching a component.

## Deliberately not in v1

- **Dark mode as a designed theme.** The tokens exist on iOS; the design does not.
- **Multiple users, workspaces and sharing.** One password, one account, one flat device list.
- **A terminal emulator.** Mirroring a session read-only is not the same as an SSH pane, and it is
  deliberately not one. If you need a shell, use a shell.
- **File browsing and editing.** The directory picker exists to choose a working directory, nothing
  more.
- **A session-level search.** There is a session-list search, not a transcript search.
- **Deleting or renaming a session from an app.** The protocol and the device support both; neither
  app has a button that calls them. Archiving is there instead.
- **A true reading anchor across a relaunch.** The transcript keeps its scroll position across
  "Load earlier messages", but a resync or an app relaunch returns you to the bottom.
