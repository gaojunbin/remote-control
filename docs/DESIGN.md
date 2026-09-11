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
default model, working directory with recent paths and a browser, and git status with an "Isolate in
worktree" toggle. One primary button, "Start session". The first prompt is typed in the chat, not
here.

**Chat.** A sidebar of sessions grouped by device, each device's Archive collapsed under it, a header
with the title, `device:path · branch`, a Todos chip, usage and elapsed time, and a Stop button. The timeline runs down the middle on the
page's own canvas. The composer sits at the bottom with model, permission mode and voice language
pickers on a row beneath it.

Below 1024 px the web sidebar collapses into the Sessions page and the chat runs full width, which
is the layout iOS uses natively.

## Session lists: by device, then by activity

Every session list follows one rule — the web Sessions page, the web chat sidebar and the iOS
`SessionsView`. The UI may differ; the logic may not. It reads only fields the session already
carries: `agent`, `control`, `state`, `archived`, `updated_at` and `title`.

**Devices are the outer grouping.** A device gets a group when at least one of its sessions passes
the current filters; a device with nothing to show is not drawn at all. Devices holding something
active come first, and each half of the list is ordered by its most recent activity. The header
carries the device name exactly as the device reported it, its online dot and a disclosure chevron.
Groups are expanded by default, collapse on a click or a tap, and the choice is kept per device id —
`localStorage` on the web, `UserDefaults` on iOS.

**Active** is what a CLI or the device still holds: `archived` is false and `control` is `remote`,
`terminal` or `shared`. Those rows come first inside the group, ordered `needs_approval` and
`needs_input`, then `running` and `starting`, then the rest, each by `updated_at` descending.

**Archive** is that device's own, captioned "Archive · N" and collapsed by default. It holds the
device's sessions whose `control` is `none` — the CLI exited and nothing owns them any more —
together with the ones the user archived by hand, flat and newest first. A hand-archived row is
marked "Archived", so the two halves stay apart. A device with nothing archived gets no sub-header.
The open or closed choice is kept per device id as well. A session that comes back to life — resumed
from a terminal, or written to from an app — leaves the Archive by itself: the device clears the
flag as the turn starts (A15), and the row is back among Active rows before its first output lands.

**A session that never held a message does not exist.** A CLI picks an id the moment it starts, and
if the person then resumes another session or quits, that id never gets a transcript. The device
removes it the instant the terminal leaves it (A16), so no list ever shows an "Untitled session" row
that nothing can open. When a terminal moves from one session to another with `/resume` or `/clear`,
the row that turns `shared` is the one the terminal is actually in, and the one it left drops to the
Archive by the rule above.

A non-empty search overrides both stored choices without writing either: it opens every device group
that still holds a match and every Archive a match landed in. Clearing the query hands the list back
to what was stored.

Archiving stays a row action and `session.archive` stays in the protocol, but the action is offered
on exactly one kind of row: a session the device is driving — `control: "remote"`, whoever created
it — that is not archived. Archiving it stops it, and the row moves to the Archive by the rule
above. A row a terminal holds (`terminal` or `shared`) offers no archive at all: the terminal owns
it, and it leaves Active by itself the moment the terminal exits. A row already in the Archive
offers nothing either, not even "unarchive" — writing to it, or a terminal coming back to it, is what
brings it back (A15). There is no global "show archived" switch: it only ever toggled the
hand-archived rows, which were already folded inside the collapsed Archive, so it read as a control
that did nothing.

**Agents are visible and filterable.** Every row carries a tinted agent chip on its meta line,
"Claude Code" or "Codex", falling back to the raw agent id. The Sessions page adds an agent filter —
`All · Claude Code · Codex`, offering only the agents actually present, defaulting to All and not
persisted. It applies before the grouping, so a device whose sessions it removes disappears with
them. The filter lives in the store both lists read, so the page and the chat sidebar never
disagree.

## Surfaces, rows and controls

The app is one canvas, not a stack of boxes. These rules hold on every screen in both apps.

- **Few edges.** Pages sit on the canvas. Sections are separated by spacing and type hierarchy. Where
  grouping is needed it is one soft surface: a background one step off the canvas, a 14–16 px radius,
  no border, and at most a very soft shadow. Never a border *and* a shadow *and* a divider on the
  same element.
- **Lists, not tables.** Device and session rows are list rows: no column rules, no header row, and
  no hairlines between rows. Every row has the same fixed height, so spacing alone separates them,
  and the pointer resting on a row tints it one step off the surface (`--hover`, below); the selected
  row holds a slightly stronger tint. Hairlines are for settings groups only.
- **Settings** read like macOS System Settings: a caption above each group, one soft surface with
  label-left / control-right rows inside, generous inset padding, and an explanation as a small
  footnote under the group rather than inside it. No fieldsets, no card inside a card.
- **Chips and badges** are text-only or tinted pills. Nothing is outlined. Status is an 8 px dot plus
  a word, coloured from the state palette below.
- **Type carries the hierarchy.** Row and section titles are 15–17 px semibold, meta is 12–13 px in
  the secondary ink, and a group caption or a form label is 12–13 px in the tertiary or secondary
  ink. Line height stays at or above 1.4.
- **Nothing is re-cased.** Group captions, section headings and form labels are sentence case, and a
  name the device reported — a device name above all — is printed exactly as it arrived. No
  `text-transform` anywhere in either app.
- **Buttons.** One filled primary per surface; everything else is quiet, either text or tinted. Icon
  buttons carry no border.
- **Tokens first.** A visual change starts in `web/src/styles/tokens.css` and the iOS equivalent, not
  in a component.

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

**Two levels of detail, and Simple is the default.** Most of what an agent does is not addressed to
the person reading, and a phone screen of `Read`, `Bash`, `Edit` rows buries the sentence that is.
The timeline therefore has a detail level, set in Settings and kept per app, never on the wire:

- **Simple** shows only what is written to the person: their own messages, the agent's prose, the
  approval and question cards (they need an answer), notices and errors, and the end-of-turn line
  when a turn stopped or failed. Thinking, every tool call, and the todos chip are not drawn at all —
  not collapsed, not summarised, not counted. The status line and the status dot are what say the
  agent is busy.
- **Detailed** is the timeline described above, everything included.

The switch takes effect on the open timeline at once, in both directions, without reloading
history. The jump-to-latest count counts rows the current level draws, so a burst of tool calls
does not read as "12 updates" to someone who has chosen not to see them. Approvals are never
hidden by either level.

## The composer

The composer never guesses. It always sends `mode: "auto"` and lets the device decide what that
means, then labels the button with the decision:

| Session state | Button | Status line |
| --- | --- | --- |
| Idle | Send | — |
| Running, agent supports steering | Send | "Codex is working · your message will steer the turn" |
| Running, agent does not | Queue | "Claude Code is working · your message will be queued" |
| Terminal-controlled | disabled | "Controlled by the terminal · take over to send" |
| Terminal, attached | Send | — (the header already says `terminal · attached`; the composer behaves as for a remote session) |
| Device offline | disabled | "Device offline" |

An **attached** terminal session is the one case where a live CLI and a live composer coexist. It
looks like an ordinary session on purpose: the same composer, the same approval cards, the same
queue. One thing always marks it: the header reads `terminal · attached`, the takeover bar is gone, and
"Take over" is not offered at all, because there is nothing to take over. Nothing above the composer
repeats what the header says; the status line is reserved for information the header lacks (a
running turn's steer/queue notice, "Controlled by the terminal", "Device offline").

Everything else about it depends on what the attachment can carry, and the device says so in three
booleans on the agent. Nothing in either app asks which agent it is looking at.

| Boolean | What it turns on | Claude channel | Codex daemon |
| --- | --- | --- | --- |
| `shared_interrupt` | Stop, and "Interrupt & send" | off | on |
| `shared_settings` | The model, permission-mode and effort pickers | off | on |
| `shared_attachments` | The attachment button, and pasted files | off | on |

A control the attachment cannot drive is hidden, not disabled with a caption: a Claude channel
session shows no attachment button and no model, permission or effort chips, and nothing explains
their absence in the composer. A shared Codex session shows every control, all live. The rule keeps
the composer to two rows (the field, then one row of controls) on the phone.

Steering follows the ordinary rule rather than a shared-session rule. Codex advertises `steer`, so a
message typed into a running shared Codex turn joins that turn: the status line reads "Codex is
working · your message will steer the turn" and the button stays "Send". Claude does not, so its
shared sessions keep "will be queued" and a "Queue" button. The delivery chips below are therefore a
Claude phenomenon in practice — a Codex message rarely waits.

A steered message is drawn where the agent reads it, not where it was sent. Codex takes a steer at
its next step, after the sentence it was already writing, and the terminal shows the prompt there;
the app shows the same order. Until the device reports the message taken, the bubble stays at the
bottom of the transcript as the optimistic row from sending, below whatever the turn is still
producing. A bubble that jumped up into the middle of an answer would put the reply before the
question.

A message sent into an attached session cannot always be delivered at once, so the bubble says where
it is. A quiet chip under the text reads "waiting for the terminal" while the device holds it until
the running turn ends, and "will be re-sent" if the CLI read it as mid-turn data. The chip
disappears when the message lands; the bubble itself is replaced in place, never duplicated.

A `terminal` session that *could* be attached gets one secondary line under "Controlled by the
terminal" saying why it is not: install the shim on that machine, start the Codex app-server daemon
on it, or restart this session through the attachment that is already there. The line is a hint, not
an error, and it sits at metadata weight.

**Interrupt & send** is always a separate, explicit action, never the default. **Stop** is separate
from Send and lives in the header, so no one stops a turn while reaching for the send button.
Queued messages are listed and can be removed one at a time. A send whose outcome is unknown shows
"Delivery unconfirmed" with a Retry that reuses the original request id, because a silent automatic
resend is how an agent gets told twice.

**Voice** dictates into the composer's own field rather than into a separate panel. Tapping the
mic starts listening on the web as on the phone — there is no hold-to-talk chord to learn: a waveform and an
elapsed timer take the control row, and there are exactly two buttons, Cancel (restores the draft
as it was) and Done (keeps the transcript). There is no time limit; listening runs until one of
them is tapped. On iOS a soft multi-colour glow runs around the edge of the whole display while
listening, the way Siri does, never around the field alone. The label says "Transcribing live ·
edit before sending", and it means it — the transcript is a draft you edit and send with the
ordinary Send button; no utterance is ever sent by the act of stopping the recording. The mic
disappears entirely when the gateway has no speech backend rather than failing when pressed.

**Composer layout** on the phone: the text field has a row to itself and grows with its content
up to eight lines, then scrolls inside; the `+`, mic and Send controls sit on the row below it.

## Approvals and questions

- The UI renders **exactly the options the agent supplied**, in the order given, with the agent's
  own labels. Option ids are opaque strings; nothing in the UI assumes `allow` or `deny` exists.
- Placement comes from `style`: `primary` reads as accept, `danger` as reject, `secondary` in
  between. Every approval carries at least one of the first two.
- A card whose status is `resolved` or `expired` becomes inactive and says who decided and what:
  "Auto-accept edits · decided by terminal". A decision made in the terminal shows up in the app,
  and the reverse.
- **A shared session's approval is shared state, not a private modal.** The terminal and every app
  see the same request and either can answer it. When the answer came from somewhere else the card
  says **"Answered in the terminal"** and names no option, because the device is told only that the
  request was resolved, never by whom or with what. That is the one resolved card without an option
  on it.
- The number of options is the agent's business too. A relayed Claude prompt offers Allow and Deny;
  a Codex prompt on the shared daemon can offer four — Allow, Allow for this session, Always allow
  commands like this, Deny — and they stack between the primary and the danger button, wrapping
  inside the card on a phone. Nothing in the UI counts them.
- Questions support several questions at once, single or multiple choice, free text, and secret
  fields. A secret field says "Value is not stored or logged".

## Reading position

Nothing moves under your eyes. The timeline auto-follows the newest content only while you are at
the bottom, and "at the bottom" is read from the real scroll position, never guessed from a
gesture. As soon as you scroll away a jump-to-latest control appears — the same round down-arrow
button in the corner above the composer on the phone and on the web, there whether or not anything
new has arrived, because paging up through history needs a way back down too — and new content is
counted on it, in blocks, not streaming deltas, so a long answer is one update rather than two
hundred. Tapping it returns to the tail and resumes following. Loading an earlier page prepends above the
current anchor. Content appended below never drags the viewport. On the phone, a tap anywhere
outside a text field puts the keyboard away without stealing the tap from a control.

## Status vocabulary

One word per state, the same word in both apps and in notifications.

| Session state | What the apps say |
| --- | --- |
| `starting` | "Starting the agent…" |
| `running` | "<agent> is working" |
| `needs_approval` | "Needs your approval" |
| `needs_input` | "Waiting for your answer" |
| `idle` | "Idle" |
| `stopped` | "Stopped" |
| `readonly` | "Controlled by the terminal" |
| `error` | "Errored" |
| device offline | "Device offline" |

One label comes from `control` rather than `state`: an attached terminal session reads **"terminal ·
attached"** and takes the same dot as a session the device runs itself, because from the user's side
it behaves the same way. `readonly` keeps its own label and stays reserved for a terminal session the
device cannot reach.

### The status dot

The dot is not the state. A turn that finished and a CLI that exited both report `idle`, so the tone
reads the session's `state`, its `control` owner and the device's `online` flag together. One pure
function owns the rule on each platform — `dotTone(state, control, online)` in
`web/src/components/dotTone.ts`, `DotTone.of(state:control:online:)` in RCCore on iOS — and both are unit
tested over the whole table.

| Tone | Looks | When |
| --- | --- | --- |
| `working` | green, pulsing | `starting`, `running` |
| `waiting` | amber, solid | `needs_approval`, `needs_input`: the agent is blocked on the user |
| `live` | green, solid | `idle` or `readonly` while `control` is `remote`, `terminal` or `shared` — the session is alive and quiet: a turn finished, a terminal is still open, or the device holds it |
| `off` | grey | `stopped`, or `idle` / `readonly` with `control: "none"` (the CLI exited and the session is resumable), or the device is offline whatever the state |
| `failed` | red, solid | `error` |

Only `working` animates, so a session blocked on the user never reads as a running one at a glance.
The pulse stops under Reduce Motion. The dot's accessibility label stays the state word and its
tooltip names the tone — "Working", "Waiting for you", "Live", "Off", "Failed". A device's own dot is
not a session dot and does not follow this table: it is green when the device is online and a grey
ring when it is not.

Colour is never the only signal: the dot always sits next to the word.

## Palette and type

Light theme only in v1. iOS defines dark values so the app stays legible when the system is dark,
but the design was not reviewed in dark mode.

| Token | Value | Used for |
| --- | --- | --- |
| Canvas | `#F5F5F4` | The page |
| Surface | `#FFFFFF` | Cards, sheets, rows |
| Line | `#E6E5E1` | Field and modal edges |
| Hairline | `rgba(17,17,17,.08)` | The one divider between two rows inside a settings group |
| Hover | `rgba(17,17,17,.04)` | A list row under the pointer; the selected row uses `.07` |
| Ink | `#111111` | Primary text, and the primary button fill |
| Ink secondary | `#6B6B6B` | Metadata |
| Running | `#22A06B` | Green status |
| Attention | `#B07C00` | Needs approval or input. Amber, not orange, and above 3:1 on every row background |
| Idle | `#B5B5B0` | Resting status |
| Danger | `#D23F31` | Errors and destructive actions |
| Diff add / remove | `#1F7A4D` / `#C23A2C` | Diff counts and gutters |

Type is the system UI face — Inter-like on the web, SF on iOS — with `ui-monospace` for paths,
commands, tool titles, code and pairing codes. Radii are 14–16 px on grouped surfaces, 12 px on
fields and sheets, and 999 px on pills. Shadows stay quiet: `0 1px 2px rgba(24,24,22,.04), 0 0 1px
rgba(24,24,22,.06)` lifts a grouped surface off the canvas in place of a border, and `0 24px 60px
rgba(0,0,0,.12)` carries a modal. Motion is 120–200 ms on a single easing curve, and disabled entirely
under `prefers-reduced-motion`.

Every user-visible string lives in one catalog per app — `web/src/strings.ts` and
`ios/App/Localizable.xcstrings` — so a second language never means touching a component.

## Deliberately not in v1

- **Dark mode as a designed theme.** The tokens exist on iOS; the design does not.
- **Multiple users, workspaces and sharing.** One password, one account, one flat device list.
- **A terminal emulator.** Mirroring a session, or attaching to one, is not the same as an SSH pane,
  and it is deliberately not one. You get the agent's conversation, not its screen. If you need a
  shell, use a shell.
- **File browsing and editing.** The directory picker exists to choose a working directory, nothing
  more.
- **A session-level search.** There is a session-list search, not a transcript search.
- **Deleting or renaming a session from an app.** The protocol and the device support both; neither
  app has a button that calls them. Archiving is there instead.
- **A true reading anchor across a relaunch.** The transcript keeps its scroll position across
  "Load earlier messages", but a resync or an app relaunch returns you to the bottom.
