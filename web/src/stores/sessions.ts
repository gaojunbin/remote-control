/** Gateway session index: the list rendered by Sessions and the chat sidebar. */
import { create } from 'zustand';
import { api } from '../lib/api';
import { rpc } from '../lib/gateway';
import type { CreateSessionParams } from '../protocol/frames';
import type { Session } from '../protocol/types';

export type SessionKey = string;

export const sessionKey = (deviceId: string, sessionId: string): SessionKey =>
  `${deviceId}/${sessionId}`;

export const keyOf = (session: Session): SessionKey =>
  sessionKey(session.device_id, session.session_id);

interface SessionsState {
  sessions: Record<SessionKey, Session>;
  loaded: boolean;
  load: () => Promise<void>;
  replaceAll: (sessions: Session[]) => void;
  upsert: (session: Session) => void;
  remove: (deviceId: string, sessionId: string) => void;
  create: (params: CreateSessionParams) => Promise<Session>;
  setArchived: (session: Session, archived: boolean) => Promise<void>;
  takeover: (session: Session) => Promise<Session>;
}

export const useSessions = create<SessionsState>((set, get) => ({
  sessions: {},
  loaded: false,

  load: async () => {
    const { sessions } = await api.sessions();
    get().replaceAll(sessions);
  },

  replaceAll: (sessions) =>
    set(() => {
      const next: Record<SessionKey, Session> = {};
      for (const s of sessions) next[keyOf(s)] = s;
      return { sessions: next, loaded: true };
    }),

  upsert: (session) =>
    set((s) => ({ sessions: { ...s.sessions, [keyOf(session)]: session } })),

  remove: (deviceId, sessionId) =>
    set((s) => {
      const next = { ...s.sessions };
      delete next[sessionKey(deviceId, sessionId)];
      return { sessions: next };
    }),

  create: async (params) => {
    const { session } = await rpc('session.create', params);
    get().upsert(session);
    return session;
  },

  setArchived: async (session, archived) => {
    const result = await rpc('session.archive', { session_id: session.session_id, archived });
    get().upsert(result.session);
  },

  takeover: async (session) => {
    const result = await rpc('session.takeover', { session_id: session.session_id });
    get().upsert(result.session);
    return result.session;
  },
}));

/** Sessions sorted newest-activity-first, archived hidden unless asked for. */
export function selectSessionList(
  sessions: Record<SessionKey, Session>,
  options: { includeArchived?: boolean; deviceId?: string } = {},
): Session[] {
  return Object.values(sessions)
    .filter((s) => (options.includeArchived ? true : !s.archived))
    .filter((s) => (options.deviceId ? s.device_id === options.deviceId : true))
    .sort((a, b) => b.updated_at - a.updated_at);
}

const ATTENTION_STATES = new Set(['needs_approval', 'needs_input']);
const BUSY_STATES = new Set(['running', 'starting']);

export function countWaiting(sessions: Session[]): number {
  return sessions.filter((s) => ATTENTION_STATES.has(s.state)).length;
}

/**
 * Active is what a CLI or the device still holds: `control` is `remote`,
 * `terminal` or `shared`. `control: "none"` means the CLI exited and nothing
 * owns the session any more, so it belongs to the Archive with the sessions the
 * user archived by hand.
 */
export function isActiveSession(session: Session): boolean {
  return !session.archived && session.control !== 'none';
}

/** Attention first, then a running turn, then the rest. Lower sorts earlier. */
function activityRank(session: Session): number {
  if (ATTENTION_STATES.has(session.state)) return 0;
  if (BUSY_STATES.has(session.state)) return 1;
  return 2;
}

/** One device's active sessions. `sessions` is empty for a quiet device. */
export interface SessionGroup {
  deviceId: string;
  sessions: Session[];
}

/** What every session list renders: Active by device, then one Archive group. */
export interface SessionSections {
  active: SessionGroup[];
  archive: Session[];
  /** Rows in both sections together, so a caller can spot an empty list. */
  count: number;
}

export interface SessionSectionsOptions {
  /** Whether manually archived sessions are listed at all. */
  includeArchived?: boolean;
  /** Restrict the whole list to one device. */
  deviceId?: string | null;
  /** Devices to show even when they hold no active session, in list order. */
  deviceIds?: string[];
  /** Free-text filter over title, working directory and device name. */
  query?: string;
  /** Device names, so the search can match one. */
  deviceNames?: Record<string, string>;
}

/**
 * The one grouping rule both session lists follow: Active grouped by device,
 * ordered by attention then activity, and a single Archive group at the bottom
 * ordered by last activity, with the device carried in the row instead.
 */
export function selectSessionSections(
  sessions: Record<SessionKey, Session>,
  options: SessionSectionsOptions = {},
): SessionSections {
  const { includeArchived = false, deviceId = null, deviceIds = [], deviceNames = {} } = options;

  const needle = (options.query ?? '').trim().toLowerCase();
  const matches = (session: Session): boolean => {
    if (!needle) return true;
    const name = deviceNames[session.device_id] ?? '';
    return `${session.title} ${session.cwd} ${name}`.toLowerCase().includes(needle);
  };

  const visible = selectSessionList(sessions, {
    includeArchived,
    ...(deviceId ? { deviceId } : {}),
  }).filter(matches);

  const byDevice = new Map<string, Session[]>();
  for (const id of deviceIds) {
    if (!deviceId || deviceId === id) byDevice.set(id, []);
  }

  const archive: Session[] = [];
  for (const session of visible) {
    if (!isActiveSession(session)) {
      archive.push(session);
      continue;
    }
    const list = byDevice.get(session.device_id);
    if (list) list.push(session);
    else byDevice.set(session.device_id, [session]);
  }

  const active = [...byDevice.entries()].map(([id, list]) => ({
    deviceId: id,
    sessions: list.sort((a, b) => activityRank(a) - activityRank(b) || b.updated_at - a.updated_at),
  }));

  return { active, archive, count: visible.length };
}
