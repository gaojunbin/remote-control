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
| `rc-client hook session-start` | The `SessionStart` hook Claude Code runs; never run it by hand |
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
  state/claude-settings.json  the SessionStart hook the shim passes to Claude Code
  state/channel.sock        where channel bridges register (see below)
  state/link.json           what the gateway link last said about itself
  bin/claude                the shim that starts an attachable Claude session
  logs/                     rc-client.out.log and rc-client.err.log (macOS)
```

`RC_CLIENT_HOME` moves the whole directory, which is how you run a second daemon against a test
gateway without touching your real one.

## Service management

**macOS** installs a launchd user agent labelled `dev.remote-control.client` at
`~/Library/LaunchAgents/dev.remote-control.client.plist`, with stdout and stderr in
`~/.rc-client/logs/`. It starts at login and restarts on failure.

The plist deliberately sets no `ProcessType`. It used to say `Background`, which puts the job in
macOS's lowest scheduling band and throttles its disk I/O; on a loaded machine that was enough to
stall the event loop for tens of seconds at a time and drop the gateway link. Left unset, launchd
treats the job as `Adaptive`. **An install made before this change keeps the old plist**: re-run
`rc-client service install` to pick it up, which rewrites the file and restarts the service.

The Codex daemon's own agent, `dev.remote-control.codex-daemon`, dropped `ProcessType` for the same
reason: it is the app-server every Codex round trip goes through, and throttling it throttles them.
An existing install picks that up on the next `rc-client codex setup`.

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

The `user_message` the daemon publishes for a `session.send` carries **that request's id as its
`block_id`** (amendment A12), on all four paths: Claude through the SDK, Claude through a channel,
Codex through the shared daemon and Codex through its own app-server. The app has already drawn the
bubble under that id the moment the user pressed send, so the device's event replaces it rather than
adding a second one, and a retry of an unconfirmed send lands on the same block. A queued message
keeps the id from its `queue` entry through to the `user_message` that goes out when the queue
drains, and so does a steer. Messages the device originates itself — typed in a terminal, or
replayed from a queue entry that arrived without a request id — keep ids the device mints.

A steered message is the one exception (amendment A14). Codex does not read a message steered into
a running turn where it was sent but at its next step, after whatever it was already saying, and it
echoes the prompt as a `userMessage` item when it gets there. The device therefore holds the
`user_message` until that echo arrives and publishes it then, under the request id, so `first_seq`
places the bubble after the output that preceded it — where a terminal on the same thread draws it.
`accepted: "steered"` still comes back immediately, and the app keeps its optimistic row at the
bottom until the block lands. A turn that ends without ever reading the message publishes it at the
turn's end instead, with a `notice` of level `warn` when the turn was interrupted, so a steered
message is shown exactly once either way. This holds on both Codex paths, the shared daemon and the
private app-server.

Sending is arranged so that nothing the user waits for sits behind a round trip. The message is
published first and the agent is asked afterwards: a Codex send that has to resume its thread, and a
Claude send that has to restart the CLI to apply a new effort level, both show the bubble before
they start. A Codex thread whose first turn has not run refuses every `thread/resume`, so a refusal
is remembered and not repeated until a turn boundary on that thread says something has changed.

`session.history` pages backwards with `before_seq` and forwards with `after_seq`; the second form
is how the gateway backfills events produced while its link to the device was down.

An archived session is one the user folded away, not a different kind of session. The daemon reads
one back after a restart as `stopped` and leaves it there, but it clears `archived` and republishes
the summary the moment the session comes back to life (amendment A15): a turn starts in it, from an
app or from a terminal; a `session.send` arrives, including one that only joins the queue; or a
terminal attaches to it, through the Claude channel or by opening its Codex thread. The control a
revived session reports is the one the path that revived it gives it — `remote` when an app resumed
it, `shared` when a terminal attached — so it never comes back still claiming that nothing holds it.
Archiving closes a running agent, so archiving and then sending again is a restart of the CLI, not a
handover to a process that was still there.

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

Ownership is worked out for the whole scan round at once, and a process is credited to one session
only. A CLI started with `--resume` or `--session-id` names its session in its own command line, and
one started through the shim names it over the channel the moment its bridge registers; those are
the only two facts that say which conversation a process is in. The working directory is a last
resort, used only where one directory holds exactly one unidentified CLI and exactly one session
still without one. A home directory routinely holds a dozen finished sessions, and crediting the
same process to all of them made every one of them look like the live one. A shim-started CLI is
never matched by directory at all: it will name its session a moment later, and guessing in the
meantime is always wrong.

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

### What the terminal chose

A session a terminal holds takes no settings from an app, so the device reports what the terminal
chose instead and the apps draw the three values where their pickers would be (amendment A17). Every
Claude session the device mirrors — `terminal` or `shared` — has its `model`, `permission_mode` and
`effort` read out of the transcript: the model attachment Claude Code writes at start, after a resume
and on every `/model`; the permission-mode row it writes once per turn; and the effort carried on
each assistant message. A change is published as a `meta` event and a republished summary, so a
`/model` typed in the terminal reaches the apps within one tail interval. The model is recorded only
when it changes, which usually puts the value in force far behind the point a mirror starts reading,
so the whole transcript is read once — off the event loop, capped at 64 MiB — when the session is
adopted, and for every mirrored Claude session the daemon starts with. The ids are the agent's own
and kept verbatim, suffix and all, whether or not they appear in the advertised lists: `auto` is a
permission mode only the terminal sets, `claude-opus-5[1m]` a model no list carries, and an app shows
either by its id. All three rows are internal to Claude Code, so one of an unknown shape is read as
"no settings" rather than as an error, exactly as the title rows are. A session this device drives
sets these itself and is never read this way.

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

The wrapper appends the channel flags, and the settings file described below, **only** when stdin
and stdout are both terminals and the command line carries none of `-p`, `--print`,
`--input-format`, `--output-format`, `--sdk-url`, `--mcp-config` or
`--dangerously-load-development-channels`. Every other invocation reaches the real executable
unchanged, which is what keeps the device's own remote sessions — driven over pipes with
`stream-json` — out of the attachment path entirely. The daemon resolves the real binary itself and
never points the SDK at the wrapper.

Open a new terminal after installing, then run `claude` as usual. **Claude Code shows a one-time
confirmation per session** warning about development channels; choose "I am using this for local
development" and the device attaches within a second. `rc-client shim status` prints where the shim
is, whether it is first on `PATH`, which executable it wraps, and the two files it passes.

### The hook that follows `/resume` and `/clear`

An MCP server keeps the environment it was spawned with for as long as it lives, so the channel
bridge can only ever report the session id Claude Code picked when the terminal started. That is
wrong the moment you type `/resume` and pick another conversation, or `/clear` and start a new one:
the terminal is somewhere else and the device is still pointing at the id it was told at startup.
The session you are actually typing into then shows as `control: "none"` and a message sent from a
phone cannot reach it, while the session nobody is in claims to be attached.

So the wrapper also passes `--settings ~/.rc-client/state/claude-settings.json`, whose only content
is a `SessionStart` hook: `rc-client hook session-start`. Claude Code runs it on startup and again
on every `/resume`, `/clear` and compaction. The hook reads the session id from the hook payload,
walks up to find the `claude` process that ran it, writes one line to the channel socket and exits;
the daemon then moves the live attachment to that session. It prints nothing on stdout, because
anything a `SessionStart` hook prints there is appended to the model's context, and it always exits
0, because a failing hook would interrupt your session over a feature you did not ask for. If you
pass a `--settings` file of your own the wrapper leaves it alone: the session still attaches, it
just carries no hook, and `/resume` inside it goes unnoticed again.

The hook file is written by `rc-client shim install` and again by the daemon at startup, so it names
whichever installation ran most recently. **A `claude` that was already running when the shim was
installed or re-installed keeps the wrapper it started with** — the command line and the settings
file are both read once, at startup — so quit and start it again to pick up a change.

### What works and what does not

This table is the Claude channel. Codex on the shared daemon does more; see the next section.

| From the apps | On a `shared` Claude session |
| --- | --- |
| Send a message | Yes, injected at the next idle point |
| Approve or deny a tool call | Yes, `allow` and `deny` only — the relay offers no session-scoped grant |
| Answer a question | No, `unsupported`: answer it in the terminal |
| Stop the turn | No, `unsupported`: a channel cannot interrupt. Codex does |
| Change model, permission mode or effort | No, `unsupported`: change it in the terminal. The values are shown, read from the transcript |
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

### Which TUIs the daemon owns

There is no rule about how to start Codex. A TUI started with
`--dangerously-bypass-approvals-and-sandbox` runs on the shared daemon exactly as a bare `codex`
does: verified on this Mac on 2026-09-12, where the daemon held that TUI's thread, held its rollout
open for writing, and relayed both of the turns typed into it. `-c` is different — a TUI started
with `-c model_reasoning_effort=low` was writing its own rollout on the same day, and the daemon had
no thread in its directory at all — but the device does not need to know which flags do that, and
does not look.

What it looks at is what the process holds. A TUI running its own embedded app-server writes the
thread's rollout itself, so `lsof` finds it holding a file under `$CODEX_HOME/sessions`; a TUI on
the shared daemon holds no rollout at all, because the daemon holds it. Only the first kind is left
out of the count of terminals the device attributes to daemon threads, so it never speaks for a
thread it cannot be in. It still appears in the apps, mirrored from its rollout file with
`control: "terminal"`, because the rollout holder scan finds the process holding it. Anything you
would have set with `-c` can be set from the apps instead, through the model, permission-mode and
effort pickers.

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
- `origin` and `control` follow the table in PROTOCOL.md 4.4. An unloaded thread is `none` and the
  next `session.send` resumes it.
- The daemon says nothing at all when a TUI exits, and a thread it once loaded stays loaded for as
  long as the daemon lives, so the TUI process is the only signal there is. **Being loaded therefore
  proves nothing**: a directory somebody works in holds one loaded thread for every `codex` ever
  started there, and treating them all as attached is what once put a row in the apps for each of
  them. A thread is `shared` only while a terminal is known to be in *that* thread. Two things say
  so. A thread another client opens or resumes is that client's, announced by `thread/started`; and
  a `userMessage` carrying a `clientId` that is not ours is somebody typing. Failing both, the
  ten-second process scan decides, and it decides by directory: it counts the live bare `codex`
  processes running in each `cwd` and hands out that many claims, to the thread that already had one
  first and then to whichever the daemon saw used most recently. A thread this device started is
  never handed a claim that way — it is not a thread a terminal opened — until somebody has been
  seen typing in it, after which a `codex resume` on it is as ordinary as any other terminal. A
  thread with no claim becomes `remote` while a turn this device started is running and `none` when
  it is idle; `origin` never changes, the subscription is kept, and the next message typed in that
  terminal makes it `shared` again. Evidence gathered since the last scan outranks the scan, so a
  TUI resumed from a different directory is not written off while it is being used, and a scan that
  cannot be completed changes nothing. The process test is by what the process holds, never by its
  flags: a `codex` on a terminal with no helper subcommand (`app-server`, `exec`, `mcp`,
  `mcp-server`, `proto`, `daemon`), minus any that is holding a rollout under `$CODEX_HOME/sessions`
  open, which is how a TUI running its own embedded app-server gives itself away.
- **A thread with nothing in it is not a session yet.** A TUI opens a thread the moment it starts,
  before anything is typed into it, and that thread has no name, no preview and no rollout to
  resume. Publishing it would put an untitled row in every app the moment a terminal window opens,
  and another one for every window opened and walked away from — the daemon never unloads them and
  never closes them either. Such a thread is remembered rather than published; the first thing it
  says both publishes it and proves whose it is, because the only client that can be in it is the
  one that opened it. A thread has no rollout until that first turn, so `thread/resume` refuses it
  until then; `turn/start` works throughout. The same applies in reverse: a thread created from an
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

### Reconnecting, and when not to

The close code decides (PROTOCOL.md 2.5). `4401` and `4403` are the credential's answer: the link
stops, logs `gateway link stopped` with the reason, and writes `state/link.json`, so
`rc-client status` prints a `gateway link` line saying the gateway rejected this device and to run
`rc-client enroll` again. Nothing else in the daemon stops. `4001` means a newer connection replaced
this one, and racing it back would only replace that one in turn, so the reconnect waits at least
fifteen seconds. Everything else is transient and retried with backoff, and only a connection that
stayed up for thirty seconds resets it.

Work that belongs to a fresh connection — the mirror scan the gateway backfills from — runs beside
the frame pump, never in front of it. Holding the pump back left every forwarded request unanswered
until that work finished, including the ones it was meant to answer, and the backfill request timed
out against a device that was connected and silent.

### Staying on time

Two things used to put the loop on the disk's clock. Registry writes committed one per published
event, and a commit is a flush; they are now queued and applied in batches by a single worker
thread, with reads applying whatever is queued before they run so nothing observes a missing write.
`seq` is still handed out synchronously and in order, from memory, and is written back with the
events it numbered, so a batch lost to a kill takes its events and its counter together and no `seq`
is ever reused. Pruning a session's history no longer counts its rows first; it deletes below a
watermark the index finds by skipping to it.

A probe task asks only to be woken every second and logs `event loop stalled` with how late it was
when the answer takes more than five seconds. A device that goes quiet is either a bad network or a
stalled loop, and the two are fixed in different places.

## Uninstalling

```sh
rc-client uninstall           # stop and remove the service and the shim, keep the data
rc-client uninstall --purge   # also delete config, state and logs
```

`uninstall` also deletes `bin/claude` and `state/claude-settings.json`, and strips the
`# >>> remote-control >>>` block from your shell startup file. Pass `--no-shell-rc` to leave that
file alone.

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
- Nothing tells the device that a Codex TUI has exited: verified on 2026-09-11 against Codex 0.154.0
  by watching a second subscribed client through a `/quit` and through a killed pane, neither of
  which emitted anything, and the thread stayed in `thread/loaded/list` for the whole observation
  and was still loaded twenty-five minutes later. The device therefore matches TUI processes to
  threads by working directory and by count, which still mis-answers where the directory holds more
  threads than terminals. A TUI resumed with `codex resume` from a directory other than the thread's
  own is invisible to the scan, so the thread reads as `none` once it has been silent for one scan;
  sending from an app still reaches it. Two TUIs in one directory are indistinguishable from each
  other, so which of that directory's threads each one is credited with is a guess, ordered by what
  the daemon last saw used. The count is what bounds the damage: one terminal speaks for one thread,
  never for the whole directory.
- Whether the daemon would unload a thread once its last subscriber leaves is unverified: this
  device subscribes to every loaded thread, so there is no moment without one, and answering it
  would mean stopping the user's own client.
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
