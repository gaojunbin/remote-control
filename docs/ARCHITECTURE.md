# Architecture

How the four components fit together, what each one owns, and why the boundaries fall where they
do. `protocol/PROTOCOL.md` is the normative contract; this document explains the shape behind it.

## Components

| Component | Runs on | Owns |
| --- | --- | --- |
| `rc_gateway` | Your VPS, in Docker | Authentication, device enrollment, routing between apps and devices, the session index, a bounded replay buffer, the speech-to-text proxy, push, and serving the web app and the installer |
| `rc-client` | Every developer machine | Agent discovery, session lifecycle, the Claude and Codex adapters, terminal-session mirroring, the full event history, and the local `seq` counter |
| `web` | A browser | The four screens, live rendering of the block timeline, voice capture, Web Push |
| `ios` | An iPhone | The same four screens natively, on-device or gateway dictation, APNs |

Two WebSocket endpoints carry everything: `WS /ws/device` for daemons and `WS /ws/app` for apps.
Everything else is a small HTTP surface under `/api`, plus `GET /install.sh` and `GET /dist/*.whl`.

## Data flow

**Device to app.** The daemon assigns a `seq` to every session event, writes it to its own SQLite
history, and pushes it to the gateway as `session.event`. The gateway appends it to that session's
replay buffer, stamps `device_id` (amendment A5), and fans it out to the app connections subscribed
to that session. Nothing is stored beyond the buffer and the latest session summary.

**App to device.** An app sends a request such as `session.send` on `/ws/app`. The gateway resolves
the owning device, adds `device_id` when the request is addressed by `session_id`, stamps an opaque
`from` connection id, and forwards the frame unchanged. The device replies with the same `id` and
`from`, and the gateway routes the reply to that one connection. If the device is offline the
gateway answers `device_offline` itself; if no reply arrives within 60 s it answers `timeout`.

The gateway never inspects message text, tool input or tool output. It parses the envelope
(`type`, `id`, `device_id`, `session_id`, `seq`) and the `Session` summaries it indexes, and treats
the rest as opaque bytes bounded by size limits.

## Liveness: one clock, and what "offline" means

The gateway pings both socket types every 25 s and closes a connection silent for 90 s. That is the
only keepalive on the link: uvicorn's own protocol-level ping, which defaults to a 20 s interval
with a 20 s answer deadline, is switched off (amendment A13). Two clocks meant the shorter one won,
so a daemon whose event loop stalled — a large `git status`, a laptop resuming — was closed with
`1011` while still comfortably inside the 90 s the contract gives it.

A device link that drops is not an offline device. Mobile NAT, a suspended laptop and a daemon
restart are indistinguishable for the first few seconds, so the gateway keeps reporting
`online: true` for a **20 s grace period** and only reports `online: false` if no replacement
connection arrived. A reconnect inside the window produces no offline frame at all, and a request
addressed to the device in the meantime waits for the replacement rather than being refused;
it is answered `device_offline` only when the period ends without one. A close carrying `4401` or
`4403`, and an explicitly removed device, skip the grace and flip `online` at once: those say the
device is not coming back, not that the link went quiet. A second connection for the same device
still replaces the first with `4001`, unchanged.

Refused `/ws/device` upgrades are logged, at one line per source address per minute carrying how
many attempts it made in between. A daemon left behind by a wiped machine retries forever — one
gateway saw 685 refusals in three hours from a single address, all silent — and logging every one
would let any client fill the disk.

## The block timeline

Both adapters normalise their agent's output into one model, so the apps contain no agent-specific
rendering. A session is a list of **blocks**, each identified by a `block_id`:

| Kind | What it renders as |
| --- | --- |
| `user_message` | A message you or the terminal sent, with attachment names |
| `assistant_text` | Streamed Markdown, `delta` events appending until one arrives with `done` |
| `thinking` | The same streaming rule, collapsed behind "Thought for 12s" |
| `tool_call` | A one-line row that expands to input, output and diffs |
| `approval` | A card with the exact options the agent offered |
| `question` | A card with one or more questions, options, free text or secret input |
| `todos` | A snapshot that replaces the previous list |

Around them sit non-block events: `turn_started`, `turn_completed`, `status`, `meta`, `queue`,
`notice` and `error`. Three rules make this safe to render incrementally:

1. **A later event with the same `block_id` replaces the earlier one**, except streaming deltas,
   which append.
2. **Blocks sort by `first_seq ?? seq`** (amendment A8), so a tool call that finishes long after it
   started keeps the position where it appeared, live and after a reload.
3. **Only `status` and `turn_*` change a session's state.** No UI infers completion from silence.

Tool payloads are bounded inside events — input 8 KiB, output 16 KiB, patch 32 KiB, 64 KiB per
frame — with a `*_truncated` flag. The untruncated block is fetched on demand with `session.block`,
up to 1 MiB.

## Session ownership, replay and backfill

The **device is the source of truth**. It holds the complete history and the `seq` counter, and the
counter survives daemon restarts. The gateway holds two things: the latest `Session` summary per
session in SQLite, so lists render while a device is offline, and a replay buffer of the last 2000
events or 4 MiB per session, whichever is smaller.

An app reconnecting renders its cached list, receives `hello`, then subscribes with the last `seq`
it applied. If the buffer still covers that cursor it gets the missing events in the reply. If it
does not, the reply carries `resync: true` and the app pages history from the device with
`session.history`. The subscribe reply also carries the latest `queue` snapshot (amendment A6),
because a queue is current state rather than a timeline row.

When a **device** reconnects after an outage, the gateway compares each session's `last_seq` from
the device `hello` with the tail of its own buffer. Where the device is ahead, it asks the device
for `session.history {after_seq: <buffer tail>}`, appends what comes back to the buffer, and fans it
out to subscribers as ordinary events (amendment A9). Apps need no special handling: they apply
events by `block_id` as always. Only a session whose buffer still holds something is backfilled —
an empty buffer already answers `session.subscribe` with `resync`, and the app pages history for
itself. The requests run as background tasks so a slow device cannot hold up the reconnect.

Nothing on the way from a device frame to the apps touches the disk. A device socket reads and
dispatches one frame at a time, so anything awaited while handling a frame is latency every later
frame from that device inherits. The `last_seq` cursor an event advances is therefore recorded in
memory, before the fan-out, and written to SQLite by a background task that batches whatever has
accumulated; every read of the index overlays the values still unwritten, so the recorded cursor is
visible to `session.subscribe` and to `hello` the instant it is recorded and an app can never be
told it is up to date about an event it has not received. Losing the unwritten tail to a crash only
makes a reconnecting app resynchronise, which the protocol already handles. Routing a request from
an app to a device is answered from the in-memory owner map for the same reason: only the owning
device id is needed to forward, and reading the whole summary back from SQLite would put a disk
read in front of every message anyone sends.

## Terminal sessions: mirroring, attaching, takeover

A session you start yourself in a terminal is not invisible. Every ten seconds the daemon scans for
Claude transcripts under `~/.claude/projects` and Codex rollouts under the Codex home, tails the
ones that have grown, and translates their rows into the same block timeline. Growth is detected by
file size rather than modification time, because `claude --resume` touches mtime without appending.
Codex mirroring is the fallback rather than the rule: a thread the shared app-server daemon knows
about is read from the daemon instead, as the Codex subsection below describes.

Mirroring answers "what is happening". A parallel process scan answers "who may type", and that
answer is the session's `control` value. It is the only thing the apps consult to decide whether the
composer is live; `state` alone never decides it (amendment A7).

| `control` | Who owns the input | What an app can do |
| --- | --- | --- |
| `remote` | The daemon runs the agent for this session | Everything |
| `terminal` | A live CLI owns it and the device has no way in | Read only, plus "Take over" when the agent advertises `takeover` |
| `shared` | A live CLI owns it **and the device is attached to it** | Compose, approve and queue exactly as for `remote` |
| `none` | No process owns it | The next message resumes it and control becomes `remote` |

`terminal` and `shared` are the same CLI process seen with and without an attachment. `shared` is
what makes leaving the desk cheap: the terminal keeps running, and the phone joins it.

State follows from that. A `terminal` session reports `running` while its terminal turn is in flight
and `readonly` only when that turn is idle, and `readonly` means exactly this one situation. An
attached session never reports it: `shared` sits at `idle` between turns, like any session the
device runs itself.

### The channel bridge

Claude Code 2.1.267 and later can load a **channel**, an MCP server allowed to inject user messages
into the live interactive session and to relay its permission prompts. `rc-client channel` is that
server. The CLI spawns it over stdio; it reads `CLAUDE_CODE_SESSION_ID` and `CLAUDE_PROJECT_DIR`
from its own environment, dials the daemon's Unix socket at `RC_CLIENT_HOME/state/channel.sock`, and
registers the session it belongs to.

```
claude (your terminal)
  └── rc-client channel          stdio MCP server, spawned by the CLI
        └── state/channel.sock    newline-delimited JSON
              └── rc-client run    the daemon, which owns the gateway link
```

A Unix socket rather than a port, because a fixed port collides the first time two things want it.
A socket path is capped at 104 bytes, so a deep `RC_CLIENT_HOME` moves the socket into a short
per-user directory under `TMPDIR` instead. The bridge buffers what it cannot send and replays its registration frame on every reconnect, so a
daemon restart does not cost the attachment. It exits the moment stdin closes, because Claude Code
does not reap it and a survivor would hold the socket.

### Inject only when the session is idle

This is a hard rule, not a tuning choice. A message injected while a turn is running does not become
a prompt: the CLI wraps it as an attachment and tells the model, in as many words, that it is
untrusted external data that must not be obeyed. The same text injected while idle starts a turn in
about twenty milliseconds.

So the daemon holds anything it cannot inject immediately, and the timeline says so. A message sent
into a running shared session appears at once as a bubble with `delivery: "pending"` and a `queue`
entry. When the transcript shows the turn has ended, the daemon injects it and republishes the same
`block_id` with `delivery: "delivered"`. If the CLI absorbs an injection anyway the bubble becomes
`delivery: "absorbed"` and the device re-sends it once at the next idle point. The block is replaced,
never duplicated, and `first_seq` keeps it in the position where it first appeared (amendment A8).

Turn state comes from the transcript the daemon already tails, so it lags reality by up to a couple
of seconds. Every injected message carries the device's own id in the channel `meta`, which survives
verbatim into the transcript row, and that is how the daemon recognises its own echo rather than
mirroring it twice.

### The permission relay

Permission prompts raised inside the attached CLI arrive on the channel as `{request_id, tool_name,
description, input_preview}` and become ordinary `approval` blocks. Two things differ from an
approval the device raised itself. The options are exactly Allow and Deny, because the relay offers
no session-scoped grant. And the terminal dialog stays open the whole time, so the same request has
two answerers.

Whoever answers first wins, and a late answer from the other side is discarded silently. When the
terminal answers first the daemon learns it from the transcript — the tool ran, or it was refused —
and resolves the card with `decision.by: "terminal"`, which is how a decision made at the desk shows
up on the phone. If the CLI exits or the attachment drops with a request still open, the card
expires.

### The shim, and why the dangerous flag is unavoidable

Claude Code refuses to load a self-hosted channel without
`--dangerously-load-development-channels`. Every alternative was tried and refused: `--channels
server:rc` is rejected as not on the approved allowlist, the same for a locally packaged plugin
channel, and the project setting `channelsEnabled` is ignored outright. The one setting that could
allowlist a channel plugin, `allowedChannelPlugins`, is managed and exists only for Team and
Enterprise accounts, so a personal plan has no path to it.

That leaves wrapping the CLI. `rc-client shim install` writes a small `claude` wrapper at
`RC_CLIENT_HOME/bin/claude` and puts that directory first on your `PATH`. The wrapper appends the
channel flags **only** when stdin and stdout are both terminals and the command line carries none of
`-p`, `--print`, `--input-format`, `--output-format`, `--sdk-url`, `--mcp-config` or
`--dangerously-load-development-channels`; anything else execs the real binary untouched. That test
is what keeps the device's own remote sessions, which are driven over pipes, out of the attachment
path entirely, and it is why a script that pipes into `claude` behaves exactly as before.

The cost is one dialog. Claude Code shows a development-channels warning once per interactive
session, and it has to be answered before the session starts. There is no way to suppress it on a
personal account, and pretending otherwise in the installer would only make the first run confusing.

### Transitions, and what takeover is still for

Control moves `terminal → shared` when a bridge registers, `shared → terminal` when the bridge drops
while the CLI is still alive, and `shared → none` when the CLI exits. Each move travels as a `meta`
event carrying `control`, a `status` event when the state changed with it, and a republished session
summary — the same mechanism every other control change uses.

Takeover has not gone away; it has become the fallback. `session.takeover` is still Claude-only and
still works only while the terminal owns an *idle* session: the daemon sends SIGTERM to the exact
process it identified, confirms the release, and resumes the session itself. That kills whatever the
CLI was running, which is the whole reason attaching exists, so it is now for one case — a terminal
that was started without the shim and cannot be attached. On a `shared` session `session.takeover`
answers `conflict`. Codex advertises no `takeover` capability and needs none: a bare `codex` is
attached rather than taken over, and a Codex TUI that cannot be attached is released by quitting it.

### Codex on the shared app-server daemon

Claude needed a shim because the CLI has to be told to load our channel. Codex needs no shim at all.
Since 0.153 every **bare** `codex` TUI runs its conversation inside one local app-server daemon, and
that daemon accepts further clients. The device is simply one more of them.

The control socket is `$CODEX_HOME/app-server-control/app-server-control.sock`, and it speaks
JSON-RPC 2.0 over a WebSocket rather than newline-delimited JSON: a client opens an `AF_UNIX` stream,
writes an RFC 6455 upgrade request, expects `101 Switching Protocols`, and exchanges frames from
there. Compression has to be off: the daemon closes the connection outright when a client offers
`permessage-deflate`. Authentication is the socket's own permission bits. It is owner-only, so the
device has to run as the same user as the TUI, and nothing here is reachable from the network. The
device opens one connection per machine at startup and names itself `remote-control`, deliberately:
the first client to connect stamps its name on every thread the daemon holds, including the ones a
terminal user starts. Every server request is dispatched to its own task, so an approval nobody
answers cannot block the read loop.

A Codex thread is therefore joined rather than mirrored. `thread/loaded/list` is the set of live
threads and `thread/list` the history; `thread/resume {excludeTurns: true}` subscribes to one, and
`thread/turns/list` with `thread/items/list` backfills it. The daemon's `item/*` and `turn/*`
notifications map onto the block timeline one for one, so a shared Codex session has no rollout
tailing behind it. Threads the daemon marks `ephemeral` never become sessions: Codex spawns one per
turn purely to generate a title.

`origin` and `control` come from one table in `protocol/PROTOCOL.md` (4.4). A thread the device
created and no terminal has typed into is `remote` and `remote`. A thread that was already loaded
when the device found it, or that carries a message typed at a TUI, is `shared`, with origin
`terminal` when the device did not create it. A thread known only from the daemon's history is
`none`, and the next message resumes it. A rollout held by a Codex process that is *not* the daemon
is `terminal` and cannot be attached at all. `shared` is sticky, because the daemon emits nothing
when a TUI exits and the thread simply stays loaded.

A thread that disappears from `thread/list` altogether is a different matter: the device deletes the
session and emits `session.removed`, so a thread deleted with `codex delete` does not linger in the
apps. That check is guarded against a thread merely falling off the end of a page and against a
session a runner is still driving, because both would otherwise look like a deletion.

Above the daemon client, a shared Codex session is an ordinary session. It is a hub runner like any
other, so `session.send`, steering, the queue, `session.stop`, `session.set` and `session.answer` all
travel the same code a remote session uses; only the runner underneath differs.

### What each attachment carries

A shared Codex session can do everything a remote one can, which is much more than a Claude channel
can. Apps stay agent-agnostic by reading five optional fields on the agent object rather than
branching on the agent id:

| Agent | `attach` | `shared_interrupt` | `shared_settings` | `shared_attachments` |
| --- | --- | --- | --- | --- |
| Claude, through the channel shim | `channel` | false | false | false |
| Codex, through the app-server daemon | `daemon` | true | true | true |

`shared_interrupt` maps to `turn/interrupt`, which works whoever started the turn. `shared_settings`
maps to `thread/settings/update`, so changing the model, the permission mode or the reasoning effort
from a phone changes the thread for everyone attached to it. `shared_attachments` is true because the
daemon accepts image inputs. And because Codex advertises `steer`, a message sent into a running
shared turn joins that turn through `turn/steer` instead of waiting for it — the one thing an
attached Claude session cannot do. A10's `delivery: "pending"` chip therefore appears on a Codex
session only when the message was deliberately queued. `attach_ready` is set from a real handshake on the socket, not from the
socket file existing, because a stale socket is exactly the case the hint text exists for.

### Approvals are shared state, not a private modal

A permission request inside the daemon is a JSON-RPC *request*, and it fans out to every subscriber:
the TUI raises its dialog and the device receives the same request with the same item id. Either side
may answer, whoever answers first wins, and the loser's answer is discarded silently with no error.
The device turns the daemon's own `availableDecisions` into the block's options — `allow`,
`allow_session`, `allow_always`, `deny` — so a card offers exactly what that prompt allows rather
than a fixed pair. A10's "exactly Allow and Deny" was always a property of the Claude relay, which
has no session-scoped grant to offer.

When the answer came from somewhere else, the daemon broadcasts `serverRequest/resolved` carrying
only a request id: not who answered, and not what they chose. The device closes the block with the
reserved decision `{option_id: "elsewhere", by: "terminal"}`, which matches none of the options on
purpose, and both apps render it as "answered in the terminal". `session.approve` still accepts only
an option the block offered, `elsewhere` included in the refusal, so an app can never send it back.

### Without a daemon, and the one rule for users

If the socket is missing or does not answer, the device falls back to what it did before: one
`codex app-server` process per session, spawned by the device, with terminal Codex sessions mirrored
from their rollout files and left at `control: "terminal"`. The agent keeps reporting
`attach: "daemon"` with `attach_ready: false`, which is what the apps' existing hint is for. The
socket is re-probed on the ordinary ten-second scan, so bootstrapping the daemon later switches the
mode without restarting the device daemon.

There is no rule about how to start Codex. Some flags make the CLI spawn its own embedded
app-server, invisible to the shared daemon and unattachable — `-c` does on 0.154, verified on
2026-09-12 — and some do not: `--dangerously-bypass-approvals-and-sandbox` runs on the shared daemon
like a bare `codex`, verified the same day. The device does not keep a list of which is which. It
looks at what the process holds: a TUI writing its own rollout is the one holding a file under
`$CODEX_HOME/sessions` open, and only that kind is left out of the count of terminals attributed to
daemon threads. Such a session still appears, mirrored from its rollout with `control: "terminal"`.
Everything one would have set with `-c` can be set from the apps instead, through the pickers
`shared_settings` unlocks.

Codex's own `daemon bootstrap` reports `backend: "pid"`: it starts the app-server and a detached
updater loop, and installs no launchd job and no unit, so nothing brings it back after a reboot. The
device therefore supplies its own supervision — a launchd agent `dev.remote-control.codex-daemon` on
macOS, an `rc-codex-daemon.service` user unit on Linux — each running the idempotent
`codex app-server daemon start`. `rc-client codex setup` does the bootstrap and the supervision;
`rc-client codex status` verifies with a handshake rather than a file test. Details are in
[`docs/CLIENT.md`](CLIENT.md#codex-on-the-shared-daemon).

Four things about the daemon are known to be untested, and all four are properties of Codex rather
than of this project. What happens when a TUI and the device start a turn on the same thread within
milliseconds is unknown. Nothing observed unloads a thread, so whether there is an eviction policy
matters for a daemon that lives for weeks. Whether subscriptions survive a dropped socket, and
whether anything emitted while disconnected is replayable beyond re-reading history, was not
established — the device re-resumes and backfills on every reconnect precisely because it is not.
And the updater loop replaces the app-server under connected clients on a new release; what they see
during that swap was never observed.

## Speech to text

Voice is a stream, not an upload. The app opens `WS /ws/stt?language=…`, captures the microphone,
downsamples to 16 kHz mono PCM16LE, and sends roughly 100–200 ms binary frames. The gateway buffers
them, wraps the audio as WAV, and asks the configured backend for a partial transcript about every
two seconds, sending back `stt.partial`. On `stt.stop` it transcribes everything and returns
`stt.final` with the detected language, then closes the socket. Limits are 120 s and 4 MiB per
utterance.

The backend is anything that implements `POST {STT_BASE_URL}/audio/transcriptions`: OpenAI itself,
or a local Whisper server on the compose network so audio never leaves the VPS. iOS can skip the
gateway entirely and use `SFSpeechRecognizer` on-device instead, which is a different privacy story
and is labelled as such in its settings. Either way the transcript lands in the composer as an
editable draft; sending is always a separate, explicit action.

There is also a non-streaming `POST /api/stt/transcribe` for a recorded file. Both return `503`
when `STT_PROVIDER` is `none`.

## Push

The gateway watches for four things worth interrupting someone about: a session that needs approval,
one that needs an answer, a completed turn, and an error. It sends Web Push through VAPID and APNs
through an ES256 provider token, with a payload that names the device and the reason and nothing
else:

```json
{"rc": {"v": 1, "kind": "needs_approval", "device_id": "…", "session_id": "…",
        "device_name": "mac-studio-office", "title": "…"}}
```

A notification fires on an observed *transition* between two states, and only when no app is
actively watching that session. A session the gateway is seeing for the first time has no previous
state, so a device reconnecting with a hundred sessions produces no notifications at all.

Delivery is an outbound HTTP call to a browser vendor or to APNs, so it runs as a task rather than
inside the frame handler that noticed the transition: awaiting it there would hold every later
frame from that device behind a third party's response time. Whether an app is watching is decided
at the transition, not when the call goes out, so running late cannot change the outcome. A
shutdown gives the calls already in flight five seconds to finish before cancelling them.

## Storage

| Where | What |
| --- | --- |
| Gateway `DATA_DIR` | `auth.sqlite3` (issued login sessions), `devices.sqlite3` (devices, pairing codes — both hashed), `sessions.sqlite3` (the latest summary per session), `push.sqlite3` (Web Push subscriptions, APNs tokens, a delivery journal), `session_secret` and `vapid_private.pem`. The databases and both secrets are created at 0600 |
| Gateway memory | Live connections, the per-session replay buffer, and a cache in front of the login-session store that also holds the event which closes a socket the moment its session is signed out |
| Device `~/.rc-client` | `config.toml` at 0600 (gateway origin, device id, device token, name), `state/rc-client.sqlite3` (sessions, events, a key-value table, and request ids for `session.send` idempotency), `state/attachments/`, `state/channel.sock` and `state/claude-mcp.json` for the attachment, `bin/claude` when the shim is installed, `logs/` |
| iOS | The bearer token in the Keychain, device-only and never synchronised; a session list and per-session draft cache in Application Support, versioned separately from the wire protocol |
| Browser | The session cookie, plus whatever the service worker caches of the built app |

Schema changes on the gateway are additive only: `CREATE TABLE IF NOT EXISTS` cannot add a column to
a database that already exists, so each of the four stores declares the columns it has gained since
its first release and applies the missing ones every time it opens the file. An existing `DATA_DIR`
is migrated in place, which is what makes an image update over a live volume safe. Nothing is
dropped or renamed.

A login session is a row rather than a process-local entry, so a token issued before a restart is
still valid afterwards: the first request that presents it misses the memory cache, finds the row,
and hydrates an entry. Signing out writes a revocation that outlives the process, and expired or
revoked rows are pruned at startup.

## Design decisions

**Python for the gateway and the daemon.** The daemon's job is driving `claude-agent-sdk` and a
`codex app-server` subprocess, and the SDK is Python. Sharing the language across both backend
components means one set of conventions, one test style, and protocol code that can be read side by
side. The cost is a heavier runtime than a Go binary would need; for a service that spends its life
waiting on sockets, that has not mattered.

**The device owns history.** The alternative — a gateway that stores every event — would make the
VPS the thing you have to back up, secure and scale, and would put your prompts and tool output on
a machine that has no other reason to hold them. Keeping history on the device makes the gateway
disposable: wipe its volume and you lose sessions' *index*, not their content. The price is that
history is unavailable while a device is offline, and that the replay buffer has to be big enough to
cover ordinary reconnects.

**Opaque forwarding.** The gateway could parse events and serve rich queries. It deliberately does
not: every field it understands is a field it can leak, log or get wrong across versions. Parsing
only the envelope also means a protocol addition needs no gateway change.

**One block timeline instead of raw agent events.** Both agents emit their own event vocabulary, and
more agents are planned. Normalising on the device — where the agent-specific knowledge already
lives — keeps every UI free of agent-specific branches, and makes a third agent a device-side change
with no protocol change.

**PCM16 streaming for voice.** Sending a recorded file is simpler, but the wait after you stop
talking is exactly the moment the interaction feels slow. Streaming raw PCM lets partial transcripts
appear while you speak, and 16 kHz mono is what every speech backend wants anyway. The cost is an
AudioWorklet and a resampler in each client.

**No workspaces or profiles in v1.** A single password, a single user, one flat device list. The
`users` table exists so more accounts can be added later, but multi-user sharing, per-project
workspaces and role separation are deliberately absent: they would have doubled the auth surface
before the core interaction was proven.

## The contract

`protocol/PROTOCOL.md` is normative. It carries eleven amendments, all part of the frozen contract:

| Amendment | Ruling |
| --- | --- |
| A1 | The tool category is `tool_kind`; `kind` is only the event discriminator |
| A2 | `input_tokens`, `output_tokens` and `total_tokens` are required; cost and context are optional |
| A3 | Attachments are `{name, mime, size}` on events, `{name, mime, data_base64}` on `session.send` |
| A4 | Close codes: 4401 credential, 4403 forbidden, 4001 device connection replaced, 1008 protocol violation. 4001 is device-only; an app that sees it must not auto-reconnect |
| A5 | The gateway stamps `device_id` on `session.event` and `session.removed` frames to apps |
| A6 | The `session.subscribe` reply carries the latest `queue` snapshot |
| A7 | `readonly` means terminal-controlled *and* idle; the composer is gated on `control` |
| A8 | Blocks order by `first_seq ?? seq` |
| A9 | `session.history` accepts `after_seq`, and the gateway uses it to backfill after a device outage |
| A10 | `control: "shared"` for an attached terminal session; `user_message.delivery`; the relayed approval offers only Allow and Deny |
| A11 | Codex attaches through its shared app-server daemon; `shared_settings` and `shared_attachments` say what a shared session carries; a Codex approval offers the daemon's own options and ends as `elsewhere` when the terminal answered |

Every component's tests decode the fixtures in `protocol/fixtures/`, which is the cheapest way to
catch a diverged model before integration.
