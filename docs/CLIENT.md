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
| `--manual` | Print the seven steps instead of running them, for a host without `curl` in the pipeline |
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
| `rc-client uninstall [--purge]` | Remove the service, and with `--purge` the config, state and logs |

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

For `codex`: `RC_CODEX_BIN`, then `PATH`, then `~/.codex/packages/standalone/releases/*/bin`,
`~/.local/bin`, `/opt/homebrew/bin`, `/usr/local/bin`, `/usr/bin`. `CODEX_HOME` moves the Codex home
directory the daemon reads.

What each agent advertises:

| | Claude Code | Codex |
| --- | --- | --- |
| Models | `default`, `opus`, `sonnet`, `haiku`. `default` means "do not pass a model"; the real id arrives from the SDK and is reported as `meta.model` | Read live from the CLI's `model/list` and cached for ten minutes |
| Permission modes | `default` (Ask before edits), `acceptEdits` (Auto-accept edits), `plan` (Plan mode), `bypassPermissions` (Bypass permissions) | `untrusted` (Ask for everything), `on-request` (Ask when needed), `never` (Never ask) |
| Efforts | `low`, `medium`, `high`, `xhigh`, `max` | Whatever the catalogue reports, clamped per model, from `minimal` to `ultra` |
| Capabilities | `takeover`, `interrupt`, `queue`, `attachments`, `effort`, `history`, `worktree` | `interrupt`, `queue`, `steer`, `history`, `worktree`, `attachments`, `effort` |

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
| `none` | No process holds it; the session is resumable | Sending resumes it and control moves to `remote` |
| `remote` | This daemon is driving it | Normal |

A `session.send` to a terminal-controlled session is refused with `conflict`. `session.takeover`
works only for Claude, and only while the terminal session is idle: the daemon sends SIGTERM to the
exact process it identified, confirms the release, and resumes the session itself. During a live
terminal turn it refuses. Codex advertises no `takeover`, so quitting the CLI is the only way to
release a Codex session.

Two environment notes. A `claude` started from inside another Claude Code session inherits
`CLAUDE_CODE_CHILD_SESSION`, which turns transcript saving off, and the transcript is the only thing
the mirror can read. And a first run in a new directory shows a project-trust dialog that must be
answered before anything is written.

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
rc-client uninstall           # stop and remove the service, keep the data
rc-client uninstall --purge   # also delete config, state and logs
```

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
whole directory, `RC_CLAUDE_BIN` and `RC_CODEX_BIN` pin an agent executable, and `CODEX_HOME` moves
the Codex home the daemon reads.

## Known limitations

- Linux has never been run: neither the systemd unit nor `service install`.
- Codex sessions cannot be taken over, by design.
- A successful `session.takeover` has not been exercised end to end; the refusal path has.
- Codex approvals, `todos` events and attachments were all unit-tested but never driven through a
  live agent. See `docs/VALIDATION.md` and `docs/VALIDATION-APPS.md`.
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
