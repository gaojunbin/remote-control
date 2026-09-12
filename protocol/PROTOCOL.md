# remote-control wire protocol v1

Normative specification for every component: the **gateway** (VPS service `rc_gateway`), a **device**
(a machine running the client daemon `rc-client`), and an **app** (the web UI or the iOS app).

This document is the reader-facing form of the frozen contract. It is paired with two things that
make it machine-checkable, and all three must agree:

| Artifact | Purpose |
| --- | --- |
| `schema/*.json` | JSON Schema draft 2020-12 for every object, event and frame |
| `fixtures/**/*.json` | A valid example of every frame type, event kind and HTTP body |
| `scripts/validate_fixtures.py` | Checks the fixtures against the schema and reports coverage |

"MUST", "MUST NOT" and "SHOULD" carry their usual meaning. Only the orchestrator changes this
contract; a component that finds a gap reports it instead of deviating.

## Conventions

- All frames are UTF-8 JSON text messages on a WebSocket, one object per message. Binary frames
  appear only on the speech-to-text socket.
- Field names are `snake_case`. Enumerations are lowercase strings.
- Timestamps are Unix **milliseconds** as integers.
- Ids are strings, UUID v4 unless stated otherwise.
- **Unknown fields MUST be ignored, never rejected.** New optional fields may be added later; a
  party that validates strictly will break on the next revision. Every schema in `schema/` therefore
  leaves `additionalProperties` open.
- Protocol version is `1`. Every `hello` carries `protocol: 1`; a mismatch is a hard error with code
  `unsupported`.

The contract carries eleven orchestrator amendments, listed with their wording in section 11.
Section 5 uses the amended field names throughout.

---

## 1. Overview

### 1.1 Vocabulary

| Term | Meaning |
| --- | --- |
| gateway | The VPS service. Authenticates users and devices, routes frames, indexes sessions, buffers events for replay, proxies speech-to-text and push. It never runs an agent and never holds model credentials. |
| device | A developer machine running `rc-client`. It drives the locally installed agents and is the source of truth for session history. |
| app | The web UI or the iOS app. Apps never talk to devices directly. |
| agent | `"claude"` (Claude Code) or `"codex"` (Codex CLI). The field is an **extensible string**: a UI that meets an unknown agent renders it generically, using the id as the label. |
| session | One conversation with one agent on one device. `session_id` is device-local (for Claude it equals the Claude session id, for Codex the thread id). Global identity is the pair (`device_id`, `session_id`). |
| block | One renderable unit in a session timeline. |
| seq | Per-session, monotonically increasing integer assigned by the device to every session event. It survives device restarts and is the replay cursor. |
| turn | One user message plus the agent's work until it goes idle. |

### 1.2 Topology

```
web browser ──┐  wss /ws/app                       wss /ws/device  ┌── rc-client (device A) ── claude / codex
              ├────────────► gateway (rc_gateway) ◄────────────────┤
iOS app     ──┘              HTTP /api/*, /ws/stt                  └── rc-client (device B) ── claude / codex
```

Devices dial out only. The gateway parses just the envelope fields it needs for routing (`type`,
`id`, `device_id`, `session_id`, `seq`) plus the session summaries it indexes; every other payload is
forwarded opaquely.

---

## 2. Transport and envelope

### 2.1 Frame shape

Every frame is `{"type": "<domain.action>", ...fields}`. A request additionally carries `id`, chosen
by the requester and echoed in the reply:

```json
{"type": "reply", "id": "…", "ok": true,  "result": { }}
{"type": "reply", "id": "…", "ok": false, "error": {"code": "not_found", "message": "human readable"}}
```

### 2.2 Error codes

| Code | Used when |
| --- | --- |
| `bad_request` | The frame or body is malformed, or a value is out of range. |
| `unauthorized` | Missing or invalid credentials. |
| `forbidden` | Authenticated, but not allowed to touch this resource. |
| `not_found` | No such device, session, block, request or pairing code. |
| `device_offline` | The owning device has no live socket. Produced by the gateway. |
| `agent_unavailable` | The device has the session but the agent binary is missing or will not start. |
| `conflict` | The action contradicts current state, for example sending to a session the terminal controls. |
| `timeout` | No reply arrived in time. Produced by the gateway after 60 s. |
| `internal` | Unexpected failure. |
| `unsupported` | Protocol version mismatch, or a feature the deployment does not provide. |
| `too_large` | Payload over a documented bound. |

`error.message` is optional and is for humans; clients MUST branch on `error.code` only.

### 2.3 Routing of forwarded requests

The gateway forwards an app request to the owning device unchanged, except that it

1. adds `device_id` when the request is addressed by `session_id`, and
2. stamps `from`, an opaque app-connection id.

The device echoes `id` and `from` in its `reply`; the gateway delivers that reply only to the
originating connection and strips `from` before doing so. If the device is offline the gateway
answers `device_offline` itself. If no reply arrives within 60 s the gateway answers `timeout`.
`session.create` and `session.history` may use the full 60 s; every other request SHOULD reply in
under 5 s.

### 2.4 Idempotency

A device MUST treat a repeated `session.send` carrying the same `id` within the same session as a
duplicate: it returns the original result and does not send the message again. This is what makes an
app's retry of an unconfirmed send safe.

### 2.5 Liveness and close codes

The gateway sends `ping` every 25 s on both socket types and closes a connection silent for 90 s.
Any party that receives no frame at all for 60 s MUST treat the connection as half-open and
reconnect: mobile NAT never delivers `onclose`. These are the **only** keepalives on the link: the
gateway disables its WebSocket server's own protocol-level ping/pong timeout (amendment A13), so a
device whose event loop stalls for a few seconds is not cut off by a second, shorter clock.

A device link that drops is not offline yet. The gateway keeps reporting `online: true` for a
**grace period of 20 s** after the socket closes for a transient reason, and reports `online: false`
only when no replacement connection arrived in that time; a close with `4401` or `4403`, or a device
that was explicitly removed, turns it `false` at once. Requests addressed to the device during the
grace period wait for the replacement connection and are answered `device_offline` when the period
ends without one. A reconnect within the period is invisible to apps.

The close code says whether reconnecting is worth trying (amendment A4). It applies to `/ws/app` and
`/ws/device` alike.

| Code | Meaning | What the client does |
| --- | --- | --- |
| `4401` | The credential is missing, invalid, expired or revoked | **Stop reconnecting.** An app returns to login; a device re-enrolls. |
| `4403` | The credential is valid but not allowed for the requested resource | Do not retry the same resource. |
| `4001` | This device connection was replaced by a newer one | The device does not reconnect immediately. |
| `1008` | Protocol violation, and nothing else | Fix the client; retrying unchanged will fail again. |
| anything else | Transient | Reconnect with backoff. |

An app that retries through a `4401` will loop against a dead token, so treat the code as
authoritative and clear stored credentials before returning to login.

### 2.6 Size bounds

| Limit | Value |
| --- | --- |
| Session event frame | 64 KiB. The gateway drops larger frames and logs. |
| `tool_call.input` | 8 KiB of JSON, then `input_truncated: true` |
| `tool_call.output` | 16 KiB, then `output_truncated: true` |
| `diff.patch` | 32 KiB, then `patch_truncated: true` |
| `session.block` reply | 1 MiB |
| `session.send` text | 64 KiB |
| `session.send` attachments | 8 attachments, 6 MiB each decoded |
| `session.history` limit | 200 by default, 1000 maximum |
| Gateway replay buffer | 2 000 events or 4 MiB per session, whichever is smaller |
| Streaming delta flush | at most one flush per 80 ms per block |
| Speech-to-text utterance | 120 s of audio, 4 MiB total |

Truncated content is not lost: the full block is fetched with `session.block`.

---

## 3. HTTP API

Authentication is a single shared password by default (`RC_PASSWORD`); the `users` table leaves room
for more users later. Browsers authenticate with the HttpOnly cookie `rc_session`
(`SameSite=Strict`, `Secure` under https). Native apps send `Authorization: Bearer <token>` with the
same token value. WebSocket upgrades accept either.

**Origin rule.** Cookie-authenticated upgrades and cookie-authenticated mutating requests MUST carry
an `Origin` header equal to `PUBLIC_ORIGIN`. Bearer-authenticated requests need no `Origin`.

### 3.1 Unauthenticated

| Method | Path | Request | Response | Errors |
| --- | --- | --- | --- | --- |
| GET | `/api/health` | – | `HealthResponse` | – |
| POST | `/api/login` | `LoginRequest` | `LoginResponse` + `Set-Cookie: rc_session` | `401 unauthorized`. Rate limited to 5 per minute per IP. |
| POST | `/api/devices/enroll` | `EnrollRequest` | `EnrollResponse` | `404` unknown or expired code, `409` code already used |
| GET | `/install.sh` | – | The client install script with `__GATEWAY_ORIGIN__` replaced by `PUBLIC_ORIGIN` | – |
| GET | `/dist/rc_client-latest.whl` | – | The client wheel built into the image. The versioned filename also resolves. | – |
| POST | `/api/pairing/requests` | – | `PairingRequestResponse` | A host asks to be claimed by scanning (A23). `429 too_many_requests` beyond 6 per minute per IP or 50 outstanding tokens. |
| GET | `/api/pairing/requests/{token}` | – | `PairingRequestStatusResponse` | Long-poll up to 25 s. `waiting` until claimed; `claimed` carries the code once, then the token is spent. `404` unknown, `410` expired. |
| GET | `/` and any non-API path | – | The web app (single-page-app fallback) | – |

The pairing code is the only credential `POST /api/devices/enroll` needs. `device_token` is returned
once and stored hashed.

```json
{"ok": true, "version": "0.1.0", "protocol": 1, "auth": {"mode": "password"}}
```

`fixtures/http/login.request.json`

```json
{
  "password": "correct horse battery staple",
  "username": "admin"
}
```

`fixtures/http/login.response.json`

```json
{
  "ok": true,
  "token": "rc1.7f3c2a19d84b4e0f9a6c1b25e30d7a48.5c9e1f",
  "exp": 1791536400000,
  "user": {
    "username": "admin"
  }
}
```

`fixtures/http/devices.enroll.request.json` (abridged)

```json
{
  "code": "RC-7K42-QX9M",
  "name": "mac-studio-office",
  "platform": "macos",
  "hostname": "mac-studio.local",
  "arch": "arm64",
  "client_version": "0.1.0"
}
```

`fixtures/http/devices.enroll.response.json`

```json
{
  "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712",
  "device_token": "rcd1.c5efb1ec29124619.9f4a2d7b1e6c08f35a2d",
  "gateway_ws_url": "wss://rc.example.com/ws/device"
}
```

### 3.2 Authenticated — account and configuration

| Method | Path | Request | Response |
| --- | --- | --- | --- |
| POST | `/api/logout` | – | `OkResponse`, and the token is revoked |
| GET | `/api/session` | – | `AuthSessionResponse` |
| GET | `/api/config` | – | `ConfigResponse` |

`fixtures/http/auth.session.response.json`

```json
{
  "ok": true,
  "user": {
    "username": "admin"
  },
  "exp": 1791536400000
}
```

`client` names the wheel the gateway serves: its `version`, its `build` (the SHA-256 of the file)
and its `url`; a device whose `client_build` differs can be brought to it with `device.update` (A22).

`fixtures/http/config.response.json`

```json
{
  "public_origin": "https://rc.example.com",
  "stt": {
    "enabled": true,
    "languages": [
      "auto",
      "zh",
      "en"
    ]
  },
  "push": {
    "web_enabled": true,
    "apns_enabled": false
  },
  "version": "0.1.0"
}
```

### 3.3 Authenticated — devices and pairing

| Method | Path | Request | Response | Notes |
| --- | --- | --- | --- | --- |
| GET | `/api/devices` | – | `DeviceListResponse` | |
| PATCH | `/api/devices/{device_id}` | `DevicePatchRequest` | `DeviceResponse` | Renames the device |
| DELETE | `/api/devices/{device_id}` | – | `OkResponse` | Revokes the device token, closes its socket, drops its sessions from the gateway index |
| POST | `/api/devices/pairing` | – | `PairingResponse` | Code format `RC-XXXX-XXXX`, Crockford base32 without I, L, O and U. Single use, 10-minute lifetime. |
| DELETE | `/api/devices/pairing/{code}` | – | `OkResponse` | Cancels an outstanding code |
| POST | `/api/pairing/requests/{token}/claim` | – | `PairingClaimResponse` | Binds a host's request to the caller and mints its pairing code (A23); `pairing.progress` follows for that code. `404` unknown or expired, `409` already claimed. |

`fixtures/http/devices.patch.request.json`

```json
{
  "name": "studio"
}
```

`fixtures/http/devices.pairing.response.json`

```json
{
  "code": "RC-7K42-QX9M",
  "expires_at": 1788945000000,
  "install": {
    "macos": "curl -fsSL https://rc.example.com/install.sh | sh -s -- --pair RC-7K42-QX9M",
    "linux": "curl -fsSL https://rc.example.com/install.sh | sh -s -- --pair RC-7K42-QX9M"
  }
}
```

### 3.4 Authenticated — sessions

| Method | Path | Query | Response |
| --- | --- | --- | --- |
| GET | `/api/sessions` | `device_id`, `archived` | `SessionListResponse` |

This reads the gateway index, so it renders the last known summaries even while a device is offline.

### 3.5 Authenticated — speech to text

| Method | Path | Request | Response | Errors |
| --- | --- | --- | --- | --- |
| POST | `/api/stt/transcribe` | multipart form: `audio` (`wav`, `webm`, `m4a`, `mp3`), optional `language` | `SttTranscribeResponse` | `503` with code `unsupported` when speech to text is not configured |

`fixtures/http/stt.transcribe.response.json`

```json
{
  "text": "Fix the flaky refresh test in tests slash test underscore auth dot py.",
  "language": "en"
}
```

### 3.6 Authenticated — push registration

| Method | Path | Request | Response |
| --- | --- | --- | --- |
| GET | `/api/push/web/vapid` | – | `VapidResponse` |
| POST | `/api/push/web/subscribe` | `WebPushSubscribeRequest` | `OkResponse` |
| DELETE | `/api/push/web/subscribe` | `WebPushUnsubscribeRequest` | `OkResponse` |
| POST | `/api/push/apns/register` | `ApnsRegisterRequest` | `OkResponse` |
| DELETE | `/api/push/apns/register` | `ApnsUnregisterRequest` | `OkResponse` |

`fixtures/http/push.apns.register.request.json`

```json
{
  "token": "a8f3c91d4b7e250689fc13ad5e207b46c8d90a1f3e7b5c249d06f8a1b3c5d7e9",
  "environment": "production",
  "bundle_id": "com.junbingao.remotecontrol"
}
```

### 3.7 Push payload

Both the Web Push JSON body and the APNs custom data carry the same object. The visible notification
text is generic, for example "mac-studio-office: approval needed". **No prompt text, message text or
tool output ever appears in a push.**

`fixtures/http/push.payload.json`

```json
{
  "rc": {
    "v": 1,
    "kind": "needs_approval",
    "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712",
    "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
    "device_name": "mac-studio-office",
    "title": "mac-studio-office: approval needed"
  }
}
```

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rc.v` | `1` | yes | Payload version |
| `rc.kind` | `needs_approval` \| `needs_input` \| `turn_completed` \| `error` | yes | Why the user is being notified |
| `rc.device_id` | uuid | yes | Deep-link target |
| `rc.session_id` | string | yes | Deep-link target |
| `rc.device_name` | string | yes | Shown in the notification text |
| `rc.title` | string | yes | The generic notification text |

### 3.8 Speech-to-text streaming socket

`WS /ws/stt?language=<code>`, authenticated as above.

The client sends **binary** frames of PCM16LE, 16 kHz, mono, 100–200 ms per frame. It sends
`stt.stop` to finish and transcribe everything received, or `stt.cancel` to discard the utterance.
The gateway sends `stt.partial` about every 2 s while audio is arriving, then `stt.final` after
`stt.stop`, or `stt.error` on failure. The gateway closes the socket after `stt.final` or
`stt.error`.

`fixtures/stt/stt.stop.json`

```json
{
  "type": "stt.stop"
}
```

`fixtures/stt/stt.cancel.json`

```json
{
  "type": "stt.cancel"
}
```

`fixtures/stt/stt.partial.json`

```json
{
  "type": "stt.partial",
  "text": "fix the flaky refresh"
}
```

`fixtures/stt/stt.final.json`

```json
{
  "type": "stt.final",
  "text": "Fix the flaky refresh test in tests/test_auth.py.",
  "language": "en"
}
```

`fixtures/stt/stt.error.json`

```json
{
  "type": "stt.error",
  "message": "speech to text backend returned 502"
}
```

---

## 4. Shared objects

Schema: `schema/objects.json`. These objects appear in HTTP bodies and in frames on both sockets.

### 4.1 Device

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `device_id` | uuid | yes | Assigned by the gateway at enrollment |
| `name` | string | yes | User-editable display name |
| `platform` | `macos` \| `linux` | yes | |
| `hostname` | string | yes | Reported by the device |
| `arch` | `arm64` \| `x86_64` | yes | |
| `client_version` | string | yes | `rc-client` version |
| `client_build` | string \| null | no | SHA-256 of the wheel the client was installed from; null when unknown (A22) |
| `update_state` | `idle` \| `updating` \| `failed` | no | An app-requested update in flight or failed; absent means idle (A22) |
| `update_message` | string \| null | no | Why the last update failed (A22) |
| `online` | boolean | yes | True while the device socket is live |
| `last_seen` | timestamp | yes | |
| `created_at` | timestamp | yes | Enrollment time |
| `latency_ms` | integer \| null | yes | Last ping round trip, null when unknown |
| `agents` | `AgentInfo[]` | yes | Agents detected on the device |

`fixtures/app/device.updated.json` (abridged)

```json
{
  "type": "device.updated",
  "device": {
    "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712",
    "name": "mac-studio-office",
    "platform": "macos",
    "hostname": "mac-studio.local",
    "arch": "arm64",
    "client_version": "0.1.0",
    "online": true,
    "last_seen": 1788944400000,
    "created_at": 1788426000000,
    "latency_ms": 18,
    "agents": [
      {
        "agent": "claude",
        "available": true,
        "version": "2.1.266",
        "path": "/Users/me/.local/bin/claude",
        "models": [
          {
            "id": "claude-opus-4-6",
            "label": "Opus 4.6"
          }
        ],
        "default_model": "claude-sonnet-4-5",
        "permission_modes": [
          {
            "id": "default",
            "label": "Ask before edits"
          }
        ],
        "default_permission_mode": "default",
        "efforts": [
          {
            "id": "low",
            "label": "Low"
          }
        ],
        "default_effort": "medium",
        "capabilities": [
          "worktree",
          "takeover",
          "interrupt",
          "queue",
          "attachments",
          "effort",
          "history"
        ]
      }
    ]
  }
}
```

The agent and option arrays are shortened here; the fixture holds the full object.

### 4.2 AgentInfo

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `agent` | string | yes | `claude`, `codex`, or a future id. Never assume a closed set. |
| `available` | boolean | yes | False when the binary is missing or will not start |
| `version` | string \| null | yes | |
| `path` | string \| null | yes | Resolved binary path |
| `models` | `LabeledId[]` | yes | Native model ids with human labels |
| `default_model` | string \| null | yes | |
| `permission_modes` | `LabeledId[]` | yes | See 4.3 for the ids each agent exposes |
| `default_permission_mode` | string \| null | yes | |
| `efforts` | `LabeledId[]` | yes | Empty array when the agent has no effort levels |
| `default_effort` | string \| null | yes | |
| `speeds` | `LabeledId[]` | no | Speed tiers the agent can run a session at beyond its standard speed, for example Codex's `priority` ("Fast"); empty or absent when it has none (amendment A21) |
| `capabilities` | string[] | yes | Subset of `worktree`, `takeover`, `interrupt`, `queue`, `steer`, `attachments`, `effort`, `history` |
| `attach` | `channel` \| `daemon` \| null | no | How this agent's terminal sessions can be attached. `channel` is the Claude channel shim, `daemon` the Codex shared app-server. Null or absent means terminal sessions can only be taken over or resumed. |
| `attach_ready` | boolean | no | Whether the device is prepared to attach: for Claude the `claude` shim is installed and on `PATH`, for Codex a handshake on the shared daemon socket succeeds. Apps use it only to word the hint on a `terminal` session. |
| `shared_interrupt` | boolean | no | Whether the attachment can interrupt a running turn. `session.stop` on a `shared` session needs this **and** capability `interrupt`. False when absent. |
| `shared_settings` | boolean | no | Whether `session.set` for `model`, `permission_mode`, `effort` and `speed` works on a `shared` session. False when absent. |
| `shared_attachments` | boolean | no | Whether `session.send` attachments are delivered on a `shared` session. False when absent. |

Capabilities gate the UI. `steer` decides whether a message sent during a running turn is steered or
queued; `takeover` decides whether a terminal-controlled session offers "Take over"; `history`
decides whether the app can page backwards.

`capabilities` is unchanged by the attachment fields. `takeover` keeps its meaning and applies to
`control: "terminal"` only; `attach`, `attach_ready`, `shared_interrupt`, `shared_settings` and
`shared_attachments` describe `control: "shared"` instead (4.4).

The five attachment fields are the whole story an app needs: nothing in this protocol is specific to
one agent's attachment mechanism. Claude reports `attach: "channel"` with `shared_interrupt`,
`shared_settings` and `shared_attachments` all false, because a channel can neither interrupt a turn
nor change settings nor carry bytes. Codex reports `attach: "daemon"`, `attach_ready` true once a
WebSocket handshake on the shared daemon socket succeeds rather than merely because the socket file
is there, and `shared_interrupt`, `shared_settings` and `shared_attachments` all true, because the
shared app-server accepts interrupts, settings updates and image inputs from every attached client.
`fixtures/objects/agent.claude-attach.json` and `fixtures/objects/agent.codex-daemon.json` are the
two worked examples.

### 4.3 Permission-mode ids exposed by the device

| Agent | Ids and labels |
| --- | --- |
| Claude | `default` "Ask before edits", `acceptEdits` "Auto-accept edits", `plan` "Plan mode", `bypassPermissions` "Bypass permissions" |
| Codex | `untrusted` "Ask for everything", `on-request` "Ask when needed", `never` "Never ask" |

Model ids are the agents' native ids.

### 4.4 Session

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `session_id` | string | yes | Device-local. Global identity is (`device_id`, `session_id`). |
| `device_id` | uuid | yes | |
| `agent` | string | yes | |
| `title` | string | yes | Device-supplied summary of the conversation |
| `cwd` | string | yes | Working directory on the device |
| `git` | `Git` \| null | yes | Null when `cwd` is not a repository |
| `state` | see 4.5 | yes | |
| `state_detail` | string \| null | yes | One line of context for the current state |
| `origin` | `remote` \| `terminal` | yes | Who created the session |
| `control` | `remote` \| `terminal` \| `shared` \| `none` | yes | Who owns the input right now. See the table below. |
| `model` | string \| null | yes | |
| `permission_mode` | string \| null | yes | |
| `effort` | string \| null | yes | |
| `speed` | string \| null | no | The tier from `AgentInfo.speeds` the session runs at; null or absent is the standard speed (A21) |
| `created_at` | timestamp | yes | |
| `updated_at` | timestamp | yes | |
| `last_seq` | integer | yes | Highest `seq` the device has produced |
| `archived` | boolean | yes | |
| `turn` | `{turn_id, started_at}` \| null | yes | Non-null while a turn is in progress |
| `todos` | `{total, done}` \| null | yes | Counts for the header chip |
| `usage` | `Usage` \| null | yes | |
| `queued` | integer | yes | Number of queued remote messages |

#### `control` values

| Value | Meaning |
| --- | --- |
| `remote` | The device daemon runs the agent process for this session: one created with origin `remote`, or a terminal session that was resumed or taken over. |
| `terminal` | A live CLI process owns the session and the device has **no** way in. The composer is disabled; "Take over" is offered only when the agent has capability `takeover`. |
| `shared` | A live CLI process owns the session **and the device is attached to it**: for Claude through a channel the CLI loads, for Codex through the shared app-server. Apps enable the composer and approvals exactly as for `remote`, hide "Take over", and hide Stop unless the agent lists capability `interrupt` **and** reports `shared_interrupt: true` (4.2). Claude channels cannot interrupt a running turn; Codex can. |
| `none` | No process owns the session; the next `session.send` resumes it under device control. |

Control moves `terminal → shared` when the attachment registers, `shared → terminal` when the
attachment drops while the CLI process is still alive, and `shared → none` when the CLI exits. Each
transition travels the way every other `control` change does: a `meta` event carrying `control`
(5.11), a `status` event when the state changes with it (5.10), and a republished session summary.

An attachment follows the CLI process, not the id the process was started with (amendment A16). A
Claude CLI that resumes another session inside the TUI, or clears to a fresh one, keeps its channel;
the device learns the new id from the CLI's own `SessionStart` hook and moves the attachment there:
the session the terminal is now in becomes `shared`, and the one it left becomes `none` by the
transition above. A session that never held a message and has no transcript on disk — the id a CLI
was started with and then left by `/resume` or by quitting — is not kept: the device removes it with
`session.removed` (7) as soon as the terminal leaves it, and on its next scan for any it missed.
`session.removed` is therefore not only the answer to a `session.delete`; an app drops the row
whenever the frame arrives.

#### What a terminal-held session reports about itself

For a Claude session it mirrors from a transcript, the device fills `model`, `permission_mode` and
`effort` from the transcript's own records — the model row Claude Code writes at start and on every
change, the permission-mode row it writes each turn, and the effort carried on each assistant
message — and publishes every change as `meta` (5.11), so an app sees a `/model` typed in the
terminal within a scan (amendment A17); a Codex thread reports its `speed` from the daemon's own
settings as soon as the device attaches to it (A21). The
values are the agent's own ids and need not appear in
`AgentInfo`; an app shows an unknown one by its id. Changing them is still `session.set`, which a
`shared` session refuses with `unsupported` unless `shared_settings` is true (4.2) and a
`terminal` session refuses outright.

#### `origin` and `control` for Codex threads on the shared daemon

Codex runs every bare `codex` TUI inside one local app-server daemon, and the device attaches to
that daemon as a second client. The device derives `origin` and `control` for a Codex thread like
this.

| Situation | `origin` | `control` |
| --- | --- | --- |
| The device created the thread and no message typed in a terminal has been seen on it | `remote` | `remote` |
| The thread was already loaded in the daemon when the device found it, or any message on it was typed in a terminal | `terminal` when the device did not create the thread, otherwise unchanged | `shared` |
| The thread is known from the daemon's history but is not loaded | unchanged | `none`, and the next `session.send` resumes it |
| The rollout is held by a Codex process that is not the daemon, which is what a TUI started with configuration overrides does | `terminal` | `terminal`, because there is nothing to attach to |

The daemon reports nothing at all when a TUI exits and offers no way to ask who is attached, so the
device takes the TUI process itself as the signal: a Codex thread stays `shared` while a live bare
`codex` process is running in that thread's `cwd`, which the device checks on its ten second scan,
and a message typed in a terminal since the last scan always counts as a terminal being there. Once
the terminal is gone the thread keeps its `origin` and becomes `remote` while a turn the device
started is still running, `none` otherwise; `none` is resumable, so the next `session.send` drives
the same loaded thread. A later message typed in a terminal makes it `shared` again, and a process
scan that cannot be completed changes nothing. Apps need no Codex-specific logic here; they read
`control` and the agent's attachment fields (4.2) and nothing else.

Not every thread the daemon's history lists is a session. Codex keeps one history for the whole
machine, and the desktop app's chats and scheduled automations, an IDE extension's threads and the
subagents a thread spawned all land in it beside the terminal's. The device publishes a Codex
thread only when it is the device's to show (amendment A18): its `source` is a plain string — a
subagent's `source` is an object naming its parent, and a subagent is never a session of its own —
and either the thread's `originator` is the name the device itself connects to Codex under, or its
`source` is `cli` or `exec`, which is a terminal on that machine running its own Codex. Any other
thread belongs to the application, or the parent thread, that started
it: the device never publishes it, never mirrors its rollout, and sends `session.removed` for any
it published before this rule, repeating the frame on the next link as A16 does. The apps need
nothing for this; a session they never receive is a row they never draw.

`fixtures/app/session.updated.json`

```json
{
  "type": "session.updated",
  "session": {
    "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
    "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712",
    "agent": "claude",
    "title": "Fix flaky auth refresh test",
    "cwd": "/Users/me/dev/gateway",
    "git": {
      "branch": "main",
      "dirty": false,
      "ahead": 0,
      "behind": 0,
      "worktree": false
    },
    "state": "idle",
    "state_detail": null,
    "origin": "remote",
    "control": "remote",
    "model": "claude-sonnet-4-5",
    "permission_mode": "default",
    "effort": "medium",
    "created_at": 1788942600000,
    "updated_at": 1788944461100,
    "last_seq": 41,
    "archived": false,
    "turn": null,
    "todos": {
      "total": 4,
      "done": 4
    },
    "usage": {
      "input_tokens": 48120,
      "output_tokens": 6210,
      "total_tokens": 54330,
      "context_used": 61000,
      "context_window": 200000,
      "cost_usd": 0.42
    },
    "queued": 0
  }
}
```

### 4.5 Session state

| State | Meaning |
| --- | --- |
| `starting` | The device is creating or resuming the session |
| `idle` | No turn in progress; the composer is live |
| `running` | A turn is in progress |
| `needs_approval` | Sub-state of running: blocked on an `approval` |
| `needs_input` | Sub-state of running: blocked on a `question` |
| `error` | The session failed; `state_detail` says how |
| `stopped` | The agent process ended |
| `readonly` | A mirrored terminal session that is not currently working: `control == "terminal"` **and** no turn in progress. While the terminal-driven turn runs, the device reports `running`, `needs_approval` or `needs_input` as usual (amendment A7). |

A `shared` session reports `idle` when no turn is in progress; `readonly` stays reserved for
`control == "terminal"`. While a turn runs, whether the terminal started it or the device injected
it, the device reports `running`, `needs_approval` or `needs_input` as usual.

### 4.6 Git

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `branch` | string | yes | |
| `dirty` | boolean | yes | Uncommitted changes present |
| `ahead` | integer | yes | Commits ahead of upstream |
| `behind` | integer | yes | Commits behind upstream |
| `worktree` | boolean | yes | True when the session runs in an isolated worktree |

### 4.7 Usage

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `input_tokens` | integer | yes | |
| `output_tokens` | integer | yes | |
| `total_tokens` | integer | yes | |
| `context_used` | integer \| null | no | Tokens currently occupying the context window |
| `context_window` | integer \| null | no | Size of the model's context window |
| `cost_usd` | number \| null | no | Omitted or null when the agent reports no cost |

Only the token totals are guaranteed (amendment A2). An agent that reports no context or cost figures
omits those fields; a UI must render a session whose `usage` carries three fields.
`fixtures/events/turn_completed.no_cost.json` and `fixtures/timelines/codex.json` are the worked
examples.

### 4.8 Attachment

Two shapes carry the same idea in opposite directions.

| Direction | Object | Fields |
| --- | --- | --- |
| App to device (`session.send`) | `AttachmentUpload` | `name`, `mime`, `data_base64` |
| Device to app (`user_message`) | `Attachment` | `name`, `mime`, `size` |

Bytes travel up only. A `user_message` event reports metadata, never the payload.

### 4.9 LabeledId

`{"id": "high", "label": "High"}`. Used for models, permission modes and effort levels. Ids are
opaque to the UI, which renders the label and sends back the id unchanged. Approval options extend
this with `style`; question options extend it with an optional `description`.

---

## 5. Session events

Schema: `schema/events.json`. Events travel device → gateway → app inside a `session.event` frame and
are also the record format returned by `session.history`.

```json
{"type": "session.event", "session_id": "…", "event": {"seq": 17, "ts": 1788944409800, "kind": "…"}}
```

Every event carries `seq`, `ts` and `kind`. `ts` is the device clock; **`seq` ordering wins over `ts`
for display order**.

### 5.1 The block model

Six kinds own a block and carry `block_id`, plus the optional `first_seq`: `user_message`,
`assistant_text`, `thinking`, `tool_call`, `approval`, `question`. They may also carry
`parent_block_id`, set when the event was produced inside a sub-agent; the parent is a `tool_call`
with `tool_kind: "subagent"`.

Three rules govern how a timeline is built:

1. **Replacement.** A later event with the same `block_id` fully replaces the earlier one.
2. **Streaming.** The exception is a `delta`: `assistant_text` and `thinking` events that carry
   `delta` append to the block's text. The final event of a stream has `done: true` and the complete
   `text`, and carries no `delta`. Devices flush deltas at most every 80 ms per block.
3. **Ordering.** Display order is `first_seq ?? seq`, where `first_seq` is the `seq` at which the
   block first appeared (amendment A8). Devices set it on replacement events and on
   `session.history` and `session.block` results; on a block's first event it equals `seq` and is
   usually omitted. Without it, a tool call that starts early and finishes late would jump to the
   bottom of the timeline every time it was replaced.

The remaining kinds are not blocks. `todos` and `queue` are snapshots that replace the previous
snapshot of the same kind; `status`, `meta`, `turn_started`, `turn_completed`, `notice` and `error`
are timeline entries or state updates.

### 5.2 `user_message`

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `block_id` | uuid | yes | |
| `first_seq` | integer | no | Where the block started; order by `first_seq ?? seq` |
| `text` | string | yes | |
| `attachments` | `Attachment[]` | no | Metadata only |
| `source` | `remote` \| `terminal` \| `queue` | yes | Where the message came from |
| `delivery` | `delivered` \| `absorbed` | no | Set only on `shared` sessions; see below |

The `user_message` a device emits for an app's `session.send` carries the request's `id` as its
`block_id` (amendment A12). An app therefore renders the message the moment it is sent, under that
id and with `source: "remote"`, and the device's event later replaces it under rule 5.1.1; nothing
in the timeline moves, and a retry with the same `id` (2.4) lands on the same block. Messages the
device originates itself (typed in a terminal, replayed from a queue whose item came without an id)
keep device-minted ids.

A steered message is the exception to "at once" on the device side (amendment A14). When a
`session.send` is answered with `accepted: "steered"`, the agent does not read the message where it
was sent but at its next step, after whatever it was already saying; a terminal on the same session
shows the prompt there. The device therefore emits the `user_message` only when the agent reports
having taken the message (for Codex, the daemon's `userMessage` item for it), so `first_seq` places
it after the output that preceded it. The app keeps its optimistic row at the bottom until then —
that is where the message will land. If the turn ends without the agent ever taking the message,
the device emits the block at the turn's end, with a `notice` of level `warning` when the turn was
interrupted, so the message is shown exactly once either way.

`delivery` reports what happened to a message injected into an attached CLI and is absent for an
ordinary prompt. `delivered` means it was injected into the CLI. `absorbed` means the CLI treated
it as mid-turn data rather than a prompt, so the device will re-inject it at the next idle point.
A message the device accepted while the terminal's turn was running is not a block at all until it
is injected (amendment A19): it is a `queue` entry (5.12), the app keeps it in its queue as it does
for any queued message, and the `user_message` appears only when the CLI takes it, so `first_seq`
places it after the output of the turn it waited for — the order the terminal shows. The block
keeps its `block_id` throughout, and each re-injection is a replacement event carrying
`delivery: "delivered"`. `source` stays `remote` for messages apps send into a shared session and
`terminal` for messages typed in the CLI.

`fixtures/events/user_message.json`

```json
{
  "seq": 2,
  "ts": 1788944401000,
  "kind": "user_message",
  "block_id": "78c122e5-9703-4501-bff4-e6e6d8a27efe",
  "text": "Have a look at the screenshot and tell me what broke the layout.",
  "attachments": [
    {
      "name": "settings-drawer.png",
      "mime": "image/png",
      "size": 284913
    }
  ],
  "source": "remote"
}
```

### 5.3 `assistant_text`

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `block_id` | uuid | yes | |
| `first_seq` | integer | no | Where the block started; order by `first_seq ?? seq` |
| `delta` | string | when `done` is false | Appended to the block |
| `text` | string | when `done` is true | The complete text |
| `done` | boolean | yes | |

History contains final events only.

`fixtures/events/assistant_text.delta.json`

```json
{
  "seq": 8,
  "ts": 1788944403300,
  "kind": "assistant_text",
  "block_id": "5ccffa9e-4949-4c98-a8a5-066f320bd531",
  "delta": "Let me read the test and the token helper.",
  "done": false
}
```

`fixtures/events/assistant_text.done.json`

```json
{
  "seq": 9,
  "ts": 1788944403500,
  "kind": "assistant_text",
  "first_seq": 8,
  "block_id": "5ccffa9e-4949-4c98-a8a5-066f320bd531",
  "text": "Let me read the test and the token helper.",
  "done": true
}
```

### 5.4 `thinking`

Same fields and streaming rule as `assistant_text`, plus `duration_ms` (integer, optional) on the
final event. The content may be a summary rather than the full reasoning.

`fixtures/events/thinking.done.json`

```json
{
  "seq": 7,
  "ts": 1788944402900,
  "kind": "thinking",
  "first_seq": 5,
  "block_id": "e3c776b7-73e5-449f-a25e-25cec51cf406",
  "text": "The failure is timing dependent, so the refresh window is probably compared against a wall clock that can drift.",
  "done": true,
  "duration_ms": 4200
}
```

### 5.5 `tool_call`

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `block_id` | uuid | yes | |
| `first_seq` | integer | no | Where the block started; order by `first_seq ?? seq` |
| `tool` | string | yes | The agent's own tool name, for example `Bash` |
| `tool_kind` | `shell` \| `read` \| `edit` \| `write` \| `search` \| `web` \| `mcp` \| `subagent` \| `todo` \| `other` | yes | Drives the icon and the collapsed row |
| `title` | string | yes | One-line human summary produced by the device: command line, file path, query |
| `status` | `running` \| `succeeded` \| `failed` \| `cancelled` | yes | |
| `input` | object | no | At most 8 KiB of JSON |
| `input_truncated` | boolean | no | |
| `output` | string | no | At most 16 KiB |
| `output_truncated` | boolean | no | |
| `summary` | string | no | Short result badge, for example `2 failed` |
| `diff` | `{path, additions, deletions, patch?, patch_truncated?}` | no | `patch` is at most 32 KiB |
| `started_at` | timestamp | yes | |
| `ended_at` | timestamp | no | Present once the call finishes |
| `duration_ms` | integer | no | |

A device emits the same `block_id` twice: once with `status: "running"` (optionally with partial
`output` for a live output box) and once with the terminal status. Use `session.block` to fetch the
untruncated version.

`fixtures/events/tool_call.shell.json`

```json
{
  "seq": 26,
  "ts": 1788944430700,
  "kind": "tool_call",
  "first_seq": 25,
  "block_id": "d026b2fb-c6fd-4a29-bbdc-34b98ee362bb",
  "tool": "Bash",
  "tool_kind": "shell",
  "title": "pytest -k refresh --count 20",
  "status": "failed",
  "input": {
    "command": "pytest -k refresh --count 20",
    "timeout": 120000
  },
  "output": "============================= test session starts ==============================\nplatform darwin -- Python 3.12.12, pytest-8.3.4, pluggy-1.5.0\nrootdir: /Users/me/dev/gateway\nplugins: anyio-4.7.0, asyncio-0.25.0, repeat-0.9.3\ncollected 20 items\n\ntests/test_auth.py ..F.................F                                  [100%]\n\n=================================== FAILURES ===================================\n__________________ test_refresh_window_is_stable[iteration-3] __________________\n\n    def test_refresh_window_is_stable() -> None:\n        token = issue(ttl=30)\n        freeze(token.expires_at - 1)\n>       assert should_refresh(token) is True\nE       assert False is True\n\ntests/test_auth.py:118: AssertionError\n",
  "output_truncated": true,
  "summary": "2 failed",
  "started_at": 1788944424300,
  "ended_at": 1788944430700,
  "duration_ms": 6400
}
```

`fixtures/events/tool_call.edit.json`

```json
{
  "seq": 23,
  "ts": 1788944424000,
  "kind": "tool_call",
  "block_id": "9d231cee-9a1c-48da-8f14-77c0746ced34",
  "tool": "Edit",
  "tool_kind": "edit",
  "title": "rc_gateway/auth/tokens.py",
  "status": "succeeded",
  "input": {
    "file_path": "/Users/me/dev/gateway/rc_gateway/auth/tokens.py"
  },
  "diff": {
    "path": "rc_gateway/auth/tokens.py",
    "additions": 4,
    "deletions": 2,
    "patch": "--- a/rc_gateway/auth/tokens.py\n+++ b/rc_gateway/auth/tokens.py\n@@ -41,9 +41,11 @@ def should_refresh(token: Token) -> bool:\n-    remaining = token.expires_at - time.time()\n-    return remaining < REFRESH_WINDOW\n+    remaining = token.expires_at - time.monotonic_offset()\n+    skew = min(MAX_CLOCK_SKEW, REFRESH_WINDOW / 2)\n+    return remaining < REFRESH_WINDOW + skew\n"
  },
  "summary": "+4 -2",
  "started_at": 1788944424000,
  "ended_at": 1788944424100,
  "duration_ms": 90
}
```

`fixtures/events/tool_call.subagent.json`

```json
{
  "seq": 18,
  "ts": 1788944409200,
  "kind": "tool_call",
  "first_seq": 15,
  "block_id": "5ca335d3-3c57-4026-9358-49567115efde",
  "tool": "Task",
  "tool_kind": "subagent",
  "title": "Audit clock handling in the auth package",
  "status": "succeeded",
  "input": {
    "description": "Audit clock handling",
    "subagent_type": "Explore"
  },
  "summary": "time.time() is compared with a monotonic deadline; up to 2 s of skew",
  "started_at": 1788944405300,
  "ended_at": 1788944409200,
  "duration_ms": 4300
}
```

### 5.6 `todos`

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `items` | `{id, text, status}[]` | yes | `status` is `pending`, `in_progress` or `completed` |

A snapshot that replaces the previous list. It carries no `block_id`.

`fixtures/events/todos.json`

```json
{
  "seq": 12,
  "ts": 1788944404400,
  "kind": "todos",
  "items": [
    {
      "id": "t1",
      "text": "Reproduce the flake",
      "status": "completed"
    },
    {
      "id": "t2",
      "text": "Find the timing dependency",
      "status": "completed"
    },
    {
      "id": "t3",
      "text": "Fix the refresh window",
      "status": "completed"
    },
    {
      "id": "t4",
      "text": "Run the test 20 times",
      "status": "completed"
    }
  ]
}
```

### 5.7 `approval`

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `block_id` | uuid | yes | |
| `first_seq` | integer | no | Where the block started; order by `first_seq ?? seq` |
| `request_id` | uuid | yes | Quoted back in `session.approve` |
| `tool` | string | yes | |
| `tool_kind` | see 5.5 | yes | |
| `title` | string | yes | |
| `input` | object | no | |
| `diff` | see 5.5 | no | Present for edit and write approvals |
| `options` | `{id, label, style}[]` | yes | `style` is `primary`, `secondary` or `danger` |
| `status` | `pending` \| `resolved` \| `expired` | yes | |
| `decision` | `{option_id, by}` | no | `by` is `remote`, `terminal` or `policy` |

Option ids are chosen by the device adapter and are **opaque to the UI**, which renders exactly the
options it is given and never assumes a specific id. Claude typically offers `allow`,
`allow_session`, `deny`; Codex offers `accept`, `acceptForSession`, `decline`, `cancel`. Every
`approval` includes at least one option with `style: "primary"` (accept) and one with
`style: "danger"` (reject), so a UI can place them consistently.

On a `shared` session the device emits an `approval` block for every permission request the
attachment relays. Through a Claude channel `options` are exactly
`[{id: "allow", label: "Allow", style: "primary"}, {id: "deny", label: "Deny", style: "danger"}]`,
because session-scoped grants are not available through that relay. `input` carries
`{tool_name, description, input_preview}` as the relay supplies it, and `diff` is absent since the
relay provides none. The dialog in the terminal stays open alongside the relayed request and
whichever side answers first wins; when the terminal answers first the device resolves the block
with `decision.by: "terminal"`. A pending approval becomes `expired` when the CLI exits or the
attachment drops.

Through the Codex shared daemon the options mirror what the daemon offers for that particular
prompt, so a `shared` Codex approval carries up to four of them:

| Option | Label | `style` |
| --- | --- | --- |
| `allow` | Allow | `primary` |
| `allow_session` | Allow for this session | `secondary` |
| `allow_always` | Always allow commands like this | `secondary` |
| `deny` | Deny | `danger` |

The device sends only the options the daemon lists for that request, and every set still contains one
`primary` and one `danger` option. `input` carries `{command, cwd, command_actions}` for a command
approval and the summary of the proposed change for a file approval; `diff` is present when the
daemon supplies one. `request_id` is minted by the device, as it is for every relayed approval.

The terminal and the device see the same Codex request and either can answer it, but the daemon does
not say who answered or what they chose. When the device learns that a request it did not answer has
been resolved, it resolves the block with `decision: {option_id: "elsewhere", by: "terminal"}`.
`elsewhere` is an ordinary option id that never appears in `options`; an app renders it as "answered
in the terminal" and never sends it back. `fixtures/events/approval.codex-shared-pending.json` and
`fixtures/events/approval.codex-elsewhere.json` are the worked pair.

`fixtures/events/approval.pending.json`

```json
{
  "seq": 19,
  "ts": 1788944409800,
  "kind": "approval",
  "block_id": "3db744e1-a6c3-4f21-bc51-a8ca32c6cd9a",
  "request_id": "0f99737c-e8c1-4242-ac23-42f50fe1654e",
  "tool": "Edit",
  "tool_kind": "edit",
  "title": "rc_gateway/auth/tokens.py",
  "input": {
    "file_path": "/Users/me/dev/gateway/rc_gateway/auth/tokens.py"
  },
  "diff": {
    "path": "rc_gateway/auth/tokens.py",
    "additions": 4,
    "deletions": 2,
    "patch": "--- a/rc_gateway/auth/tokens.py\n+++ b/rc_gateway/auth/tokens.py\n@@ -41,9 +41,11 @@ def should_refresh(token: Token) -> bool:\n-    remaining = token.expires_at - time.time()\n-    return remaining < REFRESH_WINDOW\n+    remaining = token.expires_at - time.monotonic_offset()\n+    skew = min(MAX_CLOCK_SKEW, REFRESH_WINDOW / 2)\n+    return remaining < REFRESH_WINDOW + skew\n"
  },
  "options": [
    {
      "id": "allow",
      "label": "Allow",
      "style": "primary"
    },
    {
      "id": "allow_session",
      "label": "Allow for this session",
      "style": "secondary"
    },
    {
      "id": "deny",
      "label": "Deny",
      "style": "danger"
    }
  ],
  "status": "pending"
}
```

`fixtures/events/approval.resolved.json`

```json
{
  "seq": 21,
  "ts": 1788944423800,
  "kind": "approval",
  "first_seq": 19,
  "block_id": "3db744e1-a6c3-4f21-bc51-a8ca32c6cd9a",
  "request_id": "0f99737c-e8c1-4242-ac23-42f50fe1654e",
  "tool": "Edit",
  "tool_kind": "edit",
  "title": "rc_gateway/auth/tokens.py",
  "input": {
    "file_path": "/Users/me/dev/gateway/rc_gateway/auth/tokens.py"
  },
  "diff": {
    "path": "rc_gateway/auth/tokens.py",
    "additions": 4,
    "deletions": 2,
    "patch": "--- a/rc_gateway/auth/tokens.py\n+++ b/rc_gateway/auth/tokens.py\n@@ -41,9 +41,11 @@ def should_refresh(token: Token) -> bool:\n-    remaining = token.expires_at - time.time()\n-    return remaining < REFRESH_WINDOW\n+    remaining = token.expires_at - time.monotonic_offset()\n+    skew = min(MAX_CLOCK_SKEW, REFRESH_WINDOW / 2)\n+    return remaining < REFRESH_WINDOW + skew\n"
  },
  "options": [
    {
      "id": "allow",
      "label": "Allow",
      "style": "primary"
    },
    {
      "id": "allow_session",
      "label": "Allow for this session",
      "style": "secondary"
    },
    {
      "id": "deny",
      "label": "Deny",
      "style": "danger"
    }
  ],
  "status": "resolved",
  "decision": {
    "option_id": "allow",
    "by": "remote"
  }
}
```

### 5.8 `question`

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `block_id` | uuid | yes | |
| `first_seq` | integer | no | Where the block started; order by `first_seq ?? seq` |
| `request_id` | uuid | yes | Quoted back in `session.answer` |
| `questions` | `QuestionItem[]` | yes | |
| `status` | `pending` \| `resolved` \| `expired` | yes | |
| `answers` | map | no | Question id to a list of option ids, or to free text |
| `by` | `remote` \| `terminal` | no | On a resolved question: who answered it (amendment A20) |

`QuestionItem` is `{id, prompt, options: [{id, label, description?}], multi, allow_text, secret?}`.
`multi` allows several options; `allow_text` allows a free-text answer; `secret` asks the UI to mask
the input.

On an attached Claude Code session (`control: "shared"`) the CLI's own question dialog and this block
exist at the same time (amendment A20). The device raises the block from the `PermissionRequest`
hook Claude Code runs beside the dialog, reports `needs_input`, and answers the hook with the
first reply that arrives: a `session.answer` from an app, or the dialog in the terminal. The other
side then sees the block `resolved` with `by` saying who answered, and the CLI's dialog is
withdrawn when the app was first. A question the device could not raise this way — an older shim
without the hook — stays what it was before: a running `tool_call` the terminal answers alone.

`fixtures/events/question.resolved.terminal.json` is the block after the person at the terminal
answered.

`fixtures/events/question.pending.json`

```json
{
  "seq": 28,
  "ts": 1788944431500,
  "kind": "question",
  "block_id": "c918d535-0a55-4521-8254-66c9bec5be9d",
  "request_id": "dbe1ce62-ee02-41b3-a229-50b5b4689610",
  "questions": [
    {
      "id": "q1",
      "prompt": "Two runs still fail on a machine whose clock jumps. How should the refresh window treat skew?",
      "options": [
        {
          "id": "clamp",
          "label": "Clamp skew to half the window",
          "description": "Safest; never refreshes early by more than 2.5 s."
        },
        {
          "id": "monotonic",
          "label": "Switch to a monotonic deadline",
          "description": "Bigger change, touches the issue path."
        },
        {
          "id": "widen",
          "label": "Widen the window to 10 s"
        }
      ],
      "multi": false,
      "allow_text": true
    }
  ],
  "status": "pending"
}
```

### 5.9 `turn_started` and `turn_completed`

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `turn_id` | uuid | yes | Matches `Session.turn.turn_id` |
| `trigger` | `remote` \| `terminal` \| `queue` | yes | `turn_started` only |
| `stop_reason` | `completed` \| `interrupted` \| `error` | yes | `turn_completed` only |
| `duration_ms` | integer | yes | `turn_completed` only |
| `usage` | `Usage` | no | `turn_completed` only |

`fixtures/events/turn_started.json`

```json
{
  "seq": 3,
  "ts": 1788944401100,
  "kind": "turn_started",
  "turn_id": "ad5ec709-3ce6-44c6-80a5-af2cb46308bc",
  "trigger": "remote"
}
```

`fixtures/events/turn_completed.json`

```json
{
  "seq": 40,
  "ts": 1788944461100,
  "kind": "turn_completed",
  "turn_id": "ad5ec709-3ce6-44c6-80a5-af2cb46308bc",
  "stop_reason": "completed",
  "duration_ms": 71400,
  "usage": {
    "input_tokens": 48120,
    "output_tokens": 6210,
    "total_tokens": 54330,
    "context_used": 61000,
    "context_window": 200000,
    "cost_usd": 0.42
  }
}
```

A Codex turn reports no cost, so `usage` carries the token totals only:

`fixtures/events/turn_completed.no_cost.json`

```json
{
  "seq": 36,
  "ts": 1788945049900,
  "kind": "turn_completed",
  "turn_id": "e411cdfb-4811-467a-9057-20393b6abb21",
  "stop_reason": "completed",
  "duration_ms": 19600,
  "usage": {
    "input_tokens": 26800,
    "output_tokens": 3640,
    "total_tokens": 30440,
    "context_used": 36900,
    "context_window": 272000
  }
}
```

### 5.10 `status`

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `state` | see 4.5 | yes | |
| `detail` | string | no | One line of context |

Emitted on every state change. Along with `turn_started` and `turn_completed` these are the only
events that change a session's state.

`fixtures/events/status.json`

```json
{
  "seq": 20,
  "ts": 1788944409800,
  "kind": "status",
  "state": "needs_approval",
  "detail": "Edit rc_gateway/auth/tokens.py"
}
```

### 5.11 `meta`

A partial update of `Session` fields: `title`, `model`, `permission_mode`, `effort`, `speed`, `cwd`,
`git`, `control`, `agent_version`. Every field is optional; apply only what is present.

`fixtures/events/meta.json`

```json
{
  "seq": 88,
  "ts": 1788945300000,
  "kind": "meta",
  "title": "Fix flaky auth refresh test",
  "model": "claude-opus-4-6",
  "permission_mode": "acceptEdits",
  "effort": "high",
  "cwd": "/Users/me/dev/gateway",
  "git": {
    "branch": "fix/refresh-window",
    "dirty": true,
    "ahead": 1,
    "behind": 0,
    "worktree": true
  },
  "control": "remote",
  "agent_version": "2.1.266"
}
```

### 5.12 `queue`

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `pending` | `{id, text, ts}[]` | yes | `id` is the id of the original `session.send` request |

A snapshot of queued remote messages. Remove one with `session.queue_remove`.

`fixtures/events/queue.json`

```json
{
  "seq": 22,
  "ts": 1788945028800,
  "kind": "queue",
  "pending": [
    {
      "id": "573d2674-debc-4db0-973c-16e8a7c2e9b1",
      "text": "Then run the full test suite.",
      "ts": 1788945028800
    }
  ]
}
```

### 5.13 `notice`

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `level` | `info` \| `warn` \| `error` | yes | |
| `text` | string | yes | |

System lines: context compaction, takeover, reconnect.

`fixtures/events/notice.json`

```json
{
  "seq": 27,
  "ts": 1788944431000,
  "kind": "notice",
  "level": "warn",
  "text": "Context was compacted; earlier turns are summarised."
}
```

### 5.14 `error`

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `message` | string | yes | |
| `code` | error code | no | |
| `block_id` | uuid | no | Set when the failure belongs to a block |

`fixtures/events/error.json`

```json
{
  "seq": 91,
  "ts": 1788945330000,
  "kind": "error",
  "message": "The agent process exited before the turn completed.",
  "code": "internal",
  "block_id": "cbe34579-6f2c-4db5-9d2d-b95a7a2f4468"
}
```

---

## 6. App ↔ gateway frames

Schema: `schema/app_frames.json`. Endpoint `WS /ws/app`.

### 6.1 Gateway → app

On connect the gateway sends `hello`, then pushes updates for as long as the socket lives.

| Type | Payload | When |
| --- | --- | --- |
| `hello` | `protocol`, `gateway_version`, `user`, `devices`, `sessions`, `stt`, `server_time` | First frame |
| `device.updated` | `device` | A device connects, disconnects, is renamed or re-detects agents |
| `device.removed` | `device_id` | A device is deleted |
| `session.updated` | `session` | Any change to a session summary |
| `session.removed` | `session_id`, `device_id` | A session is deleted |
| `session.event` | `session_id`, `device_id`, `event` | Only for sessions this connection subscribed to |
| `pairing.progress` | `code`, `step`, `device?` | While a pairing code is outstanding. `step` is `waiting`, `enrolled`, `online` or `agents`. |
| `ping` | – | Every 25 s; the app replies `pong` |
| `reply` | `id`, `ok`, `result` or `error` | Answer to a request |

The gateway stamps `device_id` on every `session.event` and `session.removed` frame it sends to
an app, because it knows the device from the socket (amendment A5). Devices never send it.
`session_id` remains globally unique and is the primary key an app indexes by; `device_id` is
informational and for routing.

`fixtures/app/hello.json` (abridged)

```json
{
  "type": "hello",
  "protocol": 1,
  "gateway_version": "0.1.0",
  "user": {
    "username": "admin"
  },
  "devices": [
    {
      "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712",
      "name": "mac-studio-office",
      "platform": "macos",
      "hostname": "mac-studio.local",
      "arch": "arm64",
      "client_version": "0.1.0",
      "online": true,
      "last_seen": 1788944400000,
      "created_at": 1788426000000,
      "latency_ms": 18,
      "agents": [
        {
          "agent": "claude",
          "available": true,
          "version": "2.1.266",
          "path": "/Users/me/.local/bin/claude",
          "models": [
            {
              "id": "claude-opus-4-6",
              "label": "Opus 4.6"
            }
          ],
          "default_model": "claude-sonnet-4-5",
          "permission_modes": [
            {
              "id": "default",
              "label": "Ask before edits"
            }
          ],
          "default_permission_mode": "default",
          "efforts": [
            {
              "id": "low",
              "label": "Low"
            }
          ],
          "default_effort": "medium",
          "capabilities": [
            "worktree",
            "takeover",
            "interrupt",
            "queue",
            "attachments",
            "effort",
            "history"
          ]
        }
      ]
    }
  ],
  "sessions": [
    {
      "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
      "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712",
      "agent": "claude",
      "title": "Fix flaky auth refresh test",
      "cwd": "/Users/me/dev/gateway",
      "git": {
        "branch": "main",
        "dirty": false,
        "ahead": 0,
        "behind": 0,
        "worktree": false
      },
      "state": "idle",
      "state_detail": null,
      "origin": "remote",
      "control": "remote",
      "model": "claude-sonnet-4-5",
      "permission_mode": "default",
      "effort": "medium",
      "created_at": 1788942600000,
      "updated_at": 1788944461100,
      "last_seq": 41,
      "archived": false,
      "turn": null,
      "todos": {
        "total": 4,
        "done": 4
      },
      "usage": {
        "input_tokens": 48120,
        "output_tokens": 6210,
        "total_tokens": 54330,
        "context_used": 61000,
        "context_window": 200000,
        "cost_usd": 0.42
      },
      "queued": 0
    }
  ],
  "stt": {
    "enabled": true,
    "languages": [
      "auto",
      "zh",
      "en"
    ]
  },
  "server_time": 1788944400000
}
```

The device, session and option arrays are shortened here; the fixture holds the full frame.

`fixtures/app/device.removed.json`

```json
{
  "type": "device.removed",
  "device_id": "1fcda02a-8af6-4019-bef9-2a9dfacae4a3"
}
```

`fixtures/app/session.removed.json`

```json
{
  "type": "session.removed",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
  "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712"
}
```

`fixtures/app/session.event.json`

```json
{
  "type": "session.event",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
  "event": {
    "seq": 11,
    "ts": 1788944404200,
    "kind": "tool_call",
    "first_seq": 10,
    "block_id": "3216f1bc-6f4f-4cc4-8666-3370bb8e3b97",
    "tool": "Read",
    "tool_kind": "read",
    "title": "tests/test_auth.py",
    "status": "succeeded",
    "input": {
      "file_path": "/Users/me/dev/gateway/tests/test_auth.py"
    },
    "output": "  1\timport time\n  2\t\n  3\tfrom rc_gateway.auth.tokens import issue, should_refresh\n",
    "output_truncated": true,
    "summary": "84 lines",
    "started_at": 1788944403700,
    "ended_at": 1788944404200,
    "duration_ms": 480
  },
  "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712"
}
```

`fixtures/app/pairing.progress.json` (abridged)

```json
{
  "type": "pairing.progress",
  "code": "RC-7K42-QX9M",
  "step": "agents"
}
```

`fixtures/app/ping.json`

```json
{
  "type": "ping"
}
```

`fixtures/app/reply.error.json`

```json
{
  "type": "reply",
  "id": "d7c20931-eada-4d1f-9df5-e8426a3d8dcd",
  "ok": false,
  "error": {
    "code": "conflict",
    "message": "controlled by terminal; take over first"
  }
}
```

### 6.2 App → gateway, handled by the gateway

| Type | Fields | Reply |
| --- | --- | --- |
| `session.subscribe` | `id`, `session_id`, `since_seq?` | `{session, events, resync, queue?}` |
| `session.unsubscribe` | `session_id` | none |
| `pong` | – | none |

`events` are the buffered events with `seq > since_seq`. Omitting `since_seq` returns an empty array,
which tells the app to call `session.history`. `resync: true` means the replay buffer no longer
covers `since_seq`, so the app must reload from history. A connection may subscribe to several
sessions; subscribing again replaces the cursor.

`queue` is the latest `queue` event payload the gateway saw for this session (amendment A6). It
is absent when the gateway has seen none, which is the normal case for a session nobody has
queued to. An app applies it exactly as it would apply a live `queue` event.

`fixtures/app/session.subscribe.json`

```json
{
  "type": "session.subscribe",
  "id": "21a35285-bdfa-41c4-baed-7d6f847f6a22",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
  "since_seq": 30
}
```

`fixtures/app/session.unsubscribe.json`

```json
{
  "type": "session.unsubscribe",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0"
}
```

`fixtures/app/pong.json`

```json
{
  "type": "pong"
}
```

A reply that carries a queue snapshot, taken from the Codex session while one message was
waiting:

`fixtures/replay/subscribe.codex.reply.json`

```json
{
  "type": "reply",
  "id": "393ab352-34aa-4086-a1c4-23f41dd7b103",
  "ok": true,
  "result": {
    "session": {
      "session_id": "9cabb33b-a6e2-4faf-a905-865f89dcfa8e",
      "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712",
      "agent": "codex",
      "title": "Typecheck the web app",
      "cwd": "/Users/me/dev/web",
      "git": {
        "branch": "feat/settings-drawer",
        "dirty": true,
        "ahead": 2,
        "behind": 0,
        "worktree": false
      },
      "state": "running",
      "state_detail": null,
      "origin": "remote",
      "control": "remote",
      "model": "gpt-5.4-codex",
      "permission_mode": "on-request",
      "effort": "medium",
      "created_at": 1788944940000,
      "updated_at": 1788945028800,
      "last_seq": 22,
      "archived": false,
      "turn": {
        "turn_id": "192a2a3b-0e93-4d42-bf6c-9d395dda731e",
        "started_at": 1788945001100
      },
      "todos": {
        "total": 3,
        "done": 2
      },
      "usage": null,
      "queued": 1
    },
    "events": [
      {
        "seq": 14,
        "ts": 1788945012800,
        "kind": "status",
        "state": "running"
      },
      {
        "seq": 15,
        "ts": 1788945013000,
        "kind": "tool_call",
        "block_id": "5e808f56-b645-41fa-bba5-9a49992f3d57",
        "tool": "shell",
        "tool_kind": "shell",
        "title": "npm run typecheck",
        "status": "running",
        "input": {
          "command": [
            "npm",
            "run",
            "typecheck"
          ],
          "cwd": "/Users/me/dev/web"
        },
        "started_at": 1788945013000
      },
      {
        "seq": 16,
        "ts": 1788945020800,
        "kind": "tool_call",
        "first_seq": 15,
        "block_id": "5e808f56-b645-41fa-bba5-9a49992f3d57",
        "tool": "shell",
        "tool_kind": "shell",
        "title": "npm run typecheck",
        "status": "failed",
        "input": {
          "command": [
            "npm",
            "run",
            "typecheck"
          ],
          "cwd": "/Users/me/dev/web"
        },
        "output": "src/state/sessions.ts(74,11): error TS2345: Argument of type 'string | undefined' is not assignable to parameter of type 'string'.\n",
        "summary": "1 error",
        "started_at": 1788945013000,
        "ended_at": 1788945020800,
        "duration_ms": 7800
      },
      {
        "seq": 17,
        "ts": 1788945023800,
        "kind": "user_message",
        "block_id": "faf38b44-3516-44e8-bf66-ddc93f3037f1",
        "text": "While you are in there, also check the settings drawer.",
        "source": "remote"
      },
      {
        "seq": 18,
        "ts": 1788945023900,
        "kind": "notice",
        "level": "info",
        "text": "Message steered into the running turn."
      },
      {
        "seq": 19,
        "ts": 1788945024200,
        "kind": "tool_call",
        "block_id": "643de5d9-01ef-47ef-816e-432020d413a0",
        "tool": "fuzzyFileSearch",
        "tool_kind": "search",
        "title": "settings drawer",
        "status": "succeeded",
        "input": {
          "query": "settings drawer"
        },
        "output": "src/ui/SettingsDrawer.tsx\nsrc/ui/SettingsDrawer.test.tsx\n",
        "summary": "2 files",
        "started_at": 1788945024200,
        "ended_at": 1788945024400,
        "duration_ms": 180
      },
      {
        "seq": 20,
        "ts": 1788945024600,
        "kind": "tool_call",
        "block_id": "ab5cb1a9-2833-4cfa-bec9-592efca4f6c3",
        "tool": "apply_patch",
        "tool_kind": "edit",
        "title": "src/state/sessions.ts",
        "status": "succeeded",
        "input": {
          "path": "src/state/sessions.ts"
        },
        "diff": {
          "path": "src/state/sessions.ts",
          "additions": 3,
          "deletions": 1,
          "patch": "--- a/src/state/sessions.ts\n+++ b/src/state/sessions.ts\n@@ -71,7 +71,9 @@\n-  return byDevice(session.device_id)\n+  if (!session.device_id) return undefined\n+  return byDevice(session.device_id)\n"
        },
        "summary": "+3 -1",
        "started_at": 1788945024600,
        "ended_at": 1788945024800,
        "duration_ms": 120
      },
      {
        "seq": 21,
        "ts": 1788945024800,
        "kind": "todos",
        "items": [
          {
            "id": "p1",
            "text": "Run npm run typecheck",
            "status": "completed"
          },
          {
            "id": "p2",
            "text": "Fix reported type errors",
            "status": "completed"
          },
          {
            "id": "p3",
            "text": "Re-run typecheck",
            "status": "in_progress"
          }
        ]
      },
      {
        "seq": 22,
        "ts": 1788945028800,
        "kind": "queue",
        "pending": [
          {
            "id": "573d2674-debc-4db0-973c-16e8a7c2e9b1",
            "text": "Then run the full test suite.",
            "ts": 1788945028800
          }
        ]
      }
    ],
    "resync": false,
    "queue": {
      "pending": [
        {
          "id": "573d2674-debc-4db0-973c-16e8a7c2e9b1",
          "text": "Then run the full test suite.",
          "ts": 1788945028800
        }
      ]
    }
  }
}
```

### 6.3 App → gateway → device

The gateway forwards these to the owning device and returns the device's reply.

| Type | Fields | Result |
| --- | --- | --- |
| `session.create` | `device_id`, `agent`, `cwd`, `model?`, `permission_mode?`, `effort?`, `speed?`, `worktree?`, `first_message?`, `title?` | `{session}` |
| `session.send` | `session_id`, `text`, `attachments?`, `mode` | `{accepted, queued_id?}` |
| `session.stop` | `session_id` | `{}` |
| `session.approve` | `session_id`, `request_id`, `option_id`, `message?` | `{}` |
| `session.answer` | `session_id`, `request_id`, `answers` | `{}` |
| `session.set` | `session_id`, `model?`, `permission_mode?`, `effort?`, `speed?`, `title?` | `{session}` |
| `session.history` | `session_id`, `before_seq?`, `after_seq?`, `limit?` | `{events, has_more}` |
| `session.block` | `session_id`, `block_id` | `{event}` |
| `session.queue_remove` | `session_id`, `queued_id` | `{}` |
| `session.takeover` | `session_id` | `{session}` |
| `session.archive` | `session_id`, `archived` | `{session}` |
| `session.delete` | `session_id` | `{}` |
| `device.dirs` | `device_id`, `path?` | `{path, parent, entries, recent}` |
| `device.git` | `device_id`, `path` | `{is_repo, branch?, dirty?, ahead?, behind?}` |
| `device.agents` | `device_id` | `{agents}` |
| `device.update` | `device_id`, `build` | `{accepted: true, from}` — the device fetches the gateway's wheel, refuses it unless its SHA-256 is `build`, installs it, restarts its service and reconnects with the new `client_build` (A22). `conflict` while a session it drives is running or when it already runs `build`; `unsupported` when the client cannot update itself (installed from source). |

Every request carries `id`. `session.stop` is idempotent. `session.delete` removes the session from
the device registry and does **not** delete the agent's own transcripts. `device.dirs` returns
directories only, excludes hidden entries, and defaults to the home directory when `path` is
omitted. `session.takeover` needs capability `takeover` and applies to `control: "terminal"`
sessions; on a `shared` session it fails with `conflict`.

#### `session.send` modes

| Mode | Behaviour |
| --- | --- |
| `auto` | Send now if the session is idle. If it is running, steer when the agent has capability `steer`, otherwise queue. |
| `queue` | Always queue. |
| `interrupt` | Stop the current turn, then send. |

The result's `accepted` field reports what actually happened: `sent`, `queued` or `steered`.
`queued_id` is present when the message was queued and is the handle for `session.queue_remove`.

#### Rules the device enforces

- `session.send` to a session with `control: "terminal"` is refused with `conflict`
  ("controlled by terminal; take over first").
- `session.send` to a session with `control: "none"` makes the device resume the session first
  (Claude `resume`, Codex `thread/resume`) and then send.
- `session.set` with `effort` on Claude may need the SDK connection restarted before the next turn.
  The device replies immediately with the updated `Session` and applies the change lazily. The same
  applies to `permission_mode` when the agent cannot change it live.

#### Requests on a `shared` session

| Request | Behaviour |
| --- | --- |
| `session.send` | Accepted whatever `mode` says; `steered` never applies. When the session is idle the device injects at once, replies `accepted: "sent"` and emits `user_message {delivery: "delivered"}`. When a turn is running, `auto` and `queue` alike, the device holds the message locally, replies `accepted: "queued"` with a `queued_id` and emits a `queue` event listing it — no `user_message` yet (A19) — then injects it once the transcript shows the turn ended and emits the block with `delivery: "delivered"`. `session.queue_remove` works on held items. Attachments are refused with `unsupported` ("attachments cannot be delivered to a terminal session"). |
| `session.approve` | Relays the option the block offered. An `option_id` the block did not offer, `elsewhere` included, is `bad_request`. Replying to a request the terminal already answered is a no-op returning `{}`. |
| `session.answer` | Supported for a `question` block the device raised through its `PermissionRequest` hook (A20): the answer goes to the CLI as the tool's own answers and the block resolves with `by: "remote"`. Answering a question the terminal already answered is a no-op returning `{}`. A question the device did not raise has no block to answer. |
| `session.stop` | `unsupported` unless the agent lists capability `interrupt` **and** reports `shared_interrupt: true`. Message: "stop it in the terminal". |
| `session.set` | `unsupported` for `model`, `permission_mode`, `effort` and `speed` ("change it in the terminal"). `title` works. |
| `session.takeover` | `conflict` ("already attached"). |

The table above describes what an attachment can do at its narrowest, which is what a Claude channel
can do. An agent whose attachment can do more says so in its `AgentInfo` (4.2), and the rows below
replace the ones they name for a `shared` Codex session on the daemon.

| Request | Behaviour |
| --- | --- |
| `session.send` | Every `mode` behaves as it does on a `remote` session. Idle: the device starts the turn and replies `accepted: "sent"`. Running under `mode: "auto"`: the device steers the running turn and replies `accepted: "steered"`, because Codex has capability `steer`. Running under `mode: "queue"`: the device holds the message, replies `accepted: "queued"` with a `queued_id`, emits `user_message {delivery: "pending"}` and a `queue` event, and starts the turn once the running one completes. `mode: "interrupt"` interrupts and then sends. Attachments are delivered, because `shared_attachments` is true. |
| `session.stop` | Interrupts the running turn, because `shared_interrupt` is true. |
| `session.set` | `model`, `permission_mode`, `effort` and `speed` reach the daemon and change the thread for everyone attached to it, because `shared_settings` is true; `title` stays device-local as always. |
| `session.answer` | Supported. The daemon relays questions asked of the thread and accepts the answer from whichever client replies. |

`session.takeover` is still `conflict`, and every rule of 5.1 through 5.13 applies to a shared Codex
session exactly as it does to a remote one.

#### How the device attaches to the Codex daemon

Normative for the device and invisible to apps.

- The device opens one connection to the shared daemon at startup and identifies itself by name, so
  the daemon and its other clients can tell our traffic apart.
- It subscribes to a thread lazily, when an app subscribes to the session or the daemon reports the
  thread loaded, and backfills the thread's history from the daemon rather than from the rollout
  file. Threads the daemon marks ephemeral are never surfaced as sessions.
- It reconnects with backoff, and on every reconnect re-subscribes to each thread it was following
  and backfills from the last item it saw, so no event is lost across a daemon restart.
- A Codex thread has no rollout until its first turn starts, and resuming a thread without one
  fails. The device therefore cannot subscribe to a thread the terminal has only just created. It
  still reports the session `shared`, `turn/start` works on it, and the device subscribes the moment
  the first turn creates the rollout. The same gap runs the other way: a thread created from an app
  can be reopened in a terminal with `codex resume <id>` only after its first turn.
- With no daemon socket present the device falls back to running one Codex app-server per session.
  It keeps reporting `attach: "daemon"` with `attach_ready: false`, terminal Codex sessions stay
  `control: "terminal"`, and the apps' existing hint for an agent with `attach` set already says the
  right thing.

`fixtures/app/session.create.json`

```json
{
  "type": "session.create",
  "id": "327b6ada-c1fd-4888-a6ba-97507efa5579",
  "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712",
  "agent": "claude",
  "cwd": "/Users/me/dev/gateway",
  "model": "claude-sonnet-4-5",
  "permission_mode": "default",
  "effort": "medium",
  "worktree": false,
  "first_message": "Fix the flaky refresh test in tests/test_auth.py.",
  "title": "Fix flaky auth test"
}
```

`fixtures/app/reply.session.create.json`

```json
{
  "type": "reply",
  "id": "327b6ada-c1fd-4888-a6ba-97507efa5579",
  "ok": true,
  "result": {
    "session": {
      "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
      "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712",
      "agent": "claude",
      "title": "Fix flaky auth test",
      "cwd": "/Users/me/dev/gateway",
      "git": {
        "branch": "main",
        "dirty": false,
        "ahead": 0,
        "behind": 0,
        "worktree": false
      },
      "state": "starting",
      "state_detail": null,
      "origin": "remote",
      "control": "remote",
      "model": "claude-sonnet-4-5",
      "permission_mode": "default",
      "effort": "medium",
      "created_at": 1788942600000,
      "updated_at": 1788944400000,
      "last_seq": 0,
      "archived": false,
      "turn": null,
      "todos": null,
      "usage": null,
      "queued": 0
    }
  }
}
```

`fixtures/app/session.send.json`

```json
{
  "type": "session.send",
  "id": "d7c20931-eada-4d1f-9df5-e8426a3d8dcd",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
  "text": "Have a look at the screenshot and tell me what broke the layout.",
  "attachments": [
    {
      "name": "settings-drawer.png",
      "mime": "image/png",
      "data_base64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
  ],
  "mode": "auto"
}
```

`fixtures/app/reply.session.send.json`

```json
{
  "type": "reply",
  "id": "d7c20931-eada-4d1f-9df5-e8426a3d8dcd",
  "ok": true,
  "result": {
    "accepted": "queued",
    "queued_id": "573d2674-debc-4db0-973c-16e8a7c2e9b1"
  }
}
```

`fixtures/app/session.stop.json`

```json
{
  "type": "session.stop",
  "id": "5e3657e8-af32-4c9a-837c-5dc77aa0edcb",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0"
}
```

`fixtures/app/reply.empty.json`

```json
{
  "type": "reply",
  "id": "5e3657e8-af32-4c9a-837c-5dc77aa0edcb",
  "ok": true,
  "result": {}
}
```

`fixtures/app/session.approve.json`

```json
{
  "type": "session.approve",
  "id": "b68baab6-7341-4b82-8f7b-d94dd6a7958b",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
  "request_id": "0f99737c-e8c1-4242-ac23-42f50fe1654e",
  "option_id": "allow"
}
```

`fixtures/app/session.answer.json`

```json
{
  "type": "session.answer",
  "id": "c5d9573b-2521-418d-8551-d39111c7cb34",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
  "request_id": "dbe1ce62-ee02-41b3-a229-50b5b4689610",
  "answers": {
    "q1": [
      "clamp"
    ]
  }
}
```

`fixtures/app/session.set.json`

```json
{
  "type": "session.set",
  "id": "ed9c12f6-754a-4b65-b843-20f35de3827b",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
  "model": "claude-opus-4-6",
  "permission_mode": "acceptEdits",
  "effort": "high",
  "title": "Fix flaky auth refresh test"
}
```

`fixtures/app/session.history.json`

```json
{
  "type": "session.history",
  "id": "bc51b5b2-2149-4418-966c-5884477c745d",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
  "before_seq": 41,
  "limit": 200
}
```

`fixtures/app/session.block.json`

```json
{
  "type": "session.block",
  "id": "db04f345-eeba-4a1c-a904-1e5b24096685",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
  "block_id": "d026b2fb-c6fd-4a29-bbdc-34b98ee362bb"
}
```

`fixtures/app/session.queue_remove.json`

```json
{
  "type": "session.queue_remove",
  "id": "763f021c-1e47-40f9-8c1b-97231505c649",
  "session_id": "9cabb33b-a6e2-4faf-a905-865f89dcfa8e",
  "queued_id": "573d2674-debc-4db0-973c-16e8a7c2e9b1"
}
```

`fixtures/app/session.takeover.json`

```json
{
  "type": "session.takeover",
  "id": "c111366e-43d9-4a94-b842-474932d7ff9e",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0"
}
```

`session.archive` with `archived: true` records the user's choice and stops the session when the
device is driving it, so the row goes to `control: "none"` in the same publish. The device clears `archived` on its own the moment
the session comes back to life — a turn starts in it, from an app or from a terminal, or a terminal
attaches to it again — and publishes the session with `archived: false` (amendment A15). An
archived session therefore never runs, and apps need no rule of their own for it: the row moves
out of the Archive when the device's `session.updated` arrives.

`fixtures/app/session.archive.json`

```json
{
  "type": "session.archive",
  "id": "9b774ca5-6325-4bd0-8545-6e75c45b7ad9",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
  "archived": true
}
```

`fixtures/app/session.delete.json`

```json
{
  "type": "session.delete",
  "id": "2d74f3da-cd06-4d68-ba36-10c1986269e4",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0"
}
```

`fixtures/app/device.dirs.json`

```json
{
  "type": "device.dirs",
  "id": "9ed320c0-44f6-4a52-a1ac-756b4dbb0a7c",
  "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712",
  "path": "/Users/me/dev"
}
```

`fixtures/app/reply.device.dirs.json`

```json
{
  "type": "reply",
  "id": "9ed320c0-44f6-4a52-a1ac-756b4dbb0a7c",
  "ok": true,
  "result": {
    "path": "/Users/me/dev",
    "parent": "/Users/me",
    "entries": [
      {
        "name": "gateway",
        "path": "/Users/me/dev/gateway",
        "is_git": true
      },
      {
        "name": "web",
        "path": "/Users/me/dev/web",
        "is_git": true
      },
      {
        "name": "scratch",
        "path": "/Users/me/dev/scratch",
        "is_git": false
      }
    ],
    "recent": [
      {
        "path": "/Users/me/dev/gateway",
        "last_used": 1788943800000
      },
      {
        "path": "/Users/me/dev/web",
        "last_used": 1788937200000
      }
    ]
  }
}
```

`fixtures/app/device.git.json`

```json
{
  "type": "device.git",
  "id": "74d5f4eb-37e6-41b7-88ab-30cbf8b142a1",
  "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712",
  "path": "/Users/me/dev/gateway"
}
```

`fixtures/app/reply.device.git.json`

```json
{
  "type": "reply",
  "id": "74d5f4eb-37e6-41b7-88ab-30cbf8b142a1",
  "ok": true,
  "result": {
    "is_repo": true,
    "branch": "main",
    "dirty": false,
    "ahead": 0,
    "behind": 3
  }
}
```

`fixtures/app/device.agents.json`

```json
{
  "type": "device.agents",
  "id": "d0da1eee-6cd3-41e5-9d4e-166838a07374",
  "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712"
}
```

### 6.4 `session.history` and what it returns

`session.history` returns, in ascending `seq` order:

- the **latest** event for each block: `user_message`, `assistant_text` with `done: true`, `thinking`
  with `done: true`, `tool_call`, `approval`, `question`;
- plus `turn_started`, `turn_completed`, `notice` and `error`;
- plus the latest `todos` snapshot.

It never returns streaming deltas, `status`, `meta` or `queue` events: those describe current state
and are carried by the `Session` object.

The ordering of the reply itself is unchanged: ascending `seq`, one event per block. Each block event
also carries `first_seq`, so an app that pages backwards can place a block at its original position
rather than where its latest event happens to land.

Two cursors select the page, and they are mutually exclusive:

| Cursor | Direction | Returns |
| --- | --- | --- |
| `before_seq` | Backwards from the newest event | Events ending just before `before_seq` |
| `after_seq` | Forwards | Events with `seq` greater than `after_seq` (amendment A9) |
| neither | Backwards from the newest event | The most recent page |

Both forms return at most `limit` events, ascending by `seq`, with `has_more`. Apps page backwards
with `before_seq`; `after_seq` exists for the gateway backfill described in 7.1.

`fixtures/history/page.json` is a worked page built from `fixtures/timelines/claude.json`, and
`scripts/validate_fixtures.py` enforces every rule above on it. Contrast it with
`fixtures/replay/subscribe.reply.json`, which is a replay-buffer answer and therefore does contain
deltas and `status` events.

---

## 7. Device ↔ gateway frames

Schema: `schema/device_frames.json`. Endpoint `WS /ws/device`, header
`Authorization: Bearer <device_token>`.

The device sends `hello` first and the gateway answers `hello_ack`. A second connection for the same
device replaces the first; the old socket is closed with code 4001.

| Direction | Type | Payload |
| --- | --- | --- |
| device → gateway | `hello` | `protocol`, `client_version`, `client_build?`, `name`, `platform`, `hostname`, `arch`, `agents`, `sessions` |
| gateway → device | `hello_ack` | `device_id`, `server_time`, `config: {delta_flush_ms, max_event_bytes}` |
| device → gateway | `session.updated` | `session` |
| device → gateway | `session.removed` | `session_id` |
| device → gateway | `session.event` | `session_id`, `event` |
| device → gateway | `agents.updated` | `agents` |
| device → gateway | `update.failed` | `message` — the update the app asked for did not complete; the old client is still running (A22) |
| device → gateway | `pong` | – |
| device → gateway | `reply` | `id`, `from`, `ok`, `result` or `error` |
| gateway → device | forwarded request | any type from 6.3, plus `from` and `device_id` |
| gateway → device | `ping` | – every 25 s |

`fixtures/device/hello.json` (abridged)

```json
{
  "type": "hello",
  "protocol": 1,
  "client_version": "0.1.0",
  "name": "mac-studio-office",
  "platform": "macos",
  "hostname": "mac-studio.local",
  "arch": "arm64",
  "agents": [
    {
      "agent": "claude",
      "available": true,
      "version": "2.1.266",
      "path": "/Users/me/.local/bin/claude",
      "models": [
        {
          "id": "claude-opus-4-6",
          "label": "Opus 4.6"
        }
      ],
      "default_model": "claude-sonnet-4-5",
      "permission_modes": [
        {
          "id": "default",
          "label": "Ask before edits"
        }
      ],
      "default_permission_mode": "default",
      "efforts": [
        {
          "id": "low",
          "label": "Low"
        }
      ],
      "default_effort": "medium",
      "capabilities": [
        "worktree",
        "takeover",
        "interrupt",
        "queue",
        "attachments",
        "effort",
        "history"
      ]
    }
  ],
  "sessions": [
    {
      "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
      "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712",
      "agent": "claude",
      "title": "Fix flaky auth refresh test",
      "cwd": "/Users/me/dev/gateway",
      "git": {
        "branch": "main",
        "dirty": false,
        "ahead": 0,
        "behind": 0,
        "worktree": false
      },
      "state": "idle",
      "state_detail": null,
      "origin": "remote",
      "control": "remote",
      "model": "claude-sonnet-4-5",
      "permission_mode": "default",
      "effort": "medium",
      "created_at": 1788942600000,
      "updated_at": 1788944461100,
      "last_seq": 41,
      "archived": false,
      "turn": null,
      "todos": {
        "total": 4,
        "done": 4
      },
      "usage": {
        "input_tokens": 48120,
        "output_tokens": 6210,
        "total_tokens": 54330,
        "context_used": 61000,
        "context_window": 200000,
        "cost_usd": 0.42
      },
      "queued": 0
    }
  ]
}
```

The agent, session and option arrays are shortened here; the fixture holds the full frame.

`fixtures/device/hello_ack.json`

```json
{
  "type": "hello_ack",
  "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712",
  "server_time": 1788944400000,
  "config": {
    "delta_flush_ms": 80,
    "max_event_bytes": 65536
  }
}
```

`fixtures/device/session.removed.json`

```json
{
  "type": "session.removed",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0"
}
```

`fixtures/device/agents.updated.json` (abridged)

```json
{
  "type": "agents.updated",
  "agents": [
    {
      "agent": "claude",
      "available": true,
      "version": "2.1.266",
      "path": "/Users/me/.local/bin/claude",
      "models": [
        {
          "id": "claude-opus-4-6",
          "label": "Opus 4.6"
        }
      ],
      "default_model": "claude-sonnet-4-5",
      "permission_modes": [
        {
          "id": "default",
          "label": "Ask before edits"
        }
      ],
      "default_permission_mode": "default",
      "efforts": [
        {
          "id": "low",
          "label": "Low"
        }
      ],
      "default_effort": "medium",
      "capabilities": [
        "worktree",
        "takeover",
        "interrupt",
        "queue",
        "attachments",
        "effort",
        "history"
      ]
    }
  ]
}
```

`fixtures/device/pong.json`

```json
{
  "type": "pong"
}
```

`fixtures/device/reply.json`

```json
{
  "type": "reply",
  "id": "d7c20931-eada-4d1f-9df5-e8426a3d8dcd",
  "from": "app-7f3c2a19",
  "ok": true,
  "result": {
    "accepted": "sent"
  }
}
```

`fixtures/device/reply.error.json`

```json
{
  "type": "reply",
  "id": "c111366e-43d9-4a94-b842-474932d7ff9e",
  "from": "app-7f3c2a19",
  "ok": false,
  "error": {
    "code": "agent_unavailable",
    "message": "claude is not installed on this device"
  }
}
```

`fixtures/device/forwarded/session.send.json` (abridged)

```json
{
  "type": "session.send",
  "id": "d7c20931-eada-4d1f-9df5-e8426a3d8dcd",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
  "text": "Have a look at the screenshot and tell me what broke the layout.",
  "mode": "auto",
  "from": "app-7f3c2a19",
  "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712"
}
```

Note the two fields the gateway added: `from` identifies the app connection and must be echoed in the
reply, and `device_id` was injected because the request was addressed by `session_id`.
`fixtures/device/forwarded/` holds all fifteen forwarded requests, plus the gateway's backfill
variant of `session.history` described in 7.1.

The gateway keeps a replay buffer of the last 2 000 events or 4 MiB per session, whichever is
smaller, and persists the latest `Session` summary per session in SQLite so lists render while a
device is offline.

### 7.1 Backfill after a device link outage

A device keeps working while its socket is down, so events produced during an outage never reach the
gateway. Amendment A9 closes that hole. When a device reconnects, the gateway compares each
session's `last_seq` in the device `hello` with the tail of its own replay buffer. For every session
whose device `last_seq` is ahead, the gateway asks the device for the missing range:

`fixtures/device/forwarded/session.history.after.json`

```json
{
  "type": "session.history",
  "id": "586c5356-ccb2-438c-aca6-1fff63807f39",
  "session_id": "ad2c9abb-4a1e-470a-835c-228778fc17f0",
  "after_seq": 22,
  "limit": 1000,
  "from": "gateway",
  "device_id": "c5efb1ec-2912-4619-90f7-93b5172fd712"
}
```

The gateway appends the returned events to the replay buffer and fans them out to that session's
current subscribers as ordinary `session.event` frames. This is the one request the gateway issues on
its own behalf rather than forwarding, so `from` names the gateway instead of an app connection, and
the gateway consumes the reply itself.

Apps need no special handling. The backfilled events arrive as normal session events and are applied
by `block_id` like any other.

---

## 8. Semantics every UI must honour

1. **State comes from events, never from silence.** Only `status`, `turn_started` and
   `turn_completed` change a session's state. Never infer that a turn finished because nothing
   arrived.
2. **Apply by `block_id`, order by `first_seq ?? seq`.** Streaming text goes into the block named
   by `block_id`. Drop any event whose `seq` is less than or equal to the last applied `seq` for that
   session: it is a late or duplicate frame. Sort blocks by `first_seq` when it is present and by
   `seq` otherwise, so a block that finishes late stays where it started.
3. **Sending during a running turn.** With `mode: "auto"` the message is queued, or steered for
   agents with capability `steer`. The UI shows "working · your message will be queued" and offers
   "Interrupt & send". A steered message stays an optimistic row until the device's
   `user_message` arrives, which happens when the agent takes it, not when it was sent (A14).
4. **Approval and question cards go inactive** as soon as `status` becomes `resolved` or `expired`.
   Only the server-supplied option ids are ever sent back.
5. **Terminal control disables the composer**, which reads "Controlled by the terminal · Take
   over". Decide this from `control`, never from `state` alone: a mirrored session reports
   `running` while its terminal-driven turn works, and `readonly` only between turns.
6. **Reconnect order.** Render the cached list, open the socket and read `hello`, then
   `session.subscribe` with `since_seq`, then `session.history` if `resync` is true or there is no
   cache.
7. **Never auto-resend.** After an uncertain `session.send` an app shows "delivery unconfirmed" and
   lets the user retry, reusing the same `id`. Device-side idempotency (2.4) makes that retry safe.
8. **Apply snapshots wherever they arrive.** `todos` and `queue` snapshots must be applied when
   they turn up in replayed events and in history, not only in the live stream, along with the
   `queue` field of a `session.subscribe` reply. An app that only handles them live shows a stale
   todo chip and a stale queue count after every reconnect.
9. **Render unknown agents generically**, using the agent id as the label, and ignore unknown fields
   and unknown event kinds rather than failing.
10. **Treat `shared` like `remote`.** A session with `control: "shared"` gets the same composer,
    approvals, queue and user-message rows as a remote session. Never offer "Take over" on it, and
    show Stop only when the agent has capability `interrupt` and reports `shared_interrupt: true`.
    Render `delivery: "absorbed"` as a quiet "will be re-sent" chip on the bubble; a held message
    is a queue entry until it lands (A19). Enable the model, permission-mode and effort
    pickers on a `shared` session when the agent reports `shared_settings: true`, and the
    attachment button when it reports `shared_attachments: true`. When both are true the composer
    status line says only that the session is attached to the terminal, with nothing disabled.
11. **Hint how to attach.** On a `terminal` session whose agent has `attach` set, the take-over bar
    may carry one line about the attachment: with `attach_ready: false`, that the terminal must be
    started through the device's shim (Claude) or with the shared daemon running (Codex); with
    `attach_ready: true`, that the running CLI was started without the attachment.
12. **Label `shared` as "terminal · attached"** in the status line, and give it the same dot colour
    as `remote` in the session list.
13. **Render whatever `options` an approval carries**, in the order they arrive, and read the
    resolution from `decision`. A block resolved with `option_id: "elsewhere"` and `by: "terminal"`
    was answered on the other side of a shared session and reads as "answered in the terminal"; an
    app never offers `elsewhere` as a button and never sends it back.

---

## 9. Conformance checklist

### 9.1 Gateway

- [ ] `hello` on `/ws/app` carries `protocol: 1`, the full device and session lists and `server_time`.
- [ ] Rejects a `hello` whose `protocol` is not 1 with error code `unsupported`.
- [ ] Adds `device_id` to forwarded requests addressed by `session_id`, stamps `from`, and strips
      `from` from the reply before delivering it to the originating connection only.
- [ ] Answers `device_offline` when the device has no socket, and `timeout` after 60 s.
- [ ] Pings every 25 s on both sockets; closes a connection silent for 90 s.
- [ ] Enforces the `Origin` check on cookie-authenticated upgrades and mutating requests.
- [ ] Rate limits `POST /api/login` to 5 per minute per IP.
- [ ] Reports the served wheel as `client` in `GET /api/config`, stores `client_build` from each
      `hello`, forwards `device.update`, marks the device `updating` on an accepted reply and
      `failed` with the message on `update.failed` or when no `hello` follows within five minutes,
      and clears both on the next `hello` (A22).
- [ ] Issues claim tokens for hosts, lets a signed-in user claim one, mints the pairing code for
      the host on that claim and hands it out exactly once (A23).
- [ ] Issues pairing codes as `RC-XXXX-XXXX` in Crockford base32 without I, L, O and U, single use,
      10-minute lifetime, and emits `pairing.progress` through `waiting`, `enrolled`, `online`,
      `agents`.
- [ ] Drops session-event frames over 64 KiB and logs them.
- [ ] Keeps a replay buffer of 2 000 events or 4 MiB per session and sets `resync: true` when a
      `since_seq` falls outside it.
- [ ] On a device reconnect, backfills every session whose device `last_seq` is ahead of the
      replay buffer tail with `session.history {after_seq}`, appends the result to the buffer and
      fans it out to subscribers as `session.event` frames.
- [ ] Stamps `device_id` on every `session.event` and `session.removed` frame sent to an app.
- [ ] Remembers the latest `queue` event payload per session and returns it as `queue` in the
      `session.subscribe` reply.
- [ ] Persists the latest `Session` summary per session so `GET /api/sessions` works while a device
      is offline.
- [ ] Closes the older socket with code 4001 when a device reconnects.
- [ ] Uses close code 4401 for a missing, invalid, expired or revoked credential, 4403 for a
      credential that is valid but not allowed here, and 1008 only for protocol violations.
- [ ] Push payloads contain only the `rc` object of 3.7 and no message content.

### 9.2 Device

- [ ] Assigns `seq` per session, strictly increasing, persisted across restarts.
- [ ] Emits `status` on every state change and `turn_started` / `turn_completed` around every turn.
- [ ] Reports `readonly` only for a terminal-controlled session with no turn in progress, and
      `running` while a terminal-driven turn is working.
- [ ] Coalesces streaming deltas to at most one flush per 80 ms per block, and ends every stream with
      a `done: true` event that carries the full `text` and no `delta`.
- [ ] Truncates `input`, `output` and `diff.patch` at 8, 16 and 32 KiB and sets the matching
      `*_truncated` flag; serves the untruncated block through `session.block`.
- [ ] Produces a one-line `title` for every `tool_call` and `approval`.
- [ ] Sets `first_seq` on every replacement event and on every `session.history` and
      `session.block` result, equal to the `seq` at which the block first appeared.
- [ ] Includes at least one `primary` and one `danger` option in every `approval`.
- [ ] Treats a repeated `session.send` id within a session as a duplicate and replays the original
      result.
- [ ] Refuses `session.send` on a `control: "terminal"` session with `conflict`, and resumes a
      `control: "none"` session before sending.
- [ ] Echoes `id` and `from` in every `reply`.
- [ ] Returns `session.history` exactly as 6.4 describes: latest event per block, ascending `seq`, no
      deltas, no `status`, no `meta`, no `queue`.
- [ ] Honours `after_seq` as well as `before_seq`, rejects a request carrying both with
      `bad_request`, and caps either form at `limit` with an accurate `has_more`.
- [ ] Reports accurate `capabilities` per agent, and never advertises `steer` or `takeover` it
      cannot perform.
- [ ] Reports `control: "shared"` only while the attachment is live, and moves the session to
      `terminal` or `none` as soon as it drops.
- [ ] On a `shared` session injects only while the transcript is idle, holds everything else as a
      `queue` entry with no `user_message`, and emits the block with `delivery: "delivered"` only
      once it is injected (A19).
- [ ] Reports `client_build` in `hello`, answers `device.update` as 6.3 says, verifies the wheel's
      SHA-256 before installing, restarts itself only after a successful install, and sends
      `update.failed` otherwise (A22).
- [ ] Advertises `speeds` from the agent's own catalogue, maps `session.set` / `session.create`
      `speed` to the agent's setting (Codex: `thread/settings/update {serviceTier}`), refuses a tier
      the session's model does not offer with `unsupported`, and reports the tier in `Session.speed`
      and `meta` (A21).
- [ ] Raises a `question` block with `needs_input` for an `AskUserQuestion` its `PermissionRequest`
      hook reports on an attached Claude session, answers the hook from the first `session.answer`,
      and resolves the block with `by: "terminal"` when the transcript shows the terminal answered
      first (A20).
- [ ] Offers exactly `allow` and `deny` on an approval relayed through a Claude channel, offers the
      options the Codex daemon lists for that request, resolves either with `decision.by: "terminal"`
      when the terminal answered first, and expires it when the CLI exits or the attachment drops.
- [ ] Reports `shared_settings` and `shared_attachments` truthfully per agent, and honours them:
      `session.set` and `session.send` attachments succeed on a `shared` session exactly when the
      matching flag is true and are refused with `unsupported` when it is false.
- [ ] Sets `attach_ready` for Codex from a successful handshake on the shared daemon socket, not
      from the socket file existing, and falls back to a per-session app-server with
      `attach_ready: false` when there is no daemon.
- [ ] Derives Codex `origin` and `control` as 4.4 describes, keeps a thread `shared` until it is
      unloaded, and reports `terminal` for a rollout held by a Codex process that is not the daemon.
- [ ] Resolves a Codex approval it did not answer with `decision: {option_id: "elsewhere",
      by: "terminal"}`.
- [ ] Moves a Claude attachment to the session id the CLI's `SessionStart` hook names, and removes
      a terminal-origin session with no events and no transcript with `session.removed` the moment
      its terminal leaves it (A16).
- [ ] Reports `model`, `permission_mode` and `effort` for a mirrored Claude session from its
      transcript and publishes each change as `meta` (A17).
- [ ] Publishes a Codex thread only when its `source` is a string and either its `originator` is
      the device's own or its `source` is `cli` or `exec`, and removes with `session.removed` any
      other thread it published before (A18).

### 9.3 App

- [ ] Ignores unknown fields, unknown event kinds and unknown agent ids.
- [ ] Shows `model`, `permission_mode` and `effort` on a terminal-held session as values it cannot
      change, by label when `AgentInfo` lists the id and by the id otherwise (A17).
- [ ] Offers Rename, Update and Remove on every device row, shows "Update available" when the
      device's `client_build` differs from `config.client.build`, and reflects `update_state` (A22).
- [ ] Offers `speeds` as one control that cycles standard → each tier → standard, drawn only when
      the list is non-empty, and shows a terminal-held session's `speed` as a value it cannot change
      (A21).
- [ ] While a `question` is pending, sends the composer's draft as that question's free-text answer
      through `session.answer` instead of queueing it, and shows a resolved question's `by` (A20).
- [ ] Applies events by `block_id` with the replacement and streaming rules of 5.1, orders blocks
      by `first_seq ?? seq`, and drops events whose `seq` is not newer than the last applied one.
- [ ] Follows the reconnect order of 8.6 and treats 60 s of silence as a dead connection.
- [ ] Applies `todos` and `queue` snapshots from replayed events, from history and from the
      `queue` field of a `session.subscribe` reply, not only from the live stream.
- [ ] Renders exactly the approval and question options it was given, keyed by id, and disables them
      once resolved or expired.
- [ ] Disables the composer from `control`, not from `state`, and offers "Take over" when the
      agent has that capability.
- [ ] Treats `control: "shared"` like `remote` for the composer, approvals and queue, never offers
      "Take over" on it, and shows Stop only when the agent has capability `interrupt` **and**
      reports `shared_interrupt: true`.
- [ ] Enables the model, permission-mode and effort pickers on a `shared` session only when the
      agent reports `shared_settings: true`, and the attachment button only when it reports
      `shared_attachments: true`.
- [ ] Renders a `decision` of `option_id: "elsewhere"` with `by: "terminal"` as answered in the
      terminal, and never offers `elsewhere` as a button.
- [ ] Renders `user_message.delivery` rather than assuming every message reached the agent.
- [ ] Stops reconnecting on close code 4401, clears the stored credential and returns to login;
      reconnects with backoff on any code other than 4401 and 4403.
- [ ] Never auto-resends a `session.send`; retries reuse the original `id`.
- [ ] Shows the queued or steered outcome from `accepted` rather than guessing.
- [ ] Decodes every fixture under `fixtures/` in its test suite.

---

## 10. Fixtures

| Path | What it is |
| --- | --- |
| `fixtures/app/` | One frame per app-socket type, in both directions, plus typed replies |
| `fixtures/device/` | One frame per device-socket type |
| `fixtures/device/forwarded/` | All fifteen forwarded requests as the device receives them, plus the A9 backfill variant |
| `fixtures/events/` | One event per kind, and one `tool_call` per `tool_kind` |
| `fixtures/objects/` | Bare `Session` and `AgentInfo` objects that no frame fixture carries, including the two attachable agents and the shared sessions |
| `fixtures/http/` | One body per HTTP request and response |
| `fixtures/stt/` | The five speech-to-text text frames |
| `fixtures/timelines/claude.json` | A complete Claude Code turn, 41 events |
| `fixtures/timelines/codex.json` | A Codex CLI session with steering and a queued message, 37 events |
| `fixtures/history/page.json` | A `session.history` reply that obeys 6.4 |
| `fixtures/replay/subscribe.reply.json` | A `session.subscribe` reply served from the replay buffer |
| `fixtures/replay/subscribe.codex.reply.json` | The same, mid-turn, carrying a `queue` snapshot |
| `fixtures_invalid/` | Frames that must be rejected, used to self-test the validator |

A timeline file is a test harness object, not a wire frame: `{name, description, device, session,
session_final, frames}`. Feed `frames` into a timeline reducer and the result must match
`session_final`. Its schema is `schema/timeline.json`.

---

## 11. Amendments

Orchestrator rulings, part of the frozen contract. This document carries the amended wording; the
list is here so a reader who knows the original text can see what moved.

**2026-09-09 A1 — `tool_kind`.** The tool-category field inside `tool_call` and `approval` events is
named `tool_kind`, with values unchanged:
`shell | read | edit | write | search | web | mcp | subagent | todo | other`. `kind` is exclusively
the event discriminator. Sub-agent parents are `tool_call` events with `tool_kind: "subagent"`.
See 5.1, 5.5 and 5.7.

**2026-09-09 A2 — `Usage`.** `input_tokens`, `output_tokens` and `total_tokens` are required
integers. `context_used`, `context_window` and `cost_usd` are optional and may be `null` or absent,
because Codex reports no cost. See 4.7.

**2026-09-09 A3 — Attachments.** `user_message.attachments[]` items are `{name, mime, size}`;
`session.send.attachments[]` items are `{name, mime, data_base64}`, and the device computes `size`.
See 4.8.

**2026-09-09 A4 — WebSocket close codes.** The gateway closes an `/ws/app` or `/ws/device` socket
with **4401** when the credential is missing, invalid, expired or revoked, and apps must stop
reconnecting and return to login; **4403** when the credential is valid but not allowed for the
requested resource; **4001** when a device connection is replaced by a newer one, and the device
should not reconnect immediately; and **1008** only for protocol violations. Any other close code
means reconnect with backoff. See 2.5.

**2026-09-09 A5 — `device_id` on forwarded session frames.** The gateway adds `device_id` to every
`session.event` and `session.removed` frame it sends to apps, because it knows the device from the
socket. `session_id` remains globally unique and is the primary key for apps; `device_id` is
informational and for routing. Devices do not send it. See 6.1.

**2026-09-09 A6 — Queue snapshot on subscribe.** The gateway remembers the latest `queue` event
payload per session and returns it as an optional `queue: {pending: [...]}` field in the
`session.subscribe` reply, absent when it has seen none. Apps must apply `todos` and `queue`
snapshots found in replayed events and history, not only in live streams. See 6.2 and 8.8.

**2026-09-10 A7 — `readonly` versus `running` for mirrored sessions.** `state: "readonly"` is used
only when `control == "terminal"` **and** no turn is in progress. While the terminal-driven turn runs
the device reports `running`, with `needs_approval` and `needs_input` as usual. Apps decide whether
the composer is enabled from `control`, never from `state` alone. See 4.5 and 8.5.

**2026-09-10 A8 — Block ordering across reloads.** Every block event carries an optional integer
`first_seq`, the `seq` at which that block first appeared in the session, equal to `seq` on the first
event. Devices set it on replacement events and on `session.history` and `session.block` results.
Apps order blocks by `first_seq ?? seq`, so a long-running tool call that finishes late stays where
it started. `session.history` keeps returning the latest event per block in ascending `seq` order.
See 5.1, 6.4 and 8.2.

**2026-09-10 A9 — Backfill after a device link outage.** `session.history` accepts an optional
integer `after_seq`. When present the device returns its stored events with `seq` greater than
`after_seq`, the latest version per block plus the non-block events of 6.4, ascending, at most
`limit`, with `has_more`. `before_seq` and `after_seq` are mutually exclusive. When a device
reconnects, the gateway compares each session's `last_seq` from the device `hello` with the tail of
its replay buffer; for every session whose device `last_seq` is ahead it requests
`session.history {after_seq: <buffer tail>}`, appends the returned events to the buffer and fans them
out to current subscribers as ordinary `session.event` frames. Apps need no change, because they
apply events by `block_id`. See 6.4 and 7.1.

**2026-09-10 A10 — `control: "shared"` for attached terminal sessions.** `control` gains a fourth
value, `shared`: a live CLI process owns the session **and** the device is attached to it, through a
channel the Claude CLI loads or through the Codex shared app-server. Apps treat `shared` like
`remote` for the composer, approvals, queue and user-message rows, never offer "Take over" on it,
and show Stop only when the agent has capability `interrupt` and reports `shared_interrupt: true`.
`AgentInfo` gains three optional fields, `attach` (`channel | daemon | null`), `attach_ready` and
`shared_interrupt`; `capabilities` is unchanged. `user_message` gains an optional `delivery`
(`pending | delivered | absorbed`), set only on `shared` sessions, because the device may inject a
message only while the CLI is idle and holds it otherwise. A relayed `approval` offers exactly
`allow` and `deny`, carries `{tool_name, description, input_preview}` as its `input` and no `diff`,
and resolves with `decision.by: "terminal"` when the terminal answers first. On a `shared` session
`session.send` and `session.approve` work, `session.answer` and `session.takeover` do not,
`session.stop` needs capability `interrupt` together with `shared_interrupt`, and `session.set`
accepts only `title`. `session.send`
keeps the existing result vocabulary: `accepted: "sent"` when the message is injected straight away,
`accepted: "queued"` with a `queued_id` when it is held. `readonly` stays reserved for
`control == "terminal"` (A7). See 4.2, 4.4, 4.5, 5.2, 5.7, 6.3, 8.10 and 9.

**2026-09-10 A11 — Codex through the shared app-server daemon.** Codex runs every bare `codex` TUI
inside one local app-server daemon, and the device attaches to it as a second client, so a Codex
`shared` session can do more than a Claude one. `AgentInfo` gains two more optional booleans,
`shared_settings` (whether `session.set` for `model`, `permission_mode` and `effort` works on a
`shared` session) and `shared_attachments` (whether `session.send` attachments are delivered);
both default to false and both are false for Claude. Codex reports `attach: "daemon"`,
`attach_ready` from a successful handshake on the daemon socket rather than from the socket file
existing, and `shared_interrupt`, `shared_settings` and `shared_attachments` all true, with
`capabilities` unchanged. On a shared Codex session `session.send` behaves as it does on a remote
one, including `accepted: "steered"` and attachments, `session.stop` interrupts, `session.set`
reaches the daemon, and `session.answer` is supported; `session.takeover` is still `conflict`. A
relayed Codex approval offers the daemon's own options, drawn from `allow`, `allow_session`,
`allow_always` and `deny`, carries `{command, cwd, command_actions}` as its `input` for a command and
a `diff` when the daemon supplies one; when the request is resolved somewhere else the block ends as
`decision: {option_id: "elsewhere", by: "terminal"}`. A Codex thread has no rollout until its first
turn starts, so the device cannot subscribe to a freshly created thread and subscribes when that
turn creates the rollout, while a thread created from an app is reopenable with `codex resume <id>`
only after its first turn. A10's "exactly allow and deny" applies to
Claude channels only, and `session.approve` rejects any `option_id` the block did not offer. `origin`
and `control` for a Codex thread follow the table in 4.4, where `shared` lasts only while a terminal
still has the thread: the daemon says nothing when a TUI exits, so the device watches for the TUI
process in the thread's `cwd` and reports `remote` or `none` once it is gone. Apps stay
agent-agnostic: they read the five attachment fields.
See 4.2, 4.4, 5.7, 6.3, 8.10, 8.13 and 9.

**2026-09-11 A12 — the app's request id is the `user_message` block id.** Apps waited for the
device's `user_message` before showing what the user had just typed, which costs a full
app→gateway→device→gateway→app round trip on every send. The `user_message` a device emits for a
`session.send` now carries the request's `id` as its `block_id`, so an app renders the message at
once under that id and the device's event replaces it by the ordinary replacement rule; queued
messages keep the same id from `queue.pending[].id` through to their `user_message`. Devices that
predate this still emit their own ids, and an app must then fall back to reconciling by text and
`source`. See 2.4, 5.1, 5.2 and 5.12.

**2026-09-11 A13 — one keepalive clock, and a grace period before `online: false`.** Devices were
reported offline for half a minute at a time about twice an hour. The gateway's WebSocket server
had its own default 20 s ping/pong timeout on top of the 25 s / 90 s contract in 2.5, so a device
whose event loop stalled for twenty seconds was closed with `1011` and broadcast as offline the same
instant. 2.5 now states that the 25 s / 90 s application pings are the only keepalives, and that the
gateway holds `online: true` for a 20 s grace period after a transient close, answering requests
addressed to the device only once the period ends without a replacement connection (they wait for
one in the meantime). `4401`, `4403` and an explicit removal still flip `online` immediately. See
2.5 and 4.1.

**2026-09-11 A14 — a steered `user_message` is emitted when the agent takes it.** A message sent
into a running Codex turn was drawn where it was sent, while the terminal drew it where Codex read
it: after the agent message that was already streaming. The two orders disagreed on every send made
mid-turn. The device now emits the `user_message` for an `accepted: "steered"` send only when the
agent reports having taken it (the daemon's `userMessage` item), so `first_seq` lands after the
output that preceded it, and falls back to the end of the turn when the message was never taken.
Apps keep the optimistic row from A12 at the bottom in the meantime. See 5.2 and 9.

**2026-09-11 A15 — a session that comes back to life leaves the Archive.** An archived session that
was resumed from a terminal, or written to from an app, ran on with `archived: true` and stayed
folded in the Archive while it worked. The device now clears `archived` whenever a turn starts in
the session or a terminal attaches to it, and publishes the change, so the row returns to the
device's Active list by the ordinary layout rule. See 6.3.

**2026-09-12 A16 — the attachment follows the CLI, and a session that never held a message is
removed.** A Claude CLI started through the shim registered its channel under the id it was started
with; `/resume` and `/clear` inside the TUI moved the process to another id without telling anybody,
so the live session showed `control: "none"` while an empty row stayed `shared`, and every CLI start
that was quit or resumed away from left an empty session behind for good. The device now takes a
`SessionStart` hook from the CLI (installed through a settings file the shim passes), moves the
attachment to the id the hook names, and removes any terminal-origin session with no events and no
transcript the moment its terminal leaves it. The device sends `session.removed` on its own
initiative for those; the gateway and the apps already treat the frame as authoritative. See 4.4
and 7.

**2026-09-12 A17 — a terminal-held session reports what the terminal chose.** A `shared` Claude
session cannot take `session.set` (the channel has no such method), so the apps drew nothing where
the model, permission-mode and effort pickers go, and a person on the phone could not tell which
model the terminal was running. The device now reads the three values from the transcript Claude
Code writes for itself and publishes changes as `meta`; apps show them as values that cannot be
changed from there. Nothing new on the wire: `Session` already carried the fields and `meta` already
carried the updates. See 4.4, 5.11, 9.2 and 9.3.

**2026-09-12 A18 — work another application owns is not a session.** The daemon's `thread/list`
is the history of every Codex thread on the machine, and the device turned all of it into sessions:
the ChatGPT desktop app's scheduled automations and chats showed up in the apps as terminal
sessions, one of them `control: "terminal"` for as long as that app held its rollout open, none of
them anything a person had started at a terminal. The device now reads the `originator` and
`source` the index carries and publishes only threads its own daemon holds or a terminal started;
the rest are that application's and are removed if they were ever published. Nothing new on the
wire: `session.removed` already exists and the apps already honour it. See 4.4 and 9.2.

**2026-09-12 A19 — a held message is a queue entry until the CLI takes it.** A message sent into a
running attached Claude turn was published at once as a `user_message {delivery: "pending"}`, so
its `first_seq` pinned it in the middle of the turn — between the tool call that was running and
the rest of the answer — while the terminal drew it after the turn, where Claude Code read it. The
device now holds such a message as a `queue` entry only and emits the block when it injects it, as
A14 already did for a steered Codex message; `delivery: "pending"` is gone from the protocol and
`fixtures/events/user_message.pending.json` with it. See 5.2, 4.4 and 9.

**2026-09-12 A20 — a question Claude Code asks in an attached session can be answered from an app.**
The channel never relays `AskUserQuestion` (the CLI relays only tools that need no user
interaction, and its permission reply carries no answers), so a shared session showed the question
as a running tool call and the composer queued whatever was typed. Claude Code runs a
`PermissionRequest` hook beside its own dialog and takes whichever answers first, so the device now
installs one for `AskUserQuestion` in the settings file the shim passes, raises the `question`
block from it with `needs_input`, feeds the first `session.answer` back as the tool's answers, and
resolves the block `by: "terminal"` when the dialog was answered there. `question` gains an
optional `by`; `session.answer` is no longer `unsupported` on a shared session. See 5.8, 4.4 and 9.

**2026-09-13 A21 — a speed tier beside the model, the effort and the permission mode.** Codex offers
a faster tier per model (`serviceTiers` in its catalogue, `priority` named "Fast") and the TUI
toggles it with `/fast`; the apps could not. `AgentInfo` gains `speeds`, `Session` and `meta` gain
`speed`, `session.create` and `session.set` accept it, and the device maps it to the agent's own
setting. Nothing else changes; an agent that lists no speeds draws no control.

**2026-09-13 A22 — a device is updated from an app.** Bringing a device to a new client meant
re-running the installer on each machine. The gateway now reports the wheel it serves by its
SHA-256 (`GET /api/config` `client`), every device reports the build it runs (`hello`
`client_build`, `Device.client_build`), and `device.update` asks a device to fetch exactly that
build, install it and restart. `Device` gains `update_state` and `update_message`; the device
gains the `update.failed` frame. See 3.2, 4.1, 6.3, 7 and 9.

**2026-09-13 A23 — a host is paired by scanning.** The only way to pair was to mint a code in an
app and type it into the host's terminal, which is awkward from a phone. A host may now ask the
gateway for a claim token (`POST /api/pairing/requests`), print it as a QR code, and long-poll
for the outcome; a signed-in app claims the token (`POST /api/pairing/requests/{token}/claim`),
the gateway mints the ordinary pairing code for that host and hands it back to the poll, and
enrolment proceeds exactly as before, `pairing.progress` included. The QR encodes
`<public_origin>/pair#<token>`, which the web app honours too. See 3.1, 3.3 and 9.1.
