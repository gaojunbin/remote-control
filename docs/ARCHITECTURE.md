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

## Terminal mirroring and takeover

A session you start yourself in a terminal is not invisible. Every ten seconds the daemon scans for
Claude transcripts under `~/.claude/projects` and Codex rollouts under the Codex home, tails the
ones that have grown, and translates their rows into the same block timeline. Growth is detected by
file size rather than modification time, because `claude --resume` touches mtime without appending.

A parallel process scan decides who owns the input. A live CLI process holding the transcript means
`control: "terminal"`: the composer is disabled and the apps show "Controlled by the terminal ·
Take over". When the CLI exits, `control` becomes `"none"` and the session is resumable — the next
message from an app resumes it through the SDK and control moves to `"remote"`.

`session.takeover` is for Claude only, and only while the terminal owns an idle session. The daemon
sends SIGTERM to the exact process it identified, confirms the release, and resumes the session
itself. During a live terminal turn it refuses with `conflict`. Codex advertises no `takeover`
capability, so the only way to release a Codex session is to quit the CLI.

Apps decide whether the composer is enabled from `control`, never from `state` alone (amendment A7):
a mirrored session reports `running` while its terminal turn is in flight and `readonly` only when
that turn is idle.

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

## Storage

| Where | What |
| --- | --- |
| Gateway `DATA_DIR` | `auth.sqlite3` (issued login sessions), `devices.sqlite3` (devices, pairing codes — both hashed), `sessions.sqlite3` (the latest summary per session), `push.sqlite3` (Web Push subscriptions, APNs tokens, a delivery journal), `session_secret` and `vapid_private.pem`. The databases and both secrets are created at 0600 |
| Gateway memory | Live connections, the per-session replay buffer, and a cache in front of the login-session store that also holds the event which closes a socket the moment its session is signed out |
| Device `~/.rc-client` | `config.toml` at 0600 (gateway origin, device id, device token, name), `state/rc-client.sqlite3` (sessions, events, a key-value table, and request ids for `session.send` idempotency), `state/attachments/`, `logs/` |
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

`protocol/PROTOCOL.md` is normative. It carries nine amendments, all part of the frozen contract:

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

Every component's tests decode the fixtures in `protocol/fixtures/`, which is the cheapest way to
catch a diverged model before integration.
