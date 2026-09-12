# Deploying the gateway

The gateway is the only thing you deploy. Devices dial out to it and apps dial in; neither needs
anything installed on the VPS beyond this stack.

## Prerequisites

- A Linux host with Docker and the Compose plugin.
- A reverse proxy you run and configure yourself. The stack contains no TLS terminator: it only
  publishes the gateway on a host port. Nginx Proxy Manager, Traefik and plain nginx all work; see
  [Reverse proxy](#reverse-proxy).
- A domain whose A or AAAA record points at the host, so that proxy can get a certificate.
- About 512 MB of memory for the gateway, more if you enable the local speech-to-text profile,
  which downloads and runs a Whisper model.

## First deployment

```sh
git clone <this repository> remote-control && cd remote-control
cp .env.example .env
$EDITOR .env          # set PUBLIC_ORIGIN and RC_PASSWORD
docker compose up -d
docker compose logs -f gateway
```

The first run builds three stages: the web app with `npm ci && npm run build`, the `rc_client` wheel
with `uv build`, and the Python service itself. Expect several minutes. A healthy start logs
`gateway ready` with the origin, the speech-to-text provider and whether push is enabled.

Verify on the host first, straight against the published port:

```sh
curl http://127.0.0.1:8787/api/health
# {"ok":true,"version":"0.1.0","protocol":1,"auth":{"mode":"password"},"devices_online":0}
```

Then point your reverse proxy at that port, following [Reverse proxy](#reverse-proxy), and repeat
the check through the public hostname:

```sh
curl https://rc.example.com/api/health
```

Open the origin in a browser, sign in with `RC_PASSWORD`, and add your first device from
**Devices → Add device**.

## `.env` reference

Only `PUBLIC_ORIGIN` and `RC_PASSWORD` are required; the gateway refuses to start without them and
names the missing one.

| Variable | Default | Meaning |
| --- | --- | --- |
| `PUBLIC_ORIGIN` | — | **Required.** The exact origin apps use, `scheme://host[:port]`, no path and no trailing slash. Checked against the browser's `Origin` header on cookie-authenticated writes, and baked into the pairing command served at `/install.sh` |
| `RC_PASSWORD` | — | **Required.** The login password for the single user `admin`. Use a long random value |
| `GATEWAY_PORT` | `8787` | The host port compose publishes the gateway on. Your reverse proxy forwards here |
| `GATEWAY_BIND` | `0.0.0.0` | The host address that port binds to. A proxy running as a container reaches the host over the Docker bridge, so loopback only works when the proxy is on the host network |
| `RC_SECRET` | generated | Signs login tokens. Left empty, one is generated into `DATA_DIR` on first start. Set it explicitly to pin the signing key instead of depending on a file inside the volume |
| `DATA_DIR` | `/data` | Where SQLite databases and generated keys live. Backed by the `rc-data` volume |
| `STT_PROVIDER` | `none` | One of `none`, `openai`, `mimo`. `none` disables voice input, `openai` targets any OpenAI-compatible transcription server, `mimo` targets Xiaomi MiMo. Any other value stops the gateway at startup |
| `STT_BASE_URL` | `https://api.openai.com/v1` | Base URL. `openai` posts to `{STT_BASE_URL}/audio/transcriptions`, `mimo` to `{STT_BASE_URL}/chat/completions` |
| `STT_API_KEY` | empty | Bearer token for that server. Not needed by most local servers |
| `STT_MODEL` | `whisper-1` | Model name the backend expects |
| `STT_LANGUAGES` | `auto,zh,en` | The languages offered in the composer's picker. `auto` lets the backend detect |
| `APNS_TEAM_ID` | empty | Apple developer team id |
| `APNS_KEY_ID` | empty | Key id of the APNs `.p8` signing key |
| `APNS_KEY_PATH` | empty | Path to that `.p8` **inside the container** |
| `APNS_TOPIC` | `com.junbingao.remotecontrol` | The app's bundle id |
| `APNS_ENVIRONMENT` | `production` | `production` or `sandbox`. Debug builds register sandbox tokens |
| `WEB_PUSH_CONTACT` | `mailto:admin@example.com` | The VAPID `sub` claim. Set it to enable Web Push; the key pair is generated into `DATA_DIR` on first start. Empty disables Web Push |
| `TRUSTED_PROXIES` | `127.0.0.0/8,::1/128` | Comma-separated CIDR networks whose `X-Forwarded-For` the gateway believes. Must list your proxy's source address as the gateway sees it |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warning` or `error` |

Five more variables exist for running outside compose and are set by the image: `RC_HOST`
(`0.0.0.0`), `RC_PORT` (`8787`), `WEB_DIST_DIR`, `CLIENT_DIST_DIR` and `CLIENT_INSTALL_SCRIPT`. From
a source checkout they default to the matching directories in the repository, so you rarely set
them; `RC_HOST` and `RC_PORT` are useful when another process already holds 8787.

All four APNs values must be present for APNs to be enabled. An unreadable key path is logged as
`APNs disabled: signing key not found` and the gateway starts anyway.

## Reverse proxy

The stack terminates nothing. It publishes the gateway on `GATEWAY_BIND:GATEWAY_PORT` and you put
your own proxy in front to own the hostname, the certificate and the HTTP to HTTPS redirect.

**Nginx Proxy Manager.** Create a Proxy Host:

| Field | Value |
| --- | --- |
| Domain Names | your public hostname, e.g. `rc.example.com` |
| Scheme | `http` |
| Forward Hostname / IP | the address the proxy can reach the VPS on: the host's IP, `host.docker.internal`, or the Docker bridge gateway (often `172.17.0.1`) when the proxy is itself a container |
| Forward Port | `GATEWAY_PORT`, `8787` by default |
| Websockets Support | **on** — without it `/ws/app`, `/ws/device` and `/ws/stt` never upgrade and nothing streams |
| Block Common Exploits | on, harmless here |
| SSL | request a certificate, then enable **Force SSL** and **HTTP/2 Support** |

In the host's **Advanced** tab add:

```nginx
client_max_body_size 80m;
```

nginx defaults to 1 MB, which is smaller than a single dictated audio upload: the gateway accepts
25 MiB on `/api/stt/transcribe`. 80 MB leaves headroom. Note that it does not bound `session.send`
attachments — the protocol's eight 6 MiB attachments travel as WebSocket frames after the upgrade,
which `client_max_body_size` does not apply to; the gateway caps those itself at 72 MiB.

Nothing else is needed: the gateway pings every WebSocket every 25 seconds, so nginx's default
60 second `proxy_read_timeout` never fires on an idle session.

**Then, in `.env`:**

- Set `PUBLIC_ORIGIN` to the `https://` origin the browser will show, no path and no trailing slash.
  A mismatch down to the scheme or port makes the browser's login return 403.
- Put the proxy's source network in `TRUSTED_PROXIES`, as the gateway sees it. A proxy on the host
  network is loopback or the host address; a containerised proxy arrives from its Docker network
  subnet, which `docker network inspect <network>` prints. `172.16.0.0/12` covers the default bridge
  ranges. Make sure the proxy *replaces* `X-Forwarded-For` rather than appending: a proxy that
  appends lets a caller pick the first element and therefore its own rate-limit bucket. Nginx Proxy
  Manager replaces it.

**Security headers.** The gateway sets them itself on every HTTP response: a content security
policy, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
a permissions policy that keeps the microphone available for the composer, and HSTS whenever
`PUBLIC_ORIGIN` is `https://`. It sends no `Server` banner. Do not add a second, conflicting
content security policy in the proxy; the browser enforces the intersection of both and the app
will break in ways that are hard to read.

**Body limits and caching** are the gateway's job too. `/api/login` accepts 4 KB, the device and
push routes 16 KB, and an audio upload 25 MiB; the SPA shell, the service worker and the manifest
are served `no-store` so a deploy never leaves a stale `index.html` pointing at assets that no
longer exist.

**Any other proxy** needs the same four things: forward to the published port, forward WebSocket
upgrades, raise the body limit, and replace `X-Forwarded-For`.

**Plain HTTP** is only appropriate on a LAN, on loopback, or for a local test: the login password
and the bearer token cross the wire in the clear otherwise. Set `PUBLIC_ORIGIN` to the `http://`
address apps will actually use. The device installer refuses a plain-HTTP gateway that is not
loopback or an RFC 1918 address, so a publicly reachable host must be https.

## Lifecycle

```sh
docker compose up -d                      # build if needed, then start
docker compose ps                         # gateway should be "healthy"
docker compose logs -f gateway            # structured JSON to stderr
docker compose restart gateway
docker compose down                       # stop, keep the volumes
docker compose down -v                    # stop and DELETE the volumes
```

**Upgrading.** Pull the new revision and rebuild:

```sh
git pull
docker compose build
docker compose up -d
```

Each of the four databases applies its own additive migrations when it is opened, so an existing
`DATA_DIR` is brought up to the new schema in place and an upgrade needs no manual step. Signed-in
apps stay signed in: login sessions are recorded in `auth.sqlite3` rather than in memory, so a
restart or an image update no longer signs everyone out. Expired and revoked rows are pruned at
startup, which is logged as `pruned expired login sessions`.

**Backing up.** Everything durable is in the `rc-data` volume: login sessions, the device registry,
the session index, push subscriptions, the token-signing secret and the VAPID private key.

```sh
docker run --rm -v remote-control_rc-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/rc-data-$(date +%F).tar.gz -C /data .
```

Check the volume's real name with `docker volume ls` first; Compose prefixes it with the project
directory name. Wiping the volume invalidates every login session **and every device token**: each
device has to be enrolled again with a fresh pairing code, and every Web Push subscription is
orphaned along with the VAPID key.

## Speech to text

Voice input is off until `STT_PROVIDER` names a backend. Three ways to provide one.

**A hosted OpenAI-compatible provider.** Any server implementing
`POST {STT_BASE_URL}/audio/transcriptions`:

```sh
STT_PROVIDER=openai
STT_BASE_URL=https://api.openai.com/v1
STT_API_KEY=sk-…
STT_MODEL=whisper-1
```

**MiMo.** Xiaomi MiMo has no transcription endpoint: the gateway sends the utterance as a base64
WAV data URL inside a chat completion and reads the transcript back out of the assistant's reply.

```sh
STT_PROVIDER=mimo
STT_BASE_URL=https://api.xiaomimimo.com/v1
STT_API_KEY=…
STT_MODEL=mimo-v2.5-asr
```

MiMo accepts only `auto`, `zh` and `en`, so `STT_LANGUAGES` must list no others. It also caps an
utterance at 10 MB of base64; the gateway refuses anything larger before it sends the request, which
no recording under the protocol's 120 s limit reaches. The request shape was checked against a fake
local server, not against MiMo: the machine that wrote this had no MiMo key.

**On the VPS.** The compose file carries an optional `stt` service on the internal network, so audio
never leaves the host:

```sh
STT_PROVIDER=openai
STT_BASE_URL=http://stt:8000/v1
STT_MODEL=Systran/faster-distil-whisper-small.en
```

```sh
docker compose --profile local-stt up -d
```

The first request downloads the model into the `stt-models` volume, which takes a while and needs
disk. Neither this profile nor any other speech backend has been exercised: every validation run
used `STT_PROVIDER=none`. See `docs/VALIDATION.md` and `docs/VALIDATION-APPS.md`.

## Push notifications

**Web Push.** Set `WEB_PUSH_CONTACT` to a `mailto:` address. The VAPID key pair is generated into
`DATA_DIR` on first start, and `GET /api/push/web/vapid` serves the public key to the browser. With
the variable empty, that endpoint returns 503 and the web app hides the notification toggle. Keep
`vapid_private.pem` in your backups: replacing it invalidates every existing browser subscription.

**APNs.** Create an APNs auth key in the Apple developer portal, mount the `.p8` into the container
read-only, and set all four `APNS_*` values. The mount is commented out in `docker-compose.yml`:

```yaml
    volumes:
      - rc-data:/data
      - ./secrets/AuthKey.p8:/run/secrets/apns/AuthKey.p8:ro
```

with `APNS_KEY_PATH=/run/secrets/apns/AuthKey.p8`. The file must be readable by uid 10001, the
unprivileged user the image runs as. Use `APNS_ENVIRONMENT=sandbox` while testing a debug build.

Neither transport has been verified against a real endpoint, on the web or on iOS.

## Health checks

`GET /api/health` is unauthenticated and returns the version, the protocol version, the auth mode
and the number of online devices. The gateway container polls it every 30 seconds, so `docker
compose ps` reports `healthy` only when the service really answers. Note that every route is
declared `GET`-only: a `HEAD` request returns 405, which matters if your external monitor defaults
to `HEAD`.

## Troubleshooting

| Symptom | Log line or check | Cause and fix |
| --- | --- | --- |
| Container exits immediately | `rc-gateway: PUBLIC_ORIGIN is required…` | `.env` is missing, unreadable, or the variable is empty |
| Container exits immediately | `rc-gateway: DATA_DIR /data is not writable` | The volume is owned by the wrong uid; the image runs as 10001 |
| Login works in the app, fails in the browser | 403 on `/api/login` | `PUBLIC_ORIGIN` does not match the origin the browser actually used, down to scheme and port |
| Login returns 429 | `login rate limited` | Five attempts per minute per IP. Wait, and check `TRUSTED_PROXIES` if everyone shares one bucket |
| The hostname does not answer | `curl http://127.0.0.1:8787/api/health` on the host | If that works, the fault is in your reverse proxy: wrong forward host or port, or the container cannot reach `GATEWAY_BIND` |
| Everything loads but nothing streams | no `app connected` line | Websockets Support is off in the proxy host, so the `/ws/*` upgrade never reaches the gateway |
| Install one-liner 404s | `install script missing` | The image was built with `--target gateway` instead of `release`; rebuild with `docker compose build` |
| Device never appears | `device connected` absent | The daemon cannot reach the origin, or its token was revoked. Check `rc-client status` on the machine |
| A new device brings in old terminal sessions | none | Expected: a freshly enrolled machine mirrors its recent Claude and Codex transcripts, capped at 50 sessions from the last 14 days. Narrow it with `[mirror] max_sessions` and `max_age_days` in the device's `config.toml`, described in `docs/CLIENT.md` |
| Device flaps | `device disconnected` every few minutes | The proxy is timing out idle WebSockets; the gateway pings every 25 s, so allow at least 60 s. nginx's 60 s default is enough |
| Sessions listed but every action fails | `device_offline` replies | The daemon is not connected; the index still shows its last known summaries |
| Voice button missing | `GET /api/config` shows `stt.enabled: false` | `STT_PROVIDER` is `none` |
| Upload rejected with 413 | nginx returns it before the gateway logs anything | `client_max_body_size` is still at nginx's 1 MB default; set it to `80m` in the proxy host's Advanced tab |
| Dictation fails mid-utterance | `stt backend unreachable` or `stt backend rejected the request` | Wrong `STT_BASE_URL`, missing `STT_API_KEY`, or an unknown `STT_MODEL` |
| Browser notifications never arrive | `web push delivery failed` | Subscriptions were created against a different VAPID key; unsubscribe and subscribe again |
| iPhone notifications never arrive | `apns delivery abandoned` | Wrong topic, wrong environment, or an expired key |
| Every app is signed out at once | check `docker volume ls` and `.env` | Sessions live in `auth.sqlite3` and survive a restart, so this means either `RC_SECRET` changed, which invalidates tokens but leaves devices enrolled, or the volume was replaced, which also drops every device token |

Gateway logs are structured JSON on stderr and never contain tokens, passwords, pairing codes,
prompt text or tool output. `LOG_LEVEL=debug` adds detail without changing that rule.
