# The device daemon

`rc-client` runs on every machine where your agents live. It dials out to the gateway, drives the
locally installed Claude Code and Codex CLIs, and mirrors the sessions you start yourself in a
terminal. Nothing listens on the machine and no model credential ever leaves it.

## What the installer does

The gateway serves the installer at `GET /install.sh`, with its own origin substituted into the
script, so the command you copy from **Add device** already points at your gateway:

```sh
curl -fsSL https://rc.example.com/install.sh | sh -s -- --pair RC-7K42-QX9M
```

It installs `uv` into `~/.local/bin` if it is not already there, installs Python 3.12, creates a
private virtual environment at `~/.rc-client/venv`, downloads the `rc_client` wheel from the
gateway, enrolls the device with the pairing code, registers the background service, starts it, and
prints the agents it found. Re-running upgrades in place.

It refuses to run as root on macOS, refuses an unknown operating system or architecture, and refuses
a plain `http://` gateway unless the host is a literal loopback or private address — the wheel it is
about to download will run as a service, so the path has to be one nobody can sit on.

| Flag | Effect |
| --- | --- |
| `--pair RC-XXXX-XXXX` | The pairing code from the web UI. Required unless uninstalling |
| `--name NAME` | The device name shown in the apps. Defaults to the hostname |
| `--gateway ORIGIN` | Override the origin baked into the script |
| `--manual` | Print the steps instead of running them, for a host without `curl` in the pipeline |
| `--no-codex` | Skip the shared Codex app-server daemon setup |
| `--uninstall` | Stop and remove the service, keeping `~/.rc-client` |
| `-h`, `--help` | Usage |

Pairing codes are single use and expire after ten minutes. If enrollment fails, mint a fresh one.

## Commands

`rc-client` takes a global `--log-level` (`debug`, `info`, `warning`, `error`) and `--version`.

| Command | What it does |
| --- | --- |
| `rc-client enroll --gateway URL --pair CODE [--name N]` | Redeem a pairing code and write `config.toml` |
| `rc-client run` | Run the daemon in the foreground |
| `rc-client status` | Print the device identity, paths and service state |
| `rc-client agents` | Print the detected agents as JSON |
| `rc-client service install\|uninstall\|start\|stop\|status` | Manage the background service |
| `rc-client shim install\|remove\|status [--no-shell-rc]` | Manage the `claude` shim that makes terminal sessions attachable |
| `rc-client codex setup\|status [--no-install]` | Bring up and check the shared Codex app-server daemon that makes terminal Codex sessions attachable |
| `rc-client channel` | The channel bridge Claude Code spawns; never run it by hand |
| `rc-client uninstall [--purge] [--no-shell-rc]` | Remove the service and the shim, and with `--purge` the config, state and logs |

Exit codes: `0` success, `1` runtime failure, `2` usage error, `3` not enrolled.

After the one-line install, the executable is at `~/.rc-client/venv/bin/rc-client`. Add that
directory to your `PATH` to call it by name.

## Files

```
~/.rc-client/
  config.toml               gateway_origin, device_id, device_token, name   (0600)
  venv/                     the private Python environment the installer creates
  state/rc-client.sqlite3   sessions, events, request idempotency, tail offsets
  state/attachments/        files received with a message
  state/claude-mcp.json     the channel server definition the shim passes to Claude Code
  state/channel.sock        where channel bridges register (see below)
  bin/claude                the shim that starts an attachable Claude session
  logs/                     rc-client.out.log and rc-client.err.log (macOS)
```

`RC_CLIENT_HOME` moves the whole directory, which is how you run a second daemon against a test
gateway without touching your real one.

## Service management

**macOS** installs a launchd user agent labelled `dev.remote-control.client` at
`~/Library/LaunchAgents/dev.remote-control.client.plist`, with stdout and stderr in
`~/.rc-client/logs/`. It starts at login and restarts on failure.

**Linux** installs a systemd *user* unit, `rc-client.service`, under
`~/.config/systemd/user/`. It runs with `NoNewPrivileges` and `UMask=0077`, restarts after five
seconds, and logs to the journal (`journalctl --user -u rc-client -f`). A user unit stops when you
log out unless lingering is enabled:

```sh
loginctl enable-linger $USER
```

The Linux path has not been exercised: neither `rc-client service install` nor the systemd unit has
been run on a real machine.

## Agent discovery

On start, and whenever an app calls `device.agents`, the daemon locates each CLI and probes its
version. Resolution order for `claude`:

`RC_CLAUDE_BIN`, then `PATH`, then `~/.local/bin`, `~/.claude/local`, `~/.npm-global/bin`,
`/usr/local/bin`, `/opt/homebrew/bin`, `~/node_modules/.bin`, `~/.yarn/bin`.

For `codex`: `RC_CODEX_BIN`, then the standalone build at
`~/.codex/packages/standalone/current/bin/codex`, then `PATH`, then
`~/.codex/packages/standalone/releases/*/bin`, `~/.local/bin`, `/opt/homebrew/bin`,
`/usr/local/bin`, `/usr/bin`. The standalone build comes ahead of `PATH` deliberately: it is the
build the shared daemon runs, and a session this device starts has to be the same one. `CODEX_HOME`
moves the Codex home directory the daemon reads, and both standalone paths with it, exactly as the
official installer does.

What each agent advertises:

| | Claude Code | Codex |
| --- | --- | --- |
| Models | `default`, `opus`, `sonnet`, `haiku`. `default` means "do not pass a model"; the real id arrives from the SDK and is reported as `meta.model` | Read live from the CLI's `model/list` and cached for ten minutes |
| Permission modes | `default` (Ask before edits), `acceptEdits` (Auto-accept edits), `plan` (Plan mode), `bypassPermissions` (Bypass permissions) | `untrusted` (Ask for everything), `on-request` (Ask when needed), `never` (Never ask) |
| Efforts | `low`, `medium`, `high`, `xhigh`, `max` | Whatever the catalogue reports, clamped per model, from `minimal` to `ultra` |
| Capabilities | `takeover`, `interrupt`, `queue`, `attachments`, `effort`, `history`, `worktree` | `interrupt`, `queue`, `steer`, `history`, `worktree`, `attachments`, `effort` |
| Attachment | `attach: "channel"`, `attach_ready` from the shim, `shared_interrupt`, `shared_settings` and `shared_attachments` all false | `attach: "daemon"`, `attach_ready` from a real handshake on the daemon socket, `shared_interrupt`, `shared_settings` and `shared_attachments` all true |

An agent that is not installed is reported with `available: false` rather than hidden, so the apps
can say why a device offers only one agent.

`session.set` validates `permission_mode` and `effort` against what the device advertises and
answers `bad_request` otherwise. `model` stays open, because model ids are the agents' own and a
cached catalogue can lag a release. A change that the running agent cannot apply live — an effort
change on Claude, sometimes a permission mode — is acknowledged immediately and applied on the next
turn.

## Sessions

`session.create` takes a device, an agent, a working directory, and optionally a model, a permission
mode, an effort, a first message and a title. With `worktree: true` the daemon runs `git worktree
add` on a new branch under `<repo>/.rc-worktrees/<slug>` and uses that as the working directory, so
an agent can work without touching your checkout.

A session id is the agent's own: for Claude it is the Claude session id, named up front and passed
to the SDK; for Codex it is the thread id. That is what makes a session resumable and what lets a
terminal session and a remote session be the same thing.

The daemon queues a message that arrives while a turn is running and launches it at the turn
boundary, with `trigger: "queue"` so the UI can tell a queued turn from a typed one. Codex sessions
advertise `steer`, so an app can redirect a turn in flight instead of queueing. A repeated
`session.send` with the same request id is recognised as a duplicate and never delivered twice.

`session.history` pages backwards with `before_seq` and forwards with `after_seq`; the second form
is how the gateway backfills events produced while its link to the device was down.

### Titles

A session has three possible names, in order of precedence:

1. **A title the user set**, through `session.create`, `session.set`, or `/rename` in a Claude Code
   terminal, which writes a `custom-title` row into the transcript. It sticks: nothing an agent
   produces replaces it, whichever way it was set. Which sessions were named this way is
   device-local state, kept in the registry rather than on the wire.
2. **The agent's own summary of the conversation**, which replaces a first-prompt title whenever it
   arrives and again whenever it changes. Claude Code generates one shortly after the first turn and
   writes it as an `ai-title` row, then writes another when a plan is accepted; Codex names a thread
   and announces it with `thread/name/updated`. The device reads the Claude rows in all three places
   a transcript is read: a session it drives itself, an attached session and a mirrored terminal one.
3. **The first line of the first message**, which is what a session is called until something better
   shows up.

Every source is capped at 60 characters. A change is published as a `meta` event and a republished
session summary, so the sidebars follow it live.

Both transcript rows are internal to Claude Code and documented as liable to change, so they are
parsed defensively: a row of an unknown shape, or one whose title field is missing, empty or not a
string, is read as "not a title" and never as an error. Nothing about the tail depends on them, so a
format change costs the titles and nothing else. When several titles arrive in one read they are
applied in order, which is what stops a generated title that lands after a rename from winning.

## Terminal sessions

Every ten seconds the daemon scans for agent sessions it did not create:

- **Claude** — transcripts under `~/.claude/projects`, at most 200 files and nothing older than 14
  days. Growth is detected by file size, never mtime, because `claude --resume` touches mtime
  without appending a byte.
- **Codex** — rollout files under `~/.codex/sessions`, identified by the `session_meta` record that
  starts each one.

A discovered session is published with `origin: "terminal"` and its rows are mirrored into the same
block timeline, with `source: "terminal"` on the user messages. A process scan decides who owns the
input, using `lsof` on macOS to find the process holding the transcript:

| `control` | Meaning | What the apps do |
| --- | --- | --- |
| `terminal` | A live CLI process owns the session | Composer disabled, "Controlled by the terminal · Take over" |
| `shared` | A live CLI process owns it **and the device is attached** | Composer and approvals as for `remote`; see "Attached terminal sessions" |
| `none` | No process holds it; the session is resumable | Sending resumes it and control moves to `remote` |
| `remote` | This daemon is driving it | Normal |

A `session.send` to a terminal-controlled session is refused with `conflict`. `session.takeover`
works only for Claude, and only while the terminal session is idle: the daemon sends SIGTERM to the
exact process it identified, confirms the release, and resumes the session itself. During a live
terminal turn it refuses. Codex advertises no `takeover` and needs none: a Codex session started as
a bare `codex` is `shared` rather than `terminal`, and the apps drive it in place. Only a Codex
started with configuration overrides falls back to `terminal`, and then quitting the CLI is the only
way to release it.

Two environment notes. A `claude` started from inside another Claude Code session inherits
`CLAUDE_CODE_CHILD_SESSION`, which turns transcript saving off, and the transcript is the only thing
the mirror can read. And a first run in a new directory shows a project-trust dialog that must be
answered before anything is written.

## Attached terminal sessions

A terminal session normally has to be *taken over* to be driven from a phone, which means killing
the CLI and resuming it — losing its sub-agents, background commands and loops. Claude Code 2.1.267
and later can instead load a **channel**: an MCP server that injects user messages into the live
session and relays its permission prompts. When one is attached the session reports
`control: "shared"`, and the apps treat it exactly like a session the device runs itself.

### How it works

```
claude (your terminal)
  └── rc-client channel        stdio MCP server, spawned by the CLI
        └── state/channel.sock  newline-delimited JSON, one line per message
              └── rc-client run  the daemon
```

The bridge reads `CLAUDE_CODE_SESSION_ID` and `CLAUDE_PROJECT_DIR` from its own environment,
registers with the daemon and stays until stdin closes. It survives a daemon restart: frames are
buffered and the registration is replayed on reconnect. It logs nothing but warnings, and never the
text of a message.

The daemon injects a message **only when the session is idle**. A message injected during a turn is
handed to the model as untrusted external data it is told not to obey, which is why anything sent
mid-turn is held on the device, shown as `delivery: "pending"` with a `queue` entry, and injected at
the next idle point — the same bubble then becomes `delivery: "delivered"`. If the CLI absorbs an
injection anyway the bubble becomes `delivery: "absorbed"` and the device re-sends it once. Turn
state comes from the transcript, which the daemon already tails, so it lags reality by up to two
seconds.

### The shim

Claude Code refuses to load a self-hosted channel without
`--dangerously-load-development-channels`, and there is no way to allowlist one on a personal
account. The installer therefore writes `~/.rc-client/bin/claude`, a POSIX shell wrapper, and puts
that directory in front of `PATH` in your shell startup file inside a marked block:

```
# >>> remote-control >>>
export PATH="/Users/me/.rc-client/bin:$PATH"
# <<< remote-control <<<
```

The block is appended, never rewritten, so a startup file that is a symlink into a dotfile
repository stays one. `install.sh --no-shell-rc` skips it, and `rc-client uninstall` removes it by
its markers.

The wrapper appends the channel flags **only** when stdin and stdout are both terminals and the
command line carries none of `-p`, `--print`, `--input-format`, `--output-format`, `--sdk-url`,
`--mcp-config` or `--dangerously-load-development-channels`. Every other invocation reaches the real
executable unchanged, which is what keeps the device's own remote sessions — driven over pipes with
`stream-json` — out of the attachment path entirely. The daemon resolves the real binary itself and
never points the SDK at the wrapper.

Open a new terminal after installing, then run `claude` as usual. **Claude Code shows a one-time
confirmation per session** warning about development channels; choose "I am using this for local
development" and the device attaches within a second. `rc-client shim status` prints where the shim
is, whether it is first on `PATH`, and which executable it wraps.

### What works and what does not

This table is the Claude channel. Codex on the shared daemon does more; see the next section.

| From the apps | On a `shared` Claude session |
| --- | --- |
| Send a message | Yes, injected at the next idle point |
| Approve or deny a tool call | Yes, `allow` and `deny` only — the relay offers no session-scoped grant |
| Answer a question | No, `unsupported`: answer it in the terminal |
| Stop the turn | No, `unsupported`: a channel cannot interrupt. Codex does |
| Change model, permission mode or effort | No, `unsupported`: change it in the terminal |
| Rename | Yes |
| Take over | No, `conflict`: the session is already attached |
| Attachments | No, `unsupported`: they cannot be delivered to a terminal session |

Whoever answers a permission prompt first wins. When the terminal answers, the device sees the tool
run or be refused in the transcript and closes the block with `decision.by: "terminal"`; a later
reply from an app is a no-op. When the attachment drops, pending approvals become `expired`, and the
session moves to `terminal` if the CLI process is still alive or to `none` if it exited. Messages
still held in the queue stay there and go out through the ordinary resume path.

## Codex on the shared daemon

Codex 0.153 and later run **every bare `codex` TUI inside one local app-server daemon**, whose
control socket is `$CODEX_HOME/app-server-control/app-server-control.sock`. The device connects to
that socket as a second client, so a Codex session started in a terminal is `control: "shared"`
rather than `control: "terminal"`, and the apps drive it in place: no take over, no SIGTERM, no lost
sub-agents or background commands.

A shared Codex session can do everything a remote one can, which is more than a Claude channel can:

| From the apps | On a `shared` Codex session |
| --- | --- |
| Send a message | Yes. Idle starts a turn; during a turn `auto` steers it and `queue` holds the message until the turn ends |
| Stop the turn | Yes, `turn/interrupt`, whoever started it |
| Approve or deny a tool call | Yes, with whatever options the daemon offers for that prompt |
| Answer a question | Yes |
| Change model, permission mode or effort | Yes, `thread/settings/update` changes the thread for everyone attached to it |
| Attachments | Yes. Images become image inputs; other files are written to disk and named in the prompt |
| Take over | No, `conflict`: the session is already attached |

### The one rule for users

**Start Codex as a bare `codex`.** Any `-c`, `--enable`, `--disable` or
`--dangerously-bypass-approvals-and-sandbox` on the command line makes the CLI spawn its own
embedded app-server, which the daemon cannot see and the device cannot attach to. Such a session
still appears in the apps, mirrored from its rollout file with `control: "terminal"`, because the
rollout holder scan finds the process holding it. Anything you would have set with `-c` is set from
the apps instead, through the model, permission-mode and effort pickers.

### Setup

`install.sh` runs `rc-client codex setup` unless you pass `--no-codex`. It is idempotent:

1. Find the standalone Codex at `~/.codex/packages/standalone/current/bin/codex`. **Only that path
   counts.** An npm or Homebrew `codex` earlier on `PATH` is the same CLI but not the install the
   daemon manages, and step 2 refuses to run without the standalone one whichever build invokes it:

   ```
   Error: managed standalone Codex install not found at
   ~/.codex/packages/standalone/current/codex

   This command requires the standalone install managed by the Codex installer, because the
   daemon starts and updates app-server from that fixed path.
   ```

   If it is missing and `~/.local/bin` is already on `PATH`, download
   `https://chatgpt.com/codex/install.sh` to a temporary file, check that it really is a shell
   script, and run it with `CODEX_NON_INTERACTIVE=1`. Otherwise print the two commands to run by
   hand and stop. The condition matters: Codex's own installer rewrites a shell profile when its
   target directory is not on `PATH`, which would replace a symlinked dotfile with a regular file.
   `CODEX_HOME` and `CODEX_INSTALL_DIR` move both of those directories, and are read here for the
   same reason the installer reads them.
2. `codex app-server daemon bootstrap` **on the standalone binary**, never `--remote-control`, and
   never on whatever `PATH` resolved. Remote control enrols the machine with OpenAI's relay, which
   this project does not use, and the device only ever logs the `remoteControl/status/changed` it
   receives.
3. Install supervision on the same standalone binary, because the bootstrap uses a pid backend and
   leaves none: a launchd agent `dev.remote-control.codex-daemon` on macOS, a
   `rc-codex-daemon.service` systemd user unit on Linux. `codex app-server daemon start` is
   idempotent and returns immediately, so both run it at login and again every five minutes rather
   than trying to hold a process open. On Linux run
   `loginctl enable-linger $USER` so it survives logging out.
4. Verify with a real WebSocket handshake on the socket, not a file-exists check.

`rc-client codex status` prints the standalone binary the daemon commands use, what `codex` on
`PATH` resolves to, whether the socket is there, whether it answers, and the state of our
supervision. When those first two are different installs it adds a warning, which `install.sh`
prints too:

```
warning: PATH resolves codex to /opt/homebrew/bin/codex, not the standalone build; terminal
sessions started with it may not join the shared daemon.
  Remove it (npm uninstall -g @openai/codex, or brew uninstall codex) or put
  /Users/you/.local/bin first on PATH.
```

The comparison is by realpath, because the standalone build is normally reached through two
symlinks. `rc-client status` carries the same warning in its one-line summary: `codex daemon
healthy (loaded); warning: foreign codex on PATH`.

Removing a Codex install is always yours to do — this tool never uninstalls one.
`rc-client uninstall` removes our launchd agent or unit and leaves Codex, its daemon and its
sessions completely alone.

### How the device uses it

One connection for the whole machine, opened at startup, identified as `remote-control` (the first
client to connect names the daemon for every thread, so the name is deliberate). On top of it:

- `thread/list` is the session history and `thread/loaded/list` the live threads. Threads marked
  `ephemeral` are dropped: Codex spawns one per turn just to generate a title.
- A loaded thread is subscribed with `thread/resume {excludeTurns: true}` and backfilled from
  `thread/items/list`, so the block timeline comes from the daemon rather than from a rollout file.
  Rollout mirroring stays only for the threads the daemon does not know about.
- `origin` and `control` follow the table in PROTOCOL.md 4.4. `shared` is sticky while the thread
  stays loaded, because the daemon says nothing at all when a TUI exits; an unloaded thread is
  `none` and the next `session.send` resumes it.
- A thread has no rollout until its first turn, so a thread the terminal just created cannot be
  resumed yet. It is still `shared` and `turn/start` still works on it; the subscription is taken the
  moment the first turn creates the rollout. The same applies in reverse: a thread created from an
  app can only be reopened with `codex resume <id>` **after** its first turn.
- The daemon echoes the prompt that started a turn back as a `userMessage` item, on `item/started`
  and again on `item/completed` with the same item id, and the observed `clientId` of our own
  injection is `null`. The device matches that echo against the prompts it has sent, keyed by text,
  and then remembers the item id, so the second event, a backfill and a reconnect all drop it
  instead of adding a `terminal` bubble beside the one the apps already show. One echo is spent per
  message sent, so a terminal user typing the same words still gets their own bubble. A `clientId`
  that has never appeared on an echo of ours is a TUI sharing the thread, which is what turns a
  `remote` thread `shared`.
- `thread/name/updated` carries `{threadId, threadName}` and reaches every subscriber, so a thread
  named in a terminal renames itself in the apps. It never overrides a title the user set.
- Approvals arrive as JSON-RPC requests that fan out to every subscriber. The options come from the
  daemon's own `availableDecisions` for that prompt, mapped onto `allow`, `allow_session`,
  `allow_always` and `deny`. Whoever answers first wins; when somebody else answers,
  `serverRequest/resolved` closes our block with `decision: {option_id: "elsewhere", by: "terminal"}`
  and our late reply is discarded silently.
- The connection reconnects with backoff. On every reconnect each followed thread is resumed again
  and backfilled from the last item we published, so a daemon restart loses nothing.

### When there is no daemon

If the socket is missing or does not answer, the device falls back to today's behaviour: one
`codex app-server` process per session, spawned by the device, with terminal sessions mirrored from
their rollout files. The agent still reports `attach: "daemon"` but with `attach_ready: false`, which
is what the apps' hint text is for. The socket is re-probed on the ordinary ten-second scan, so
bootstrapping the daemon later switches the mode without restarting the device; sessions already
discovered keep the runner they have until they are next resumed.

### `RC_CODEX_THREAD_CONFIG`

An optional JSON object merged into the `config` of threads **this device starts**, applied by Codex
to the thread wherever it is later resumed, including in a terminal. It is never sent on a thread
somebody else created. It exists because some settings have no wire equivalent; leave it unset unless
you know you need it.

## Attachments

Both agents read files from disk far more reliably than they accept inline binary payloads, so an
attachment sent with a message is written to `state/attachments/<session>/` with a sanitised name,
and the prompt gains the resulting paths. Limits are 8 attachments and 6 MiB each after decoding.
Files are removed when the session is deleted. This path has not been exercised end to end.

## Logs

Logs are structured JSON on stderr and never contain tokens, pairing codes, prompt text or tool
output. On macOS the service writes them to `~/.rc-client/logs/`; on Linux they go to the journal.
Raise detail with `rc-client --log-level debug run`.

## Network path

The daemon dials the gateway directly and ignores every proxy setting: neither the WebSocket link
nor the enrollment request consults `HTTP_PROXY`, `ALL_PROXY` or the system network settings. The
link is a long-lived tunnel to a gateway the operator runs, so a corporate or local proxy in the
middle only adds a failure mode.

Before that was pinned down, a machine with a SOCKS proxy in its macOS network settings logged
`gateway link lost` with an `ImportError` on every reconnect and never came up. The client library
had adopted the system proxy on its own and SOCKS support needs an extra package. A `gateway link
lost` line now carries the exception message, so a failure of that kind is readable in
`~/.rc-client/logs/rc-client.err.log`.

## Uninstalling

```sh
rc-client uninstall           # stop and remove the service and the shim, keep the data
rc-client uninstall --purge   # also delete config, state and logs
```

`uninstall` also deletes `bin/claude` and strips the `# >>> remote-control >>>` block from your
shell startup file. Pass `--no-shell-rc` to leave that file alone.

or, from the gateway's own script:

```sh
curl -fsSL https://rc.example.com/install.sh | sh -s -- --uninstall
```

Removing the device from the web UI revokes its token and drops its sessions from the gateway index.
The machine keeps its agents, its transcripts and its own history.

## Fixed after validation

App validation (`docs/VALIDATION-APPS.md`, section 4) found four defects in this component. All four
are fixed and covered by tests.

| Defect | Root cause and fix |
| --- | --- |
| Claude tool approvals expired about ten milliseconds after they were raised | The daemon let the Claude CLI load the machine's user-level `~/.claude/settings.json`, whose auto-approval settings resolved the permission request in-process before any remote user could answer. `setting_sources` now defaults to `["project", "local"]`, and a request the machine answers by itself emits a warning notice instead of failing silently. Re-verified from the web UI: the card stays pending, Allow writes the file, Deny leaves it absent, and both decisions are recorded as `by: "remote"` |
| A daemon restart duplicated a remote session's transcript | The mirror re-read the transcript of a session the daemon had driven itself, producing fresh block ids and `source: "terminal"`. It now skips any session whose persisted `origin` is `remote`, which is what survives the restart |
| An interrupted turn zeroed `Session.usage` | A stopped turn reports all-zero token counts. Those no longer overwrite the session's usage; a payload with no counts leaves the previous total in place |
| A fresh device published every local transcript it could find | The initial mirror import is now capped at 50 sessions from the last 14 days, both configurable |

## User-level Claude settings are not loaded

This is the trade-off behind the first fix, and it changes what a remote Claude session can do.

A remote session must never be approved by the machine on behalf of someone who is not looking at
it. The Claude CLI loads user-level settings by default, and those settings can carry auto-approval
(`permissions.defaultMode: "auto"`, `autoMode`, and `PermissionRequest` or `PreToolUse` hooks that
answer for you). So the daemon starts remote sessions with `setting_sources` set to `project` and
`local` only.

The practical effect: **anything scoped to your user does not apply to a remote session** — user-level
MCP servers, skills, hooks, and `permissions.defaultMode`. Project settings (`.claude/settings.json`
in the working directory) and local settings (`.claude/settings.local.json`) do apply, so per-project
MCP servers and hooks keep working.

To change it, add to `~/.rc-client/config.toml`:

```toml
[claude]
setting_sources = ["user", "project", "local"]
```

Accepted values are `user`, `project` and `local`; anything else is ignored. Adding `user` back
restores your full local configuration and, with it, the possibility that the machine approves a
tool call before you see the card. When that happens the session shows a warning notice saying so.

## Configuration

`~/.rc-client/config.toml` is written by `rc-client enroll` and can be edited afterwards. Restart the
daemon for a change to take effect.

| Key | Default | Meaning |
| --- | --- | --- |
| `gateway_origin` | from enrollment | The gateway this device dials |
| `device_id`, `device_token` | from enrollment | The device's identity and its bearer token |
| `name` | the hostname | The device name shown in the apps |
| `[mirror] max_sessions` | `50` | How many terminal sessions the initial mirror import publishes |
| `[mirror] max_age_days` | `14` | How far back that import reaches |
| `[claude] setting_sources` | `["project", "local"]` | Which Claude settings layers a remote session loads |

Environment overrides, useful for development and for unusual installs: `RC_CLIENT_HOME` moves the
whole directory, `RC_CLAUDE_BIN` and `RC_CODEX_BIN` pin an agent executable, `CODEX_HOME` moves the
Codex home the daemon reads and the standalone build inside it, and `CODEX_INSTALL_DIR` moves the
directory the official Codex installer symlinks into. The last two are read here because Codex's own
installer reads them; setting either to something the installer does not use would put `codex setup`
and Codex out of step.

## Known limitations

- Linux has never been run: neither the systemd unit nor `service install`.
- Codex sessions cannot be taken over, by design: on the shared daemon they are attached instead.
- A successful `session.takeover` has not been exercised end to end; the refusal path has.
- `todos` events were unit-tested but never driven through a live agent. See `docs/VALIDATION.md`
  and `docs/VALIDATION-APPS.md`.
- Claude approvals were answered live with Allow and with Deny, both on a `Write`. The
  session-scoped grant (`allow_session`) and an approval for a command have not been answered end to
  end, so nothing has confirmed that a second tool call runs unattended after a session grant.
- Killing the daemon can orphan a `claude` child that holds a transcript open for one more scan, so
  a restarted daemon may briefly report a remote session as terminal-controlled. It self-corrects.
- A mirrored session reports `readonly` during a long *silent* tool call, because the mirror infers
  activity from transcript rows arriving and a `sleep 45` writes nothing.
- The process scan runs about every ten seconds, so a very short terminal session can finish before
  the mirror sees the CLI holding it and appears directly as `control: "none"`. Only the live-control
  window is missed, never the timeline.
- Whether a foreign `codex` on `PATH` joins the shared daemon is unverified, which is why the
  warning in `codex status` says "may not join" rather than "will not". The npm package is a thin
  launcher around the same native binary as the standalone build and reads the same `CODEX_HOME`,
  so its TUI may well reach the same control socket; it may equally be refused, since the daemon
  starts and updates app-server from the standalone path alone. Two attempts to settle it by
  driving a TUI on a synthetic pty failed — neither build painted a frame or created a thread
  there, so the standalone control run produced nothing either and the npm result means nothing.
  Answering it needs a real terminal. What is verified: a foreign build cannot bootstrap the daemon
  unless the standalone install is present, and when it is, the bootstrap delegates to the
  standalone binary.
- The Codex daemon path is macOS only so far. The systemd unit that supervises it renders and is
  unit-tested, but has never been enabled on a real Linux machine.
- A Codex thread created from an app cannot be reopened with `codex resume` until its first turn
  exists, because the rollout the CLI resumes from is written when a turn starts.
- Nothing tells the device that a Codex TUI has exited, so a thread stays `shared` until the daemon
  unloads it. Nothing observed unloads a thread short of restarting the daemon.
- The Codex daemon's originator and user agent are set globally by whichever client connects first
  and are then stamped on every thread. Where the device connects first, threads a person starts in
  a terminal are labelled `remote-control` inside Codex's own records.
- A session that is attached before its first turn has no transcript, so after a daemon restart it
  is invisible to the scan that promotes a live CLI to `control: "terminal"`. It has no content
  either; the next turn creates the transcript and the session appears normally.
- `rc-client uninstall` and `rc-client service stop` reach launchd by service label, which is
  per-user and not per-`RC_CLIENT_HOME`. Running either against a scratch home therefore stops the
  real service as well. Restore it with
  `launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/dev.remote-control.client.plist`.
- `attach_ready` reports whether the *daemon's* `PATH` resolves `claude` to the shim, which the
  service files set at install time. It does not prove that your interactive shell does, so a
  session started before you opened a new terminal shows as `terminal`, not `shared`.
