# remote-control

Remote control for the coding agents you already run. `remote-control` is a self-hosted control
plane: a **gateway** on your own VPS sits between the **developer machines** where Claude Code and
Codex are installed and the **apps** you carry — a web UI and a native iOS client. Add a machine
with one pairing command, see every session on it (including the ones you started yourself in a
terminal), and drive a session the way you drive a chat: send text or dictate it, watch answers,
thinking, tool calls and diffs stream in, approve or deny a tool, answer a question, stop a turn,
queue the next one. Model credentials never leave the machine; the gateway routes and indexes but
never runs an agent.

## Architecture

```
      apps                        gateway (your VPS)                 developer machines
 ┌───────────┐             ┌───────────────────────────┐         ┌─────────────────────┐
 │  web app  │──┐          │  your reverse proxy  :443 │      ┌──│ rc-client daemon    │
 └───────────┘  │          ├───────────────────────────┤      │  │   claude  (SDK)     │
                │          │  rc_gateway         :8787 │      │  │   codex   (JSON-RPC)│
                ├─────────►│  auth · devices · index   │◄─────┤  └─────────────────────┘
 ┌───────────┐  │          │  replay buffer · STT      │      │
 │  iOS app  │──┘          │  push · web · install.sh  │      │  ┌─────────────────────┐
 └───────────┘             └───────────────────────────┘      └──│ rc-client daemon    │
                                                                 │  (another machine)  │
                                                                 └─────────────────────┘

   https /api · wss /ws/app      SQLite in DATA_DIR                wss /ws/device
```

Devices dial out only — nothing listens on them. Apps never reach a device directly. The gateway
reads only the envelope fields it needs to route a frame and forwards the rest opaquely. The wire
contract is `protocol/PROTOCOL.md`, and it is normative for all four components.

## Repository layout

| Path | What it is |
| --- | --- |
| `protocol/` | The frozen wire protocol: `PROTOCOL.md`, JSON Schema, and fixtures every component decodes in its tests |
| `gateway/` | `rc_gateway`, the VPS service. Python 3.12, FastAPI, SQLite, WebSockets |
| `client/` | `rc-client`, the device daemon and its installer. Python 3.12, `claude-agent-sdk`, Codex app-server |
| `web/` | The browser app. React 19, Vite, TypeScript, hand-written CSS |
| `ios/` | The iPhone app. SwiftUI, iOS 17+, xcodegen, no third-party dependencies |
| `docs/` | The documentation you are reading |
| `web-moke/` | The three prototype screenshots the UI was built against |
| `docker-compose.yml`, `.env.example` | The one-command stack and every setting it takes |

## Quick start on a VPS

Prerequisites: Docker with the Compose plugin, and a reverse proxy you run yourself — Nginx Proxy
Manager, Traefik, plain nginx — holding the public hostname and its certificate. The stack publishes the
gateway on one host port and nothing else; see [docs/DEPLOY.md](docs/DEPLOY.md#reverse-proxy).

```sh
git clone <this repository> remote-control && cd remote-control
cp .env.example .env
```

Edit `.env` and set two values:

```sh
PUBLIC_ORIGIN=https://rc.example.com   # the exact origin apps will use, no trailing slash
RC_PASSWORD=<a long random password>   # the login password for the single user "admin"
```

`GATEWAY_PORT` (`8787`) and `GATEWAY_BIND` (`0.0.0.0`) decide where the stack publishes the gateway;
point your proxy at that address and put its own source network in `TRUSTED_PROXIES`.

Then bring the stack up and open the web UI:

```sh
docker compose up -d
curl https://rc.example.com/api/health
```

Sign in with `RC_PASSWORD`. Go to **Devices → Add device**, pick the macOS or Linux tab, and run the
printed one-liner on the machine where your agents live:

```sh
curl -fsSL https://rc.example.com/install.sh | sh -s -- --pair RC-7K42-QX9M
```

The pairing code is single use and expires after ten minutes. The modal walks through the handshake
live — gateway ready, device handshake, detected agents — and the device appears in the list when it
connects. Now open **Sessions → New session**, choose the device, the agent, a working directory and
an optional first message, and start working.

Full deployment reference, including every `.env` variable, TLS options, upgrades, backups and
troubleshooting: [`docs/DEPLOY.md`](docs/DEPLOY.md).

## Local development

Run the gateway from a source checkout on port 8787:

```sh
cd gateway && uv sync
PUBLIC_ORIGIN=http://127.0.0.1:8787 RC_PASSWORD=devpassword DATA_DIR=/tmp/rc-data uv run rc-gateway
```

`rc-gateway` takes no flags; it reads the repository root `.env` when one exists, and otherwise the
environment. It serves `web/dist` at `/` when that directory has been built, and a placeholder page
when it has not.

The web app:

```sh
cd web && npm ci
npm run dev        # Vite on 5173, proxying /api and /ws to 127.0.0.1:8787
npm run dev:mock   # the same server plus a bundled mock gateway; sign in with: dev
npm run build      # typecheck, then build to web/dist
npm test           # vitest
```

A device against that gateway, in a scratch home so your real one is untouched:

```sh
cd client && uv sync
export RC_CLIENT_HOME=/tmp/rc-dev
uv run rc-client enroll --gateway http://127.0.0.1:8787 --pair RC-XXXX-XXXX
uv run rc-client run
```

Plain `http://` is accepted only for loopback and private addresses; everything else must be https.

The iOS app in a simulator, using a real Xcode toolchain for the process only:

```sh
cd ios && xcodegen generate
export DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer
xcodebuild -project RemoteControl.xcodeproj -scheme RemoteControl \
  -destination 'generic/platform=iOS Simulator' -configuration Debug \
  CODE_SIGNING_ALLOWED=NO build
```

The simulator shares the Mac's network, so it signs in against `http://127.0.0.1:8787`. `swift run
RCVerify` checks the protocol fixtures with plain Command Line Tools and needs no simulator.

## What works

- **Devices** — one-line install for macOS and Linux, single-use pairing codes, live enrollment
  progress, rename, revoke. Installed agents and their models, permission modes and effort levels
  are detected on the device and reported to the apps.
- **Sessions** — create one on any online device with a working directory, an optional git worktree,
  a model, a permission mode and an effort level. Archive them, and change the model, permission
  mode or effort mid-session.
- **Agents** — Claude Code through `claude-agent-sdk`, Codex through its app-server JSON-RPC
  interface. Both are normalised to one block timeline, so the UI has no agent-specific code paths.
- **Timeline** — streamed assistant Markdown, collapsible thinking, one-line tool rows that expand
  to input and output, diffs with per-file counts, todo snapshots, approval cards, question cards,
  turn markers and usage.
- **Turn control** — stop, queue while a turn runs, steer a Codex turn mid-flight, or interrupt and
  send. Queued messages are visible and removable.
- **Terminal sessions** — a `claude` or `codex` you started in a terminal is discovered and mirrored
  read-only. Claude sessions can be taken over when their terminal is idle; the daemon releases the
  CLI and resumes the session itself.
- **Voice** — dictation through the gateway's speech-to-text proxy, streamed as 16 kHz PCM16 with
  live partial transcripts. iOS can use on-device recognition instead. The transcript is always an
  editable draft; sending stays a separate action.
- **Notifications** — Web Push (VAPID) and APNs plumbing for approvals, questions, completed turns
  and errors. Payloads carry no prompt or output text.

## Status and limitations

This is a first version, validated end to end on macOS against the real `claude` and `codex` CLIs.
[`docs/VALIDATION.md`](docs/VALIDATION.md) covers the gateway and the device daemon;
[`docs/VALIDATION-APPS.md`](docs/VALIDATION-APPS.md) covers the web and iOS apps driven against a
live backend. Below is what those two reports found, and what they could not cover.

**Fixed after validation.** App validation found four defects in the device daemon. All four are
fixed and covered by tests.

- **Claude tool approvals expired before you could answer them.** The daemon let the Claude CLI load
  the machine's user-level `~/.claude/settings.json`, and the auto-approval configured there resolved
  the request in-process about ten milliseconds after it was raised. Remote sessions now load project
  and local settings only, and a request the machine answers by itself emits a warning notice. Both
  answers were then re-verified from the web UI: a card that stays pending, Allow writing the file,
  Deny leaving it absent, and each decision attributed to the remote user.
- **Restarting the daemon duplicated a remote session's transcript.** The mirror re-imported a
  session the daemon had driven itself; it now skips sessions whose recorded origin is remote, which
  is what survives the restart.
- **Stopping a turn zeroed the session's usage.** An all-zero usage payload from an interrupted turn
  no longer overwrites what the session had already counted.
- **A fresh device published every local transcript it could find.** The initial mirror import is
  capped at 50 sessions from the last 14 days, and both bounds are configurable.

**One consequence worth knowing.** A remote session must never be approved by the machine on behalf
of someone who is not looking at it, so remote Claude sessions do **not** load your user-level
`~/.claude/settings.json`. User-scoped MCP servers, skills, hooks and `permissions.defaultMode` do
not apply to them; project and local settings do. Change it with `[claude] setting_sources` in
`~/.rc-client/config.toml` — see [`docs/CLIENT.md`](docs/CLIENT.md).

**Not verified.**

- **Push delivery.** No Web Push or APNs message has reached a real endpoint. The registration
  endpoints, senders and payload shapes exist and are unit-tested.
- **Speech to text.** Every run used `STT_PROVIDER=none`, so voice was never driven against a
  backend from either app, and the optional `local-stt` compose profile was never started.
- **Attachments, Codex approvals and `todos` events.** All unit-tested, none produced by a live
  agent: this machine's Codex sandbox never escalates, and no session emitted a todo snapshot.
- **Two corners of the approval card.** Allow and Deny were driven end to end on a `Write`; the
  session-scoped middle option was never chosen, and no approval for a command rather than an edit
  was answered.
- **A successful `session.takeover`.** The refusal path was verified; accepting needs an idle
  interactive CLI that the harness could not hold open.
- **Linux.** Only macOS was exercised. The systemd unit and `rc-client service install` have not
  been run anywhere.
- **TLS.** The stack was only exercised over plain HTTP on the published port. No reverse proxy,
  certificate or HSTS response was in front of it during validation.
- **iOS beyond four smoke tests.** Sign-in, a real session, Devices and Settings ran against a live
  gateway. New session, add device, the directory picker, voice and push were exercised only by the
  offline demo suite, and there was no physical device, no dark mode, no VoiceOver, no CI run and no
  TestFlight upload.
- **Codex sessions cannot be taken over.** Only Claude advertises the `takeover` capability. That
  one is a design decision rather than a gap.

## Security model

- **The device dials out.** Nothing listens on a developer machine, so no inbound port, no SSH key
  and no tunnel is involved.
- **Credentials stay where they belong.** The agents' model credentials never leave the device. The
  gateway has no model API key and cannot run an agent.
- **The gateway forwards, it does not read.** It parses the routing envelope and the session
  summaries it indexes; message text, tool input and tool output pass through opaquely.
- **Pairing codes are single use**, expire in ten minutes and are stored hashed. Device tokens are
  shown once at enrollment and stored hashed; revoking a device drops its socket and its sessions.
- **Browser sessions** use an HttpOnly, SameSite=Strict cookie and an `Origin` check on every
  cookie-authenticated write; native apps use a bearer token instead. Login is rate limited per IP.
  Issued sessions are recorded on disk, so signing out is durable and a restart does not quietly
  hand a revoked token back its access.
- **The machine cannot approve for you.** Remote Claude sessions skip user-level settings, so
  auto-approval configured on the device does not answer a request you never saw.
- **Notifications say nothing.** A push payload carries a device name and a reason, never content.
- **Logs redact.** Tokens, passwords, pairing codes, prompt text and tool output are never logged.

## Documentation

| Document | What it covers |
| --- | --- |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Components, data flow, the block timeline, storage, design trade-offs |
| [`docs/DEPLOY.md`](docs/DEPLOY.md) | VPS deployment, `.env` reference, TLS, upgrades, backups, troubleshooting |
| [`docs/CLIENT.md`](docs/CLIENT.md) | The device daemon: installer, commands, services, mirroring, configuration |
| [`docs/WEB.md`](docs/WEB.md) | The browser app |
| [`docs/IOS.md`](docs/IOS.md) | The iPhone app |
| [`docs/DESIGN.md`](docs/DESIGN.md) | The interaction design behind both apps |
| [`docs/VALIDATION.md`](docs/VALIDATION.md) | Backend validation: what was tested end to end, what failed and was fixed, what was not |
| [`docs/VALIDATION-APPS.md`](docs/VALIDATION-APPS.md) | App validation: web and iOS driven against a real gateway, device and CLIs |
| [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md) | The normative wire contract |

## License

MIT. This project is a second-generation build on
[cc-remote](https://github.com/muggle-stack/cc-remote), also MIT licensed: the gateway's forwarding
core is adapted from it, and the device daemon reuses its hard-won details about driving the two
CLIs. Both debts are itemised file by file in `gateway/THIRD_PARTY_NOTICES.md` and
`client/THIRD_PARTY_NOTICES.md`. No top-level `LICENSE` file has been added to the repository yet.
