/**
 * Mock gateway for local web development (`npm run dev:mock`).
 *
 * Speaks the app-facing half of PROTOCOL-FROZEN.md: the HTTP API, `WS /ws/app`
 * with two scripted devices and five sessions, a scripted live turn, pairing
 * progress, history paging, and a fake `WS /ws/stt`.
 */
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { randomUUID } from 'node:crypto';
import { WebSocketServer, type WebSocket } from 'ws';
import type {
  AgentInfo,
  Device,
  Session,
  SessionEvent,
  TodoItem,
  QueuedMessage,
} from '../src/protocol/types';
import { HOME, devices, historyFor, recentDirs, sessions } from './fixtures';
import {
  ECHO_DELAY_MS,
  afterAnswer,
  afterApproval,
  codexSharedAfterApproval,
  codexSharedTurn,
  sharedAfterApproval,
  sharedTurn,
  turnScript,
  type Step,
} from './script';

const PORT = Number(process.env.PORT ?? 8787);
const PASSWORD = process.env.RC_PASSWORD ?? 'dev';
const GATEWAY_VERSION = '0.1.0-mock';
const COOKIE = 'rc_session';

/* ----------------------------------------------------------------- state */

const state = {
  devices: structuredClone(devices) as Device[],
  sessions: structuredClone(sessions) as Session[],
  events: new Map<string, SessionEvent[]>(),
  todos: new Map<string, TodoItem[]>(),
  queues: new Map<string, QueuedMessage[]>(),
  pairings: new Map<string, { expires_at: number; timers: NodeJS.Timeout[] }>(),
  tokens: new Set<string>(),
  /** A10: messages the device accepted but could not inject yet, per session. */
  held: new Map<string, { block_id: string; text: string; queued_id: string }[]>(),
};

for (const session of state.sessions) {
  const history = historyFor(session.session_id);
  state.events.set(session.session_id, history);
  session.last_seq = history.at(-1)?.seq ?? 0;
}

const findSession = (id: string): Session | undefined =>
  state.sessions.find((s) => s.session_id === id);

const agentFor = (session: Session): AgentInfo | undefined =>
  state.devices
    .find((d) => d.device_id === session.device_id)
    ?.agents.find((a) => a.agent === session.agent);

const blockIdOf = (event: SessionEvent): string | undefined =>
  'block_id' in event && typeof event.block_id === 'string' ? event.block_id : undefined;

/**
 * Amendment A8: every block event carries the seq at which the block first
 * appeared, so an app can order a late-finishing tool call correctly.
 */
function stampFirstSeq(history: readonly SessionEvent[], event: SessionEvent): void {
  const blockId = blockIdOf(event);
  if (!blockId || event.first_seq !== undefined) return;
  const first = history.find((e) => blockIdOf(e) === blockId);
  event.first_seq = first ? (first.first_seq ?? first.seq) : event.seq;
}

function nextSeq(sessionId: string): number {
  const session = findSession(sessionId);
  const seq = (session?.last_seq ?? 0) + 1;
  if (session) session.last_seq = seq;
  return seq;
}

/* ------------------------------------------------------------ connections */

interface AppConn {
  socket: WebSocket;
  subscriptions: Set<string>;
}

const conns = new Set<AppConn>();

const send = (socket: WebSocket, frame: unknown): void => {
  if (socket.readyState === socket.OPEN) socket.send(JSON.stringify(frame));
};

function broadcast(frame: unknown): void {
  for (const conn of conns) send(conn.socket, frame);
}

function emit(sessionId: string, event: SessionEvent): void {
  const list = state.events.get(sessionId) ?? [];
  stampFirstSeq(list, event);
  list.push(event);
  state.events.set(sessionId, list);
  applyToSummary(sessionId, event);
  const deviceId = findSession(sessionId)?.device_id;
  for (const conn of conns) {
    if (conn.subscriptions.has(sessionId)) {
      // Amendment A5: the gateway knows the device from the socket.
      send(conn.socket, { type: 'session.event', session_id: sessionId, device_id: deviceId, event });
    }
  }
}

function applyToSummary(sessionId: string, event: SessionEvent): void {
  const session = findSession(sessionId);
  if (!session) return;
  session.updated_at = event.ts;
  if (event.kind === 'status') {
    session.state = event.state;
    session.state_detail = event.detail ?? null;
  }
  if (event.kind === 'todos') {
    state.todos.set(sessionId, event.items);
    session.todos = {
      total: event.items.length,
      done: event.items.filter((i) => i.status === 'completed').length,
    };
  }
  if (event.kind === 'turn_started') session.turn = { turn_id: event.turn_id, started_at: event.ts };
  if (event.kind === 'turn_completed') {
    session.turn = null;
    if (event.usage) session.usage = event.usage;
  }
  if (event.kind === 'queue') {
    state.queues.set(sessionId, event.pending);
    session.queued = event.pending.length;
  }
  broadcast({ type: 'session.updated', session });
}

/** Play a list of scripted steps against a session. */
function play(sessionId: string, steps: Step[], done?: () => void): void {
  for (const step of steps) {
    setTimeout(() => {
      const ts = Date.now();
      emit(sessionId, step.event(nextSeq(sessionId), ts));
    }, step.after);
  }
  const total = steps.at(-1)?.after ?? 0;
  if (done) setTimeout(done, total + 20);
}

/**
 * A remote turn, followed by whatever the queue collected while it ran. The
 * dequeued message keeps its original request id as its `block_id` (A12).
 */
function playRemote(sessionId: string, text: string, blockId: string): void {
  play(sessionId, turnScript(text, blockId), () => drainQueue(sessionId));
}

function drainQueue(sessionId: string): void {
  const [next, ...rest] = state.queues.get(sessionId) ?? [];
  if (!next) return;
  emit(sessionId, { seq: nextSeq(sessionId), ts: Date.now(), kind: 'queue', pending: rest });
  setTimeout(() => playRemote(sessionId, next.text, next.id), 400);
}

/** Ends the running turn as `interrupted` and leaves the session idle. */
function interruptTurn(sessionId: string): void {
  const session = findSession(sessionId);
  if (!session?.turn) return;
  emit(sessionId, {
    seq: nextSeq(sessionId),
    ts: Date.now(),
    kind: 'turn_completed',
    turn_id: session.turn.turn_id,
    stop_reason: 'interrupted',
    duration_ms: Date.now() - session.turn.started_at,
  });
  emit(sessionId, { seq: nextSeq(sessionId), ts: Date.now(), kind: 'status', state: 'idle' });
}

/* -------------------------------------------------------------- http api */

function json(res: ServerResponse, status: number, body: unknown, headers: Record<string, string> = {}): void {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(payload),
    ...headers,
  });
  res.end(payload);
}

const unauthorized = (res: ServerResponse): void =>
  json(res, 401, { ok: false, error: { code: 'unauthorized', message: 'sign in first' } });

function authed(req: IncomingMessage): boolean {
  const cookie = req.headers.cookie ?? '';
  const token = /rc_session=([^;]+)/.exec(cookie)?.[1];
  if (token && state.tokens.has(token)) return true;
  const bearer = req.headers.authorization?.replace(/^Bearer\s+/i, '');
  return Boolean(bearer && state.tokens.has(bearer));
}

async function readBody(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  if (chunks.length === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8')) as Record<string, unknown>;
  } catch {
    return {};
  }
}

const CODE_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

function pairingCode(): string {
  const group = () =>
    Array.from({ length: 4 }, () => CODE_ALPHABET[Math.floor(Math.random() * CODE_ALPHABET.length)]).join('');
  return `RC-${group()}-${group()}`;
}

function startPairing(code: string): void {
  const timers: NodeJS.Timeout[] = [];
  const emitStep = (delay: number, step: 'waiting' | 'enrolled' | 'online' | 'agents', withDevice = false) =>
    timers.push(
      setTimeout(() => {
        broadcast({
          type: 'pairing.progress',
          code,
          step,
          ...(withDevice ? { device: newDevice(code) } : {}),
        });
      }, delay),
    );
  emitStep(300, 'waiting');
  emitStep(2_600, 'enrolled');
  emitStep(4_200, 'online', true);
  emitStep(6_000, 'agents', true);
  timers.push(
    setTimeout(() => {
      const device = newDevice(code);
      if (!state.devices.some((d) => d.device_id === device.device_id)) state.devices.push(device);
      broadcast({ type: 'device.updated', device });
    }, 6_100),
  );
  state.pairings.set(code, { expires_at: Date.now() + 10 * 60_000, timers });
}

function newDevice(code: string): Device {
  const template = state.devices[0];
  if (!template) throw new Error('no template device');
  return {
    ...template,
    device_id: `dev-${code.slice(3, 7).toLowerCase()}`,
    name: 'new-laptop',
    hostname: 'new-laptop.local',
    latency_ms: 24,
    created_at: Date.now(),
    last_seen: Date.now(),
  };
}

const server = createServer((req, res) => {
  void handleHttp(req, res).catch(() => {
    json(res, 500, { ok: false, error: { code: 'internal', message: 'mock failure' } });
  });
});

async function handleHttp(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const url = new URL(req.url ?? '/', `http://127.0.0.1:${PORT}`);
  const path = url.pathname;
  const method = req.method ?? 'GET';

  if (path === '/api/health') {
    json(res, 200, { ok: true, version: GATEWAY_VERSION, protocol: 1, auth: { mode: 'password' } });
    return;
  }

  if (path === '/api/login' && method === 'POST') {
    const body = await readBody(req);
    if (body.password !== PASSWORD) {
      json(res, 401, { ok: false, error: { code: 'unauthorized', message: 'wrong password' } });
      return;
    }
    const token = randomUUID();
    state.tokens.add(token);
    json(
      res,
      200,
      { ok: true, token, exp: Date.now() + 30 * 86_400_000, user: { username: 'admin' } },
      { 'Set-Cookie': `${COOKIE}=${token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000` },
    );
    return;
  }

  if (path === '/install.sh') {
    res.writeHead(200, { 'Content-Type': 'text/x-shellscript' });
    res.end('#!/bin/sh\necho "mock install script"\n');
    return;
  }

  if (!authed(req)) {
    unauthorized(res);
    return;
  }

  if (path === '/api/logout' && method === 'POST') {
    state.tokens.clear();
    json(res, 200, { ok: true }, { 'Set-Cookie': `${COOKIE}=; Path=/; Max-Age=0` });
    return;
  }

  if (path === '/api/session') {
    json(res, 200, { ok: true, user: { username: 'admin' }, exp: Date.now() + 86_400_000 });
    return;
  }

  if (path === '/api/config') {
    json(res, 200, {
      public_origin: `http://127.0.0.1:5173`,
      stt: { enabled: true, languages: ['auto', 'zh', 'en'] },
      push: { web_enabled: true, apns_enabled: false },
      version: GATEWAY_VERSION,
    });
    return;
  }

  if (path === '/api/devices' && method === 'GET') {
    json(res, 200, { devices: state.devices });
    return;
  }

  if (path.startsWith('/api/devices/pairing')) {
    if (method === 'POST') {
      const code = pairingCode();
      startPairing(code);
      json(res, 200, {
        code,
        expires_at: Date.now() + 10 * 60_000,
        install: {
          macos: `curl -fsSL http://127.0.0.1:5173/install.sh | sh -s -- \\\n  --pair ${code}`,
          linux: `curl -fsSL http://127.0.0.1:5173/install.sh | sh -s -- \\\n  --pair ${code}`,
        },
      });
      return;
    }
    if (method === 'DELETE') {
      const code = decodeURIComponent(path.split('/').pop() ?? '');
      state.pairings.get(code)?.timers.forEach(clearTimeout);
      state.pairings.delete(code);
      json(res, 200, { ok: true });
      return;
    }
  }

  const deviceMatch = /^\/api\/devices\/([^/]+)$/.exec(path);
  if (deviceMatch) {
    const deviceId = decodeURIComponent(deviceMatch[1] ?? '');
    const device = state.devices.find((d) => d.device_id === deviceId);
    if (!device) {
      json(res, 404, { ok: false, error: { code: 'not_found', message: 'no such device' } });
      return;
    }
    if (method === 'PATCH') {
      const body = await readBody(req);
      device.name = String(body.name ?? device.name);
      broadcast({ type: 'device.updated', device });
      json(res, 200, { device });
      return;
    }
    if (method === 'DELETE') {
      state.devices = state.devices.filter((d) => d.device_id !== deviceId);
      state.sessions = state.sessions.filter((s) => s.device_id !== deviceId);
      broadcast({ type: 'device.removed', device_id: deviceId });
      json(res, 200, { ok: true });
      return;
    }
  }

  if (path === '/api/sessions') {
    const deviceId = url.searchParams.get('device_id');
    const archived = url.searchParams.get('archived');
    json(res, 200, {
      sessions: state.sessions
        .filter((s) => (deviceId ? s.device_id === deviceId : true))
        .filter((s) => (archived === null ? true : String(s.archived) === archived)),
    });
    return;
  }

  if (path === '/api/push/web/vapid') {
    json(res, 200, { public_key: 'BJ' + 'x'.repeat(85) });
    return;
  }

  if (path === '/api/push/web/subscribe') {
    json(res, 200, { ok: true });
    return;
  }

  json(res, 404, { ok: false, error: { code: 'not_found', message: `no route for ${path}` } });
}

/* ---------------------------------------------------------------- ws/app */

const appWss = new WebSocketServer({ noServer: true });
const sttWss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url ?? '/', `http://127.0.0.1:${PORT}`);
  if (url.pathname === '/ws/app') {
    appWss.handleUpgrade(req, socket, head, (ws) => onAppSocket(ws));
    return;
  }
  if (url.pathname === '/ws/stt') {
    sttWss.handleUpgrade(req, socket, head, (ws) => onSttSocket(ws));
    return;
  }
  socket.destroy();
});

function onAppSocket(socket: WebSocket): void {
  const conn: AppConn = { socket, subscriptions: new Set() };
  conns.add(conn);

  send(socket, {
    type: 'hello',
    protocol: 1,
    gateway_version: GATEWAY_VERSION,
    user: { username: 'admin' },
    devices: state.devices,
    sessions: state.sessions,
    stt: { enabled: true, languages: ['auto', 'zh', 'en'] },
    server_time: Date.now(),
  });

  const ping = setInterval(() => send(socket, { type: 'ping' }), 25_000);

  socket.on('message', (raw) => {
    let frame: Record<string, unknown>;
    try {
      frame = JSON.parse(String(raw)) as Record<string, unknown>;
    } catch {
      return;
    }
    handleAppFrame(conn, frame);
  });

  socket.on('close', () => {
    clearInterval(ping);
    conns.delete(conn);
  });
}

const reply = (conn: AppConn, id: unknown, result: unknown): void =>
  send(conn.socket, { type: 'reply', id, ok: true, result });

const replyError = (conn: AppConn, id: unknown, code: string, message: string): void =>
  send(conn.socket, { type: 'reply', id, ok: false, error: { code, message } });

function handleAppFrame(conn: AppConn, frame: Record<string, unknown>): void {
  const id = frame.id;
  const type = String(frame.type ?? '');
  const sessionId = String(frame.session_id ?? '');

  switch (type) {
    case 'pong':
      return;

    case 'session.subscribe': {
      const session = findSession(sessionId);
      if (!session) return replyError(conn, id, 'not_found', 'no such session');
      conn.subscriptions.add(sessionId);
      const sinceSeq = typeof frame.since_seq === 'number' ? frame.since_seq : undefined;
      const all = state.events.get(sessionId) ?? [];
      const events = sinceSeq === undefined ? [] : all.filter((e) => e.seq > sinceSeq);
      const pending = state.queues.get(sessionId);
      reply(conn, id, {
        session,
        events,
        resync: false,
        // Amendment A6: the latest queue snapshot, omitted when none was seen.
        ...(pending ? { queue: { pending } } : {}),
      });
      if (sessionId === 'ses-flaky' && (session.state === 'running' || session.state === 'starting')) {
        maybeStartDemoTurn();
      }
      return;
    }

    case 'session.unsubscribe':
      conn.subscriptions.delete(sessionId);
      return;

    case 'session.history': {
      const all = state.events.get(sessionId) ?? [];
      const beforeSeq = typeof frame.before_seq === 'number' ? frame.before_seq : Infinity;
      const limit = typeof frame.limit === 'number' ? Math.min(frame.limit, 1000) : 200;
      const final = historyEvents(all, beforeSeq);
      const page = final.slice(Math.max(0, final.length - limit));
      reply(conn, id, { events: page, has_more: page.length < final.length });
      return;
    }

    case 'session.block': {
      const blockId = String(frame.block_id ?? '');
      const all = state.events.get(sessionId) ?? [];
      const latest = [...all].reverse().find((e) => 'block_id' in e && e.block_id === blockId);
      if (!latest) return replyError(conn, id, 'not_found', 'no such block');
      const full: SessionEvent =
        latest.kind === 'tool_call'
          ? {
              ...latest,
              output: `${latest.output ?? ''}\n[full output — ${'-'.repeat(40)}]\n${Array.from({ length: 40 }, (_, i) => `line ${i + 1}`).join('\n')}`,
              output_truncated: false,
            }
          : latest;
      reply(conn, id, { event: full });
      return;
    }

    case 'session.create': {
      const deviceId = String(frame.device_id ?? '');
      const session: Session = {
        session_id: `ses-${randomUUID().slice(0, 8)}`,
        device_id: deviceId,
        agent: String(frame.agent ?? 'claude'),
        title: String(frame.title ?? frame.first_message ?? 'New session').slice(0, 60),
        cwd: String(frame.cwd ?? HOME[deviceId] ?? '/'),
        git: { branch: 'main', dirty: false, ahead: 0, behind: 0, worktree: Boolean(frame.worktree) },
        state: 'idle',
        state_detail: null,
        origin: 'remote',
        control: 'remote',
        model: (frame.model as string) ?? null,
        permission_mode: (frame.permission_mode as string) ?? null,
        effort: (frame.effort as string) ?? null,
        created_at: Date.now(),
        updated_at: Date.now(),
        last_seq: 0,
        archived: false,
        turn: null,
        todos: null,
        usage: null,
        queued: 0,
      };
      state.sessions.push(session);
      state.events.set(session.session_id, []);
      broadcast({ type: 'session.updated', session });
      reply(conn, id, { session });
      if (typeof frame.first_message === 'string' && frame.first_message.length > 0) {
        // No `session.send` behind it, so the device mints the block id here.
        setTimeout(() => {
          const sid = session.session_id;
          play(sid, turnScript(frame.first_message as string), () => drainQueue(sid));
        }, 400);
      }
      return;
    }

    case 'session.send': {
      const session = findSession(sessionId);
      if (!session) return replyError(conn, id, 'not_found', 'no such session');
      // A10 §6.3: a shared session accepts every send; the device decides
      // between injecting now and holding until the terminal turn ends.
      if (session.control === 'shared') return sharedSend(conn, id, session, frame);
      if (session.control === 'terminal') {
        return replyError(conn, id, 'conflict', 'controlled by terminal; take over first');
      }
      const text = String(frame.text ?? '');
      const mode = String(frame.mode ?? 'auto');
      const running = session.state === 'running' || session.state === 'needs_approval';
      if (running && mode !== 'interrupt') {
        // A12: the queue entry keeps the request id all the way to its
        // `user_message`, so the app can correlate the row it already shows.
        const queuedId = String(id);
        const pending = [...(state.queues.get(sessionId) ?? []), { id: queuedId, text, ts: Date.now() }];
        emit(sessionId, { seq: nextSeq(sessionId), ts: Date.now(), kind: 'queue', pending });
        reply(conn, id, { accepted: 'queued', queued_id: queuedId });
        return;
      }
      reply(conn, id, { accepted: 'sent' });
      playRemote(sessionId, text, String(id));
      return;
    }

    case 'session.stop': {
      const session = findSession(sessionId);
      // A10 §6.3: the Claude channel cannot interrupt a running turn.
      if (session?.control === 'shared' && agentFor(session)?.shared_interrupt !== true) {
        return replyError(conn, id, 'unsupported', 'stop it in the terminal');
      }
      interruptTurn(sessionId);
      reply(conn, id, {});
      return;
    }

    case 'session.approve': {
      const requestId = String(frame.request_id ?? '');
      const optionId = String(frame.option_id ?? '');
      const session = findSession(sessionId);
      const shared = session?.control === 'shared';
      const all = state.events.get(sessionId) ?? [];
      const approval = [...all]
        .reverse()
        .find((e) => e.kind === 'approval' && e.request_id === requestId);
      // A11 clarification 2: the app may only send back an option the block
      // offered. A Claude relay offers Allow and Deny; the Codex daemon offers
      // whatever `availableDecisions` held.
      if (
        approval?.kind === 'approval' &&
        !approval.options.some((option) => option.id === optionId)
      ) {
        return replyError(conn, id, 'bad_request', 'that option was not offered');
      }
      if (approval && approval.kind === 'approval') {
        emit(sessionId, {
          ...approval,
          seq: nextSeq(sessionId),
          ts: Date.now(),
          status: 'resolved',
          decision: { option_id: optionId, by: 'remote' },
        });
      }
      reply(conn, id, {});
      if (sessionId === 'ses-flaky') play(sessionId, afterApproval());
      if (shared && session) {
        const allowed = optionId !== 'deny';
        const steps =
          session.agent === 'codex'
            ? codexSharedAfterApproval(
                sharedNonceOf(sessionId),
                sharedTurnIds.get(sessionId) ?? `shared-${sharedNonceOf(sessionId)}`,
                allowed,
              )
            : sharedAfterApproval(sharedNonceOf(sessionId), allowed);
        play(sessionId, steps, () => flushHeld(sessionId));
      }
      return;
    }

    case 'session.answer': {
      const requestId = String(frame.request_id ?? '');
      const all = state.events.get(sessionId) ?? [];
      const question = [...all]
        .reverse()
        .find((e) => e.kind === 'question' && e.request_id === requestId);
      if (question && question.kind === 'question') {
        // A20: the block resolves naming whoever answered it, and the session
        // stops waiting.
        emit(sessionId, {
          ...question,
          seq: nextSeq(sessionId),
          ts: Date.now(),
          status: 'resolved',
          answers: frame.answers as Record<string, string[] | string>,
          by: 'remote',
        });
        if (findSession(sessionId)?.state === 'needs_input') {
          emit(sessionId, { seq: nextSeq(sessionId), ts: Date.now(), kind: 'status', state: 'idle' });
        }
      }
      reply(conn, id, {});
      if (sessionId === 'ses-flaky') play(sessionId, afterAnswer());
      return;
    }

    case 'session.set': {
      const session = findSession(sessionId);
      if (!session) return replyError(conn, id, 'not_found', 'no such session');
      // A10/A11 §6.3: the terminal owns the model, the permission mode and the
      // effort unless the device reports that the attachment forwards them.
      const optionKeys = ['model', 'permission_mode', 'effort'] as const;
      const settingsLocked =
        session.control === 'shared' && agentFor(session)?.shared_settings !== true;
      if (settingsLocked && optionKeys.some((k) => typeof frame[k] === 'string')) {
        return replyError(conn, id, 'unsupported', 'change it in the terminal');
      }
      if (typeof frame.model === 'string') session.model = frame.model;
      if (typeof frame.permission_mode === 'string') session.permission_mode = frame.permission_mode;
      if (typeof frame.effort === 'string') session.effort = frame.effort;
      if (typeof frame.title === 'string') session.title = frame.title;
      session.updated_at = Date.now();
      broadcast({ type: 'session.updated', session });
      reply(conn, id, { session });
      return;
    }

    case 'session.queue_remove': {
      const queuedId = String(frame.queued_id ?? '');
      state.held.set(
        sessionId,
        (state.held.get(sessionId) ?? []).filter((h) => h.queued_id !== queuedId),
      );
      const pending = (state.queues.get(sessionId) ?? []).filter((q) => q.id !== queuedId);
      emit(sessionId, { seq: nextSeq(sessionId), ts: Date.now(), kind: 'queue', pending });
      reply(conn, id, {});
      return;
    }

    case 'session.takeover': {
      const session = findSession(sessionId);
      if (!session) return replyError(conn, id, 'not_found', 'no such session');
      // A10 §6.3: there is nothing to take over — the device is already attached.
      if (session.control === 'shared') return replyError(conn, id, 'conflict', 'already attached');
      session.control = 'remote';
      session.state = 'idle';
      broadcast({ type: 'session.updated', session });
      // A10 §6.5: apps learn a new owner from `meta.control`; `status` follows
      // only because taking over also moves the session to `idle`.
      emit(sessionId, {
        seq: nextSeq(sessionId),
        ts: Date.now(),
        kind: 'meta',
        control: 'remote',
      });
      emit(sessionId, { seq: nextSeq(sessionId), ts: Date.now(), kind: 'status', state: 'idle' });
      emit(sessionId, {
        seq: nextSeq(sessionId),
        ts: Date.now(),
        kind: 'notice',
        level: 'info',
        text: 'You took over this session from the terminal.',
      });
      reply(conn, id, { session });
      return;
    }

    case 'session.archive': {
      const session = findSession(sessionId);
      if (!session) return replyError(conn, id, 'not_found', 'no such session');
      session.archived = Boolean(frame.archived);
      broadcast({ type: 'session.updated', session });
      reply(conn, id, { session });
      return;
    }

    case 'session.delete': {
      state.sessions = state.sessions.filter((s) => s.session_id !== sessionId);
      const removed = findSession(sessionId);
      broadcast({
        type: 'session.removed',
        session_id: sessionId,
        device_id: removed?.device_id ?? '',
      });
      reply(conn, id, {});
      return;
    }

    case 'device.dirs': {
      const deviceId = String(frame.device_id ?? '');
      const home = HOME[deviceId] ?? '/home/user';
      const path = typeof frame.path === 'string' && frame.path.length > 0 ? frame.path : home;
      const entries = dirEntries(path, home);
      if (entries === null) return replyError(conn, id, 'not_found', 'no such directory');
      reply(conn, id, {
        path,
        parent: path === '/' ? null : path.slice(0, path.lastIndexOf('/')) || '/',
        entries,
        recent: recentDirs,
      });
      return;
    }

    case 'device.git': {
      const path = String(frame.path ?? '');
      const isRepo = path.includes('remote-control') || path.includes('work');
      reply(
        conn,
        id,
        isRepo
          ? { is_repo: true, branch: 'main', dirty: path.endsWith('web'), ahead: 0, behind: 0 }
          : { is_repo: false },
      );
      return;
    }

    case 'device.agents': {
      const device = state.devices.find((d) => d.device_id === String(frame.device_id ?? ''));
      if (!device) return replyError(conn, id, 'not_found', 'no such device');
      reply(conn, id, { agents: device.agents });
      return;
    }

    default:
      replyError(conn, id, 'unsupported', `mock has no handler for ${type}`);
  }
}

/**
 * PROTOCOL-FROZEN §8: ascending by `seq`, the latest event per block plus the
 * turn markers, notices, errors and the latest `todos` snapshot. Never streaming
 * deltas, never `status` / `meta` / `queue`.
 */
function historyEvents(all: readonly SessionEvent[], beforeSeq: number): SessionEvent[] {
  const candidates = all
    .filter((e) => e.seq < beforeSeq)
    .filter((e) => !['status', 'meta', 'queue'].includes(e.kind))
    .filter((e) => !('delta' in e && e.delta !== undefined));

  const latestByBlock = new Map<string, SessionEvent>();
  let latestTodos: SessionEvent | undefined;
  const rest: SessionEvent[] = [];
  for (const event of candidates) {
    const blockId = blockIdOf(event);
    if (blockId) latestByBlock.set(blockId, event);
    else if (event.kind === 'todos') latestTodos = event;
    else rest.push(event);
  }

  const out = [...latestByBlock.values(), ...rest];
  if (latestTodos) out.push(latestTodos);
  return out.sort((a, b) => a.seq - b.seq);
}

const TREE: Record<string, string[]> = {
  '': ['dev', 'work', 'Documents'],
  '/dev': ['remote-control', 'scratch'],
  '/dev/remote-control': ['gateway', 'client', 'web', 'ios', 'protocol'],
  '/work': ['api', 'infra'],
};

function dirEntries(path: string, home: string): { name: string; path: string; is_git: boolean }[] | null {
  const relative = path.startsWith(home) ? path.slice(home.length) : null;
  if (relative === null) return path === '/' ? [{ name: 'Users', path: '/Users', is_git: false }] : [];
  const names = TREE[relative];
  if (names === undefined) return relative === '' ? [] : [];
  return names.map((name) => ({
    name,
    path: `${path}/${name}`,
    is_git: relative === '/dev/remote-control' || name === 'remote-control' || name === 'api',
  }));
}

/* --------------------------------------------------- A10/A11 shared sessions */

const sharedNonces = new Map<string, string>();
const sharedTurnIds = new Map<string, string>();
const sharedApprovalShown = new Set<string>();

const sharedNonceOf = (sessionId: string): string => sharedNonces.get(sessionId) ?? '0';

/**
 * `session.send` on a session the device is attached to (A10 §6.3, A11
 * clarification 1). Idle injects at once. On a running turn, `auto` steers
 * when the agent can steer, `interrupt` ends the turn and starts a new one,
 * and anything else is held until the terminal turn finishes.
 */
function sharedSend(
  conn: AppConn,
  id: unknown,
  session: Session,
  frame: Record<string, unknown>,
): void {
  const sessionId = session.session_id;
  const agent = agentFor(session);
  const attachments = Array.isArray(frame.attachments) ? frame.attachments : [];
  // A11 §4.2: the daemon takes image inputs; the Claude channel does not.
  if (attachments.length > 0 && agent?.shared_attachments !== true) {
    return replyError(conn, id, 'unsupported', 'attachments cannot be delivered to a terminal session');
  }
  const text = String(frame.text ?? '');
  const mode = String(frame.mode ?? 'auto');
  // A12: the request id is the block id the app already rendered under.
  const blockId = String(id);
  const busy =
    session.state === 'running' ||
    session.state === 'needs_approval' ||
    session.state === 'needs_input';

  if (!busy) {
    reply(conn, id, { accepted: 'sent' });
    afterEcho(() => {
      emitUserMessage(sessionId, blockId, text);
      playShared(sessionId, text, false);
    });
    return;
  }

  // A11 clarification 1: `auto` on a running thread steers when the agent can.
  if (mode === 'auto' && agent?.capabilities.includes('steer')) {
    reply(conn, id, { accepted: 'steered' });
    afterEcho(() => {
      emitUserMessage(sessionId, blockId, text);
      playShared(sessionId, text, true);
    });
    return;
  }

  // A11 clarification 1: `interrupt` needs the same attachment as `session.stop`.
  if (mode === 'interrupt') {
    if (agent?.shared_interrupt !== true) {
      return replyError(conn, id, 'unsupported', 'stop it in the terminal');
    }
    interruptTurn(sessionId);
    reply(conn, id, { accepted: 'sent' });
    afterEcho(() => {
      emitUserMessage(sessionId, blockId, text);
      playShared(sessionId, text, false);
    });
    return;
  }

  const queuedId = String(id);
  state.held.set(sessionId, [
    ...(state.held.get(sessionId) ?? []),
    { block_id: blockId, text, queued_id: queuedId },
  ]);
  reply(conn, id, { accepted: 'queued', queued_id: queuedId });
  // A19: a held message is a queue entry and nothing else. Its block appears
  // when the CLI takes it, after the output of the turn it waited for.
  afterEcho(() => {
    const pending = [...(state.queues.get(sessionId) ?? []), { id: queuedId, text, ts: Date.now() }];
    emit(sessionId, { seq: nextSeq(sessionId), ts: Date.now(), kind: 'queue', pending });
  });
}

/** What a device costs to answer: the reply is instant, the echo is not. */
function afterEcho(run: () => void): void {
  setTimeout(run, ECHO_DELAY_MS);
}

/** A19: a message an app sent into a shared session, once the CLI took it. */
function emitUserMessage(sessionId: string, blockId: string, text: string): void {
  emit(sessionId, {
    seq: nextSeq(sessionId),
    ts: Date.now(),
    kind: 'user_message',
    block_id: blockId,
    source: 'remote',
    text,
    delivery: 'delivered',
  });
}

function playShared(sessionId: string, text: string, steered: boolean): void {
  const session = findSession(sessionId);
  const nonce = String(Date.now()).slice(-6);
  sharedNonces.set(sessionId, nonce);
  const withApproval = !sharedApprovalShown.has(sessionId);
  sharedApprovalShown.add(sessionId);
  // Steering joins the live turn; anything else opens a new one.
  const turnId = (steered ? session?.turn?.turn_id : null) ?? `shared-${nonce}`;
  sharedTurnIds.set(sessionId, turnId);
  const steps =
    session?.agent === 'codex'
      ? codexSharedTurn(text, nonce, { turnId, steered, withApproval })
      : sharedTurn(text, nonce, withApproval);
  play(sessionId, steps, withApproval ? undefined : () => flushHeld(sessionId));
}

/**
 * The terminal turn ended: inject everything held, replacing each block with
 * `delivery: "delivered"` under its original `block_id`.
 */
function flushHeld(sessionId: string): void {
  const items = state.held.get(sessionId) ?? [];
  if (items.length === 0) return;
  state.held.set(sessionId, []);
  for (const item of items) emitUserMessage(sessionId, item.block_id, item.text);
  emit(sessionId, { seq: nextSeq(sessionId), ts: Date.now(), kind: 'queue', pending: [] });
  const last = items[items.length - 1];
  if (last) setTimeout(() => playShared(sessionId, last.text, false), 500);
}

let demoStarted = false;

function maybeStartDemoTurn(): void {
  if (demoStarted) return;
  demoStarted = true;
  const prompt = 'test_refresh_flow fails ~1 in 5 on CI, never locally. Find the race and fix it.';
  // A device-minted block id, the way a pre-A12 device or a terminal sends one.
  setTimeout(() => play('ses-flaky', turnScript(prompt), () => drainQueue('ses-flaky')), 600);
}

/* ---------------------------------------------------------------- ws/stt */

const PARTIALS = [
  'also add a retry',
  'also add a retry to the token refresh path',
  'also add a retry to the token refresh path and re-run the suite',
];

function onSttSocket(socket: WebSocket): void {
  let index = 0;
  let receivedAudio = false;
  const timer = setInterval(() => {
    if (!receivedAudio) return;
    const text = PARTIALS[Math.min(index, PARTIALS.length - 1)];
    index += 1;
    send(socket, { type: 'stt.partial', text });
  }, 2_000);

  socket.on('message', (raw, isBinary) => {
    if (isBinary) {
      receivedAudio = true;
      return;
    }
    let frame: { type?: string };
    try {
      frame = JSON.parse(String(raw)) as { type?: string };
    } catch {
      return;
    }
    if (frame.type === 'stt.stop') {
      send(socket, {
        type: 'stt.final',
        text: 'also add a retry to the token refresh path and re-run the suite on the CI runner too',
        language: 'en',
      });
      clearInterval(timer);
      setTimeout(() => socket.close(), 50);
    }
    if (frame.type === 'stt.cancel') {
      clearInterval(timer);
      socket.close();
    }
  });

  socket.on('close', () => clearInterval(timer));
}

server.listen(PORT, '127.0.0.1', () => {
  console.log(`mock gateway listening on http://127.0.0.1:${PORT} (password: ${PASSWORD})`);
});
