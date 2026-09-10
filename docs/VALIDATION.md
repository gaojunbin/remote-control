# Backend validation

End-to-end validation of the gateway and the device daemon against the real `claude` and `codex`
CLIs. Run on 2026-09-09/10, and re-run in full against the final tree after both component owners
applied their security reviews. It records what was exercised, what failed and was fixed, and what was
not covered. The last section is a smoke procedure a maintainer can repeat.

The web app and the iOS app are out of scope here; this validates the two backend components and
the wire protocol between them, driven by a scripted app that speaks `WS /ws/app` directly.

## Environment

| Component | Version |
| --- | --- |
| macOS | 27.0, arm64 |
| Claude Code CLI | 2.1.266 (`~/.local/bin/claude`) |
| Codex CLI | 0.153.4 (`/opt/homebrew/bin/codex`) |
| uv | 0.9.21, Python 3.12 |
| Docker / Compose | 29.4.0 / v5.1.2 |
| Node | 26.5.0 |

The gateway ran from source on `127.0.0.1:8791`. Port 8787 is the documented default but the web
app's mock gateway (`cd web && npm run mock`) also binds it, and on macOS a listener on
`127.0.0.1:8787` silently wins over one on `0.0.0.0:8787`. Use a free port when both may run.

## 1. Static conformance

| Command | Result |
| --- | --- |
| `cd protocol && uv run --with jsonschema python scripts/validate_fixtures.py` | pass, 125 fixtures, 21 negative cases, 0 problems |
| `cd gateway && uv run ruff check . && uv run ruff format --check . && uv run mypy && uv run pytest -q` | pass, 202 tests |
| `cd client && uv run ruff check . && uv run ruff format --check . && uv run mypy rc_client && uv run pytest -q` | pass, 227 tests, 2 skipped |
| `cd client && RC_REAL_AGENTS=1 uv run pytest -q tests/test_real_agents.py` | pass, 2 tests (one real turn per agent) |

## 2. Enrolment

`GET /install.sh` is byte-identical to `client/install.sh` with `__GATEWAY_ORIGIN__` replaced, and
`sh -n` accepts it. `POST /api/devices/pairing` produced `RC-XXXX-XXXX`; `rc-client enroll` redeemed
it and `rc-client run` connected. `config.toml` is `0600`; state lands in `state/rc-client.sqlite3`.

`pairing.progress` arrived in order — `waiting`, `enrolled`, `online`, `agents` — and the trailing
`device.updated` carried both agents:

| Agent | Version | Models | Permission modes | Efforts | Capabilities |
| --- | --- | --- | --- | --- | --- |
| claude | 2.1.266 | 4 | default, acceptEdits, plan, bypassPermissions | low…max | takeover, interrupt, queue, attachments, effort, history, worktree |
| codex | 0.153.4 | 6 | untrusted, on-request, never | low…ultra | interrupt, queue, steer, history, worktree |

`rc-client service install` was deliberately never run, so no launchd agent was registered.

## 3. Claude session flows

All against a scratch git repository, `permission_mode: "default"`.

| Flow | Result |
| --- | --- |
| `session.create` with `first_message` | `session.updated` → `turn_started` (`trigger: "remote"`) → streamed `assistant_text` deltas → final `done: true` → `turn_completed` with usage. 7 s end to end |
| Usage | `input_tokens`, `output_tokens`, `total_tokens` present as integers, plus `cost_usd`, `context_used`, `context_window` |
| Approval | "Create a file called hello.txt containing hi" → `approval` (`tool: "Write"`, `tool_kind: "write"`, options `allow`/`allow_session`/`deny`), state `needs_approval`, `session.approve allow` → `resolved` with `decision.by: "remote"` → file written → `turn_completed` |
| Stop | `session.stop` mid-turn → `turn_completed` with `stop_reason: "interrupted"` → `idle`; a second `session.stop` is a no-op |
| Queue | `session.send mode:"queue"` while running → `accepted: "queued"` with `queued_id`, `queue` snapshot, `Session.queued == 1`; after the turn the queued message launched a turn with `trigger: "queue"` and `user_message.source: "queue"`, then the queue drained |
| `session.set` | `permission_mode`, `model` and `title` round-trip; an unknown `permission_mode` is refused with `bad_request` |
| `session.subscribe` | without `since_seq` returns no events and `resync: false`; with `since_seq` returns only newer events; `since_seq: 0` replayed the whole buffer |
| `session.history` | ascending `seq`, one event per block, no deltas, no `status`/`meta`/`queue`, `assistant_text` all `done: true`; `before_seq` pages backwards |
| `session.block` | a `Bash` tool printing `seq 1 5000` was truncated to 16382 bytes with `output_truncated: true`; `session.block` returned the full 23892 bytes untruncated |
| `session.queue_remove`, `session.archive`, `session.delete` | all round-trip; delete emits `session.removed` and later requests get `not_found` |
| Idempotency | a repeated `session.send` with the same `id` returned the original result and delivered the prompt once |

## 4. Codex session flows

| Flow | Result |
| --- | --- |
| `session.create` (`permission_mode: "on-request"`) | thread started, real thread id returned, streamed reply, `turn_completed` |
| Usage | required integers present, **no `cost_usd`**, as PROTOCOL amendment A2 requires |
| Stop | `session.stop` mid-turn → `stop_reason: "interrupted"` → `idle` |
| Steering | `session.send mode:"auto"` while running returned `accepted: "steered"` (capability `steer`) |
| Approval | **not exercised live** — see gaps |
| Todos | **not exercised live** — see gaps |

## 5. Terminal mirroring

A real `claude` process started outside the daemon, in a scratch repository, with a clean
environment. The daemon discovered it within about 6 s.

| Step | Result |
| --- | --- |
| Discovery | session published with `origin: "terminal"`, `control: "terminal"`, `state: "readonly"` |
| Rows | `user_message` mirrored with `source: "terminal"`, followed by the assistant text |
| Send while the terminal owns it | refused with `conflict`, "controlled by terminal; take over first" |
| `session.takeover` during a live turn | refused with `conflict`, "the terminal process did not release the session" — correct, takeover is only for an idle terminal session |
| CLI exit | `control` moved to `none`, state `idle` |
| Resume | `session.send` was accepted, the device resumed the same session id, the turn completed, and `control` moved to `remote`. Control transitions observed: `terminal → terminal → terminal → none → none → remote → remote` |
| History | holds both the terminal turn and the remote turn |

Two environment notes for anyone repeating this. A CLI launched from inside another Claude Code
session inherits `CLAUDE_CODE_CHILD_SESSION`, which turns transcript saving off, and the transcript
is the only thing the mirror can read: strip `CLAUDE_*` from the child environment. A first run in a
new directory shows a project-trust dialog that must be answered before anything is written.

## 6. Restart resilience

| Step | Result |
| --- | --- |
| Kill `rc-client run` | `device.updated` with `online: false`; `session.send` answered `device_offline` by the gateway itself |
| Restart it | device back online, sessions restored with `last_seq` unchanged (193), the next turn continued at 194 with strictly increasing `seq` |
| Restart the gateway | the app reconnected, the SQLite session index survived, the device redialled on its own |
| `session.subscribe` after the restart | a cursor older than the emptied replay buffer returned `resync: true`; a cursor inside the new buffer returned `resync: false` with only the newer events |

A `claude` child orphaned by killing the daemon can hold the transcript open for one process scan,
so a restarted daemon may briefly report a remote session as `control: "terminal"`. It self-corrects
on the next scan; a client should not treat the first post-restart snapshot as final.

## 7. Docker stack

The stack ships no reverse proxy. `docker-compose.yml` defines one non-profile service, `gateway`,
published on `GATEWAY_BIND:GATEWAY_PORT`; TLS and the public hostname belong to whatever proxy the
operator runs. This section was re-run on 2026-09-10 after that change, with a scratch root `.env`
copied from `.env.example` and `PUBLIC_ORIGIN=http://127.0.0.1:18787`, `GATEWAY_PORT=18787`,
`GATEWAY_BIND=127.0.0.1`.

`docker compose config --services` printed exactly `gateway`; the `stt` service stayed behind its
`local-stt` profile and the only volume left is `rc-data`. `docker compose up -d --build` rebuilt
all three stages (`npm ci && npm run build`, `uv build`, the service) into `rc-gateway:latest`,
269 MB, and `docker compose ps` reported `healthy` with `127.0.0.1:18787->8787/tcp`.

| Check on the published port | Result |
| --- | --- |
| `GET /api/health` | `{"ok":true,"version":"0.1.0","protocol":1,"auth":{"mode":"password"},"devices_online":0}` |
| Security headers on that response | CSP, `x-content-type-options: nosniff`, `x-frame-options: DENY`, `referrer-policy: no-referrer`, `permissions-policy: … microphone=(self) …`, all from the gateway itself |
| `server:` response header | absent — uvicorn runs with `server_header=False` |
| `strict-transport-security` | absent, correctly: `PUBLIC_ORIGIN` is `http://` |
| `GET /` | 200, the built web app, `cache-control: no-store, must-revalidate` |
| `GET /index.html`, `/sw.js`, `/manifest.webmanifest` | 200 each, all `no-store, must-revalidate` |
| `WS /ws/app` with no credential | upgrade accepted (HTTP 101) through the published port, then closed `4401 unauthorized`, as PROTOCOL section 4 requires |

Teardown with `docker compose down -v` removed the container, the volume and the network; the
scratch `.env` was deleted afterwards.

The earlier pass had driven the served installer, a pairing redemption and one live Claude turn
through the bundled proxy. That proxy no longer exists and those rows were **not** repeated against
the published port, so they are not claimed here.

## 8. Amendments and device isolation

Every item below was exercised against a live gateway and daemon on the final tree, not only by
unit test. A second device was enrolled so one device could try to touch the other's session.

### A4 — WebSocket close codes

Both sockets now accept and then close, so the code reaches the client instead of being swallowed
by an HTTP 403 on the upgrade.

| Case | Close code |
| --- | --- |
| `/ws/app` with no credential | 4401 |
| `/ws/device` with an invalid token | 4401 |
| Session revoked on a live app socket | 4401 |

### A5 — `device_id` on forwarded session frames

Every `session.event` the gateway pushes carries the device identity derived from the socket, never
one the frame claimed. Confirmed on the source gateway, 8 of 8 events in a turn.

### A6 — queue snapshot on subscribe

A fresh app socket subscribing mid-turn received `queue: {pending: [{id, text, ts}]}` in the
`session.subscribe` reply, naming the queued message.

### A7 — `readonly` versus `running` for mirrored sessions

Sampling a mirrored session twice a second while a real `claude` process drove it:

```
state=readonly  control=terminal     the CLI holds the session, nothing streaming
state=running   control=terminal     the terminal turn is in flight
state=idle      control=none         the CLI exited, the session is resumable
```

### A8 — `first_seq` block ordering

Every block event carries `first_seq`, and a replacement keeps the value from where the block first
appeared: a `tool_call` first seen at seq 82 still reported `first_seq: 82` when it completed at 83,
and an `assistant_text` block updated at 85 and 86 kept `first_seq: 84`. `session.history` and
`session.block` carry it too, and history is still ascending by `seq`.

### A9 — backfill after a device link outage

`session.history` accepts `after_seq`, returns only newer events ascending, and refuses
`before_seq` and `after_seq` together with `bad_request`.

The real outage: a long turn was started, the daemon was killed with `SIGKILL` mid-turn (no clean
close), and the daemon was restarted. The app had last seen seq 101.

| Check | Result |
| --- | --- |
| Events delivered after the reconnect | seq 102 to 125, 24 events |
| Gaps | none, the range is contiguous |
| `device_id` on the backfilled frames | present (A5 holds for backfill too) |
| Index cursor | caught up to 125 |
| A fresh subscriber with `since_seq: 101` | got the same 24 events from the replay buffer, `resync: false` |
| The session afterwards | next turn completed normally |

### Device isolation

| Attack from a second enrolled device | Result |
| --- | --- |
| Announce the victim's session under its own `device_id` | owner unchanged, summary not overwritten |
| Inject an `assistant_text` event at `seq: 9000000` | never delivered to any app, stored cursor unmoved |
| `session.removed` for the victim's session | ignored, the session survives |
| Race three forged `reply` frames using the `from` id it learned by being addressed once | the app received only the owning device's reply, the real turn completed |
| The owning device afterwards | unaffected, next turn completed |

### Upgrade path

The current gateway was started against a `DATA_DIR` created earlier in this session, before the
`redeemed_at` column and the persistent login store existed:

| Check | Result |
| --- | --- |
| `pairing_codes` columns before | `code_hash, username, created_at, expires_at` |
| after start | `redeemed_at` added in place |
| `POST /api/devices/enroll` | 200 |
| Re-redeeming the same code | 409 |
| 86 pre-existing sessions | still listed |
| `auth.sqlite3` | created alongside |
| `session_secret` | reused unchanged, so existing tokens stay valid |

### Persistent login sessions

A token issued before a gateway restart still authenticated afterwards (200), and `POST /api/logout`
still revoked it (subsequent request 401). A restart no longer signs every client out.

## 9. Defects found and fixed

Cross-component defects found by this pass. Each was fixed in the component that violates the
contract, with a test. Two further defects found late in the run were handed to the component owners
and are fixed on this tree: the missing `redeemed_at` migration (see the upgrade path above) and
A4 close codes being swallowed by a pre-accept close.

### Gateway

1. **`POST /api/login` required an `Origin` header** (`rc_gateway/security.py`,
   `rc_gateway/routes/session_routes.py`). PROTOCOL section 2 scopes the Origin rule to
   cookie-authenticated mutations; login is where a native app obtains its bearer token, and it sends
   no Origin, so the iOS app could never log in. `reject_foreign_origin` now enforces the rule only
   when the header is present, which still blocks login CSRF from a browser. Test:
   `test_login_without_an_origin_is_allowed`.
2. **The wheel alias carried no filename** (`rc_gateway/routes/static_routes.py`). `pip install
   <url>` parses a wheel's Python, ABI and platform tags out of the filename, and
   `rc_client-latest.whl` has none, so `uv pip install` refused it outright. The response now sets
   `Content-Disposition` naming the real wheel. Test extended in
   `test_the_wheel_alias_resolves_to_the_newest_build`.

### Client

3. **`session.create` returned a placeholder session id** (`rc_client/sessions/hub.py`,
   `rc_client/agents/claude/adapter.py`). The hub minted `pending-<uuid>` and swapped it for
   Claude's own id mid-turn, so an app that subscribed with the id it got back stopped receiving
   events. Claude only reports its id after the first prompt, but it accepts one: the hub now names
   the session up front and passes it to the SDK as `session_id`, which the CLI honours exactly.
   Resume still passes `resume` alone. Tests:
   `test_create_names_the_session_before_the_agent_starts`,
   `test_claude_options_name_a_new_session_but_not_a_resumed_one`.
4. **A queued message started a turn with `trigger: "remote"`** (`rc_client/agents/base.py`, both
   adapters, `rc_client/sessions/hub.py`). Both adapters hard-coded the source, so PROTOCOL section 4's
   `queue` trigger and `user_message.source: "queue"` never appeared and a UI could not tell a queued
   turn from a typed one. `send()` now takes `source`. Test extended in
   `test_send_while_running_queues_and_launches_at_the_turn_end`.
5. **`session.history` was ordered by first appearance** (`rc_client/registry.py`). PROTOCOL
   section 8 and the protocol validator both require ascending `seq`, and a page whose `seq` went
   backwards would be rejected by the canonical checker. History now orders by the event's own `seq`
   with a matching index; `before_seq` pages against the same column. Test renamed and rewritten as
   `test_history_returns_the_latest_event_per_block_ascending_by_seq`.
6. **Codex echoed every remote prompt back as a second `user_message`**
   (`rc_client/agents/codex/translate.py`, `rc_client/agents/codex/adapter.py`). The live app-server
   stream replays the prompt the daemon just sent, and it was mirrored with `source: "terminal"`, so
   every message appeared twice in the timeline. A dead `skip_user_item` hook was meant to prevent
   this and was never called. The translator now takes `mirror_user_messages`, false on the live
   stream and true for rollouts read from disk. Test:
   `test_the_live_stream_does_not_mirror_prompts_the_daemon_sent`.

Two further hardening changes:

7. **`session.set` accepted any value** (`rc_client/sessions/hub.py`). An unknown `permission_mode`
   was stored and would break the next CLI start. `_check_choices` now validates `permission_mode`
   and `effort` against what the device advertises in `AgentInfo` and replies `bad_request`. `model`
   stays open because model ids are the agents' own and the detected catalogue can lag a release.
   Test: `test_settings_outside_the_advertised_choices_are_refused`.
8. **The served installer refused its own origin** (`client/install.sh`). The gateway replaces every
   occurrence of the placeholder, including the one in the guard that detects an unsubstituted
   script, so the guard turned into "reject the configured origin" and the one-line install always
   failed. The guard now checks for a URL scheme instead, which still refuses an unsubstituted copy.
   The script also downloads the wheel with `curl -O -J` and installs the saved file, because of
   defect 2. Test: `test_the_served_install_script_accepts_its_own_origin`.

## 10. Not verified

- **Codex approvals, live.** This machine's `~/.codex/config.toml` sets `sandbox_mode =
  workspace-write` with network access enabled and trusts `/Users/junbingao`, so writes under the
  workspace, writes under `/tmp`, writes under `$HOME` and network calls all ran unattended and
  nothing ever escalated. Changing the developer's Codex configuration to force an escalation was out
  of scope. The handler had no test at all, so `client/tests/test_codex_approvals.py` now drives
  `CodexRunner._on_request` directly for all three approval methods, covering the pending and
  resolved cards, the option styles, the state transitions and the JSON-RPC response for accept,
  decline and session-scoped grants.
- **`todos` events, live.** Codex produced no plan for two different prompts, and Claude reported that
  `TodoWrite` is not in the tool list of an SDK-driven session, so no `todos` event was ever emitted
  through the stack. Both translations are unit-tested
  (`test_plan_updates_become_a_todos_snapshot`, `test_todowrite_becomes_a_todos_snapshot_not_a_tool_row`).
  Whether a remote Claude session should expose the todo tool is a client decision, probably via
  `setting_sources`, and is left to the client owner.
- **`session.answer` and the `question` event.** No prompt in this run triggered `AskUserQuestion`.
- **Attachments** on `session.send`.
- **Interactive TUI mirroring.** The mirror was proven with a real `claude` process, but driving the
  interactive TUI from a pseudo-terminal did not work on this machine: the project-trust dialog and a
  `/rc` slash-command plugin that is still connecting swallow the first keystrokes. Nothing suggests a
  product defect, only that this is a poor test harness.
- **`session.takeover` succeeding.** It was correctly refused during a live terminal turn; the
  accepting path needs an idle interactive CLI, which the harness above could not hold open.
- **Speech to text.** `STT_PROVIDER=none` throughout, so `/api/stt/transcribe` and `WS /ws/stt`
  returned 503 as designed but were never driven against a backend. The `local-stt` compose profile
  was not started.
- **Push delivery.** `/api/push/web/vapid` returns 503 without `WEB_PUSH_CONTACT`; no Web Push or
  APNs message was delivered to a real endpoint.
- **Linux.** Only macOS was exercised; the systemd unit and `service install` were never run.
- **`rc-client service install`.** Deliberately skipped so this machine gets no launchd agent.
- **TLS and the reverse proxy.** Everything ran over plain HTTP on the published port. No proxy
  terminated TLS in front of the gateway, so HSTS, the Nginx Proxy Manager recipe in
  `docs/DEPLOY.md` and `TRUSTED_PROXIES` against a non-loopback proxy are untested end to end.

## 11. Observations, not defects

- `HEAD` returns 405 on every route, including `/api/health` and `/dist/…`, because the routes are
  declared `GET`-only. `GET` is unaffected and the protocol requires no `HEAD`, but health checkers
  and CDNs often use it.
- A request naming an unknown `device_id` is answered `device_offline` rather than `not_found`.
- The `readonly` versus `running` question raised by this pass was settled by amendment A7 and the
  device now matches it. Verified in section 8.
- `latency_ms` is `null` until the first ping round trip completes, about 25 s after a device
  connects.
- A mirrored session reports `readonly` during a long *silent* tool call, because the mirror infers
  activity from transcript rows arriving and a `sleep 45` writes nothing. A7 is satisfied whenever
  the turn produces output; a quiet tool call is indistinguishable from an idle session to a file
  tail. Worth knowing before someone reads it as a bug.
- The daemon's process scan runs about every 10 s, so a very short terminal session can finish
  before the mirror ever sees the CLI holding it; the session then appears directly as
  `control: "none"`. Only the live-control window is missed, never the timeline.
- Streaming delta events carry `first_seq` as well as `delta` and `done`. That is A8 behaving as
  written; apps ordering by `first_seq ?? seq` get the same answer either way.
- Both components were edited by their owners between the first pass and this one. Everything in
  this document was re-run against the final tree; earlier results were discarded rather than
  carried forward.

## Smoke procedure

Roughly fifteen minutes, one short turn per agent.

```sh
# 1. Static checks
cd protocol && uv run --with jsonschema python scripts/validate_fixtures.py
cd ../gateway && uv run ruff check . && uv run mypy && uv run pytest -q
cd ../client && uv run ruff check . && uv run mypy rc_client && uv run pytest -q

# 2. Gateway from source, on a port the web mock does not use
cd ../gateway
PUBLIC_ORIGIN=http://127.0.0.1:8791 RC_PASSWORD=devpassword \
  DATA_DIR=/tmp/rc-smoke-data RC_HOST=127.0.0.1 RC_PORT=8791 uv run rc-gateway

# 3. Pair this machine, in a second shell
curl -s -X POST http://127.0.0.1:8791/api/login \
     -H 'content-type: application/json' -d '{"password":"devpassword"}'   # -> token
curl -s -X POST http://127.0.0.1:8791/api/devices/pairing \
     -H "Authorization: Bearer $TOKEN"                                     # -> RC-XXXX-XXXX
cd client
export RC_CLIENT_HOME=/tmp/rc-smoke-home
uv run rc-client enroll --gateway http://127.0.0.1:8791 --pair RC-XXXX-XXXX
uv run rc-client run
```

Then, as an app on `WS /ws/app` with `Authorization: Bearer <token>`:

1. `session.create` (`agent: "claude"`, a git repository as `cwd`, `permission_mode: "default"`,
   `first_message: "Reply with exactly OK"`). Check the returned `session_id` is a UUID and **not**
   `pending-…`, then `session.subscribe` with `since_seq: 0` and confirm `turn_started`, streamed
   `assistant_text`, and `turn_completed` with usage.
2. `session.send` "Create a file called hello.txt containing hi" → an `approval` card →
   `session.approve` with the `primary` option → the file exists → `turn_completed`.
3. `session.send` a long task, then `session.stop` → `stop_reason: "interrupted"`.
4. `session.send` while running with `mode: "queue"` → `queue` snapshot → after the turn a
   `turn_started` with `trigger: "queue"`.
5. `session.history` → ascending `seq`, no `status`/`meta`/`queue`, no deltas.
6. Repeat 1 and 3 for `agent: "codex"` and confirm `usage` has no `cost_usd`.
7. Mirroring: run `claude -p "Reply with exactly OK"` in another repository, with `CLAUDE_*` stripped
   from the environment. A session appears with `origin: "terminal"`; a `session.send` while it runs
   is refused with `conflict`; after it exits `control` is `none` and a `session.send` resumes it.
8. Restart `rc-client run`, confirm the sessions return with `seq` unbroken. Restart the gateway,
   confirm the device redials and `session.subscribe` with a stale cursor reports `resync: true`.
9. Backfill (A9): start a long turn, `kill -9` the daemon mid-turn, restart it, and confirm the
   subscribed app receives the missing events contiguously after the reconnect.
10. Upgrade path: start the gateway against a `DATA_DIR` from an older build and enrol a device.
    A fresh volume hides every schema regression, so this is the check that matters before a
    release. Keep a copy of an old `DATA_DIR` for it.
11. Docker: write a root `.env` with `PUBLIC_ORIGIN=http://127.0.0.1:18787`, `GATEWAY_PORT=18787`,
    `GATEWAY_BIND=127.0.0.1` and an `RC_PASSWORD`, then `docker compose build && docker compose up
    -d`. Check `GET http://127.0.0.1:18787/api/health` and its security headers, install a device
    with the one-liner from `POST /api/devices/pairing`, run one turn, then `docker compose down -v`
    and delete the `.env`.
