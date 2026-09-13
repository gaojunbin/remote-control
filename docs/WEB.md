# The web app

A static single-page app the gateway serves from `web/dist`. React 19, Vite, TypeScript, and
hand-written CSS with no framework. It talks only to the gateway and follows
`protocol/PROTOCOL.md` exactly.

## Screens

| Route | What it does |
| --- | --- |
| `/` | Decides where an open lands and goes there; the `*` fallback does the same |
| `/login` | Username and password sign-in against the gateway, and **Create an account** when the gateway takes registrations (A24) |
| `/devices` | Device list with online state, agents, session counts and the client build; Rename, Update and Revoke on every row; **Add device** with the copyable one-liner, the pairing code, its expiry, live handshake steps, and the scan flow beside them |
| `/pair` | Claims the token a host printed as a QR code and shows the same handshake (A23) |
| `/sessions` | Every session across every device: one collapsible group per device, its active rows and then its own collapsed **Archive**, a search, an agent filter and a device filter, and **New session** in a right-hand drawer |
| `/sessions/:deviceId/:sessionId` | The chat: sidebar, timeline, composer, status line |
| `/settings` | Grouped settings — the account with its role, **Change password** for a member or **Users** for an admin, sign out, browser notifications, voice language and push-to-talk, and an About group with the gateway origin, both versions and the connection state |
| `/users` | The admin's accounts screen: the registration switch, one row per account, Reset password / Disable / Delete, and **Add user** (A24). A member who types it lands on Sessions |

## Commands

```sh
cd web && npm ci
```

| Command | What it does |
| --- | --- |
| `npm run dev` | Vite on 5173, proxying `/api` and `/ws` to `http://127.0.0.1:8787` |
| `npm run dev:mock` | The same server plus the bundled mock gateway. Sign in as `admin` / `dev`, or as the member `alice` / `devdevdev` |
| `npm run mock` | Only the mock gateway, on 8787 |
| `npm run build` | Typecheck, then build to `web/dist` |
| `npm test` | Vitest |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run lint` | ESLint, flat config |

`RC_GATEWAY=http://host:port npm run dev` points the proxy somewhere else.

## The mock gateway

`mock/server.ts` implements the app-facing half of the protocol — the HTTP API, `WS /ws/app` and
`WS /ws/stt` — so the whole UI can be developed with no gateway and no device. It ships two devices
and nineteen sessions covering running, needs-approval, needs-input, errored, idle,
terminal-controlled, shared through the Claude channel, shared through the Codex daemon, Codex,
terminal sessions on a device that has neither the shim nor the Codex daemon, three sessions whose
CLI exited (`control: "none"`) and two archived by hand, spread over both devices so every device
group has both halves of an Archive under it. Between them they show all five status-dot tones.
One of the two devices advertises all five agents (A25) — the three newest copied from
`protocol/fixtures/objects/agent.grok.json`, `agent.cursor.json` and `agent.pi.json`, which
`tests/agents.test.tsx` asserts they still equal — and carries one session of each: a Grok Build
session a terminal holds, the only new agent whose terminal sessions the device mirrors, a Cursor
session whose model card has no effort to show and a pi session with no permission picker at all.
Opening the running session plays a scripted turn: streamed thinking, streamed Markdown, tool rows
with a live output box, a failing shell run, two diffs, an approval and a question. Both drive the
turn to completion. The shared session plays the A10 path end to end: a send while the terminal is
idle is injected at once and answers with a relayed Allow/Deny approval, a send during that turn is
held as a queue entry and becomes a bubble only when the turn ends and the CLI takes it (A19), and
`session.set`, `session.stop` and `session.takeover` answer with the errors the contract specifies.
Its history also carries a question the terminal answered first, so the card reads "Answered in the
terminal" (A20). The shared
Codex session plays the A11 path: it opens on a turn the terminal started, a send steers that turn
(`accepted: "steered"`), `session.set` applies, Stop interrupts, and a command approval offers all
four daemon decisions. Its history carries a request the TUI answered first, so the card reads
"Answered in the terminal". A send is answered at once and the `user_message` echoed 400 ms later under the
request id, the way a device behaves (A12), so the pending bubble is visible in development; a
message queued during a turn is dequeued when that turn ends and keeps its id, while a turn the mock
starts itself, such as a `first_message`, mints its own block id and exercises the app's fallback.
It knows two accounts (A24), `admin` / `dev` and the member `alice` / `devdevdev`, with the
registration switch closed and the account routes of 3.9 behind the admin's role, so the sign-in
form, registration, the Users screen and a member's Settings can all be driven with no gateway. The
scripted devices are the admin's: a member's `hello`, device list and session list come back empty,
a subscribe naming one of the admin's sessions answers `not_found`, and a pushed frame reaches only
the account that owns the device it is about. Pairing walks
`waiting → enrolled → online → agents` over about six seconds, and the speech socket returns
scripted partials and a final transcript. One of the two devices runs the build `/api/config`
reports and the other the one before it, so the list shows a row with nothing to do beside a row
offering Update; `device.update` is accepted, flips that device to `updating`, and brings it back on
the new build six seconds later, which is what its `hello` would report (A22). Both halves of
pairing by scanning are there (A23): `POST /api/pairing/requests` mints a claim token for a host,
`POST /api/pairing/requests/{token}/claim` mints its pairing code and starts the same progress, and
the host's status poll answers at once instead of holding a connection for 25 s the way the gateway
does — nothing in the app polls it. The mock and the tests share
`mock/fixtures.ts`, so a fixture change shows up in both.

## Structure

```
src/
  protocol/    wire types and frame shapes
  lib/         HTTP client, app socket, formatters
  stores/      zustand stores; timeline.ts is the pure event -> renderable-items reducer
  components/  buttons, modal, drawer, popover, segmented control, status dots
  layout/      the signed-in shell, the mark, and the landing rule
  features/    login, devices, sessions, chat, voice, settings, users
  push/        Web Push subscription and service worker registration
  styles/      tokens.css and base.css
mock/          the mock gateway
tests/         vitest suites
```

Every user-visible string lives in `src/strings.ts`, the English table and the type; the Chinese
table beside it is `src/strings.zh-Hans.ts`.

## Language

The app speaks English or 中文 (`Settings → Language`), and it starts in English whatever
`navigator.language` says — a developer whose system is Chinese still reads the agent in English,
and a surprise translation at first launch reads as a different product. The choice is
`language` in the settings store, persisted with the other preferences under the signed-in
account's key (`rc.settings.<username>`, A24), and `<html lang>` follows it.

`strings` is not a table but a view on one: a proxy that reads `stringTables[language]` on every
property access, so a component keeps writing `strings.composer.send` and needs to know nothing.
Two things follow from that. A change re-renders every open screen because `App` carries the
language as the key of its `<Routes>` — the socket and the session probe sit above it and are not
disturbed. And **no module may read a string at import time**: a table built at module scope
(`const TABS = [{ label: strings.nav.devices }]`) keeps the language the app booted in.
`src/layout/AppLayout.tsx` and `src/lib/errors.ts` build theirs inside a function for exactly that
reason.

`src/strings.zh-Hans.ts` is typed `StringTable`, so the compiler refuses it the moment a key is
added or renamed in English, and `tests/language.test.tsx` walks both tables to catch a missing
nested key that the type cannot see. The functions in the table (`archiveGroup(n)`,
`status.workingQueued(agent)`, `format.minutesAgo(n)`) are translated as functions, which is how
"3m ago" becomes "3 分钟前" while the number stays where it is.

Only the app's own words are translated. Everything the device reported — agent output, device
names, paths, branches, and the model, effort, permission and speed **labels** the agent's own
lists carry — is drawn as it arrived, so the composer's chip reads "Sonnet 4.5 High" in both
languages. "Remote Control", "Claude Code" and "Codex" stay in Latin script, and each of the two
interface languages is always named in its own script.

## The icon

`public/icon.svg` is the source: a near-black rounded square with three white dots at the corners of
a downward-pointing triangle, joined by grey bars that stop short of the dots. It matches the iOS
`AppIcon` and `src/layout/Mark.tsx`, the same drawing inlined for the topbar, the login card and the
chat sidebar. `icon-192.png`, `icon-512.png` and `icon-maskable-512.png` are rendered from it in a
headless Chrome; the maskable one is full bleed with the artwork inside the central 80 %. Change the
SVG and re-render all three together.

## Voice

Tapping the mic opens `WS /ws/stt`, captures the microphone through an AudioWorklet with a
ScriptProcessor fallback, downsamples to 16 kHz mono PCM16LE, and sends roughly 120 ms binary
frames. Partial transcripts stream into the composer's own field. The control row then holds a
waveform, an elapsed timer and exactly one button, **Done**, which stands where Send stands, at
Send's size, and stays disabled until the socket is listening. Done sends `stt.stop`, waits for
`stt.final` and leaves the transcript in the field: nothing is ever sent by the act of stopping the
recording. There is no Cancel — a dictation you do not want is Done and then edited or cleared like
any draft — and no time limit; a long dictation is cut into segments whose transcripts are joined in
order. Typing takes the field back and stops listening, keeping the words recognised so far. The mic
is hidden entirely when the gateway reports `stt.enabled: false`.

## Push and the service worker

`public/sw.js` is registered in production builds only. It is network-first for navigations,
cache-first for hashed assets under `/assets/`, and bypasses `/api`, `/ws`, `/install.sh` and
`/dist/`. Only successful same-origin responses are cached, so a gateway error page never becomes
the offline shell. A push payload carries only a device name and a reason; clicking the notification
focuses an open tab or opens `/sessions/<device_id>/<session_id>`.

## Accounts

Every person on a gateway has an account (A24), and what an account sees is its own: its devices,
their sessions, its pairing codes. The app never has to filter anything — the gateway answers only
what belongs to the caller — so the work here is the three screens the amendment adds and one rule
about what this browser remembers.

**Signing in** asks for a username and a password. The username is remembered in `localStorage`
under `rc.username` and prefilled next time, so the second sign-in is the password alone;
`localStorage` is already scoped to the gateway's origin, which is what makes one key enough.
A `403` reads "This account is disabled." and a `401` says only that one of the two was wrong.
`LoginPage` is the only screen that reads `GET /api/health`'s `auth.registration_open`, because it
is the only screen that offers **Create an account**: the link appears under the button when the
gateway takes registrations, swaps the card for username, password and **Create account**, and puts
"Sign in instead" beneath it. Registering is a sign-in. A refusal is worded from its status —
`409` taken, `400` the username and password rules, `403` registration closed, which also removes
the link.

**The Account group in Settings** shows the username with its role word under it, then the one row
that account has: **Change password** for a member (a modal asking the current password and the new
one; `401` reads "That is not your current password.") or **Users** for an admin. `admin`'s password
is the gateway's `RC_PASSWORD`, so an admin is offered no password row at all. **Sign out** stays
last.

**`/users`** is `src/features/users/`, gated on the role in the auth store: a member who types the
address is sent to `/sessions` before anything is fetched. The registration switch sits at the top
and answers the tap before the `PATCH /api/registration` lands, going back if the gateway refuses
it. Each row carries the username, `role · state`, the device count and the last sign-in as a
relative time or "never"; the `admin` row is given no menu, because none of its three actions is
allowed. Delete names what goes with the account ("…and its 2 devices"). Every dialog surfaces its
own refusal, read from `error.code` by `userErrorText` in `src/lib/accountErrors.ts`; `conflict`
means a taken username on `POST /api/users` and a refusal to touch `admin` everywhere else, so each
caller supplies that one sentence. The list is `src/stores/users.ts`: nothing pushes accounts over
the socket, so it is whatever the last `GET /api/users` said, and every write re-reads it.

**The app's own preferences belong to the account, not to the browser.** The settings store
persists under `rc.settings.<username>`, and `readSettingsFor` in `src/stores/settings.ts` points
it at the signed-in account whenever that changes — `App` calls it from the auth store's username.
The persist `merge` lays the defaults under whatever was stored, so an account that has chosen
nothing reads the defaults rather than inheriting the last person's language, dictation language or
timeline detail. The login screen, where nobody is signed in, keeps its own key, `rc.settings`.
Nothing of this reaches the gateway.

## Behaviour worth knowing

- **Where an open lands** is `src/layout/Landing.tsx`, the element behind `/` and behind the `*`
  fallback. It waits for the devices store's `loaded` — the socket's `hello` snapshot calls
  `replaceAll`, which sets it — drawing the same `boot` placeholder `App` draws while the session
  probe is out, and then navigates with `replace` to `/sessions` when the account has at least one
  device and to `/devices` when it has none. Deciding before the snapshot has synced would send
  every account to Devices for the length of a round trip. The rule runs once per open because the
  navigation unmounts it: somebody who then opens Devices on an empty account stays there, and a
  device arriving later moves nobody. `LoginPage` returns to `/` when there is nothing to go back
  to, so signing in lands by the same rule; a remembered `from` still wins, fragment and all (A23).
- **The session list** is one selector, `selectSessionLayout` in `src/stores/sessions.ts`. It
  filters on the device, the agent and the search text, then returns one `DeviceGroup` per device
  that still has something to show: the device, whether the user folded it shut, its active rows,
  its own Archive and whether that Archive is open. Both the Sessions page and the chat sidebar
  render its result, so the two lists cannot drift; `tests/sessionLayout.test.ts` owns the rule.
  `docs/DESIGN.md` states it in full.
- **A session with no title still has a name.** `sessionTitle` in `src/strings.ts` turns an empty
  or blank `title` into "Untitled session", and the session row, the chat header, the chat sidebar
  and the search text `selectSessionLayout` matches on all read it, so an unnamed thread never
  draws a blank line above its meta and is still found by those words.
- **Collapse state** is two arrays of device ids in the settings store, `collapsedDevices` and
  `archiveExpanded`, persisted with the rest of the preferences under the signed-in account's key. Device groups
  are open by default, Archives shut. A non-empty search overrides both — it opens every device
  group and every Archive holding a match, without writing either array, and clearing the query
  hands the list back to what was stored. There is no global "show archived" toggle: it only ever
  toggled the hand-archived sessions, which the collapsed Archive already hid, so pressing it
  changed nothing on screen. Archiving a row still works and still calls `session.archive`.
- **The agent filter** is `agentFilter` in the sessions store rather than in a page, so the Sessions
  page and the chat sidebar always show the same slice. It is not persisted, and its options come
  from `selectAgents`, the agents the loaded sessions actually run. A25 turned it from a segmented
  control into a menu beside the device one: six segments reading `All · Claude Code · Codex ·
  Cursor · Grok Build · pi` were 27 px wider than a 400 px viewport, and a menu holds any number of
  agents at any width.
- **Five agents, and what an agent does not have is not drawn** (A25). `agentLabels` and
  `agentMarks` in `src/strings.ts` name the five and mark them — C, X, G, Cu and π — and an id
  neither table knows is drawn as itself with its own first letter as its mark. The mark is what
  the new-session form's agent control shows, because five names do not fit side by side; the line
  under the row names the one that is chosen, and each segment carries its agent's name as its
  accessible name and its tooltip. Everything else reads the agent's own lists and draws nothing
  for an empty one: no permission picker in the composer and no permission row in the form for an
  agent whose `permission_modes` is empty (pi), no effort slider and no effort word beside the
  model for one whose `efforts` is empty (Cursor), so its model card reads the model alone. Those
  three branches were already in `Composer.tsx` and `NewSessionDrawer.tsx` for the agents that
  list nothing; `tests/agents.test.tsx` is what holds them there. Agent chips share one quiet tint
  — the name tells them apart, never a colour per vendor.
- **Popovers and menus** render in a portal on `document.body` and are placed against the viewport,
  flipping to the other side when the one asked for cannot hold the panel and clamping to the
  window's edges. Anchoring them to the trigger instead let a rounded list surface or a scrolling
  pane clip them. The geometry is pure and tested in `src/components/popoverPlacement.ts`; the
  layout effect that applies it, follows an ancestor's scroll and closes on an outside click lives
  in `src/components/Popover.tsx`. Being a portal also makes the panel a sibling of any open modal
  or drawer overlay rather than a descendant, so it takes the top of the layering scale in
  `tokens.css` — `--z-sticky` 20, `--z-overlay` 60, `--z-popover` 100. With the panel below the
  overlay the device picker inside the New session drawer opened behind the drawer and looked dead.
  The drawer's own outside-click check compares the event target with the overlay element, so a
  click inside the portalled panel never closes it.
- **The model card** (A21) is one chip in the composer row reading "<model> <effort>", with a small
  lightning before it while `session.speed` is set. It opens a popover above the composer holding
  two rows. The first has the speed toggle at its leading edge — drawn only when `agent.speeds` is
  non-empty, lit while a tier is on, and cycling standard → each tier → standard through
  `session.set {speed}` where `null` is the standard speed — then the model name, the effort word
  and a chevron; tapping the name replaces the card's contents with the model list rather than
  stacking a second popover on it. The second row is the effort slider, one stop per `agent.efforts`
  entry. Three rules hold the card together:
  - **The word follows the thumb.** The live stop is the card's own state, so the word beside the
    model name changes as the thumb moves rather than when the device echoes the change. The value
    is only sent when the thumb is released: React's `onChange` fires on every step, so the commit
    listens for the DOM's own `change` on the element instead. A change from anywhere else — a
    `/model` in the terminal, another tab — wins over the position the thumb was left in.
  - **Dots and a thick track.** The pill is `.effort-track`, 28 px tall, filled to the thumb in the
    accent colour with one 6 px dot at every stop, and the thumb is a 24 px white disc. The native
    `<input type="range">` is kept for the interaction and the keyboard and drawn transparent on top
    of it, which every engine needs told separately (`::-webkit-slider-runnable-track`,
    `::-moz-range-track` and `::-moz-range-progress`). The fill and the dots live in a layer inset
    by 12 px, half a thumb, because that is the span the thumb's centre travels; `--fill` is the
    percentage across it. The lowest stop draws no fill at all — its rounded cap would be the only
    thing visible, peeking out around the white thumb.
  - **A width that never changes.** `SizedBox` in `src/features/chat/SizedBox.tsx` puts the visible
    label and every `models × efforts` pair (`labelPairs` in `modelLabels.ts`) in one grid cell, the
    alternatives `visibility: hidden` and `aria-hidden`, so the box takes the widest of them. The
    chip and the card's name row both use it, with the ghosts drawn exactly as the visible label is
    — including the lightning whenever the agent lists tiers — so nothing beside them moves while a
    level or a model is chosen. Measured, never guessed: a number would go stale the moment an agent
    renamed a model.
  The slider's accessible name is "Effort" and its `aria-valuetext` is the word, which a screen
  reader announces as "Effort, Extra high"; the toggle names itself "Speed, Fast" or "Speed,
  Standard". An agent with no efforts draws no slider, one with no tiers draws no toggle, and the
  permission-mode picker follows the card in the row. On a session a terminal holds (A17) the same
  chip is drawn as a static value with the tier appended to its label, and it opens nothing.
- **Every change made from the card is drawn at once.** `ChatPage`'s `onSetOption` applies the patch
  to the stored session before `session.set` leaves (`applyOptions` in
  `src/features/chat/sessionOptions.ts`), so the lightning fills, the word changes and the model
  name switches on the click. The reply replaces it; a refusal puts the previous session back and
  shows the error — unless something newer has already replaced what was written, which is a
  `session.updated` that arrived in between or the reply itself. The check compares the store's
  current object identity against the one that was written, not its fields, because the patch and
  the update can carry the same value.
- **Devices offer three actions and one of them is Update** (A22). Every row carries Rename, Update
  and Revoke, in that order, and shows the client version with the first eight characters of
  `client_build` under the hostname. `updateNotice` in `src/stores/devices.ts` is the one rule for
  what replaces that build: "Updating…" while `update_state` is `updating`, "Update failed ·
  <message>" for `failed`, and "Update available" when the device's build differs from
  `config.client.build` — the wheel the gateway serves, read once on boot with the rest of
  `/api/config`. Update confirms first, then sends `device.update {device_id, build}` with the
  gateway's build, never the row's. It is disabled with a title saying why while the device is
  offline, while an update is in flight, when the builds already match and when the gateway serves
  no wheel at all. A refusal the device sends back — a running session, a client installed from
  source — is not an `update_state`, so it is kept per device in the store's `updateErrors` and
  drawn in the same place until the gateway sends that device again.
- **Pairing has two ways in** (A23). The Add device modal mints a code as before and now carries a
  second block, "From your phone", with the one-liner the scan flow uses: the host asks the gateway
  for a claim token and prints it as a QR code encoding `<origin>/pair#<token>`. `/pair` claims that
  token with `POST /api/pairing/requests/{token}/claim` and then shows the handshake the modal
  shows, because the gateway mints the host an ordinary pairing code and `pairing.progress` follows
  it. Claiming spends the token, so `PairPage` claims once per token rather than once per mount — an
  effect that ran twice would answer its own first request with "This code was already used." Signed
  out, the login screen keeps the whole path including the fragment, since the token lives there;
  `rememberedDestination` accepts only a path inside the app.
- **Reconnect** backs off exponentially to 5 s, replies to `ping`, and treats 60 s of silence as a
  half-open socket. Subscriptions are re-issued with the latest `since_seq`.
- **Close codes** 4401 and 4403 end the session and return to login; every other code reconnects.
- **Sending** is always `mode: "auto"`; the device decides between send, steer and queue, and the
  button label follows that decision. "Interrupt & send" is a separate, explicit action.
- **A send shows up immediately** (amendment A12). The app mints the `session.send` request id, and
  the device echoes it as the `user_message` block id, so the composer clears and the bubble is in
  the timeline in the same tick as the click — no waiting for the round trip. It renders dimmed with
  a quiet "Sending…" chip until the device's event replaces it under the ordinary replacement rule,
  so nothing moves and nothing is duplicated. The pending rows live in `timeline.optimistic`, apart
  from the device's blocks: they carry no `seq`, never move the replay cursor, always sort last, and
  survive a resync. `accepted: "queued"` takes the row away again, because the queue row above the
  composer stands for the message until the device dequeues it under that id and it lands as an
  ordinary block; a refusal the gateway is certain about also takes the row away, and hands the
  draft back if nothing was typed since; a minute with no device event turns the chip into
  "Delivery unconfirmed". A device that still mints its own block id is reconciled by `text` and
  `source: "remote"` instead, one row per event.
- **While a question is pending the button reads Answer** (A20). The composer finds the pending
  `question` block with `selectPendingQuestion`, the status line stays "Waiting for your answer",
  and submitting sends `session.answer` rather than `session.send`: the draft becomes the free-text
  answer of the first question with no option selected, beside whatever the card holds for the
  others. Nothing is queued behind a question and no optimistic row is drawn, because an answer is
  not a message. The card's own working state lives in `src/stores/answers.ts`, keyed by
  `request_id`, so the card and the composer submit the same thing; the rules are pure in
  `src/features/chat/answering.ts`. Answer is disabled when the draft has nowhere to go — the first
  unanswered question refuses free text, or every question is already answered on the card, which is
  submitted from the card.
- **Uncertain delivery** is never resent automatically. The composer offers a Retry that reuses the
  original request id.
- **The composer is gated on `control`**, never on `state`.
- **A session's status dot** is toned by `dotTone(state, control, online)` in
  `src/components/dotTone.ts`, never by the state alone: a finished turn on a live session and a
  session whose CLI exited both report `idle`. The five tones and when each applies are the table in
  `docs/DESIGN.md`; `tests/dotTone.test.ts` walks all of it. `OnlineDot`, the device's own dot, is
  not part of it.
- **`control: "shared"`** (amendments A10 and A11) is a live terminal session the device is
  attached to. It behaves like `remote`: the composer, the queue and approvals all work. What the
  attachment cannot carry is hidden rather than disabled, and "Take over" never appears, because
  there is nothing to take over. Three optional agent booleans say what it carries —
  `shared_interrupt`, `shared_settings` and `shared_attachments` — each defaulting to false.
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

A control the attachment cannot drive is **hidden, not disabled with a reason**. Nothing above the
composer repeats what the header already says, so an attached session carries no bar of its own: the
status line is left to a running turn's steer or queue notice, "Controlled by the terminal · take
over to send" and "Device offline".

| Surface | Behaviour |
| --- | --- |
| Composer | Enabled, exactly as for `remote`. Send label and queue are unchanged |
| Take over | Never shown. The device is already attached |
| Stop | Shown only when the agent lists the `interrupt` capability **and** the device reports `shared_interrupt`. A Claude channel cannot interrupt, so Stop and "Interrupt & send" both disappear |
| Model / permission mode / effort | Shown when the agent reports `shared_settings`; otherwise not rendered at all, and nothing explains their absence. The title is always editable |
| Attachments | Shown when the agent reports `shared_attachments`; otherwise the button and its file input are not rendered, and pasted files are ignored quietly |
| Attached bar | None. The header's "terminal · attached" is the only place the attachment is named |
| Status label | "terminal · attached" in the sidebar and the Sessions list; the dot tones `shared` exactly like `remote` |
| Approvals | Whatever `options` the block carries. A Claude relay sends Allow and Deny; the Codex daemon sends up to four decisions |
| Questions | Answerable, exactly as for `remote` (A20). The composer's button reads Answer while one is pending, and a question the terminal answered first reads "Answered in the terminal" |

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

### Questions answered elsewhere

A question an attached Claude Code session asks exists twice at the same moment: as the CLI's own
dialog in the terminal, and as a card here (A20). Either can answer it and the first one wins. The
card is answerable on a `shared` session exactly as on a driven one, and a question the terminal
answered first resolves with `by: "terminal"`, which the card reads as "Answered in the terminal" —
the same wording an approval answered there uses. A resolved card shows what was answered: the
options that were picked, and the free text that was typed, unless the question was `secret`.

### A held message is a queue entry

A message sent into a shared session while its terminal turn is running is not a block at all
(A19). The device holds it, answers `accepted: "queued"`, and publishes a `queue` event naming it,
so the row from sending moves into "Up next" the way any queued message does. The `user_message`
appears only when the CLI takes it, with a `first_seq` after the output of the turn it waited for —
where the terminal draws it too. One `delivery` state is left on a bubble: "will be re-sent", for a
message the CLI read as mid-turn data. The device replaces the block under its original `block_id`
once the message lands, and the chip disappears.

A `terminal` session whose agent reports `attach` adds one secondary line under "Controlled by the
terminal", saying why this session cannot be driven from here:

| `attach` | `attach_ready` | Hint |
| --- | --- | --- |
| `channel` | `false` | "Start claude through the remote-control shim to control it from here" |
| `daemon` | `false` | "Start the Codex app-server daemon on this device to control it from here" |
| either | `true` | "This terminal session was started without the attachment; restart it to control it from here" |

The "Take over" button beside the hint follows the agent's `takeover` capability, in the composer
bar and in the status line alike, and so do the words. The disabled field says only "Controlled by
the terminal", whatever the agent is; the status line adds " · take over to send" where the button
is, and stops at "Controlled by the terminal" where there is nothing to press. Grok Build and Codex
advertise no `takeover`.

## Layout and styling

`src/styles/tokens.css` holds every colour, size, radius and shadow, and `src/components/ui.css`
the primitives built on them — `.surface` for a grouped list, `.group-title` for a plain caption
above one, `.group-head` for a caption that doubles as its disclosure control, `.label` for a form
field's label, `.pill`, `.badge`, `.agent-chip`, `.btn` and the status dots. A visual change belongs
in those two files before it belongs in a component. The rules they encode are in `docs/DESIGN.md`:
one canvas, soft surfaces instead of bordered boxes, list rows instead of tables, one hairline
between rows, one filled primary button per surface, tinted rather than outlined chips, and sentence
case everywhere — no `text-transform` in any stylesheet.

At 1024 px and above the chat is two panes with the session sidebar. Below that the sidebar
collapses into the Sessions page, the chat runs full width with a back button, and the composer
sticks above the keyboard. Every page was checked at 400 px.

## Validation

The app was driven in a real Chrome against a real gateway, a real device daemon and the real CLIs:
login, devices, live pairing, the new-session drawer, a streaming Claude turn, an approval card
answered both ways, an `AskUserQuestion` card answered from the UI, Stop, reload from history,
truncated output expanded through `session.block`, a Codex session, a mirrored terminal session,
device-offline and gateway-restart recovery, and the 390 px layout. Details and screenshots are in
`docs/VALIDATION-APPS.md`.

The A11 shared-Codex surfaces were driven in headless Chrome against the mock gateway: the pickers
and the attachment button shown by the two booleans and absent without them, no bar above the
composer on either shared agent, Stop on a shared turn, a send that steers the
running turn, a four-option approval answered with "Always allow commands like this" at 1280 px and
wrapping inside the card at 390 px, the "Answered in the terminal" card, and the daemon hint on a
terminal Codex session. "Interrupt & send" was driven separately and ends the terminal turn before
starting its own. The three send modes and the `bad_request` for an option the block never offered
were checked over the socket against the mock. Screenshots are not checked into the repository.

The approval path was re-verified on the fixed device daemon: the card stays pending until it is
answered, Allow writes the file, Deny leaves it absent, and both decisions are recorded against the
remote user.

The accounts of A24 were driven in the installed Chrome against the mock gateway: the sign-in card
with and without the registration link, the registration card and its three refusals, the Users
screen with its switch, its rows and its dialogs, a member's Settings and its password modal, a
member typing `/users` and landing on Sessions, and two accounts on one browser keeping their own
language and timeline detail. The dated entry is in `docs/VALIDATION-APPS.md`.

The A12 send path was driven in the installed Chrome against the mock gateway: the bubble is on
screen in the frame after the click, the device's echo replaces it in place on the idle, steered and
queued paths, and the queued message is dequeued under its own id with no second bubble. The dated
entry is in `docs/VALIDATION-APPS.md`.

The three agents of A25 were driven in the installed Chrome against the mock gateway at 1280 px and
400 px: the five marks in the new-session form's agent control, the form dropping its permission row
for pi and its effort row for Cursor, a pi composer with no permission picker, a Cursor model card
reading "Sonnet 4 Thinking" with no effort word, a terminal-held Grok Build session that reads but
does not write, and the agent filter as a menu naming all five. The dated entry is in
`docs/VALIDATION-APPS.md`.

## Not verified

Real Web Push delivery, speech to text (the gateway ran with `STT_PROVIDER=none`, so the mic is
hidden by design), and attachments. Two corners of the approval card are untried: the session-scoped
middle option, and an approval for a command rather than an edit — both cards answered here were
`Write`. `session.delete` and renaming a session exist in the protocol and the device implements
both, but the web UI has no entry point for either.
