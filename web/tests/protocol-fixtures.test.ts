/**
 * Decodes every canonical fixture from `protocol/fixtures/` and asserts the
 * shapes this app depends on. A structural failure here means the web client
 * and the frozen contract have drifted apart.
 */
import { describe, expect, it } from 'vitest';
import { PROTOCOL_VERSION } from '../src/protocol/types';
import type {
  AgentInfo,
  Device,
  Session,
  SessionEvent,
  Usage,
} from '../src/protocol/types';
import type { HelloFrame, Reply, SubscribeResult } from '../src/protocol/frames';
import { toolCategory } from '../src/features/chat/blocks/toolCategory';
import { fixturesAvailable, listFixtures, readFixture } from './fixtures';

const EVENT_KINDS = new Set([
  'user_message',
  'assistant_text',
  'thinking',
  'tool_call',
  'todos',
  'approval',
  'question',
  'turn_started',
  'turn_completed',
  'status',
  'meta',
  'queue',
  'notice',
  'error',
]);

const TOOL_KINDS = new Set([
  'shell',
  'read',
  'edit',
  'write',
  'search',
  'web',
  'mcp',
  'subagent',
  'todo',
  'other',
]);

function assertUsage(usage: Usage): void {
  // Amendment A2: only the three token counts are required.
  expect(typeof usage.input_tokens).toBe('number');
  expect(typeof usage.output_tokens).toBe('number');
  expect(typeof usage.total_tokens).toBe('number');
  for (const key of ['context_used', 'context_window', 'cost_usd'] as const) {
    const value = usage[key];
    expect(value === undefined || value === null || typeof value === 'number').toBe(true);
  }
}

function assertAgent(agent: AgentInfo): void {
  expect(typeof agent.agent).toBe('string');
  expect(typeof agent.available).toBe('boolean');
  expect(Array.isArray(agent.models)).toBe(true);
  expect(Array.isArray(agent.permission_modes)).toBe(true);
  expect(Array.isArray(agent.efforts)).toBe(true);
  expect(Array.isArray(agent.capabilities)).toBe(true);
  for (const choice of [...agent.models, ...agent.permission_modes, ...agent.efforts]) {
    expect(typeof choice.id).toBe('string');
    expect(typeof choice.label).toBe('string');
  }
}

function assertDevice(device: Device): void {
  expect(typeof device.device_id).toBe('string');
  expect(['macos', 'linux']).toContain(device.platform);
  expect(typeof device.online).toBe('boolean');
  expect(device.latency_ms === null || typeof device.latency_ms === 'number').toBe(true);
  device.agents.forEach(assertAgent);
}

function assertSession(session: Session): void {
  expect(typeof session.session_id).toBe('string');
  expect(typeof session.device_id).toBe('string');
  expect([
    'starting',
    'idle',
    'running',
    'needs_approval',
    'needs_input',
    'error',
    'stopped',
    'readonly',
  ]).toContain(session.state);
  expect(['remote', 'terminal', 'none']).toContain(session.control);
  expect(['remote', 'terminal']).toContain(session.origin);
  expect(typeof session.last_seq).toBe('number');
  if (session.usage) assertUsage(session.usage);
  if (session.turn) expect(typeof session.turn.turn_id).toBe('string');
}

function assertEvent(event: SessionEvent): void {
  expect(EVENT_KINDS).toContain(event.kind);
  expect(typeof event.seq).toBe('number');
  expect(typeof event.ts).toBe('number');

  switch (event.kind) {
    case 'tool_call':
      expect(typeof event.tool).toBe('string');
      expect(typeof event.title).toBe('string');
      expect(['running', 'succeeded', 'failed', 'cancelled']).toContain(event.status);
      // Amendment A1: the category lives in `tool_kind`, never in `kind`.
      expect(event.tool_kind).toBeDefined();
      expect(TOOL_KINDS).toContain(event.tool_kind);
      expect(toolCategory(event.tool, event.tool_kind)).toBe(event.tool_kind);
      if (event.diff) {
        expect(typeof event.diff.path).toBe('string');
        expect(typeof event.diff.additions).toBe('number');
        expect(typeof event.diff.deletions).toBe('number');
      }
      break;
    case 'approval':
      expect(event.options.length).toBeGreaterThan(0);
      expect(event.options.some((o) => o.style === 'primary')).toBe(true);
      expect(event.options.some((o) => o.style === 'danger')).toBe(true);
      expect(['pending', 'resolved', 'expired']).toContain(event.status);
      break;
    case 'question':
      expect(event.questions.length).toBeGreaterThan(0);
      for (const question of event.questions) {
        expect(typeof question.multi).toBe('boolean');
        expect(typeof question.allow_text).toBe('boolean');
      }
      break;
    case 'user_message':
      expect(['remote', 'terminal', 'queue']).toContain(event.source);
      // Amendment A3: inbound attachments describe size, never bytes.
      for (const attachment of event.attachments ?? []) {
        expect(typeof attachment.size).toBe('number');
        expect(attachment).not.toHaveProperty('data_base64');
      }
      break;
    case 'assistant_text':
    case 'thinking':
      expect(typeof event.done).toBe('boolean');
      expect(event.delta !== undefined || event.text !== undefined).toBe(true);
      break;
    case 'turn_completed':
      expect(['completed', 'interrupted', 'error']).toContain(event.stop_reason);
      if (event.usage) assertUsage(event.usage);
      break;
    case 'todos':
      for (const todo of event.items) {
        expect(['pending', 'in_progress', 'completed']).toContain(todo.status);
      }
      break;
    default:
      break;
  }
}

describe.runIf(fixturesAvailable())('protocol fixtures', () => {
  it('parses every fixture file as JSON', () => {
    const files = listFixtures();
    expect(files.length).toBeGreaterThan(40);
    for (const file of files) {
      expect(() => readFixture(file.name), file.name).not.toThrow();
    }
  });

  it('decodes the hello frame into devices and sessions', () => {
    const hello = readFixture<HelloFrame>('app/hello.json');
    expect(hello.type).toBe('hello');
    expect(hello.protocol).toBe(PROTOCOL_VERSION);
    expect(typeof hello.gateway_version).toBe('string');
    expect(typeof hello.user.username).toBe('string');
    expect(typeof hello.stt.enabled).toBe('boolean');
    hello.devices.forEach(assertDevice);
    hello.sessions.forEach(assertSession);
  });

  it('decodes every event fixture', () => {
    const files = listFixtures('events');
    expect(files.length).toBeGreaterThan(20);
    for (const file of files) {
      assertEvent(readFixture<SessionEvent>(file.name));
    }
  });

  it('decodes the subscribe reply, including the replay tail', () => {
    const reply = readFixture<Reply<SubscribeResult>>('replay/subscribe.reply.json');
    expect(reply.ok).toBe(true);
    if (!reply.ok) return;
    assertSession(reply.result.session);
    expect(typeof reply.result.resync).toBe('boolean');
    reply.result.events.forEach(assertEvent);
  });

  it('decodes the outbound send frame with base64 attachments', () => {
    const send = readFixture<{
      type: string;
      attachments?: { name: string; mime: string; data_base64: string }[];
      mode: string;
    }>('app/session.send.json');
    expect(send.type).toBe('session.send');
    expect(['auto', 'queue', 'interrupt']).toContain(send.mode);
    for (const attachment of send.attachments ?? []) {
      expect(typeof attachment.data_base64).toBe('string');
      expect(attachment).not.toHaveProperty('size');
    }
  });

  it('decodes an error reply into a code the UI knows', () => {
    const reply = readFixture<Reply>('app/reply.error.json');
    expect(reply.ok).toBe(false);
    if (reply.ok) return;
    expect(typeof reply.error.code).toBe('string');
    expect(typeof reply.error.message).toBe('string');
  });

  it('decodes the HTTP responses the app calls on boot', () => {
    const health = readFixture<{ ok: boolean; protocol: number; version: string }>(
      'http/health.response.json',
    );
    expect(health.protocol).toBe(PROTOCOL_VERSION);

    const config = readFixture<{
      public_origin: string;
      stt: { enabled: boolean; languages: string[] };
      push: { web_enabled: boolean; apns_enabled: boolean };
    }>('http/config.response.json');
    expect(typeof config.public_origin).toBe('string');
    expect(Array.isArray(config.stt.languages)).toBe(true);
    expect(typeof config.push.web_enabled).toBe('boolean');

    const devices = readFixture<{ devices: Device[] }>('http/devices.list.response.json');
    devices.devices.forEach(assertDevice);

    const sessions = readFixture<{ sessions: Session[] }>('http/sessions.list.response.json');
    sessions.sessions.forEach(assertSession);

    const pairing = readFixture<{
      code: string;
      expires_at: number;
      install: { macos: string; linux: string };
    }>('http/devices.pairing.response.json');
    expect(pairing.code).toMatch(/^RC-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/);
    expect(pairing.install.macos).toContain(pairing.code);
    expect(pairing.install.linux).toContain(pairing.code);
  });

  it('decodes the push payload the service worker renders', () => {
    const payload = readFixture<{
      rc: { v: number; kind: string; device_id: string; session_id: string; device_name: string };
    }>('http/push.payload.json');
    expect(payload.rc.v).toBe(1);
    expect(['needs_approval', 'needs_input', 'turn_completed', 'error']).toContain(payload.rc.kind);
    expect(typeof payload.rc.device_id).toBe('string');
    expect(typeof payload.rc.session_id).toBe('string');
  });

  it('decodes the STT socket frames', () => {
    const partial = readFixture<{ type: string; text: string }>('stt/stt.partial.json');
    expect(partial.type).toBe('stt.partial');
    const final = readFixture<{ type: string; text: string; language: string }>('stt/stt.final.json');
    expect(final.type).toBe('stt.final');
    expect(typeof final.language).toBe('string');
    expect(readFixture<{ type: string }>('stt/stt.stop.json').type).toBe('stt.stop');
    expect(readFixture<{ type: string }>('stt/stt.cancel.json').type).toBe('stt.cancel');
  });
});
