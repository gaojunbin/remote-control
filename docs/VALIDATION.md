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

### Pairing by scanning, 2026-09-13 (A23)

Driven against a stand-in gateway on `127.0.0.1:8992` that implements only the three A23 routes,
with `RC_CLIENT_HOME` and `HOME` pointed at a scratch directory. `rc-client enroll --gateway … --scan`
posted `/api/pairing/requests`, printed the claim URL as a QR code with the URL itself on the line
below it, said it was waiting, and long-polled `/api/pairing/requests/{token}`. The first poll was
still open when the stand-in marked the token claimed; the host took the `RC-7K42-QX9M` it returned,
enrolled with it through the ordinary `/api/devices/enroll` path and exited 0.

**The printed code scans.** The 16 block rows the host wrote were mapped back to pixels the way a
terminal draws them — a block character is the foreground colour, so light on a dark terminal and
dark on a light one — and handed to Apple's `VNDetectBarcodesRequest`, the same detector the iOS
camera uses. It read `http://127.0.0.1:8992/pair#7ZK3M9Q2X5H8B1V4N6P0R2T4W6` from both polarities.
No phone was pointed at a real screen, so glare, focus and terminal fonts are still unproven.

At error-correction L with a one-module quiet zone the code is 31 columns by 16 rows for a claim URL,
which fits any terminal without wrapping; `tests/test_pairing.py` pins that size, since a wrapped QR
code is an unreadable one. The unit tests cover the states the live pass did not reach: a poll that
waits before it is claimed, `410` and `404`, the rate limit, and giving up at ten minutes.

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
environment, and **without** the shim, so the daemon could only mirror it. The daemon discovered it
within about 6 s. Section 6 covers the same situation with the shim installed, where the device
attaches instead and the composer stays live.

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

Both refusals above apply to `control: "terminal"`, a CLI the device has no way into. Taking over is
no longer the only route to a live composer: a session started through the shim reports
`control: "shared"` and accepts messages and approvals directly.

### What the terminal chose, 2026-09-12 (A17)

A terminal-held session cannot be retuned from an app, so the device reads the settings in force out
of the transcript and publishes each change as `meta`. Three row shapes carry them, read from Claude
Code 2.1.268 on this Mac:

| Value | Row | Written |
| --- | --- | --- |
| `model` | `{"type":"attachment","attachment":{"type":"model","identity":{"modelId":"claude-fable-5-1",…}}}` | at session start, after a resume and on every `/model` — 7 rows in a 750-turn session |
| `permission_mode` | `{"type":"permission-mode","permissionMode":"auto"}` | once per turn — 287 rows in the same session, values `auto` and `bypassPermissions` |
| `effort` | a top-level `"effort"` on an `assistant` row | once per assistant message, values `high`, `xhigh`, `max` |

`{"type":"mode","mode":"normal"}` is the input mode, not the permission mode, and `perTurnEffort` is
not the effort; neither is read. `auto` is a permission mode the advertised list does not carry, and
a model id keeps any suffix it has (`claude-opus-5[1m]`), so the apps must show an unknown id as it
stands.

`tests/test_mirroring.py` covers the three shapes translating to one `session_settings` emit each, a
row of an unknown or malformed shape translating to nothing, `latest_settings` returning the last
value of each over a file with several changes and honouring its byte cap, a mirrored session
publishing one `meta` carrying all three at adoption, no `meta` when a turn repeats the same values,
one `meta` carrying `model` alone after a `/model`, and a session the device drives being left
untouched by the scan, the backfill and the tail.

Live check, against the owner's own 32 MiB transcript
(`~/.claude/projects/-Users-junbingao-github-remote-control/178ad3ef-….jsonl`, read only):

```sh
cd client && uv run python -c "
from rc_client.agents.claude.transcripts import latest_settings
print(latest_settings('$HOME/.claude/projects/-Users-junbingao-github-remote-control/178ad3ef-1cb2-414b-aa89-1e64bbfea82b.jsonl'))"
# {'permission_mode': 'auto', 'effort': 'xhigh', 'model': 'claude-fable-5-1'} — 0.07 s
```

## 6. Attached terminal sessions (A10)

Run on 2026-09-10, after A10 landed. This is the only part of the document driven against a real
**interactive** Claude Code TUI rather than a `-p` run: a `claude` 2.1.267 started through the shim
under `pexpect`, a gateway from source on port 18787, and a scratch device at a scratch
`RC_CLIENT_HOME`, with a scripted app on `WS /ws/app` recording every frame. Every prompt was tiny.

Static checks were re-run with the A10 fixtures in place:

| Command | Result |
| --- | --- |
| `cd protocol && uv run --with jsonschema python scripts/validate_fixtures.py` | pass, 133 fixtures, 23 negative cases, 0 problems |
| `cd client && uv run ruff check . && uv run ruff format --check . && uv run mypy rc_client && uv run pytest -q` | pass, 323 tests, 3 skipped |

The new client tests are `tests/test_attach_channel.py` (7), `tests/test_shared_control.py` (24) and
`tests/test_shim.py` (30), covering bridge framing, a real socket round trip including
buffer-then-reconnect, inject-only-when-idle, dedupe by message id, absorbed re-injection, the
approval relay with its terminal-resolved and stale-reply cases, both close transitions, and the
shim script executed for real against a pipe.

### Procedure

```sh
# 1. Gateway from source on a free port, scratch DATA_DIR
cd gateway && RC_HOST=127.0.0.1 RC_PORT=18787 PUBLIC_ORIGIN=http://127.0.0.1:18787 \
  RC_PASSWORD=… DATA_DIR=<scratch> uv run rc-gateway

# 2. A device in a scratch home, with the shim installed
cd ../client && export RC_CLIENT_HOME=<scratch>
uv run rc-client enroll --gateway http://127.0.0.1:18787 --pair RC-XXXX-XXXX
uv run rc-client shim install --no-shell-rc
uv run rc-client run

# 3. An interactive CLI through the shim, in a scratch git repository,
#    with CLAUDE_* stripped from the environment
PATH="$RC_CLIENT_HOME/bin:$PATH" claude
```

Answer the project-trust dialog, then the development-channels dialog with "I am using this for
local development". Then, as an app on `WS /ws/app`, subscribe to the session that appears.

### Results

| Check | Result | Evidence |
| --- | --- | --- |
| (a) The session appears as `control: "shared"` | pass | `session.updated` within a second of the dialog being answered |
| (b) Send while idle | pass | one `user_message` with `delivery: "delivered"`, then `assistant_text` and `turn_completed`, seq 22–27 |
| (c) Send during a terminal turn (`sleep 8`) | pass | `delivery: "pending"` plus a `queue` entry, then the **same** `block_id` republished as `delivered` once the turn ended, seq 28–44 |
| (d) Relayed approval, both answers | pass | Allow wrote `hello.txt`; Deny left `bye.txt` absent, seq 48–56 and 60–66 |
| (e) Answered in the TUI first | pass | the card resolved with `decision.by: "terminal"`, seq 72–75 |
| (f) `/exit` in the CLI | pass | `meta {control: "none"}` at seq 81 |
| (g) A remote SDK session with the shim first on `PATH` | pass | `session.create` streamed `OK`; the shim passed the pipe-driven run through untouched |

Two further checks beyond the list, both live:

- **`shared → terminal`.** Killing the bridge while the CLI kept running moved control back to
  `terminal`, and the takeover bar returned. This is the transition the process scan gets wrong if it
  treats every `claude` carrying `mcp` on its command line as a non-holder, which the shim's own
  `--mcp-config` guarantees; that bug was found here and fixed.
- **Bridge reconnect.** Restarting `rc-client run` under a live attached session: the bridge
  reconnected, replayed its registration and the session returned to `shared` without touching the
  CLI.

Three defects were found by this pass and fixed with regression tests: the holder scan above, a
mirror that read turn state before reading new rows and so closed every turn one tail interval early,
and a session attached before its first turn never being re-checked for ownership.

### Not covered by this pass

- **`--permission-mode acceptEdits` and `bypassPermissions`.** Only `default` was driven. Those modes
  relay fewer permission prompts or none; nothing here confirms what the relay does under them.
- **Codex.** Attaching Codex is a different mechanism and has its own pass; see section 7.
- **Linux.** The shim, the shell-rc block and the socket paths were exercised on macOS only.
- **A message still pending when the attachment drops.** Queue survival is unit-tested; the later
  delivery through the ordinary resume path was not driven end to end.
- **A session attached before its first turn, across a daemon restart.** It has no transcript yet, so
  the scan that promotes a live CLI to `control: "terminal"` cannot see it. It has no content either,
  and the next turn creates the transcript.

### One incident worth recording

While testing `rc-client uninstall` against a fake `HOME`, `launchctl bootout` stopped the
maintainer's **real** device service: launchd is scoped by service label, not by `RC_CLIENT_HOME`.
It was restored with `launchctl bootstrap gui/<uid> ~/Library/LaunchAgents/dev.remote-control.client.plist`
and the plist was never deleted. Anyone repeating this must not run `uninstall` or `service remove`
on a machine that carries a real installation, whatever `RC_CLIENT_HOME` says.

### Titles from Claude's own transcript, 2026-09-11

Claude Code names a session for itself and writes the result into the transcript as a row of its own,
`{"type":"ai-title","aiTitle":"…","sessionId":"…"}`. The `claude-agent-sdk` message stream carries
nothing equivalent — `SystemMessage`, `ResultMessage` and the task messages have no title field, and
the SDK's own title handling reads the same file — so the device reads the row rather than the
stream.

| Check | Result | Evidence |
| --- | --- | --- |
| The row exists on this machine | pass | 35 transcripts under `~/.claude/projects` carry one; several were written by this repository's own SDK-driven tests, so a session the device drives gets one too |
| The parser reads it | pass | `TitleTail.read_new()` returned the title for each of four real transcripts and `""` on the next read; `TranscriptTailer.translate` turned the row into the internal `ai_title` emit; `find_transcript` located each file from its session id alone |
| A session the device drives picks it up live | pass | a real `ClaudeRunner` in a scratch working directory, one turn of "Reply with exactly OK": the title moved from the first-prompt `Reply with exactly OK` to the CLI's own `Session title` after `MirrorService._watch_titles()` and `_read_titles()`, with the transcript found by glob under `~/.claude/projects` |
| A `/rename` outranks a generated title | pass | no `custom-title` row existed on this machine, so one was produced by Claude Code's own writer — `claude_agent_sdk._internal.session_mutations.rename_session`, the function behind `/rename` — against the throwaway session above. It appended `{"type":"custom-title","customTitle":"Ledger migration","sessionId":"64a44d16-…"}` and the device parsed it as a user-set title. The CLI's own reader agrees on precedence: `_internal/sessions.py` takes the last `customTitle` before any `aiTitle` |

The attached and mirrored-terminal paths share `TranscriptTailer`, which the parser check above
exercised against real files; neither was re-driven against a live TUI in this pass. Both row shapes
are internal to Claude Code and its documentation warns they may change, so the parser treats an
unrecognised or malformed row as "not a title" and a format change would cost the titles alone.

### The session behind a terminal changes (2026-09-12)

Two defects, both from the same missing fact. Every interactive `claude` started through the shim
spawns its channel bridge with `CLAUDE_CODE_SESSION_ID` set to the id Claude Code picked at startup,
and an MCP server keeps the environment it was spawned with for as long as it runs. Picking another
conversation with `/resume`, or clearing one with `/clear`, therefore changes nothing the bridge
says.

The first defect is the sessions left behind. The device's own state database held nine Claude rows
created between 02:08 and 02:28 that day — `origin: "terminal"`, no stored events, no transcript
under `~/.claude/projects`, empty title — one per `claude` whose startup id was abandoned before
anyone typed into it. Claude Code never writes a transcript for such an id, so nothing ever removed
them and the apps showed nine "Untitled session" rows. The second is the live session the device
cannot reach: at 02:28:46 `claude` pid 11668 started on `f5048523…`, the person resumed `178ad3ef…`
inside the TUI, and the database had `f5048523` as `control: "shared"` with no events while
`178ad3ef`, the conversation actually on screen, was `control: "none"`. A message sent from the phone
would have gone to the abandoned id.

The fix gives Claude Code a `SessionStart` hook through the shim's `--settings` file. The hook sends
one `session_start` frame to the daemon socket — the session id, the CLI's own pid, the source
(`startup`, `resume`, `clear` or `compact`) and the transcript path — and hangs up; it is not an
attachment and is answered nothing. The hub keeps the last frame per pid, so a bridge that registers
afterwards lands on the session the hook named rather than the one in its environment, and a hook
that arrives later moves the live attachment: the session being left has its approvals expired, a
running turn ended as `stopped`, `control: "none"` and no holder, and the session being entered is
created or reused and takes the attachment over. A session with a runner of this device's own is
never moved onto. A session that was never used — a Claude terminal session with no stored events, no
transcript and no live attachment — is removed outright when its terminal leaves it, when its bridge
closes, and by a sweep over every entry on each mirror scan.

Two decisions are worth recording. The first is how "never used" is measured. `last_seq` cannot
answer it: it counts every event a session was given a number for, including the `meta` and `status`
ones an attachment publishes about itself and that are never stored, so an attached-then-closed
session stands at `last_seq` 2 with nothing in its history. The registry gained `has_events()`, one
indexed lookup in the `events` table, and that is the test; `client/tests/test_session_start.py`
asserts both halves of it against a real registry. The second concerns publishing. `GatewayLink.send`
drops every frame while the link is down, and the gateway keeps every session a device has ever
announced (its `hello` handler adds sessions and prunes none), so a removal published before the link
is up would leave the ghost in the apps for good. The sweep therefore runs unconditionally and each
ghost removal is repeated once on the next link, which the hub recognises from the `hello` it builds;
the repeat itself waits until the daemon reports the link as connected, so it cannot fall into the
same gap. The mirror scan the link runs on connect is what carries it out.

Covered by `client/tests/test_session_start.py` (twelve cases: a bridge landing on the hook's id, a
startup hook for the current session doing nothing, a resume moving the attachment and taking the
empty session with it, a clear leaving a used session at `control: "none"`, a move onto an existing
session keeping its title and history, a refusal to move onto a session this device drives, a bridge
closing on an empty and on a used session, a closed bridge's pid being forgotten, the sweep against
sessions with events, with a transcript, with a live attachment, of another agent and of remote
origin, the repeat on the next link, and a full `MirrorService.scan_once` confirming the moved
session is `control: "shared"` while the one left behind stays `none`) and by the frame round trip in
`client/tests/test_attach_channel.py`.

Not driven live in this pass: the owner's daemon was not restarted and no real TUI was resumed
against it, so the nine ghosts above are still in the database — they disappear the first time the
daemon runs this code, and the shim has to be reinstalled before any already-running `claude` starts
sending hook frames. Not covered either: `compact`, which reports the same id and is treated as the
no-op it is, and a `--settings` file the person passes themselves, which keeps the CLI attached but
without the hook and so back to the old behaviour.

### A held message is a queue entry (2026-09-12, A19)

A message sent into a running attached turn used to be published at once as
`user_message {delivery: "pending"}`. Its `first_seq` therefore pinned it in the middle of the turn,
between the tool call that was running and the rest of the answer, while the terminal drew it after
the turn — where Claude Code actually reads it. The device now holds such a message as a `queue`
entry only and emits the block when it injects it, so `first_seq` is issued at injection and the
bubble lands where the terminal shows it. `delivery: "pending"` is gone from the device, and the
fixture that carried it with it.

Covered by `client/tests/test_shared_control.py`: a mid-turn send now publishes no `user_message` at
all and one `queue` entry; the block appears once, as `delivered`, after the transcript goes idle
(`test_a_held_message_is_issued_after_the_turn_it_waited_for` asserts its `first_seq` equals its own
`seq` and is greater than the `seq` of what the turn said while it waited); and a message held across
a detachment leaves no bubble behind, so the resume path — which emits under the item's own id, the
app's request id — draws the only one there will ever be
(`test_a_message_held_across_a_detachment_is_never_shown_twice`).

### A question is answered where you are (2026-09-12, A20)

Driven against a real interactive Claude Code 2.1.269 (`~/.local/bin/claude`) under `pexpect`, in a
scratch git repository, with a scratch `RC_CLIENT_HOME` and the real `AttachServer` in process
standing in for the hub. Three runs, one per case; each was quit with `/exit`.

The bundle facts this rests on, read out of the 2.1.269 bundle on this Mac:

- The channel never sees `AskUserQuestion`. `notifications/claude/channel/permission_request` is
  sent only when `!tool.requiresUserInteraction()`, which that tool is not, and the channel's
  `permission` reply carries `{request_id, behavior}` and no `updatedInput`. That path is closed.
- Claude Code runs `PermissionRequest` hooks beside its own dialog and races them
  (`Promise.race([prompt, hooks])`). Hook stdin is JSON with `session_id`, `transcript_path`, `cwd`,
  `hook_event_name`, `tool_name` and `tool_input`; there is no `tool_use_id`. Stdout answers with
  `{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow","updatedInput":{…,"answers":{"<question>":"<label>"}}}}}`,
  multi-select labels comma-joined. Exit 0 with no stdout means "no decision" and the dialog stands.
- Hooks from the shim's `--settings` file fire in every permission mode.

**(a) An app answers first, `--permission-mode bypassPermissions`.** The stub answered five seconds
after the frame arrived, with the second option:

```
  4.98s  session_start startup a3538f99-6168-4212-aa79-90f97c7f18a4
 12.33s  question frame: {"questions": [{"question": "接下来想做点什么？", "header": "下一步", …
 17.33s  answered the hook {"接下来想做点什么？": "先聊聊别的"} sent=True
 17.33s  question hook connection closed
```

The dialog closed on the CLI's own screen and the transcript recorded the answer as the tool's:

```
⏺ User answered Claude's questions:
  ⎿  · 接下来想做点什么？ → 先聊聊别的
  ⎿  Allowed by PermissionRequest hook
⏺ 你选择了"先聊聊别的"。
```

**(b) The terminal answers first, `bypassPermissions`.** Down-arrow and Enter in the pty picked the
second option. The finding that matters: **Claude Code does not stop the hook when its own dialog is
answered.** The hook process was still alive four seconds later, and it is the device that has to
release it — which is exactly what the transcript's `AskUserQuestion` result makes it do:

```
 14.77s  answered in the pty
 14.87s  question frame: {"questions": [{"question": "接下来你想让我做什么？", …
 18.83s  hook still running after the terminal answered: 25220 …/rc-client hook permission-request
 18.91s  answered the hook null sent=True
 20.46s  hook processes after the null: none
 32.61s  transcript toolUseResult: {"questions": […], "answers": {"接下来你想让我做什么？": "先看看项目现状"}, "annotations": {}}
 32.61s  the hook connection closed +0.07s after that row
```

The hook printed nothing, so the terminal's own answer stood, and the screen carries no "Allowed by
PermissionRequest hook" line — the one-line difference between a question answered in the terminal
and one answered from an app.

**(c) An app answers first, `--permission-mode default`.** Identical to (a) with the status line
reading `⏸ manual mode on`:

```
 17.62s  question frame: {"questions": [{"question": "给你出一个示例问题：你更喜欢哪种沟通风格？", …
 22.62s  answered the hook {"给你出一个示例问题：你更喜欢哪种沟通风格？": "详细说明"} sent=True
 34.95s  transcript toolUseResult: … "answers": {"给你出一个示例问题：你更喜欢哪种沟通风格？": "详细说明"}
⏺ 你选择了「详细说明」——沟通风格更偏向包含背景与推理过程，适合复杂决策场景。
```

What this pass did **not** drive: a gateway, an app, or the hub itself — the block the device emits
from the frame, `session.answer` reaching it, and the resolution `by: "terminal"` from the transcript
are covered by `client/tests/test_shared_control.py` (thirteen cases: the frame becoming a pending
block with `needs_input` and resolving `by: "remote"`, free text in both directions, the transcript
result resolving `by: "terminal"` with the labels mapped back to option ids, an unusable result
resolving without `answers`, a stale answer as a no-op, the hook going away as `expired`, a second
question expiring the first, a question on a session nothing is attached to being declined at once,
a detachment releasing the hook, a `session.send` from an older app being queued rather than
injected into the open dialog, and the transcript emit itself) and
`client/tests/test_hook.py` (the frame the hook sends, the decision JSON it prints, and the four
ways it stays silent). Also not covered: two apps answering the same question at the same instant,
and a question left open long enough to reach the 24-hour hook timeout.

## 7. Codex on the shared daemon (A11)

Amendment A11 attaches the device to the local Codex app-server daemon, so a bare `codex` TUI is a
`control: "shared"` session rather than a mirrored one. Run on 2026-09-11 against a real daemon
(Codex CLI 0.154.0) and real `codex` TUIs driven under a pseudo-terminal, with a gateway from source
on port 18787, a device in a scratch `RC_CLIENT_HOME`, and a scripted app on `WS /ws/app` recording
every frame. This is the Codex counterpart to section 6, and the whole pass was re-run after the
final refactor.

Static checks with the A11 fixtures in place:

| Command | Result |
| --- | --- |
| `cd protocol && uv run --with jsonschema python scripts/validate_fixtures.py` | pass, 137 fixtures, 24 negative cases, 0 problems |
| `cd client && uv run ruff check . && uv run ruff format --check . && uv run mypy rc_client && uv run pytest -q` | pass, 393 tests, 3 skipped; ruff clean over 96 files, mypy clean over 94 |

The new client tests are `tests/test_codex_daemon_rpc.py` (11, driven against a real in-process
`AF_UNIX` WebSocket server rather than a mock transport), `tests/test_codex_daemon_sessions.py`
(32 plus 3 skipped), `tests/test_codex_setup.py` (12), and four tests that decode the A11 fixtures.

### Procedure

```sh
# 1. Gateway from source on a free port, scratch DATA_DIR
cd gateway && RC_HOST=127.0.0.1 RC_PORT=18787 PUBLIC_ORIGIN=http://127.0.0.1:18787 \
  RC_PASSWORD=… DATA_DIR=<scratch> uv run rc-gateway

# 2. A device in a scratch home, with the shared Codex daemon up and supervised
cd ../client && export RC_CLIENT_HOME=<scratch>
uv run rc-client enroll --gateway http://127.0.0.1:18787 --pair RC-XXXX-XXXX
uv run rc-client codex setup
uv run rc-client run

# 3. A bare codex in a scratch git repository, under a pseudo-terminal
codex
```

The one thing a repeat of this pass must get right is the TUI: launch it as a bare `codex`, with no
`-c`, or it runs its own embedded app-server and never joins the daemon — see "A TUI with flags is
still the daemon's" below for which flags really do that and which do not. Answer the project-trust
prompt, then subscribe as an app on `WS /ws/app` to the session that appears.

### Results

| Check | Result | Evidence |
| --- | --- | --- |
| (a) A bare `codex` TUI appears as `origin: "terminal"`, `control: "shared"` | pass | the session arrived within seconds of the trust prompt being answered |
| (b) Send while the thread is idle | pass | `accepted: "sent"`, `assistant_text` "BEE", `turn_completed` `completed`, and the TUI rendered both the injected message and the answer |
| (c) Send during a terminal-started turn with `mode: "auto"` | pass | `accepted: "steered"`, carrying `expectedTurnId`, into a running `sleep 8` |
| (d) `session.stop` on a turn the terminal started | pass | `turn_completed` with `stop_reason: "interrupted"` |
| (e) An approval raised by a TUI command, answered three ways | pass | options `allow`, `allow_always`, `deny` — the daemon offered three for `touch`, and four where it lists `acceptForSession` — with `input {command, cwd, command_actions}` and `tool_kind: "shell"`. Allow resolved `{option_id: "allow", by: "remote"}` and the file was created; Deny left it absent; answering in the TUI first resolved the block `{option_id: "elsewhere", by: "terminal"}` and the app's late reply returned `{}` |
| (f) `session.set` for `effort` | pass | the reply carried `effort: "low"` and a `meta` event followed |
| (g) A thread created from the app reopens in the terminal | pass | `codex resume <id>` joined the same thread, and a later turn reached both sides |
| (h) Restarting the device under a live shared thread | pass | the session came back as `origin: "terminal"`, `control: "shared"`, `session.history` replayed 5 events, a fresh send was answered, and the TUI stayed alive throughout |
| (i) Setup and health reporting | pass | `rc-client codex status` reported a successful handshake and loaded supervision; `rc-client status` printed `codex daemon healthy (loaded)` |

Evidence files are run artefacts under the session scratch directory
`…/scratchpad/client-codex/`: `app-frames.jsonl`, `results-a-d.json`, `results-e-g.json`,
`results-h.json`, `logs/tui-*.raw` and `device.log`. None is checked into the repository.

One transport fact was found the hard way and is worth recording: the daemon **closes the connection
when the client offers `permessage-deflate`**, so the WebSocket upgrade has to run with compression
off. The device connects with `websockets.asyncio.client.unix_connect` to
`$CODEX_HOME/app-server-control/app-server-control.sock` — overridable with
`RC_CODEX_DAEMON_SOCKET` — identifies itself as `remote-control`, asks for `experimentalApi`, and
dispatches every server request to a detached task so a slow approval cannot block the read loop.

### Where a steered message lands (A14)

Run on 2026-09-12 against the same real daemon (Codex CLI 0.154.0): a bare `codex` TUI in a scratch
directory was given a task that takes several steps, and an observer subscribed to the same thread
over the control socket sent `turn/steer` while the first step was still running, logging every
notification with a timestamp. The daemon emitted the steered prompt's `userMessage` item 1.29 s
after the steer request returned, and only after the work Codex had already started:

```
 63.457s  item/completed  agentMessage      msg_051da9b7…  the model's opening line
 63.457s  ==> turn/steer "also tell me what the current unix timestamp is"
 63.460s  ==> turn/steer accepted
 64.588s  item/started    commandExecution  exec-0df11336  /bin/zsh -lc 'cat a.txt'
 64.591s  item/completed  commandExecution  exec-0df11336
 64.747s  item/started    userMessage       01a09136-6298-7583-a5ea-f2cf3c028711
 64.748s  item/completed  userMessage       01a09136-6298-7583-a5ea-f2cf3c028711   (same id)
```

The TUI drew the steered prompt in exactly that position, below its opening message and below the
`cat a.txt` call, which is what A14 makes remote control do too: the device now publishes the
`user_message` when that echo arrives rather than at `turn/steer` time, and the item's second
sighting stays silent. The fallback paths — a turn that ends without the echo, and the `warn` notice
on an interrupted one — are covered by the client tests, not by this live run. After the change:
`ruff check`, `ruff format --check`, `mypy rc_client tests` and `pytest -q` are clean, 480 tests
passing and 3 skipped. Evidence: `…/scratchpad/steer-pass/observe.log` and `tui-pane.txt`, run
artefacts that are not checked into the repository.

### Cleanup, and one side effect

The scratch threads this pass created were removed with `codex delete --force`. Codex's own
project-trust prompt appended a `[projects."…"]` block to `~/.codex/config.toml`; that was written by
Codex, not by the device, and was left in place.

### Not driven live by this pass

- **`mode: "queue"` and `mode: "interrupt"` on a shared thread.** Both are ordinary hub paths — a
  daemon session is an ordinary hub runner, so send, steer, queue, stop, set and answer reuse the
  same code a remote session uses — and both are unit-tested, but neither was driven against the real
  daemon here. `mode: "auto"` and `session.stop` were, in (c) and (d).
- **`session.answer`.** Verified against the fake daemon only; no live thread asked a question.
- **Attachments** on a shared Codex session.
- **`session.set` for `model` and `permission_mode`.** Only `effort` was changed live.

### Not covered by this pass

- **Linux.** The systemd user unit is rendered and unit-tested but was never installed or run, and
  `loginctl enable-linger` was never exercised.
- **A daemon bootstrapped after the device started.** It affects only sessions created or discovered
  afterwards; sessions already running keep the runner they have.
- **Two clients starting a turn on the same thread at once.** What `~/.codex/thread-writer-locks/`
  enforces across processes is untested.
- **Thread eviction.** Nothing observed unloads a thread from the daemon, so the behaviour of a
  daemon that has held threads for weeks is unknown. This is why `shared` is sticky: Codex emits no
  signal when a TUI exits.
- **The auto-updater swap.** Codex's `pid-update-loop` replaces the app-server on a new release;
  what a connected client sees during that swap was never observed.
- **`approvalsReviewer: "auto_review"`**, which could remove the approval burden entirely, was not
  exercised.

Two behaviours are by design rather than gaps. A TUI that runs its own private app-server — a
`codex -c …`, as measured below — is mirrored read-only as `control: "terminal"`. And a Codex thread
has no rollout until its first turn, so the device subscribes at that first turn, and a thread
created from an app can be `codex resume`d only after it.

### The prompt echo and the thread name, 2026-09-11

Three scratch scripts were run against the **real** daemon on this machine (Codex CLI 0.154.0,
`~/.codex/app-server-control/app-server-control.sock`), using the device's own `rpc.py` and
`transport.py`: `initialize`, `thread/start` in a scratch working directory, `turn/start`, and
`thread/name/set`. Every thread they created was removed afterwards with `thread/delete`, which the
daemon accepts and answers `{}`.

| Question | Answer |
| --- | --- |
| What does the echo of our own prompt look like? | `{"type":"userMessage","id":"01a08d1c-7951-7363-8cd7-5dedfe2185e4","clientId":null,"content":[{"type":"text","text":"Reply with the single word: pong","text_elements":[]}]}` |
| How often does it arrive? | **Twice**, as `item/started` and then `item/completed`, with the same item id both times, and once more from `thread/items/list` on a backfill |
| Does `turn/start` name the item? | No. Its result is `{"turn":{"id":…,"items":[],"status":"inProgress",…}}`, so the text is the only key the echo can be matched on |
| Does `initialize` hand us a client id? | No: `{userAgent, codexHome, platformFamily, platformOs}` |
| What does a rename look like? | `thread/name/updated` with `{"threadId":…,"threadName":"Probe title from set"}`, and `thread/read` then reports the same `name` |
| Does the daemon name a thread it started over the socket? | Not within 90 s of a completed turn, and `name` stayed `null`. Threads named in `thread/list` were named by the TUI, which generates the title itself and calls `thread/name/set`. A device-created thread therefore keeps its first-prompt title unless a terminal renames it |

The duplicated bubble reported against A11 is explained by the second row of that table: the echo was
recognised on `item/started`, which consumed it, and the identical `item/completed` was then
published as a `terminal` message. The device now remembers the item id of an echo it has matched.

### What the daemon emits when a TUI exits (2026-09-11)

A session attached to a terminal kept reading "attached to the terminal" long after the terminal had
been closed. A11 had assumed TUI exit is invisible; this run settled what the daemon actually does.

An observer script connected to the **real** daemon on this machine (Codex CLI 0.154.0) as a second
client named `rc-probe`, using the device's own `transport.py` and `rpc.py`, and logged every
notification method with the text fields stripped. A real TUI was driven in tmux 3.7b rather than on
a pseudo-terminal, which is what made earlier attempts fail to create a thread at all:

```sh
tmux new-session -d -s rcprobe -x 120 -y 40 -c <scratch>/probe \
  ~/.codex/packages/standalone/current/bin/codex     # bare, no flags
tmux send-keys -t rcprobe 'reply with the single word ok'   # then Enter, separately:
tmux send-keys -t rcprobe Enter                             # one call sends it as a paste
# the observer polls thread/loaded/list for the new thread, then
# thread/resume {threadId, excludeTurns: true} once the first turn has written the rollout
tmux send-keys -t rcprobe '/quit' ; tmux send-keys -t rcprobe Enter   # run 1
tmux kill-session -t rcprobe                                          # run 2
```

| Question | Answer |
| --- | --- |
| `/quit`, with our client subscribed | **Nothing.** No `thread/closed`, no `thread/status/changed`, no notification of any kind in the 30 s after the TUI process was gone |
| Killing the pane, with our client subscribed | **Nothing**, identically |
| Does the thread leave `thread/loaded/list`? | No. Present in every poll for 30 s after each exit, and the `/quit` thread was still loaded 25 minutes later |
| Is `thread/closed` ever emitted? | Yes, but only for the empty thread a TUI opens at startup and abandons when its first message starts a new one. Never for the thread whose TUI exited |
| Does any thread field change? | No. `status` stayed `{"type":"idle"}` and `canAcceptDirectInput` stayed `true` after the TUI was gone |
| Can the daemon be asked who is attached? | No. Its `ClientRequest` union has no client or subscriber method; `thread/unsubscribe` answers only `notLoaded \| notSubscribed \| unsubscribed` |
| Is the `originator` field a hint? | No. It is stamped from whichever client connected to the daemon first, so threads a person starts in a terminal read `remote-control` |

Both probe threads were removed afterwards with `thread/delete`, and `thread/loaded/list` was checked
back to the user's own single thread. Nothing was started, stopped or reconfigured on the daemon.

The heuristic that replaces the signal was then checked against this machine as it stood: one live
TUI (`~/.local/bin/codex`, a symlink to the standalone binary, with a tty) in `~/github/onelyi`, and
one loaded daemon thread whose `cwd` is `~`. The scan found exactly that one TUI out of 934
processes, excluded the daemon's own `codex app-server` processes and the ChatGPT app's helpers, and
reported no terminal for the loaded thread — which is precisely the stuck session that was reported.

Not verified: whether the daemon would unload a thread once its last subscriber leaves. The device
subscribes to every loaded thread, so there is never a moment without a subscriber, and creating one
would mean stopping the user's own client. Also unverified: whether `codex resume <id>` run from a
directory other than the thread's own updates the thread's `cwd`. If it does not, that thread reads
as `none` one scan after its last terminal message; sending from an app still reaches it.

### Why one terminal showed several sessions, 2026-09-11

Starting a bare `codex` put **several rows in the apps' Active list at once**, all of them looking
like older sessions of the same conversation. The cause is the daemon's own bookkeeping meeting a
heuristic that cannot ask it a question.

An observer connected to the **real** daemon on this machine (Codex CLI 0.154.0) as a second client
named `rc-probe`, using the device's own `transport.py` and `rpc.py`, logged every notification with
the text stripped, and polled `thread/loaded/list`. Two real TUIs were driven in tmux 3.7b in a
scratch directory, one used and one abandoned:

```sh
tmux new-session -d -s rcprobe -x 120 -y 40 -c <scratch>/probe \
  ~/.codex/packages/standalone/current/bin/codex     # bare, no flags
tmux send-keys -t rcprobe Enter                      # answer the project-trust prompt
tmux send-keys -t rcprobe 'reply with the single word ok' ; tmux send-keys -t rcprobe Enter
tmux send-keys -t rcprobe '/quit'                    # run 1: used, then quit
tmux new-session -d -s rcprobe2 … ; tmux send-keys -t rcprobe2 '/quit'   # run 2: never typed in
```

| Question | Answer |
| --- | --- |
| What does a TUI do when it starts? | It creates a thread, announced by `thread/started`, `ephemeral: false`, `name: null`, no preview, no rollout — 53 s after launch here, because the trust prompt held it |
| Is that thread a placeholder that is thrown away? | No. The first message ran on that same thread: `thread/status/changed` to `active`, then `thread/name/updated`. The discarded thread recorded earlier is the *title* thread, `ephemeral: true`, which the daemon closes about a minute after it goes idle — the only `thread/closed` seen in either run |
| What happens to a thread whose TUI never typed? | It stays. `in_history: false`, no rollout file, `name: null`, and still in `thread/loaded/list` after `/quit` |
| What did the machine hold at that moment? | 6 loaded threads, **4 of them in `/Users/junbingao`, all four named "Respond to greeting"**, the oldest loaded for 16 hours |

That is the whole bug. The daemon never unloads a thread, so a directory accumulates one loaded
thread per `codex` ever run there; `resolve()` made every loaded thread the device had not created
`shared`; and the TUI scan could only answer "is a TUI running in this directory", which is true for
all of them at once. Starting one `codex` in that home directory therefore turned four threads
`shared` — four Active rows with the same title — and each control change published a summary, so
they also jumped to the top of the list. Two smaller faults fed the same symptom: an empty startup
thread was published as an untitled session that `_forget_deleted` then refused to drop because a
runner was attached to it, and a TUI started with `--dangerously-bypass-approvals-and-sandbox` was
believed to run an embedded app-server of its own and was struck out of the terminal count. That
second belief was wrong, and the section below records how it was measured and corrected.

The fix, covered by replays of exactly this sequence in `client/tests/test_codex_daemon_sessions.py`:

- `shared` now requires a terminal known to be in *that* thread. `thread/started` from another
  client and a `userMessage` with a foreign `clientId` are the direct evidence; failing both, the
  scan counts the TUIs per directory and hands out that many claims, sticky first and then by what
  the daemon last saw used. A thread this device started gets no scan claim until somebody is seen
  typing in it.
- An empty thread is remembered, not published. Its first word publishes it and credits it to the
  terminal that opened it.
- A thread deleted in Codex is forgotten even while a daemon runner is attached to it.
- A helper process that exits under `ps` or `lsof` no longer raises `ProcessLookupError` out of the
  scan. The device log shows it aborting whole scan rounds, which left every claim un-revoked; it is
  now an unfinished scan, which changes nothing.

Both probe threads were removed with `thread/delete` afterwards and the loaded list checked back to
the user's own threads. Not verified: how the claim is shared out when one directory really does
hold two TUIs on two threads — the count is right, but which thread each terminal is credited with
is a guess ordered by last use. Nothing in this pass was run against the gateway or the apps; the
before-and-after evidence is the daemon state above, the device log, and the replays.

### The same shape on the Claude side, 2026-09-12

Starting a bare `claude` in a directory that holds older Claude sessions put those older sessions in
the apps too. The evidence is the device's own state database
(`~/.rc-client/state/rc-client.sqlite3`, opened read-only): at 2026-09-12 00:28 three Claude
sessions with `cwd` `/Users/junbingao` carry `updated_at` values inside the same millisecond — the
session the terminal had just started, and two finished ones, both archived. The two archived
records end their stored streams at `seq` 4 and 27 while carrying `last_seq` 18 and 33; the gap is
`status` and `meta` events, which are not stored, so the gap counts how often those sessions were
told a terminal had taken them over and then given them back.

The cause is the Claude twin of the Codex bug above. A holder scan asked, once per session, "is
there a `claude` process for this session", and the only identifying fact it had was the command
line: a session id after `--resume` or `--session-id`. The shim adds neither — it appends the
channel flags and nothing else — so a shim-started CLI is anonymous, and the lookup fell through to
its working directory. Every session that shared that directory matched the same process, flipped to
`control: "terminal"` with `state: "readonly"`, and flipped back when the terminal exited, each
transition publishing a `meta`, a `status` and a fresh session summary. A second, smaller churn sat
underneath it: an archived session read back as `stopped` was moved to `idle` by the first scan that
found no terminal for it, once per daemon restart.

The fix assigns the whole round at once, so one process can be credited to one session only, and by
identity rather than by directory: the session id in the command line, then the pid the channel
bridge registered for an attached session. A shim-started CLI is excluded from the directory
fallback outright, and the fallback itself now needs one unidentified process and one waiting
session in that directory. A session whose bridge has just closed is looked up by that exact pid,
not by its directory. Archived sessions are left at `stopped` while nothing drives them. Covered by
`client/tests/test_mirroring.py`, including a replay of the three-session shape above driven through
a real `SessionHub` and `MirrorService`.

Not verified against a live gateway or the apps: the before-and-after evidence is the state database
above and the replays. Not verified either: how the directory fallback behaves when a user really
does run two un-shimmed `claude` processes in one directory — it now declines to choose, which
leaves both sessions `control: "none"` and lets an app resume one of them alongside the terminal.

### A session that comes back to life leaves the Archive (A15)

Client side only in this pass. The device clears `archived` and republishes the session summary on
five paths, each with a test in `client/tests/test_archive_revive.py` and
`client/tests/test_codex_daemon_sessions.py`: a `session.send` that starts a turn, a `session.send`
that only joins the queue, a turn started in a terminal on a shared session, a Claude channel
attach, and a Codex thread opened by a TUI. `hub.load` still reads an archived session back as
`stopped`; reviving it moves it to `idle` before whatever the reviving path sets next, so a resumed
session never reports a dead state. Archiving a running session still closes its runner and drops
`control` to `none`, and the resume that follows a later send puts it back to `remote`. Not verified
in this pass: that the apps move the row out of the Archive on the `session.updated` alone.

### A TUI with flags is still the daemon's (2026-09-12)

The owner ran `codex --dangerously-bypass-approvals-and-sandbox` in
`/Users/junbingao/github/remote-control` (pid 97125) and typed two messages. Both turns reached the
apps through the shared daemon, stored with `trigger: "terminal"` and `source: "terminal"`, so the
TUI was the daemon's. One scan interval after each turn ended the session went `control: "none"`,
`state: "idle"`, and the web folded it into the Archive while the TUI sat open.

The terminal scan had struck that TUI out of the count because its argv carried
`--dangerously-bypass-approvals-and-sandbox`, one of five flags the scan believed produced an
embedded app-server. Measured on the live processes, that belief was false for this flag:

| Question | Answer |
| --- | --- |
| Who holds the thread's rollout, `…/sessions/2026/09/12/rollout-2026-09-12T03-27-20-01a091f0….jsonl`? | `codex app-server --listen unix://`, the shared daemon, pid 92162, `74u REG`. `lsof -p 97125` shows the TUI holding no rollout at all, only unix sockets |
| Does the daemon have the thread? | Yes. `thread/loaded/list` lists `01a091f0-4eb8-7940-8120-5ecd4f27bf74`, `thread/read` answers with that `cwd` and the name "Respond to greeting", and `thread/list` has it first |
| What did the device conclude? | `resolve(created_here=False, loaded=True, terminal_holds=False)` → `("terminal", "none")`, because the directory had been handed zero claims |

So the scan now classifies a TUI by what it holds, not by the flags it was started with. A `codex`
on a terminal with no helper subcommand is a candidate; one `lsof` over the candidates then drops
any that is itself holding a file under `$CODEX_HOME/sessions`, which is what a TUI running its own
embedded app-server does and a TUI on the shared daemon never does. An `lsof` that does not complete
leaves the scan `complete: False`, which changes nothing, as before.

Verified live against the same processes, with the device's own `scan_terminals()` called from a
scratch script:

| Check | Result |
| --- | --- |
| The owner's bypass-flag TUI, still running | `complete: True`, `count("/Users/junbingao/github/remote-control") == 1`. The old code gave that directory no candidates at all |
| Does `-c` still embed on Codex 0.154? | Yes. A `codex -c model_reasoning_effort=low` driven under `pexpect` in a scratch git repository answered one prompt, and while it was alive it held its own rollout (`codex 52962 … 57u REG …/rollout-2026-09-12T03-45-16-01a09200….jsonl`), the shared daemon had no thread in that directory, and `scan_terminals()` counted 0 for it — the exclusion working on a real embedded TUI |

The scratch session was removed afterwards with
`codex delete --force 01a09200-b9b5-7cc2-8214-e23be1f368c4`, and its rollout file is gone. Codex's
project-trust prompt appended a `[projects."…"]` block for the scratch directory to
`~/.codex/config.toml`; as in the pass above, that was written by Codex and was left in place.

Not verified: whether `--enable` and `--disable` embed on 0.154 — only `-c` and the bypass flag were
measured, and the scan no longer depends on the answer. Nothing in this pass was run against the
gateway or the apps.

### Work another application owns is not a session (2026-09-12, A18)

The owner's state DB held twenty-odd Codex sessions with `origin: "terminal"` whose `cwd` was
`~/Documents/Codex/<date>/<slug>` or `~/Documents/New project`: the ChatGPT desktop app's scheduled
automations ("Daily AI News to Notion", one per day) and its chats. None was started at a terminal.
Today's automation thread `01a09321-…` was `control: "terminal"`, `state: "readonly"`, because
`/Applications/ChatGPT.app/Contents/Resources/codex … app-server` (pid 83448, no tty, child of
ChatGPT.app) held its rollout open and `file_writers` takes any holder for a terminal.

Read off the live daemon and the rollouts on disk, with `DaemonClient` and the repository's own
`rollouts._session_meta`:

| Provenance | Where it comes from |
| --- | --- |
| `originator: "remote-control"`, `source: "vscode"` | This device's threads on the shared daemon (`DAEMON_CLIENT_NAME`, the name in its handshake) |
| `originator: "rc-client"`, `source: "vscode"` | This device's threads on the app-server it spawns for itself |
| `originator: "codex-tui"` (older: `"codex_cli_rs"`), `source: "cli"` | A terminal running its own Codex |
| `originator: "codex_exec"`, `source: "exec"` | `codex exec` |
| `originator: "Codex Desktop"` or `"codex_work_desktop"`, `source: "vscode"` | The ChatGPT desktop app |
| any originator, `source: {"subagent": …}` (`{"subAgent": …}` in the index) | A subagent a thread spawned |

Counts from the predicate over live data, read-only, on 2026-09-12:

| Source | Items | Kept | Dropped |
| --- | --- | --- | --- |
| One `thread/list` page, `limit: 100`, `sortDirection: "desc"` | 89 | 57 | 32 (17 `Codex Desktop`, 9 `codex_work_desktop`, 4 `P`, 2 `spike-A`) |
| Rollouts on disk inside `discover()`'s 14-day window | 116 | 72 | 44 (14 `Codex Desktop`, 9 `codex_work_desktop`, 5 `probe`, 4 `P`, 2 `spike-A`, 10 subagents) |

`P`, `spike-A` and `probe` are earlier spikes of this project that connected under a throwaway
`clientInfo.name`; they are foreign by the same rule, which is the rule working. The default
`thread/list` page carries no subagent threads at all — five sampled through
`sourceKinds: ["subAgent"]`, none of them on the default page — so the index filter mostly meets
them through `thread/read`, and the rollout filter meets them on disk.

Covered by tests: the predicate over every pair above, including a dict `source`, `None` and both of
this device's own names (`client/tests/test_codex_provenance.py`); `summaries()` keeping a
`remote-control` and a `codex-tui`/`cli` thread and dropping a `Codex Desktop` and a subagent one;
`thread/read` refusing to adopt a foreign thread and refusing again when it speaks; a foreign
`thread/started` ignored; the startup prune withdrawing a stored foreign session, deleting its
events and leaving both a `remote`-origin session and one the daemon will not describe alone; the
withdrawal repeated on the next link when the link was down
(`client/tests/test_codex_daemon_sessions.py`); and `discover()` skipping a foreign rollout, with
the rollout cap counting only what this device may mirror (`client/tests/test_mirroring.py`).

Not verified in this pass: the prune against the owner's real daemon and real state DB — both were
left untouched — and nothing here was run against the gateway or the apps.

### The speed tier on a real thread (2026-09-13, A21)

Measured against the owner's live shared daemon, Codex 0.154.0, from a scratch thread the probe
created in a scratch directory of its own. The owner's threads were read but never written.

| Question | Answer |
| --- | --- |
| Where does the catalogue name the tiers? | `model/list` items carry `serviceTiers: [{id: "priority", name: "Fast", description: "2x speed, increased usage"}]` and `additionalSpeedTiers: ["fast"]`. `defaultServiceTier` is null |
| What sets the tier? | `thread/settings/update {threadId, serviceTier: "priority"}`, which answers `{}` |
| What clears it? | `thread/settings/update {threadId, serviceTier: null}`, which also answers `{}`. An absent key leaves the tier alone; `""` is accepted and reads back as cleared |
| Does `thread/start` take it? | No. `thread/start {cwd, model, serviceTier: "priority"}` starts the thread, ignores the key and reports `serviceTier: null`; a following `thread/settings/update` is what applies it |
| Is an unknown id refused? | No. `serviceTier: "bogus-tier"` is accepted, answers `{}` and reads back verbatim, so the device validates the id itself |
| What reads it back? | The `thread/start` and `thread/resume` results, at the top level beside `model`, `reasoningEffort` and `approvalPolicy`; and `thread/settings/updated`, whose `threadSettings` carries `serviceTier` next to `model`, `effort` and `approvalPolicy` |
| Do `thread/read` and `thread/list` carry it? | **No.** Neither the scratch thread nor any of the owner's threads has a `serviceTier` key in either result on 0.154, whatever the thread's tier actually is. The brief for this round said they carry `serviceTier: null`; they carry nothing |
| How is the standard speed spelt? | `null` on a thread that has never had a tier, and `"default"` once a tier has been cleared. Both are the standard speed, and `Session.speed` is null for both |
| Does a no-op update notify? | No. Setting the tier a thread already runs at produced no `thread/settings/updated`; every real change produced one |

So the device sets and clears the tier with `thread/settings/update`, applies a new session's tier
immediately after `thread/start`, validates the id against the catalogue entry for the session's
model, and learns a `/fast` typed in the terminal from `thread/settings/updated` and from the
`thread/resume` it takes when it attaches. A mirrored thread the device has not attached to reports
no tier, because the index does not carry one.

The scratch thread ran one turn, so that `thread/resume` could be measured on a thread with a
rollout, and was deleted afterwards with `thread/delete`. Two earlier scratch threads never ran a
turn, so they have no rollout, `thread/delete` refuses them, and they were left where they are: the
daemon never publishes an empty thread and the device never mirrors one.

Covered by tests rather than by this pass: the catalogue parse with and without tiers, the union
order, the hub's three refusals, the daemon session's requests on start and on set, and the `meta`
the settings notification produces (`client/tests/test_speed_tiers.py`).

## 8. Restart resilience

| Step | Result |
| --- | --- |
| Kill `rc-client run` | `device.updated` with `online: false`; `session.send` answered `device_offline` by the gateway itself |
| Restart it | device back online, sessions restored with `last_seq` unchanged (193), the next turn continued at 194 with strictly increasing `seq` |
| Restart the gateway | the app reconnected, the SQLite session index survived, the device redialled on its own |
| `session.subscribe` after the restart | a cursor older than the emptied replay buffer returned `resync: true`; a cursor inside the new buffer returned `resync: false` with only the newer events |

A `claude` child orphaned by killing the daemon can hold the transcript open for one process scan,
so a restarted daemon may briefly report a remote session as `control: "terminal"`. It self-corrects
on the next scan; a client should not treat the first post-restart snapshot as final.

## 9. Docker stack

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

## 10. Amendments and device isolation

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

## 11. Defects found and fixed

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

## 12. Not verified

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
- **Linux.** Only macOS was exercised; the systemd unit, `service install` and the Codex daemon's
  own systemd user unit were never run.
- **The Codex daemon's corners.** Concurrent turns from the TUI and the device, thread eviction,
  reconnect replay and the auto-updater's app-server swap are all untested; section 7 lists them.
- **`rc-client service install`.** Deliberately skipped so this machine gets no launchd agent.
- **TLS and the reverse proxy.** Everything ran over plain HTTP on the published port. No proxy
  terminated TLS in front of the gateway, so HSTS, the Nginx Proxy Manager recipe in
  `docs/DEPLOY.md` and `TRUSTED_PROXIES` against a non-loopback proxy are untested end to end.

## 13. Observations, not defects

- `HEAD` returns 405 on every route, including `/api/health` and `/dist/…`, because the routes are
  declared `GET`-only. `GET` is unaffected and the protocol requires no `HEAD`, but health checkers
  and CDNs often use it.
- A request naming an unknown `device_id` is answered `device_offline` rather than `not_found`.
- The `readonly` versus `running` question raised by this pass was settled by amendment A7 and the
  device now matches it. Verified in section 10.
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

## 14. Gateway latency

2026-09-11, macOS, `gateway/` on a local SSD. Two waits were found on the path a device frame takes
to the apps, measured before removing them and asserted against afterwards by
`gateway/tests/test_hot_path.py`.

| What | Before | After |
| --- | --- | --- |
| Recording an event's `seq`, per event, ahead of the fan-out | 0.293 ms | 0.00006 ms |
| 20 events fanned out, with each index write held at 50 ms | 2.233 s | under 0.05 s |
| Two device frames, with each notification held at 300 ms | 0.642 s | under 0.3 s |

- **The index write was on the fan-out path.** `SessionIndex.record_seq` opened a connection and
  wrote SQLite on a worker thread, and the event was not forwarded until it returned. It now
  records in memory and writes from a batching background task, with reads overlaying whatever is
  unwritten. The brief's figure of 0.8 ms per `sqlite3.connect` did not reproduce here: connecting
  costs 0.032 ms and the write itself is the rest, and it was never on the event loop.
- **A notification blocked the device's next frame.** The transition hook was awaited inside the
  frame handler, and it makes an outbound Web Push or APNs call, so every later frame from that
  device waited for a third party. It now runs as a tracked task that shutdown drains.
- **The forward path was not pure.** `Hub._forward` read the whole session summary back from SQLite
  to learn which device owns it, on every message an app sends. It now reads the in-memory owner
  map and falls back to the index only for a session first seen by an earlier process. One
  behaviour change: a session claimed from an event but not yet carrying a summary now forwards
  instead of answering `not_found`.

Not measured: wall-clock latency through two live WebSockets against a running gateway process. The
harness for it was not completed, and on loopback the socket overhead would dominate the figures
above.

## 15. Device flapping (A13)

2026-09-11. Three findings from the VPS investigation, all confirmed in the code and covered by
`gateway/tests/test_liveness.py`.

- **Two keepalive clocks, and the shorter one won.** Nothing set `ws_ping_interval`, so uvicorn ran
  its default 20 s ping with a 20 s answer deadline while the contract allows 90 s of silence
  (`gateway/rc_gateway/frames.py`). A device whose event loop stalled was closed `1011`. Both
  uvicorn WebSocket implementations honour `None`, including the `websockets-sansio` one that
  replaces the deprecated default, and the entry point now logs the effective values at startup.
- **Offline was reported the instant the socket closed.** Now a transient close starts a 20 s grace
  in which the device is still `online: true`, a replacement ends it silently, and a request
  addressed to the device waits for that replacement instead of being refused. `4401`, `4403` and
  an explicit removal still flip it immediately, and replacement by a newer connection still closes
  the old one `4001`.
- **Refused upgrades were silent.** 685 of 690 `/ws/device` upgrades in three hours were closed
  `4401` before hello, from one address, with nothing in the log. They are now reported once per
  address per minute with the count. An address past 20 attempts in a window is refused before the
  handshake rather than accepted only to be closed; the observed offender retried about four times
  a minute, so it would still receive its close code.

Not verified: none of this was exercised against the VPS, which was not touched. The 20 s period
and the 25 s / 90 s pings are driven in tests at compressed values (0.2 s), so the constants
themselves are asserted but not observed at full length.

## 16. Updating a device from an app (A22)

2026-09-13. The installer and `rc-client self-update` were driven for real, end to end, against a
stand-in gateway on `127.0.0.1:8991` serving two genuine wheels built from this tree:
`rc_client-0.1.0` (`f27ea071…`) and `rc_client-0.1.1` (`9d97cad7…`).

**The owner's own daemon was never touched.** `HOME` and `RC_CLIENT_HOME` pointed at a scratch
directory, and a stub `launchctl` was placed first on `PATH` and confirmed to be the one that
resolves, so every `bootout`, `bootstrap` and `kickstart` the service steps issue was recorded by
the stub instead of reaching launchd. The label `dev.remote-control.client` is a module constant and
cannot be pointed elsewhere, which is why the stub exists; after the pass, the real agent was still
`state = running` against `~/Library/LaunchAgents`. The restart itself is therefore simulated: no
launchd job was actually torn down and brought back on the new code.

| Step | Result |
| --- | --- |
| `install.sh --gateway … --pair …` | installed 0.1.0 and wrote `state/client-build` = `f27ea071…`, mode `0600` |
| `rc-client status` | `client build   f27ea071…` |
| `self-update --build cccc…` (not what is served) | exit 3, "not the requested build", nothing installed, build file unchanged |
| `self-update --build 9d97cad7…` (what is served) | exit 0 |

The accepted pass downloaded the wheel into `state/update-fzcitpzt/`, never `/tmp`; `uv pip install`
reported `- rc-client==0.1.0` / `+ rc-client==0.1.1`, and `site-packages` afterwards holds
`rc_client-0.1.1.dist-info`. The build file became `9d97cad7…`. `shim install` ran **without**
`--no-shell-rc` and answered "already configured", which is the point: the updater reads the shell
startup file and leaves the installer's choice as it found it. The working directory was gone
afterwards.

`rc-client --version` still says `0.1.0` after the upgrade — `__version__` is a literal in
`rc_client/__init__.py` and the second wheel only had its `pyproject.toml` version bumped. That is
an artefact of how the test wheel was built, not of the update.

Covered by tests rather than by this pass: the daemon's side of it. `tests/test_daemon.py` drives
`device.update` over a real socket for all four answers (`unsupported` with no build file,
`conflict` on the running build, `conflict` on a busy session, `{accepted, from}`), and drives the
watcher that sends `update.failed` with the log's last line when the spawned updater exits non-zero.
The updater is a fake process there, so the one thing still unproven is a real detached
`self-update` surviving the restart of the service that spawned it.

## 17. Grok Build over ACP (2026-09-13, A25)

Two authorized runs of the real `~/.grok/bin/agent agent --no-leader stdio` (grok 1.0.25), both with
the prompt "Reply with exactly OK", in a scratch directory, with every yolo flag off. Nothing under
`~/.grok` was written except the session directory those two runs necessarily created, and no
configuration was changed. Every line of both runs is recorded in
`client/tests/fixtures/grok/turn.jsonl`, `resume.jsonl`, `handshake.json` and
`resume-handshake.json`; `terminal-updates.jsonl` is the `updates.jsonl` those runs left on disk.
The translator, the runner and the mirror are all tested against those recordings.

What the runs settled:

| Question | Answer |
| --- | --- |
| `agent agent stdio --cwd … --permission-mode …` | **Rejected.** `error: unexpected argument '--permission-mode' found`, exit 2. Both flags belong to the TUI; the ACP subcommand takes `--model` and `--reasoning-effort` only |
| Working directory | `session/new {cwd, mcpServers: []}` |
| Permission mode | `session/set_mode {sessionId, modeId}` → `{}`, followed by a `current_mode_update` notification. Driven with `modeId: "plan"` |
| Live effort and model | `session/set_config_option`, value a **plain string**. The shipped docs' `{"value": "low"}` wrapper is refused: `Invalid params … untagged enum SessionConfigOptionValue`. The reply is the complete `configOptions` list |
| Interrupt | `session/cancel` as a *request* answers `-32601 Method not found`; ACP defines it as a notification, which is how the runner sends it. **Not verified** |
| Resume | `session/load` works and **replays the whole conversation** as `session/update` notifications carrying `_meta.isReplay: true`; the translator drops them, so a resumed session does not double its timeline |
| Attachments | `initialize` answers `promptCapabilities: {"image": false, "audio": false}`, so the capability is not advertised |
| Streaming | Thinking and text arrive token by token as `agent_thought_chunk` / `agent_message_chunk` with `_meta.streamStartMs`; the on-disk log coalesces the same chunks into fewer rows, and both assemble into the same block |
| Usage | `turn_completed` carries the turn's tokens plus `costUsdTicks`: 143072000 ticks, that is 0.0143072 USD, for the first run |
| The end of a turn | `_x.ai/session_notification` / `_x.ai/session/update` with `sessionUpdate: "turn_completed"`, carrying `stop_reason`, `usage` and `elapsed_ms` |
| Event ids | `_meta.eventId` is `<sessionId>-<n>` and is **not** written in strict order: in the owner's own logs an `agent_message_chunk` at `-35` follows rows at `-37` and `-41`. The cursor is therefore a resume floor, never a running maximum |

`rc-client agents` on this machine reports grok 1.0.30 (the binary auto-updated after the recon),
`~/.grok/bin/agent`, the two models, the six permission modes, four efforts and no speed tiers.
`default_effort` reads `xhigh` here because the owner's `~/.grok/config.toml` says so; on a machine
with no such setting it is `high`, which is what `fixtures/objects/agent.grok.json` shows, and
`tests/test_grok_discovery.py` asserts the whole object against that fixture.

Not verified: a real approval round trip, a real interrupt, an `ask_user_question`, a tool call or a
plan from a live run (one prompt that answers "OK" makes none of those), the leader process, and
`~/.grok/active_sessions.json` with anything in it — the file exists and reads `[]` on this machine.
Tool, plan and diff translation is tested against rows built from the shapes the owner's own session
logs show, and is marked as constructed in the test module.

## 18. pi, attached through the device's own extension (2026-09-14, A26)

pi 0.85.1 at `/opt/homebrew/bin/pi`, an xAI credential in `~/.pi/agent/auth.json`, default model
`xai/grok-4.6`. Nine short prompts were sent to a real model, all in a scratch working directory
outside the repository. The `~/.pi/agent/` files of this machine were read and never written, apart
from `extensions/remote-control.ts`, which `rc-client pi setup` installed and `rc-client pi remove`
took out again cleanly at the end.

**The extension loads and speaks.** A `pi --mode rpc` child with `-e <bundled file>` produced no
`extension_error` on any run. The `hello` it sends carries pi's session id, session file, cwd, pid,
`ctx.mode`, `provider/model`, the thinking level and the branch. 37 `message_update` deltas of one
turn crossed the socket and reassembled into the same blocks the stdout path produces. The events
the translator reads were recorded from that run rather than taken from the documentation: the
delta types, `tool_execution_start/_update/_end` with `partialResult` as the accumulated output,
`message_end` with `stopReason` and `errorMessage`, and `agent_settled` ending the turn.

**Approvals, both ways.** In `on-request` mode a `bash` call raised an `approval` block offering
`allow`, `allow_session` and `deny`. Answered `allow` from the device, the tool ran and returned
`from-terminal\n`. Answered `deny`, pi finalised the call as `isError: true` with the text
`Denied from Remote Control` and carried on with the turn rather than ending it.

**A real terminal pi, attached.** `pi` was started inside a pty, with the extension installed
globally and no `-e`. It registered as `control: "shared"` with its real cwd, `xai/grok-4.6`,
thinking level `low` and permission mode `on-request`. A prompt typed at the keyboard opened a turn
with trigger `terminal` and a `user_message` with `source: "terminal"`; its tool call was approved
from the device (`decision {option_id: allow, by: remote}`) and the turn ended `completed` with real
totals — 6037 tokens, $0.00705, 3032 of a 500k context window. A `session.send` from the device was
accepted `sent`, appeared as `source: "remote"` under the id the request carried, and its tool call
was answered at the keyboard instead: the block resolved as
`{option_id: elsewhere, by: terminal}`, which is A20's rule. Ctrl-D dropped the session to
`control: "none"` with the runner released.

**Resume.** `pi --mode rpc --session-id <the terminal session's id>` in the same cwd reopened that
conversation with all 12 branch entries, which is the path a `control: "none"` pi session takes when
an app sends to it again.

**Images.** An image sent as pi's own `ImageContent` — `{type: "image", data, mimeType}` — reached
the model, which named the colour correctly. The nested `source: {type: "base64", mediaType, data}`
form that `docs/extensions.md` shows is not what the running binary accepts; the flat shape is, and
is what the device sends both on `prompt.images` and through the extension. An 8x8 test image was
refused by xAI for being under its 512-pixel minimum, which is the provider's rule and not pi's.

**Session totals on an attached session** are summed by the extension from the branch's assistant
messages; the figures above came back through the socket's `stats` command in the same shape
`get_session_stats` returns.

Not verified against the real binary: `/tree`, `/fork` and `/clone` inside an attached TUI; a
compaction on an attached session; steering an attached terminal session from an app; stopping one;
two devices attached to one pi; whether `abort` always settles, for which the device ends the turn
itself after sixty seconds. Each of those paths has a test against a fake extension client in
`client/tests/test_pi_extension.py`.

## 19. Slash commands from the apps (2026-09-14, A27)

Each agent was probed twice: once to establish what is reachable at all (`recon/slash-commands.md`
in the round's scratchpad), then again through the adapter as shipped. Every model turn ran in a
scratch working directory outside the repository, and every thread, session and file created was
removed afterwards.

**Codex 0.154.0, the real shared daemon.** A `turn/start` whose input is `/status` ran an ordinary
model turn answering the literal string, which is why Codex's list is a fixed table. Against a
throwaway thread (`clientInfo.name` `rc-codex-commands-test`, deleted with `codex delete --force`):
`thread/compact/start` produced the `contextCompaction` item that becomes the notice "Context was
compacted; earlier turns are summarised."; `review/start {delivery: "inline"}` produced
`enteredReviewMode`, the reviewer's own turn and `exitedReviewMode`, and the brief Codex feeds the
reviewer as a `userMessage` was not mirrored as a bubble; `/init` sent the prompt read out of the
binary behind a bubble that still reads `/init`; `/status` was exercised on a thread the daemon
refused to resume, so its settings came from `thread/read` and its sandbox from `config/read`;
`/usage` read `account/usage/read` and `account/rateLimits/read`; `/skills` listed 53 skills,
`/mcp` 8 servers with 301 tools behind one of them, `/hooks` the configured hooks; `/diff` ran
`git diff --stat`, `git diff` and `git ls-files --others --exclude-standard` through `command/exec`
in the thread's cwd. Each information command arrived as one `tool_call` block titled with the
command, opened `running` and replaced `succeeded`.

**Grok Build 1.0.30, zero model turns.** A real session advertised 75 commands in its
`available_commands_update`, of which the device lists 72 (13 built-in, 49 skills, 8 plugin
commands, 2 workflows); `always-approve`, `context` and `statusline` are left out for the reasons
`agents/grok/commands.py` states. `/hooks-list` sent as the text of `session/prompt` finished in
4 ms, spent no tokens, echoed under the block id the device chose with `source: "remote"`, and its
output arrived as `assistant_text` with a `turn_completed` carrying no usage. `/context` was
accepted and produced nothing over ACP, which is why it is not listed. The list written to
`~/.rc-client/state/grok-commands.json` read back identical to the one in memory.

**pi 0.85.1, about half a cent on `xai/grok-4.3`.** In RPC mode `get_commands` listed 8 entries and
`argument-hint` was read back from a template's file as `[note]`; a prompt of `/rc-global probe`
was expanded into the template's body before the turn and the model answered `OK probe`; `compact`
on a fresh session was refused with `Nothing to compact (session too small)`, which reached the
device as `bad_request` in pi's words and produced no duplicate notice. In a terminal pi with the
new extension installed, `commands` over the socket returned 7 entries, a `send` with `echo: false`
produced no `input` frame, and a compaction the terminal ran was forwarded as
`{"type": "compaction_end", "reason": "manual", "aborted": false}`. The extension was removed from
`~/.pi/agent/extensions/` afterwards, so this Mac reports `attach_ready: false` for pi until
`rc-client pi setup` runs again.

**The apps.** Web: 457 tests, `tsc`, `eslint` and the production build; screenshots
`shots/web-commands-{codex,filter,ran,pi-groups,grok,hint,running,mobile}.png` at 1280 px and
400 px, driven against the mock gateway. iOS: 265 unit tests, RCVerify 1154 checks, RCUIVerify
226 checks, 47 UI tests (4 skipped) on the iPhone 17 simulator; screenshots
`shots/ios-commands-9{0..6}-*.png`. Neither app has listed or run a command against a real device
yet: what the three agents offer is the client's word.

Not verified: a Codex `/review` with custom instructions end to end (only the uncommitted-changes
form ran a turn); a pi extension command that registers itself through `pi.registerCommand` being
listed by `pi.getCommands()` on a TUI session with third-party extensions installed; Grok's list
after a plugin or skill reload mid-session.

## 20. The device brings the Codex daemon up itself (2026-09-14)

Three reports from other machines — Codex installed with npm, Codex upgraded, npm swapped for the
curl build — turned out to be one missing daemon. Everything below ran on this Mac in isolated
`CODEX_HOME` directories with Codex 0.154.0, 0.153.0 and 0.145.0; the real `~/.codex` daemon was
never stopped, restarted or bootstrapped, and `codex app-server daemon version` on the real home
reported the same three versions and `status: running` before and after.

**What Codex does.** A bare standalone TUI with no daemon reached its prompt and exited 35 s later
with the control socket still absent: it never starts the shared daemon. An npm-launched TUI
(`@openai/codex@0.154.0` in a local prefix) joined a running daemon like any other, adding its
thread to `thread/loaded/list`; the npm build cannot run any `daemon` subcommand against a home with
no `packages/standalone` (exit 1). A daemon on 0.153.0 kept serving after 0.154.0 was installed over
it and a 0.154.0 TUI joined it anyway, as did one across a nine-minor gap (daemon 0.145.0, CLI
0.154.0); `daemon start` answered `alreadyRunning` and left the drift, `daemon restart` cleared it in
one step, and `daemon version` was the only place it showed. `codex update` is `curl … | sh` in a
wrapper: the daemon's pid file was byte-identical before and after. `daemon start` alone, with no
bootstrap and no supervision, brought the daemon up in 0.33 s and a bare TUI then joined.

**What the device does now.** In a lab home with the standalone installed and the socket absent,
one `tick()` of `CodexDaemonService` ran `daemon start`, the socket appeared, `ready` went true and
the handshake succeeded; the supervision step was stubbed as "loaded" so no launchd job was written
under the lab, and that path is covered by unit tests. The `start`, `restart` and `version`
wrappers parsed the real daemon's JSON. The official installer was run twice into lab homes, once
plainly and once the way `codex setup` now runs it — `HOME` pointed at a throwaway directory,
`CODEX_HOME` and `CODEX_INSTALL_DIR` pinned — and the two trees were identical apart from a per-run
temporary directory name; the `# >>> Codex installer >>>` block landed in the throwaway home and
the checksum of the real `~/.zprofile` did not change.

**Two installer facts that changed the code.** The installer appends to a shell profile whenever
another `codex` is on PATH, whatever else is true, and it classifies the standalone build itself as
npm-managed because the binary embeds `#!/usr/bin/env node` in a bundled docs script — so on any
machine that already has Codex the rewrite happens, and the old "`~/.local/bin` is on PATH"
condition in `setup.py` never prevented it. During the research an installer run did repoint the
real `~/.local/bin/codex` and `codex-code-mode-host` into a lab home and append to `~/.zprofile`;
both were restored the same hour, which is the incident the throwaway `HOME` exists to prevent.

Not verified: the restart path against a real drifted daemon with our device connected (the drift
was produced and cleared by hand in the lab; the device's own restart was exercised against fakes),
and the systemd variant of supervision-from-the-daemon on Linux. Unexplained and unrepeated: during
the 0.145.0 sequence the `current` symlink once moved back to 0.154.0 on its own.

## 21. Grok Build attached through its leader (2026-09-14, A28)

A Grok session opened in a terminal showed "controlled by the terminal" in the apps, because the
device only tailed the update log Grok writes. Everything below ran on this Mac against grok 1.0.30
in isolated `GROK_HOME` directories (a copied `auth.json`, a scratch project, `[cli] use_leader =
true`), with the leader on a short `--leader-socket` path because the default `$GROK_HOME/leader.sock`
under the scratchpad exceeds the 104-byte socket limit; the real `~/.grok` was never written, no
leader was ever started on the real socket, and every scratch home was deleted afterwards. Each probe
turn was a one-word reply. Scripts: the session's `scratchpad/grok-leader/leader_probe*.py`.

**What Grok does.** With `use_leader` on, a TUI started with no leader running starts one itself —
`agent agent leader --no-exit-on-disconnect --relay-on-demand …`, a child that outlives it — and so
does an `agent agent --leader stdio` client (0.4 s to `initialize`); the leader stays up with no
clients at all. A `session/load` from a second client on a session the TUI has open joined it
(leader log: "reconnecting to existing session") and replayed the conversation with
`_meta.isReplay: true` and the same `eventId` counter the update log carries. A turn typed at the TUI
arrived at two device clients as the same `user_message_chunk` → `agent_thought_chunk` →
`agent_message_chunk` → `turn_completed` sequence; a `session/prompt` from a device client ran in
the same conversation, the TUI rendered the reply, and both landed in `chat_history.jsonl`.
`session/cancel` from a device client ended a TUI-started turn in 1.2 s with `stop_reason:
"cancelled"`; `session/set_config_option` from a device client changed the effort for everyone and
was mirrored as `config_option_update` + `model_changed`; process flags (`--reasoning-effort xhigh`)
were ignored under the leader. When the TUI quit (`/exit`) no client was told anything, Grok's own
registry `~/.grok/active_sessions.json` dropped the entry, and the session stayed loaded and
promptable; a later `grok --resume <id>` joined it in the same leader. `_x.ai/session/info` answered
a full object for a loaded session and `{}` for one on disk only, which is the test for "is a
registered TUI inside the leader"; a TUI with `use_leader` off held its `events.jsonl` open itself,
where in leader mode the leader holds it, and nobody ever holds `updates.jsonl` open in either mode.
`session/close` from a device client unloaded the TUI's session under it — the TUI's next prompt
never ran — so the device never sends it for a session a terminal registered. One TUI-driven turn
that needed approval (`rm <file>`) sent `session/request_permission` to the joined device client and
drew the TUI's dialog at once; the device's answer resolved both and the turn continued. The options
included `enable-always-approve` ("Yes, and don't ask again for anything", kind `allow_once`),
which the device never offers. `pending_interaction {tool_call_id, kind: "permission"}` and
`interaction_resolved {tool_call_id}` were broadcast around every approval. Later probes had Grok's
default mode allow `rm` and writes outside the workspace by itself in about ten milliseconds, so
whether a command prompts is Grok's decision, not the attachment's.

**What the device does now.** Against the same isolated home, a real TUI in `tmux` and the new
`GrokLeaderService` driven by a script: the registry entry became `shared` with the model and
effort read from the load result and the leader reporting 1.0.30; a prompt sent from the device
completed and its reply appeared in the TUI's frames; a turn typed at the TUI was ended by the
device's `session/cancel` and the terminal's message had been mirrored; `/exit` moved the session
to `none` and a further device prompt still ran; the leader's log showed no `session/close`. Three
one-word turns, about $0.003. Unit tests cover the control table, the cursor hand-off between
mirror and leader, echo dropping, the option filter, `interaction_resolved`, reconnection, the
mirror standing down, and `setup` editing a symlinked configuration without changing its inode:
912 passed, 3 skipped. The web app: 464 tests; the hint under a terminal Grok session and the live
composer of a shared one were driven in Chrome at 1280 px and 400 px.

One incident: during the work a `grok agent --leader stdio` was run without `GROK_HOME`, which
started a leader on the real `~/.grok/leader.sock` at 16:54. It loaded no session; it was stopped
and its socket and log removed, and `~/.grok/config.toml` was never changed. Not verified: a real
device daemon enrolled on a gateway joining a terminal `grok` end to end from a phone, the drift
restart against a real drifted leader, and a second approval run.

## 22. Dictation polish (2026-09-14, A29)

The gateway's polish endpoints ran only against a fake provider (`httpx.MockTransport`) in the
test suite: the models list with and without the `POLISH_MODELS` allowlist and the fallback when a
provider serves no `/models`, the completion call with the two strengths' prompts, quote stripping,
an empty answer and a timeout both answered as `502` `upstream`, `503` `unsupported` with the
variables unset, `400` for an empty or over-long text, an unknown strength, too many or over-long
context items and a model outside the allowlist, `429` past thirty requests a minute from one
address, and `hello` / `GET /api/config` carrying `polish.enabled`; the three protocol fixtures
decode as request and response bodies. 326 gateway tests. No real OpenAI-compatible provider was
called, so the quality of the polish itself — how a given model applies "moderate" and "strong" —
is unobserved; the web flow was driven in Chrome against the mock gateway's fake polish only.

## 23. Words another agent put into a Claude conversation (2026-09-14, A30)

The classifier was written against rows copied from this Mac's own Claude transcripts — the
"Another Claude session sent a message:" envelope with its JSON body, a `<task-notification>` with
summary and result followed by a `<system-reminder>`, a person's prompt with a reminder appended, a
reminder-only row, and an `isMeta` channel echo — and then replayed over every transcript under
`~/.claude/projects/-Users-junbingao-github-remote-control/` in a throwaway script: 215 teammate
messages and 22 task notifications came out `agent`, all 141 human prompts stayed `terminal`, and
the rows carrying `origin: {kind: "human"}` were what showed that "any origin kind but channel"
would have been wrong. 934 client tests. Not verified: a live Claude session receiving a teammate's
message while the device tails it (the replay was offline), and the SDK stream of a device-driven
session delivering such a row.

## 24. The gateway states the oldest iOS app it supports (2026-09-14, A31)

Gateway tests only: the constant is a release version, seven malformed overrides and a non-`https`
`IOS_UPDATE_URL` stop the gateway at startup with the variable named, `apps` appears in
`GET /api/health` (without a credential), `GET /api/config` and `hello` with the fixtures' key set
and validates against the schema with and without the URL. 342 gateway tests. The iOS side — the
comparison and the blocking screen — is the next iOS round and is not verified here.

## 25. Rows nobody typed, an inert bubble, and a centred way back down (2026-09-15, A32)

The client rules were written against this Mac's own transcript of the session that produced the
owner's screenshot — the plain `/compact` row, the `compact_boundary` system row, the summary
marked `isCompactSummary`, the `<command-name>` and `<local-command-stdout>` rows and the
`[Request interrupted by user]` marker, copied into tests and emptied of anything private — and
those rows were then replayed through the tailer: one `/compact` bubble, one compaction notice, no
block for the summary or the marker, the turn closed by the CLI's reply and `stop_reason:
"interrupted"` after the marker, with a `turn_completed` and no `turn_started` for it through the
shared session. The "N background agents were stopped" row in the screenshot was already `agent`
under A30; it had been published before the device on this Mac was updated. 953 client tests. Not
verified: a live interruption or compaction while the device tails the session, and the SDK
stream of a device-driven session delivering a `compact_boundary`.

Web, in Chrome against the mock gateway: hovering a user bubble changes nothing — the rested and
hovered screenshots are byte-identical — once the accounts screen stopped sharing the chat's
`.user-row` class; the back-to-latest button's centre matched the timeline's at 1280 px (772) and
390 px (195), with and without a count, and a click left no scroll remainder. 495 web tests, one
of them the guard that no two feature stylesheets declare the same root class.

iOS, in the simulator against the demo: the jump button is centred at the foot of the transcript;
the new UI test grows the transcript to about eight screens of uneven rows, pages to the top and
taps the button, and the newest message is hittable afterwards with the button gone. The landing
short the owner saw on the phone did not reproduce in the demo — the old one-shot scroll reached
the tail in three runs — so what shipped is the rule that arrival is read from the scroll view and
retried, not a fix of a recorded trace. 1288 + 274 checks, 303 unit tests, 53 UI tests (4 skipped).
Not verified: the jump on a real transcript of several hundred rows, which is where it was seen.

## 26. How each agent is signed in, what is left of its quota, and a turn that ends when the CLI says so (2026-09-15, A33, A34)

The account rules were written against this Mac's own credential files, read once and copied into
tests emptied of every secret: the Claude Code Keychain item (`claudeAiOauth` with
`subscriptionType`, `rateLimitTier`, `expiresAt`), `~/.claude.json`'s `oauthAccount`, Codex's
`auth.json` with the plan inside its `id_token`, Grok Build's `auth.json` keyed by OIDC issuer, and
pi's per-provider `auth.json`. Anthropic's OAuth usage endpoint was called once by hand with the
owner's token and answered HTTP 200 with `limits[]` rows of kind `session`, `weekly_all` and
`weekly_scoped` beside the older `five_hour` / `seven_day` pair; Codex's `account/rateLimits/read`
was already read by the `/usage` command. A read-only probe of the finished detector on this Mac
reported Claude Code as an Anthropic account on plan `team`, tier "Max 5x", with three windows;
Codex as an OpenAI account on `pro` with one window, because this account's `secondary` window is
null today; Grok Build as an xAI account with no windows and no error; pi as one `xai` account. The
gateway needed no change and its 342 tests pass with the new fixtures. 990 client tests. Not
verified: the Keychain read from the daemon as launchd starts it rather than from a shell in the
login session, a Codex account reporting both windows, and a third-party endpoint on a real
machine.

The turn-end rule (A34) came from this session's own transcript: of about 3,300 assistant rows,
96 hold only the sentence the model says before its tool calls and carry `stop_reason:
"tool_use"`, and no message's rows disagree on their `stop_reason`. The two messages the owner sent
from the phone during the turn show the consequence — `queue-operation enqueue` at 11:53:03, a
`queued_command` attachment, `remove` with `reason: "absorbed_mid_turn"` at 11:53:41, the device's
re-send at 11:53:42 and a second `absorbed_mid_turn` at 11:54:03 — and the new tests replay a
text-only `tool_use` row, an `end_turn` row and a held message through the tailer and the shared
control. Not verified: a message held during a live attached turn after the fix.

Web, in Chrome against the mock gateway: the device page at 1280 px and 390 px with the four
account shapes; the meter's fill measured as the ink colour and, at 84 %, the warning colour; both
reset wordings ("resets 22:49", "resets Fri 20:19"); the row's menu still opens without navigating.
A message from another agent draws as a block whose left edge (412 px) is the assistant prose's,
where the person's bubble sits at 805 px, and Simple draws none of them while the person's bubbles
and the prose stay. 543 web tests.

iOS, in the simulator against the demo: the device page over six demo shapes — an account with
windows, an account with none, a key with a third-party host, not signed in, a failed read, an
offline device — with "Checking…" until the delayed `device.agents` reply; the glow screenshotted
at input levels 0, 0.5 and 1 before and after, the after images plainly brighter at the edges with
the text still readable. A message from another agent draws as a block on the leading side whose
left edge is the assistant text's and whose width exceeds the person's bubble, and Simple draws
none of them, screenshotted at both levels. 1288 + 305 checks, 322 unit tests, 57 UI tests
(4 skipped; in both full runs one unrelated test, the tool card under a raised keyboard, timed out
at 30 s waiting for a scripted turn while three agents loaded this Mac, and passed alone in 41 s).
Not verified: the pull-to-refresh gesture itself, the glow on a phone's own microphone, and the
real transcript of several hundred rows.

## 27. Done becomes a spinner, and the spinner becomes Send (2026-09-16, iOS and web)

The owner reported that after Done the app waits for the final transcript and then for the polish
answer while Done still looks tappable. The cause was read from the code: `PrimaryButtonStyle` has
no disabled look, so the Done that `VoiceListeningControls` kept drawing through `.finishing` read
as live, and once the transcript was final the ordinary row came back with Send enabled while the
model was still writing. The slot is now one derivation, `ComposerPrimarySlot`, checked over all
twenty-four pairs of dictation and polish phases: Done while the microphone is live, a spinner in
Send's circle while the transcript or the answer is on its way, Send otherwise. An edit typed while
the request is out cancels it (tested: the late answer never lands), and the code was read to
confirm nothing but the person writes the draft in that phase. In the simulator against the demo,
with polish on and the demo's three-second polish delay: after the tap on Done the spinner stands
where Send stood, against the trailing edge at Send's height, with no Send and no Done on the
screen, "Polishing…" above the field; when the note "Polished · Undo" appears the arrow is back and
nothing spins. With polish off, Send is back at once and no spinner is left. 1288 + 305 checks,
325 unit tests, 57 UI tests (4 skipped, 0 failures, 966 s for the whole suite; the tool-card test that timed out under load in round 27 passed). Not verified: the spinner during `.finishing` on a real
microphone or the gateway backend (the scripted platform answers Done in the same pass, so that
phase lasts one frame in the tests), and Reduce Motion by eye.

Web, asked for by the owner as a follow-up. The same derivation, `primarySlot`, over the five voice
states and four polish phases (twenty pairs, each tested): Done while starting or listening, the
spinner while finishing or polishing, Send otherwise. The working element is a `role="status"`
span in Send's pill, not a button; Enter goes through the same gate as the button and does nothing
while the slot is not Send, and the "⋯" send menu is not drawn then either. Typing already dropped
the request on the web. The status line now reads "Finishing the transcript" while the backend
finishes. In Chrome against the mock, with the polish reply held back from the page for the
screenshot: at 1280 px and 400 px the spinner stands in Send's pill with "Polishing…" above the
field and no Send on the page; when the answer lands the arrow is back with "Polished · Undo". 550
web tests (543 before). Judged along the way and left for the owner: Enter while listening no
longer sends the partial transcript — it used to — because a keystroke that sends mid-dictation is
"stop and send" in another guise, which the design rules out; and on both apps the moment before
the microphone is granted still shows the disabled Done, which the ruling did not cover.

## 28. The review's 47 findings, fixed; every component carries its version (2026-09-16, round 29)

Four fixers worked the four review files in parallel, each with a test that failed on the old
source and passes now; the orchestrator re-ran every toolchain and read the diffs behind each High.

Gateway (342 → 366 tests). GW-1 measured through the forward path with one protocol-legal maximal
`session.send` (8 × 6 MiB, 64 MiB on the wire): live payload after `json.loads` 128 → 64 MiB,
live when queued 192 → 128 MiB, peak allocation 256 → 142 MiB, process peak RSS 310 → 248 MiB (RSS
understates it, because CPython keeps freed arenas). The byte budget admits one maximal frame; the
arithmetic against `mem_limit: 512m` is in `rc_gateway/budget.py`, and two frames would need
768 MiB, which was not granted — a second large send while one is forwarding is answered
`too_large`, which the apps show with their permanent-refusal copy (a `busy` code would be an
amendment; left as is). GW-2 reproduced with two accounts before the fix and refused after; the
replay ceiling is now 16 × 4 MiB because the 4 MiB per session is a wire rule (§ Bounds) and was
not changed. Not verified: a container run, real push delivery, the 4009 close against a real app.

Web (550 → 578 tests). WEB-1 reproduced with the reviewer's probe shape (real `ChatPage`,
`MemoryRouter`, A → B → A) and fixed by a per-session drafts store with the composer keyed on the
session; sign-out resets every store, including `sessions`, `devices` and `users`, so the landing
page waits for the new account's `hello` rather than painting the last account's devices. DOM
windowing of the transcript was left: the store cap bounds growth, and a virtual list would mean
rewriting the scroll-follow. No browser run this round.

iOS (RCVerify 1288 → 1293, RCUIVerify 305 → 345, unit tests 325 → 340). IOSC-1 and IOSC-2
reproduced as tests in the reviewer's two scenarios and pass with the scope counter; IOSC-3, IOSC-4
and IOSC-7 likewise (IOSC-7 confirmed real: the stale full-output reply did revert the block).
`ChatStore.send` now answers accepted / uncertain / refused / empty so the composer restores a
refused send's attachments without inferring it from the field. IOSU-1's callback order was
proved with a throw-away app on the simulator, and the fix is checked in RCUIVerify with the
same order. The full UI suite: 62 tests (5 new), 4 skipped, 0 failures, 1 087 s. Not verified on hardware: the camera-denied
scanner, the Local Network prompt (IOSU-6), Face ID on `.inactive`, the gateway recogniser's
segment pruning; no automated check for IOSU-13/14/15 (view state, `#if os(iOS)` paths RCUIVerify
cannot compile on macOS) and IOSU-9 (a mapped read of a temporary file does not fail on macOS).

Two things learnt about the suite itself. `xcode-select -p` on this Mac is the Command Line Tools,
so when a UI test fails xcodebuild's diagnostics collector cannot find `simctl` and the whole run
aborts at the first failure with no assertion text in the console; runs with no failure are
unaffected. And `testAgentMessageSitsOnTheAgentsSideAndSimpleHidesIt` failed twice and passed
twice on the same build: the agent's report sits near the top of a lazily laid-out transcript that
opens at its foot, so whether the row exists in the tree depended on whether the demo's question
card had landed yet. The test now scrolls the row into view; RCUIVerify has a check that the store
draws the message at Detailed after a reopen, which held throughout.

Versions: gateway 1.3.0, web 1.3.0, iOS `MARKETING_VERSION` 1.3.0 (build 2), and the device client
1.3.0 too — its code did not change this round, but the owner's rule is one number per release
across all four components, so every enrolled device will offer this update once the gateway is
redeployed and the wheel it serves is rebuilt. `IOS_MINIMUM_APP_VERSION` stays 0.1.0 because
nothing here breaks an older app. Repo tag v1.3.

## 29. Pull request #1 finished and merged: the real claude behind the shim, and a proxy on request (2026-09-16, client 1.3.1)

The contributor's two fixes were reviewed in a clean worktree (`scratchpad/review/pr-1.md`), and
the owner chose to finish them in the repository rather than send them back. Fix 1 was confirmed
real by reverting only `agents/claude/runtime.py` to `master`: the new test fails with
`resolve_binary() is None` — the shim first on the daemon's PATH hid a `claude` installed under
nvm — and passes with the PATH walk. Fix 2 was reshaped to the owner's rulings: the stored proxy is
`""` or one http/https URL; socks is refused at enrollment (the branch had accepted it with no
package able to dial it, and `rc-client enroll --proxy socks5://…` exited on an `ImportError`
traceback); `--proxy env` is resolved once on the enrolling machine with `getproxies()` and
`proxy_bypass()` for the gateway's own URL and the result is what `config.toml` keeps, so the
launchd plist and the systemd unit — which carry no proxy variables — no longer matter; every
printed proxy is stripped of its credentials (the branch echoed a mistyped URL, password included,
into stderr and the service log); `rc-client status` shows the proxy; `install.sh --proxy URL`
exports the variables for its own downloads. One PATH walk serves the shim and the daemon. Client
tests 1003 → 1018; ruff, format and mypy clean. Verified by hand on this Mac: with no proxy variable
set, `getproxies()` returns the system network setting (a local proxy here), which is why
`environment_proxy` documents that macOS reads System Settings when the variables are empty.
Not verified: `install.sh --proxy` and `service install` on a real host, and the contributor's
cluster node itself. The PR description's "1 failed" (`test_grok_leader`) did not reproduce here.
The four components moved to 1.3.1 together (iOS build 3), tag v1.3.1.

## 30. An update names its version and leaves the device as a fresh install would; adding a device asks no platform (2026-09-16, 1.3.2)

The owner reported that after Update a device did not show what a fresh install showed. Read from
the code and this Mac's own `~/.rc-client` (read-only): `self-update` downloads the served wheel,
verifies its SHA-256, `uv pip install`s it, re-runs `service install` (bootout + bootstrap, so the
daemon restarts on the new code — the local daemon's start time is ten seconds after the last
update's log line) and `shim install`; the build it installed on 2026-09-15 already carried the A33
account code, and a launchd-started process reads the Claude Keychain item (probed with a one-off
`launchctl submit` job that printed only OK/FAIL, then removed). So the device side of Update was
sound; what the person saw was the gateway, the web app or the iOS app still being older than the
device — three things that update separately — made worse by every build being called 0.1.0. Two
changes follow. The updater now also refreshes the pi extension when one is installed (it is a file
copied out of the wheel), never installs Codex and never touches Grok's leader flag (client tests
1018 → 1021, each new test failing on the old source). The apps name the version an update installs
— "Update available · 1.3.2", "Update <name> to 1.3.2?" — from `GET /api/config` `client.version`,
with the old wording on a gateway that states none (web 578 → 580; iOS RCUIVerify 345 → 351,
unit 340 → 341). The demo's devices now report realistic client versions so a screenshot of
an update does not promise 1.3.2 and show 0.1.0 afterwards.

Adding a device no longer asks macOS or Linux: the gateway hands out the same command under both
keys (`device_routes.py` builds `{"macos": command, "linux": command}`) and `install.sh` tells the
platforms apart itself, so the picker on both apps changed nothing. The protocol keeps both keys.
Full iOS UI suite: 62 tests, 4 skipped, 1 failure in 1 366 s — `testOnlyAnAdminIsOfferedTheUsersScreen`, whose typed gateway address lost its last three characters (`rc.test.exam`), the simulator keyboard flake already seen in round 29; it passed alone in 44 s and the login form did not change this round. Not verified: a real `self-update` with an installed pi extension
(the tests fake the steps), and the new wording against a real gateway. All four components 1.3.2
(iOS build 4), tag v1.3.2.

## 31. While dictation runs, the field follows the words (2026-09-16, 1.3.3)

The owner reported that a long dictation on the phone left the composer's field on its first screen
until Done. The cause was read from the code: dictation writes the draft programmatically with the
keyboard down, so nothing kept the end of a text past eight lines in view — on iOS the
`UITextView` inside `GrowingTextField` scrolls only when a caret moves, and on the web the
textarea's `scrollTop` was never touched after the height was capped. Both apps now scroll the
field to its last line, without animation, on every transcript write that lands past the visible
lines while listening and through the finishing spinner, and never while typing. The web also
gained the takeover the phone already had: a pointer down on the field while dictating ends the
dictation and keeps the words, so reading back is possible and the field stops following; the
field's own programmatic focus does not count. Web tests 580 → 584 (jsdom with a real
`scrollHeight` defined on the textarea; each new test failed on the old source). iOS: RCUIVerify 351 → 355 (the field probe and the long scripted dictation), RCVerify 1293 and unit tests 341 unchanged, one new UI test that failed with the follow switched off and passes with it on; the demo now derives the client version it serves from the app's own, so the two version literals left in `ios/` check each other.
Not verified: either app against a real microphone with a long utterance; the web in a browser
(vitest only). Full iOS UI suite: the first run showed 9 failures out of 63, the second 2, and every one of them
passed alone; what the two runs taught is recorded here because it cost an evening. The simulator
had been left with system language `zh-Hans-SG`, which gives it the Pinyin keyboard, and one early
failure under that keyboard left `/usage` in the per-session draft file — which `--reset-state`
did not clear — so every later launch opened the Codex demo session with those words already in
the field (`//usageusage` after typing), and the command panel could not open. `--reset-state`
now empties the draft store first thing in the launch task (RCUIVerify check and a unit test,
each failing on the old source), the simulator is English again, and `docs/IOS.md` says how to
keep it so. The two failures of the second run (`testSentMessageAppearsBeforeTheDeviceConfirmsIt`,
`testSharedGrokSessionStopsAndRetunesButTakesNoAttachments`) are the typed-text mismatch seen
since round 29, on a Mac whose load average was 29 while the suite ran for 1 650 s; both passed
alone. A guard that would make the text field ignore SwiftUI's own echoes while an agent streams
was tried and withdrawn, because no test could be written that failed without it. All four components 1.3.3 (iOS build 5), tag v1.3.3.

## 32. The status dot: green works, amber is for you (2026-09-17, 1.3.4)

The owner changed what the session dot's colour means so that the colour alone says whether to
look: a running session is a steady green (it pulsed), a finished turn waiting to be read is a
steady amber (it was a steady green, indistinguishable from a running one at a glance), and a
session blocked on the person pulses amber (it was steady). The `dotTone` / `DotTone.of` rule —
which state maps to which tone — did not change on either app; only the looks and the tooltip word
for the quiet tone ("Done", 已完成). Web: `.dot.working` lost its animation, `.dot.waiting` gained
it, `.dot.live` took the attention colour, and the reduced-motion rule follows `waiting`; a new
`StatusDot` test reads the stylesheet's rules and the rendered classes (five of its six cases
failed on the old source). Web tests 584 → 590. iOS: RCUIVerify 356 → 367 (each tone's colour, which tone pulses, Reduce Motion; three of the new checks failed on the old colours), RCVerify 1293 and unit tests 342 unchanged; the device row's dot became its own `OnlineDot` (online green, offline grey, updating pulsing green) so that turning `live` amber did not turn every online device amber with it — DESIGN already said a device's dot is not a session dot. Not verified: either app by eye
on a phone or in a browser this round; the demo's session list in the simulator carries the new
colours in the screenshot the iOS UI test attaches. Full iOS UI suite: 63 tests, 4 skipped, 0 failures, 1 361 s, with the simulator English and the Mac's load back under ten. All four
components 1.3.4 (iOS build 6), tag v1.3.4.

## 33. A session the usage limit stopped resumes itself; the device row says less (2026-09-17, 1.4.0)

The owner asked for a session Claude Code or Codex stopped at the five-hour or weekly window to
continue by itself once the window resets: one account-wide switch in Settings, off by default;
detection on the device; a resume scheduled from the vendor's reset time; one plain English prompt
that also tells the agent to let its subagents continue; the pending resume visible in its session
with a way to change the time and a way to cancel; a terminal closed before the time treated as
"done with this one" (dropped, and pushed); pushes when paused, resumed and dropped; Grok Build and
pi left with the hook only. Frozen as amendment **A35** (`protocol/`, ed84bb3 and ee21b6a for the
history rule) and `docs/DESIGN.md` § "Paused by the usage limit" (c68246f), then built in parallel
by four owners; a second pair, in git worktrees off the same base, simplified the device row
(`docs/DESIGN.md` § "The device row", 5542cb9) and was merged before the round closed.

**What the evidence was.** Claude Code writes a limit stop into the session's transcript as an
assistant row with `isApiErrorMessage: true`, `apiErrorStatus: 429`, `error: "rate_limit"` and
`quotaLimits {rateLimitType: "five_hour", resetsAt: <Unix seconds>}` (`message.model` is
`<synthetic>`, `stop_reason` `stop_sequence`); 91 such rows sit in this Mac's own history, and one
of them, identifiers shortened, is the fixture the device tests replay. The SDK's `ResultMessage`
(claude-agent-sdk 0.2.152) carries the same status as `api_error_status`. Before this round the
device read that row as a completed turn and published the vendor's sentence as the agent's text.
Codex: the installed standalone build's app-server `TurnError` carries `codexErrorInfo`, serialised
`usageLimitExceeded` (the core enum spells it `usage_limit_exceeded`; the reader takes both), and
`account/rateLimits/read` gives the windows' `resets_at`. The weekly `rateLimitType` value was not
observed; the reader maps `seven_day` and leaves the window unnamed otherwise.

**Gateway** (`21bd14e`): `preference_store.py` in its own `preferences.sqlite3`, `GET`/`PATCH
/api/preferences` for the caller only, `hello.preferences`, the `preferences` frame to devices
after `hello_ack` and on change, `preferences.updated` to the account's app sockets, the two resume
requests forwarded by session, the `resume` kind and `turn_completed.limit` passing untouched, and
pushes `limit_reached` / `resumed` / `resume_dropped` on `scheduled` / `fired` / `dropped` under the
existing "not while an app watches the session" rule. Tests 366 → 397; two timing flakes under a
load average above 70 passed alone and on the next clean runs. Also fixed this round, before A35:
`state.VERSION` and `__version__` had stayed at 1.3.0 under tag v1.3.4 (`hello` and `/api/config`
reported it); `state.VERSION` now reads the package version and the closing checklist names the
file (ac30672, 2e96cfe).

**Client** (`da574ea`): `sessions/limits.py` reads a limit stop from each agent's own signal and
nothing else; `sessions/resume.py` keeps pending resumes in a new `resumes` registry table, checks
every 30 s and once at start, fires a minute after the reset with the fixed sentence as
`user_message {source: "resume"}` under `trigger: "resume"`, cancels when the session is running
again or the person sent first, drops when the terminal that owned a `shared` session is gone,
reschedules up to three times, and publishes every step as a `resume` event and a summary
(`Session.resume`, null when none). Tests 1021 → 1071 (+3 skipped); one Codex-daemon timing test
failed once under load and passed alone and on the full rerun.

**Web** (`f533424`, row `7c2e92e`): `stores/preferences.ts`, the Sessions group in Settings (disabled
with a note on an older gateway), the amber notice with Change (`datetime-local`, a minute to eight
days) and Cancel, the timeline rows, the captioned resume message, the service worker's three
kinds, the mock's paused session. Tests 590 → 652, and 658 with the device row; at the default 5 s
per-test timeout 12 tests timed out under load and all passed at 60 s with two workers.

**iOS** (`46b770d`, row `e27008b`): `Resume.swift`, `Preferences.swift`, `PreferencesStore`,
`ResumeText` (every sentence in the viewer's clock), `ResumeNotice` through a second action on
`NoticeBanner`, `SessionPreferences`, the timeline rows and caption, the foreground banner for
scheduled / fired / dropped, a demo paused session, 31 zh-Hans strings. RCVerify 1293 → 1357,
RCUIVerify 367 → 418 (447 with the device row), unit tests 342 → 361 (365), two new UI tests plus
one for the device row; version 1.4.0, build 7.

**The device row** on both apps: one computer glyph leads every row, the name is said once, the
status line reads dot · online · macOS, hostname and architecture moved to the device page, agents
are logos alone with the agent's name as their accessible label. Screenshots
`ios-round33b-devices.png`, `web-round33b-devices-{1280,400}.png`, and for the resume
`ios-round33-resume-{banner,settings}.png`, `web-round33-resume-{banner,settings}-{1280,400}.png`
in the session scratchpad.

**Not verified.** No live limit stop was reproduced against a real agent: a five-hour window cannot
be exhausted on demand, so the Claude path is verified on a real transcript row replayed through
the tailer and the SDK path on a faked result, and the Codex path on the daemon's schema rather than
an observed turn. Push delivery for the three new kinds was not exercised against APNs or a browser.
Neither app was checked by eye on a phone or in a browser beyond the screenshots the tests took.
The gateway's "not while an app watches" rule for the resume pushes is a judgement the contract
does not state. Full iOS UI suite on the merged tree: 66 tests, 4 skipped, 0 failures, 1 308 s, with the
simulator English and the Mac's load under twenty. All four components 1.4.0 (iOS build 7), tag v1.4.0. The round itself met its subject: four of the six subagents hit the five-hour limit
at 05:45 and were resumed by hand after 08:50, which is the case A35 automates.

## 34. The device glyph is an outline laptop (2026-09-18, 1.4.1)

The owner changed the mark before every device row: a minimal black outline laptop — a rounded
rectangular screen, one horizontal base line under it, no fill, round caps and joins — and asked
that lucide's laptop be checked first. lucide 1.43.0 has two: `Laptop`, whose base is a trapezoid,
and `LaptopMinimal` (alias `laptop-2`), which is exactly the drawing asked for — `rect` 18×12 at
(3, 4) with `rx` 2 over a `line` from (2, 20) to (22, 20) on the 24-unit grid, `fill="none"`,
`stroke-linecap` and `stroke-linejoin` round. The web row uses it in place of `Monitor`, in the
ink rather than the secondary ink. iOS draws the same path itself (`LaptopGlyph` /
`LaptopShape` in `Sources/RCUI/Design/`), because SF Symbols' `laptopcomputer` has a trapezoid
base and a heavier weight; the stroke is 1.5 grid units on both apps, 20 pt beside the callout
title following Dynamic Type, the base line on the title's first baseline. Ruled in
`docs/DESIGN.md` § "The device row". The Devices tab item and the empty state keep SF's
`desktopcomputer`: the ruling is about the row.

**Verified.** Web: `tests/DevicesPage.test.tsx` gains a test that the glyph is an unfilled `svg`
with round caps and joins holding a rounded `rect` and a horizontal `line` below it (652 → 659
tests, tsc, lint and build clean). iOS: `RCUIVerify` checks the path's bounds on the grid
(2, 4, 20, 16) and at twice the grid, the base under the screen, the rounded corner and the stroke
ratio at 24 and 20 (447 → 453); RCVerify 1357 and 365 unit tests unchanged; the two UI tests that
touch the row and the About version row passed on simulator 32BBA636 (English), 34 s. Playwright
read the rendered `svg` as `lucide-laptop-minimal` with children `rect,line`, colour
rgb(17, 17, 17), 20 px box, at 1280 and 400 px. Gateway 397 and client 1071 (+3 skipped) after the
version bump. Screenshots `web-round34-devices-{1280,400}.png` and `ios-round34-devices.png` in
the session scratchpad. All four components 1.4.1, iOS build 8, tag v1.4.1.

**Not verified.** The full iOS UI suite was not rerun for this change (two tests were); neither
app was checked on a phone or in a browser beyond the screenshots; the glyph in dark mode (the
ink is near-white there) was not looked at by eye.

## 35. The device row's client line says one thing (2026-09-18, 1.4.2)

The owner's ruling, from the phone: the third line of a registered device's row shows no build
hash after the version (the eight characters such as `3f2b4a9c`); when an update is available it
reads "Update available" (有可用更新), and otherwise only the version the device runs, such as
"1.4.0". Ruled in `docs/DESIGN.md` § "The device row" and reconciled with § "An update names its
version": the row says "Update available" alone, the confirmation and the device page name the
version it would install, and the page keeps the full line (`client <version> · <build>`, hostname,
architecture) as the place one goes to check facts. "Updating…" and "Update failed · <reason>" are
unchanged on the row. The word "client" left the row with the hash.

Web: `DeviceRow.tsx` renders either the notice (`available` mapped to the bare
`strings.devices.updateAvailable`) or the bare `client_version` in mono; the `::before` separator
that parted the notice from the version is gone with the version. iOS: `DeviceRowClientLine`
(`Screens/DeviceLines.swift`) for the row and `DeviceUpdateText.rowLine(version:notice:)` as the
one rule; `DeviceClientLine` stays the page's. No new strings on either app.

**Verified.** Web: four tests in `tests/DeviceUpdate.test.tsx` rewritten — the up-to-date row says
"0.1.0" and neither "client" nor the hash, the outdated row says "Update available" and neither
version nor hash, the same on a gateway that names no version, and each row's bare version when the
gateway serves no build; 659 tests, tsc, lint and build clean; Playwright read the two rows' client
lines as `["Update available", "0.1.0"]` at 1280 and 400 px. iOS: RCUIVerify 453 → 459 (the rule for
none / available / updating / failed, and the demo's up-to-date machine reading just its version
with no hash); unit tests 365 and RCVerify 1357 unchanged; three UI tests on simulator 32BBA636 —
the device row (now also asserting the bare version, no "client", no hash), the update flow ("Update
available" without a version or the running version while the notice is up; after the update the
row reads the new version with nothing left to offer and no hash) and the About version row — 3
passed, 0 failures, 54 s. Gateway 397 and client 1071 (+3 skipped) after the bump. Screenshots
`web-round35-devices-{1280,400}.png`, `ios-round35-devices.png`, `ios-round35-update-available.png`,
`ios-round35-updated.png` in the session scratchpad. All four components 1.4.2, iOS build 9, tag
v1.4.2.

**Not verified.** The full iOS UI suite was not rerun (three tests were); neither app was looked at
on a phone or in a browser beyond the screenshots; the first web screenshot of this round showed a
stray "·" before "Update available" from the old separator rule, which is what the CSS change fixed
and the second screenshot confirmed.

## 36. The session row is three lines (2026-09-18, 1.4.3)

The owner's ruling, from the phone: the second line of a session's row carried the coding agent,
the connection or running state and the working directory together, too much for one line, and
the path was what got cut. The row is now three lines on both apps — title with the time at the
trailing edge; the agent chip at the leading edge and the status at the trailing edge; the working
directory alone after a folder glyph in the same style as the device row's laptop (black outline,
no fill, round caps and joins). Ruled in `docs/DESIGN.md` § "The session row".

iOS: `SessionRow` in `Screens/SessionsView.swift` restructured; new `Design/OutlineGlyph.swift`
(the grid and stroke rule the laptop and the folder share) and `Design/FolderGlyph.swift`
(`FolderShape`, lucide's `Folder` as six rounded corners walked with tangent arcs on the 24-unit
grid; `FolderGlyph`, 14 pt scaled with the footnote, in the ink); `LaptopGlyph` now reads the shared
rule. Web: `SessionRow.tsx` restructured into two `.session-line`s and a `.session-cwd`; the
`StatusDot` moved off the title to the state word; lucide `Folder` at 14 px, stroke 1.5, in the
ink; the path truncates from the head (`direction: rtl` plus a `<bdi>`); rows are `--row-h-three`
(84 px) at every width and the phone-width rules that wrapped the state onto its own line are gone.
No new strings.

**Verified.** Web: two new tests in `tests/SessionsPage.test.tsx` — the second line starts with the
agent chip and ends with the state holding the dot and carries no path; the third line is an
unfilled `svg` folder with round caps and joins followed by the tilde path — 659 → 661 tests, tsc,
lint and build clean; Playwright read the first three rows at 1280 and 400 px as
`["<title><time>", "<agent><state>"]` plus the path, each row 84 px. iOS: RCUIVerify 459 → 467
(the shared stroke's caps and joins, the folder's bounds on the grid (2, 3, 20, 17) and at twice
the grid, the tab's rise, the level edges, the rounded corner); unit tests 365 and RCVerify 1357
unchanged; three UI tests on simulator 32BBA636 — the sessions list and chat, the device row and
the About version row — 3 passed, 0 failures, 52 s. Gateway 397 and client 1071 (+3 skipped) after
the bump. Screenshots `web-round36-sessions-{1280,400}.png` and `ios-round36-sessions.png` in the
session scratchpad. All four components 1.4.3, iOS build 10, tag v1.4.3.

**Not verified.** The full iOS UI suite was not rerun; head truncation of a path longer than the
row was not exercised on either app (the demo and mock paths fit); neither app was looked at on a
phone or in a browser beyond the screenshots; the chat sidebar's compact rows are unchanged and
were not looked at.

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
12. Attaching (A10): `rc-client shim install --no-shell-rc` in the scratch home, restart the daemon,
    then start an interactive `claude` with the shim first on `PATH` and answer both dialogs. The
    session must appear as `control: "shared"`. Send one message while it is idle and confirm
    `delivery: "delivered"`; send one during a turn and confirm `pending` then `delivered` under the
    same `block_id`; raise one approval and answer it from the app. Never run `rc-client uninstall`
    or `service remove` on a machine that carries a real installation.
13. Shared Codex (A11): `rc-client codex setup` in the scratch home, confirm `rc-client codex status`
    reports a successful handshake, then start a bare `codex` in a scratch git repository and answer
    the trust prompt. The session must appear as `origin: "terminal"`, `control: "shared"`. Send one
    message while it is idle, steer a running `sleep 8` with `mode: "auto"` and confirm
    `accepted: "steered"`, `session.stop` a turn, change `effort` with `session.set`, and raise one
    approval from a TUI command — answer it from the app once and in the TUI once, confirming the
    second resolves as `{option_id: "elsewhere", by: "terminal"}`. Delete the scratch threads with
    `codex delete --force` afterwards.
