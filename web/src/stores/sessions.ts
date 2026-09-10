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

export function countWaiting(sessions: Session[]): number {
  return sessions.filter((s) => ATTENTION_STATES.has(s.state)).length;
}
