/**
 * The Active / Archive rule every session list follows. Both the Sessions page
 * and the chat sidebar read it from this one selector, so it is tested here
 * rather than through either list.
 */
import { describe, expect, it } from 'vitest';
import { keyOf, selectSessionSections, type SessionSections } from '../src/stores/sessions';
import type { ControlOwner, Session, SessionState } from '../src/protocol/types';

interface Part {
  id: string;
  device?: string;
  state?: SessionState;
  control?: ControlOwner;
  archived?: boolean;
  minutesAgo?: number;
  title?: string;
  cwd?: string;
}

function session(part: Part): Session {
  return {
    session_id: part.id,
    device_id: part.device ?? 'dev-a',
    title: part.title ?? part.id,
    cwd: part.cwd ?? '/work/api',
    agent: 'claude',
    git: null,
    state: part.state ?? 'idle',
    state_detail: null,
    origin: 'remote',
    control: part.control ?? 'remote',
    model: null,
    permission_mode: null,
    effort: null,
    created_at: 0,
    updated_at: -(part.minutesAgo ?? 0) * 60_000,
    last_seq: 0,
    archived: part.archived ?? false,
    turn: null,
    todos: null,
    usage: null,
    queued: 0,
  };
}

const index = (list: Session[]): Record<string, Session> =>
  Object.fromEntries(list.map((s) => [keyOf(s), s]));

const ids = (list: Session[]): string[] => list.map((s) => s.session_id);

/** Session ids in one device's Active group, addressed by device rather than position. */
const group = (sections: SessionSections, deviceId = 'dev-a'): string[] =>
  ids(sections.active.find((g) => g.deviceId === deviceId)?.sessions ?? []);

describe('selectSessionSections', () => {
  it('keeps the sessions a CLI or the device still holds in Active', () => {
    const sessions = index([
      session({ id: 'remote', control: 'remote' }),
      session({ id: 'terminal', control: 'terminal' }),
      session({ id: 'shared', control: 'shared' }),
    ]);

    const sections = selectSessionSections(sessions, { deviceIds: ['dev-a'] });

    expect(group(sections).sort()).toEqual(['remote', 'shared', 'terminal']);
    expect(sections.archive).toEqual([]);
  });

  it('moves a session whose CLI exited into the Archive', () => {
    const sessions = index([
      session({ id: 'live', control: 'remote' }),
      session({ id: 'gone', control: 'none', state: 'stopped' }),
    ]);

    const sections = selectSessionSections(sessions, { deviceIds: ['dev-a'] });

    expect(group(sections)).toEqual(['live']);
    expect(ids(sections.archive)).toEqual(['gone']);
  });

  it('lists a manually archived session only while archived ones are shown', () => {
    const sessions = index([
      session({ id: 'live', control: 'remote' }),
      session({ id: 'filed', control: 'remote', archived: true }),
    ]);

    const hidden = selectSessionSections(sessions, { deviceIds: ['dev-a'] });
    expect(ids(hidden.archive)).toEqual([]);
    expect(group(hidden)).toEqual(['live']);
    expect(hidden.count).toBe(1);

    const shown = selectSessionSections(sessions, {
      deviceIds: ['dev-a'],
      includeArchived: true,
    });
    expect(ids(shown.archive)).toEqual(['filed']);
    expect(group(shown)).toEqual(['live']);
    expect(shown.count).toBe(2);
  });

  it('orders a device by attention, then a running turn, then last activity', () => {
    const sessions = index([
      session({ id: 'idle-new', state: 'idle', minutesAgo: 1 }),
      session({ id: 'running-old', state: 'running', minutesAgo: 30 }),
      session({ id: 'starting-new', state: 'starting', minutesAgo: 2 }),
      session({ id: 'approval-old', state: 'needs_approval', minutesAgo: 90 }),
      session({ id: 'input-new', state: 'needs_input', minutesAgo: 5 }),
      session({ id: 'stopped-new', state: 'stopped', minutesAgo: 0 }),
    ]);

    const sections = selectSessionSections(sessions, { deviceIds: ['dev-a'] });

    expect(group(sections)).toEqual([
      'input-new',
      'approval-old',
      'starting-new',
      'running-old',
      'stopped-new',
      'idle-new',
    ]);
  });

  it('orders the Archive by last activity across every device', () => {
    const sessions = index([
      session({ id: 'old', device: 'dev-a', control: 'none', minutesAgo: 120 }),
      session({ id: 'new', device: 'dev-b', control: 'none', minutesAgo: 3 }),
      session({ id: 'mid', device: 'dev-a', control: 'none', minutesAgo: 40 }),
    ]);

    const { archive } = selectSessionSections(sessions, { deviceIds: ['dev-a', 'dev-b'] });

    expect(ids(archive)).toEqual(['new', 'mid', 'old']);
  });

  it('keeps a device with nothing open, in the order the devices were given', () => {
    const sessions = index([session({ id: 'only', device: 'dev-b' })]);

    const sections = selectSessionSections(sessions, { deviceIds: ['dev-a', 'dev-b'] });

    expect(sections.active.map((g) => g.deviceId)).toEqual(['dev-a', 'dev-b']);
    expect(group(sections, 'dev-a')).toEqual([]);
    expect(group(sections, 'dev-b')).toEqual(['only']);
  });

  it('groups a session whose device is not in the list under its own device', () => {
    const sessions = index([session({ id: 'orphan', device: 'dev-gone' })]);

    const sections = selectSessionSections(sessions, { deviceIds: ['dev-a'] });

    expect(sections.active.map((g) => g.deviceId)).toEqual(['dev-a', 'dev-gone']);
  });

  it('restricts both sections to one device', () => {
    const sessions = index([
      session({ id: 'a-live', device: 'dev-a' }),
      session({ id: 'a-gone', device: 'dev-a', control: 'none' }),
      session({ id: 'b-live', device: 'dev-b' }),
      session({ id: 'b-gone', device: 'dev-b', control: 'none' }),
    ]);

    const sections = selectSessionSections(sessions, {
      deviceId: 'dev-a',
      deviceIds: ['dev-a', 'dev-b'],
    });

    expect(sections.active.map((g) => g.deviceId)).toEqual(['dev-a']);
    expect(group(sections)).toEqual(['a-live']);
    expect(ids(sections.archive)).toEqual(['a-gone']);
    expect(sections.count).toBe(2);
  });

  it('searches the title, the working directory and the device name', () => {
    const sessions = index([
      session({ id: 'one', title: 'Fix flaky auth test', cwd: '/work/gateway' }),
      session({ id: 'two', title: 'Add traces', cwd: '/work/ingest' }),
      session({ id: 'three', device: 'dev-b', title: 'Nightly sweep', cwd: '/work/api' }),
    ]);
    const options = {
      deviceIds: ['dev-a', 'dev-b'],
      deviceNames: { 'dev-a': 'mac-studio', 'dev-b': 'ci-runner' },
    };

    const byTitle = selectSessionSections(sessions, { ...options, query: 'FLAKY' });
    expect(group(byTitle)).toEqual(['one']);
    expect(byTitle.count).toBe(1);

    const byPath = selectSessionSections(sessions, { ...options, query: 'ingest' });
    expect(group(byPath)).toEqual(['two']);

    const byDevice = selectSessionSections(sessions, { ...options, query: 'ci-runner' });
    expect(group(byDevice, 'dev-b')).toEqual(['three']);
    expect(group(byDevice, 'dev-a')).toEqual([]);
    expect(byDevice.count).toBe(1);
  });

  it('searches inside the Archive too, so a list can open it on a match', () => {
    const sessions = index([
      session({ id: 'live', title: 'Fix the ingest regression' }),
      session({ id: 'gone', control: 'none', title: 'Ingest docs rewrite' }),
    ]);

    const sections = selectSessionSections(sessions, {
      deviceIds: ['dev-a'],
      query: 'docs',
    });

    expect(group(sections)).toEqual([]);
    expect(ids(sections.archive)).toEqual(['gone']);
  });
});
