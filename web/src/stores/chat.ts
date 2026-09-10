/**
 * Per-session chat state: timeline, todos, queue, usage and the request actions
 * a session view needs. Live frames arrive through `ingestEvent`.
 *
 * State-only events (`todos`, `queue`, `status`, `meta`, `turn_*`) never become
 * timeline rows, so they are folded separately — for live frames, for the
 * buffered events in a `session.subscribe` reply, and for the newest history
 * page (PROTOCOL-FROZEN.md §8 and amendment A6).
 */
import { create } from 'zustand';
import { requestId } from '../lib/ids';
import { rpc, getSocket } from '../lib/gateway';
import { RequestError } from '../lib/ws';
import type { SendMode } from '../protocol/frames';
import type {
  OutgoingAttachment,
  QuestionAnswers,
  QueuedMessage,
  Session,
  SessionEvent,
  TodoItem,
  Usage,
} from '../protocol/types';
import {
  applyEvent,
  applyEvents,
  emptyTimeline,
  mergeHistory,
  replaceBlock,
  type TimelineState,
} from './timeline';
import { useSessions, sessionKey } from './sessions';
import { useOutbox } from './outbox';

const HISTORY_PAGE = 200;

export interface ChatSession {
  key: string;
  deviceId: string;
  sessionId: string;
  timeline: TimelineState;
  todos: TodoItem[];
  queue: QueuedMessage[];
  usage: Usage | null;
  ready: boolean;
  historyLoading: boolean;
  historyHasMore: boolean;
  error: string | null;
}

function blank(deviceId: string, sessionId: string): ChatSession {
  return {
    key: sessionKey(deviceId, sessionId),
    deviceId,
    sessionId,
    timeline: emptyTimeline(),
    todos: [],
    queue: [],
    usage: null,
    ready: false,
    historyLoading: false,
    historyHasMore: true,
    error: null,
  };
}

interface ChatState {
  sessions: Record<string, ChatSession>;
  open: (deviceId: string, sessionId: string) => void;
  close: (deviceId: string, sessionId: string) => void;
  ingestEvent: (sessionId: string, event: SessionEvent, deviceId?: string) => void;
  loadOlder: (key: string) => Promise<void>;
  expandBlock: (key: string, blockId: string) => Promise<void>;
  send: (
    key: string,
    input: { text: string; attachments?: OutgoingAttachment[]; mode: SendMode },
  ) => Promise<void>;
  retrySend: (id: string) => Promise<void>;
  stop: (key: string) => Promise<void>;
  approve: (key: string, requestId: string, optionId: string) => Promise<void>;
  answer: (key: string, requestId: string, answers: QuestionAnswers) => Promise<void>;
  removeQueued: (key: string, queuedId: string) => Promise<void>;
}

function patch(
  set: (fn: (s: ChatState) => Partial<ChatState>) => void,
  key: string,
  update: (chat: ChatSession) => ChatSession,
): void {
  set((s) => {
    const chat = s.sessions[key];
    if (!chat) return {};
    return { sessions: { ...s.sessions, [key]: update(chat) } };
  });
}

/** Fold one state-only event into the chat's own side state. Pure. */
export function foldChat(chat: ChatSession, event: SessionEvent): ChatSession {
  switch (event.kind) {
    case 'todos':
      return { ...chat, todos: event.items };
    case 'queue':
      return { ...chat, queue: event.pending };
    case 'turn_completed':
      return event.usage ? { ...chat, usage: event.usage } : chat;
    default:
      return chat;
  }
}

/** Fold state-only events into a single Session summary patch. Pure. */
export function foldSession(session: Session, events: readonly SessionEvent[]): Session {
  let next = session;
  for (const event of events) {
    switch (event.kind) {
      case 'todos':
        next = {
          ...next,
          todos: {
            total: event.items.length,
            done: event.items.filter((i) => i.status === 'completed').length,
          },
        };
        break;
      case 'queue':
        next = { ...next, queued: event.pending.length };
        break;
      case 'status':
        next = { ...next, state: event.state, state_detail: event.detail ?? null };
        break;
      case 'meta':
        next = {
          ...next,
          ...(event.title !== undefined ? { title: event.title } : {}),
          ...(event.model !== undefined ? { model: event.model } : {}),
          ...(event.permission_mode !== undefined
            ? { permission_mode: event.permission_mode }
            : {}),
          ...(event.effort !== undefined ? { effort: event.effort } : {}),
          ...(event.cwd !== undefined ? { cwd: event.cwd } : {}),
          ...(event.git !== undefined ? { git: event.git } : {}),
          ...(event.control !== undefined ? { control: event.control } : {}),
        };
        break;
      case 'turn_started':
        next = { ...next, turn: { turn_id: event.turn_id, started_at: event.ts } };
        break;
      case 'turn_completed':
        next = { ...next, turn: null, ...(event.usage ? { usage: event.usage } : {}) };
        break;
      default:
        break;
    }
  }
  return next;
}

/** Apply the session-summary side of a batch of events with a single store write. */
function upsertSessionFrom(key: string, events: readonly SessionEvent[]): void {
  const sessions = useSessions.getState();
  const session = sessions.sessions[key];
  if (!session) return;
  const next = foldSession(session, events);
  if (next !== session) sessions.upsert(next);
}

export const useChat = create<ChatState>((set, get) => ({
  sessions: {},

  open: (deviceId, sessionId) => {
    const key = sessionKey(deviceId, sessionId);
    if (!get().sessions[key]) {
      set((s) => ({ sessions: { ...s.sessions, [key]: blank(deviceId, sessionId) } }));
    }
    const socket = getSocket();
    if (!socket) return;
    const existing = get().sessions[key];
    socket.subscribe(sessionId, existing?.timeline.lastSeq || undefined, {
      onResult: (result) => {
        useSessions.getState().upsert(result.session);
        patch(set, key, (chat) => {
          const base = result.resync ? blank(deviceId, sessionId) : chat;
          // Buffered events carry todos and queue snapshots the timeline drops.
          const folded = result.events.reduce(foldChat, base);
          return {
            ...folded,
            timeline: applyEvents(folded.timeline, result.events),
            // Amendment A6: an explicit queue snapshot wins over replayed events.
            queue: result.queue ? result.queue.pending : folded.queue,
            usage: result.session.usage ?? folded.usage,
            ready: true,
            error: null,
          };
        });
        upsertSessionFrom(key, result.events);
        const chat = get().sessions[key];
        const needsHistory =
          result.resync || (chat !== undefined && chat.timeline.order.length === 0);
        if (needsHistory) void get().loadOlder(key);
      },
      onError: (err) => {
        patch(set, key, (chat) => ({
          ...chat,
          ready: true,
          error: err instanceof Error ? err.message : 'subscribe failed',
        }));
      },
    });
  },

  close: (deviceId, sessionId) => {
    getSocket()?.unsubscribe(sessionId);
    void deviceId;
  },

  ingestEvent: (sessionId, event, deviceId) => {
    const state = get();
    // `session_id` is globally unique (amendment A5); `device_id` only narrows
    // the lookup when the gateway supplied it.
    const entry = deviceId
      ? (state.sessions[sessionKey(deviceId, sessionId)] ??
        Object.values(state.sessions).find((c) => c.sessionId === sessionId))
      : Object.values(state.sessions).find((c) => c.sessionId === sessionId);
    if (!entry) return;
    const key = entry.key;
    if (event.seq <= entry.timeline.lastSeq) return;
    getSocket()?.updateCursor(sessionId, event.seq);
    patch(set, key, (chat) => {
      const folded = foldChat(chat, event);
      return { ...folded, timeline: applyEvent(folded.timeline, event) };
    });
    upsertSessionFrom(key, [event]);
  },

  loadOlder: async (key) => {
    const chat = get().sessions[key];
    if (!chat || chat.historyLoading || !chat.historyHasMore) return;
    // The first page is the newest one, so its todos snapshot is current;
    // older pages must never roll the current snapshot back.
    const isNewestPage = chat.timeline.oldestSeq === null;
    patch(set, key, (c) => ({ ...c, historyLoading: true }));
    try {
      const params: { session_id: string; before_seq?: number; limit: number } = {
        session_id: chat.sessionId,
        limit: HISTORY_PAGE,
      };
      if (chat.timeline.oldestSeq !== null) params.before_seq = chat.timeline.oldestSeq;
      const result = await rpc('session.history', params);
      patch(set, key, (c) => {
        const folded = isNewestPage ? result.events.reduce(foldChat, c) : c;
        return {
          ...folded,
          timeline: mergeHistory(folded.timeline, result.events),
          historyLoading: false,
          historyHasMore: result.has_more,
        };
      });
      if (isNewestPage) upsertSessionFrom(key, result.events);
    } catch (err) {
      patch(set, key, (c) => ({
        ...c,
        historyLoading: false,
        error: err instanceof Error ? err.message : 'history failed',
      }));
    }
  },

  expandBlock: async (key, blockId) => {
    const chat = get().sessions[key];
    if (!chat) return;
    const result = await rpc('session.block', { session_id: chat.sessionId, block_id: blockId });
    patch(set, key, (c) => ({ ...c, timeline: replaceBlock(c.timeline, result.event) }));
  },

  send: async (key, input) => {
    const chat = get().sessions[key];
    if (!chat) return;
    const id = requestId();
    const attachments = input.attachments ?? [];
    useOutbox.getState().add({
      id,
      sessionKey: key,
      sessionId: chat.sessionId,
      text: input.text,
      attachments,
      mode: input.mode,
      at: Date.now(),
      error: null,
    });
    await deliver(id, chat.sessionId, input.text, attachments, input.mode);
  },

  retrySend: async (id) => {
    const entry = useOutbox.getState().pending[id];
    if (!entry) return;
    useOutbox.getState().add({ ...entry, error: null });
    await deliver(id, entry.sessionId, entry.text, entry.attachments, entry.mode);
  },

  stop: async (key) => {
    const chat = get().sessions[key];
    if (!chat) return;
    await rpc('session.stop', { session_id: chat.sessionId });
  },

  approve: async (key, request_id, option_id) => {
    const chat = get().sessions[key];
    if (!chat) return;
    await rpc('session.approve', { session_id: chat.sessionId, request_id, option_id });
  },

  answer: async (key, request_id, answers) => {
    const chat = get().sessions[key];
    if (!chat) return;
    await rpc('session.answer', { session_id: chat.sessionId, request_id, answers });
  },

  removeQueued: async (key, queued_id) => {
    const chat = get().sessions[key];
    if (!chat) return;
    await rpc('session.queue_remove', { session_id: chat.sessionId, queued_id });
  },
}));

/** Error codes that mean the gateway definitely rejected the message. */
const DEFINITE_FAILURES = new Set([
  'bad_request',
  'unauthorized',
  'forbidden',
  'not_found',
  'conflict',
  'agent_unavailable',
  'unsupported',
  'too_large',
  'device_offline',
]);

async function deliver(
  id: string,
  sessionId: string,
  text: string,
  attachments: OutgoingAttachment[],
  mode: SendMode,
): Promise<void> {
  const outbox = useOutbox.getState();
  try {
    await rpc(
      'session.send',
      {
        session_id: sessionId,
        text,
        mode,
        ...(attachments.length > 0 ? { attachments } : {}),
      },
      { id },
    );
    outbox.clear(id);
  } catch (err) {
    const code = err instanceof RequestError ? err.code : 'internal';
    const message = err instanceof Error ? err.message : 'send failed';
    if (DEFINITE_FAILURES.has(code)) {
      outbox.clear(id);
      throw err;
    }
    // Uncertain delivery: never auto-resend, let the user retry with the same id.
    outbox.fail(id, message);
  }
}

export const chatOf = (key: string) => (s: ChatState): ChatSession | undefined => s.sessions[key];
