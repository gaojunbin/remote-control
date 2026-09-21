# remote-control — working notes for future development

Remote control of terminal coding agents (Claude Code, Codex, Grok Build, pi) from a phone or a
browser, through a gateway you host. Apps never talk to devices; the gateway routes everything.
Four components, one frozen wire protocol. This file is the short list of what matters when you
change any of it; the long form is under `docs/`.

## Layout

| Directory | What | Toolchain (must be green before a commit) |
| --- | --- | --- |
| `protocol/` | The wire contract: `PROTOCOL.md`, JSON schemas, fixtures, validator | `cd protocol && uv run --with jsonschema python scripts/validate_fixtures.py` |
| `gateway/` | FastAPI service on the VPS (`rc_gateway`), Docker Compose deployable | `cd gateway && uv run ruff check . && uv run ruff format --check . && uv run mypy rc_gateway tests && uv run pytest -q` |
| `client/` | `rc-client`, the device daemon on every developer machine (`rc_client`), plus `install.sh` | `cd client && uv run ruff check . && uv run ruff format --check . && uv run mypy rc_client tests && uv run pytest -q` |
| `web/` | React + TypeScript app the gateway serves, with a mock gateway for development | `cd web && npm test -- --run && npx tsc --noEmit && npm run lint && npm run build` |
| `ios/` | SwiftUI app: `Sources/RCCore` (protocol, state), `Sources/RCUI` (screens), `App/`, `Verification*` | `DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer swift run RCVerify && swift run RCUIVerify && swift test`, then `xcodegen generate`, the simulator build and the whole `RemoteControlUITests` target on a booted simulator (`docs/IOS.md`); CI runs everything but the UI tests |
| `docs/` | `ARCHITECTURE`, `DESIGN` (UX rulings), `CLIENT`, `WEB`, `IOS`, `DEPLOY`, `VALIDATION`, `VALIDATION-APPS` | Keep them true; every round ends with a docs commit |

## The protocol is frozen; change it by amendment

`protocol/PROTOCOL.md` is v1 plus numbered amendments (A1…A39 so far, dated entries at the end). A
change to the wire is an amendment: edit the section, the schema, the fixtures and the checklist,
append the entry, run the validator, commit `protocol/` first, and only then let anyone implement
it. Components consume the contract; nobody edits it mid-implementation. Apps stay agent-agnostic —
they read `AgentInfo` capabilities and the five attachment fields, never the agent id.

## Versions and compatibility

- The gateway serves the web app, so web and gateway always match. The device client is updated
  from the apps (`device.update`, the wheel the gateway serves, A22) or by re-running `install.sh`.
- **The iOS app is installed separately, so the gateway states the oldest iOS app it still
  supports** (`GET /api/config` and `hello` carry `apps.ios.minimum_version`, A31; the constant
  `IOS_MINIMUM_APP_VERSION` in `gateway/rc_gateway/compat.py`, overridable with `IOS_MIN_APP_VERSION`).
  An app below it shows a blocking "Update required" screen and does nothing else. **Rule for every
  release: if the gateway and the iOS app change together and the new gateway no longer works with
  an older iOS app, raise that constant in the same change**, and set `IOS_UPDATE_URL` on the
  gateway to where the new build is (TestFlight or the App Store). Raise it only when compatibility
  is really broken; an app one amendment behind must keep working when the amendment is additive.
- **One version per release, on all four components, every round.** The owner's standing rule
  (2026-09-16): whenever a round of changes is closed, the gateway, the web app, the device client
  and the iOS app all move to the same new version number — whether or not each of them changed —
  and the repository is tagged with it. A device's Update action (A22) and the Settings screens
  then read one number per release, and a build can be told from the last one. A component left at
  an old number while the repo is tagged ahead of it is a defect (round 29 found all four at 0.1.0
  under a v1.2 tag). The exact steps are under "Closing a round" below; no reminder from the owner
  is needed, and an agent that closes a round without them has not finished it.

## Closing a round

Every round — a feature, a fix batch, a merged pull request — ends with these steps, in this order,
after every changed component's toolchain is green. For the iOS app that includes the **whole**
`RemoteControlUITests` target on a booted simulator, not only the tests the round added: CI builds
and verifies but does not run UI tests (their snapshots time out on GitHub's shared simulators,
round 46), so the local run is the only one, and a stale test found there is fixed before the tag.

1. **Docs commit.** `docs/` tells the truth about what changed (`VALIDATION.md` gets a dated section
   on what was and was not verified), then commit it.
2. **Bump all four components to the round's version** (patch for fixes, minor for features), in
   one commit per component or one commit for the bumps alone:
   - gateway: `gateway/pyproject.toml` `version`, `gateway/rc_gateway/__init__.py` `__version__`
     (what `hello` and `GET /api/config` report), and the `rc-gateway` entry in `gateway/uv.lock`;
   - web: `web/package.json` `version`, and the two root entries in `web/package-lock.json`;
   - client: `client/pyproject.toml` `version`, `client/rc_client/__init__.py` `__version__`, and
     the `rc-client` entry in `client/uv.lock` (the wheel the gateway serves is named from it);
   - iOS: `ios/project.yml` `MARKETING_VERSION` (the app compares it as `major.minor.patch` against
     the gateway's minimum, A31) and `CURRENT_PROJECT_VERSION` (+1), plus the three places that
     assert it — the fallback in `ios/Sources/RCCore/State/AppVersion.swift`, the two checks in
     `ios/VerificationUI/main.swift` that read `project.yml`, and the UI test that reads the Settings
     version row — then `xcodegen generate` so `ios/RemoteControl.xcodeproj` follows.
3. **Tag and push.** `git tag -a vX.Y.Z -m "<one line on what the release is>"`, then push `master`
   and the tag. Raise `IOS_MINIMUM_APP_VERSION` in the same round only if an older iOS app really
   stopped working (see above).

## How agents are attached (why terminal sessions can be driven from a phone)

| Agent | Mechanism | Setup on the device |
| --- | --- | --- |
| Claude Code | Channels: a `claude` shim adds `--dangerously-load-development-channels`; the device's MCP channel bridge injects messages and relays permission prompts (A10) | `rc-client shim install` |
| Codex | The shared app-server daemon every bare `codex` runs inside; the device is a second client (`thread/resume` joins). The device starts and restarts the daemon itself (A11, round 20) | `rc-client codex setup` (installs the standalone build) |
| Grok Build | Grok's leader process, joined with `agent agent --leader stdio`; `session/load` joins a TUI's session (A28). Needs `[cli] use_leader = true` in `~/.grok/config.toml`, edited in place | `rc-client grok setup` |
| pi | The device's own extension copied into `~/.pi/agent/extensions/` (A26) | `rc-client pi setup` |

`control` is `remote` / `terminal` / `shared` / `none`; `shared` is a live CLI the device is
attached to. Details and the verified facts per agent: `docs/CLIENT.md`, `docs/ARCHITECTURE.md`.

## Rules of the house

- Everything in the repository is English: code, comments, docs, commit messages, UI strings
  (with a zh-Hans translation for product strings). Conversation with the owner is Chinese.
- Small focused functions and files. No version-suffixed names (`Foo2`, `handleNew`); change in
  place. Delete replaced code outright — no compatibility shims, no "removed" comments.
- Temporary files never in the repository root; use the session scratchpad. One-off scripts are
  not committed.
- The owner's dotfiles (`~/.grok/config.toml`, `~/.zprofile`, …) are often iCloud symlinks: edit
  in place, never unlink/rename/replace. Anything that writes into a user's config must keep the
  inode.
- Commits go per component with an explicit pathspec (`git commit -- client`), never `git add -A`.
- Parallel work: one owner per directory, contracts frozen first, subagents stop after two failed
  attempts and report the failure. Reports are conclusion-first and at most 20 lines.
- On this Mac `rm` is a safe-rm wrapper: use `/bin/rm -rf` in scripts and never chain `rm && …`.
- The iOS signing team id lives only in the ignored `ios/Signing.local.xcconfig`.

## Verifying against the real thing

Live checks against real agents run in isolated homes (`CODEX_HOME`, `GROK_HOME`, a scratch pi
home), with one-word turns, and clean up every process, socket and directory afterwards; the
owner's real `~/.claude`, `~/.codex`, `~/.grok`, `~/.pi` are never written. Unix socket paths must
stay under 104 bytes, so scratch homes go under a short directory. What was and was not verified is
recorded per round in `docs/VALIDATION.md` (device and gateway) and `docs/VALIDATION-APPS.md` (apps).

## Deploying

VPS: `git pull && docker compose build && docker compose up -d` (`docs/DEPLOY.md`; `.env` reference
there — STT, POLISH, APNS, VAPID). Devices: the app's Update action or `install.sh`. iOS: TestFlight
from `ios/` (`docs/IOS.md`).
