/** App <-> gateway WebSocket frames (PROTOCOL-FROZEN.md §5). */
import type {
  AgentId,
  AgentInfo,
  Device,
  GitInfo,
  OutgoingAttachment,
  QuestionAnswers,
  QueuedMessage,
  Session,
  SessionEvent,
  WireError,
} from './types';

export interface ReplyOk<T> {
  type: 'reply';
  id: string;
  ok: true;
  result: T;
}
export interface ReplyErr {
  type: 'reply';
  id: string;
  ok: false;
  error: WireError;
}
export type Reply<T = unknown> = ReplyOk<T> | ReplyErr;

export interface HelloFrame {
  type: 'hello';
  protocol: number;
  gateway_version: string;
  user: { username: string };
  devices: Device[];
  sessions: Session[];
  stt: { enabled: boolean; languages: string[] };
  server_time: number;
}

export type PairingStep = 'waiting' | 'enrolled' | 'online' | 'agents';

export type PushFrame =
  | HelloFrame
  | { type: 'device.updated'; device: Device }
  | { type: 'device.removed'; device_id: string }
  | { type: 'session.updated'; session: Session }
  | { type: 'session.removed'; session_id: string; device_id: string }
  // Amendment A5: the gateway stamps `device_id` on forwarded session frames.
  // `session_id` stays the primary key; `device_id` is routing information.
  | { type: 'session.event'; session_id: string; device_id?: string; event: SessionEvent }
  | { type: 'pairing.progress'; code: string; step: PairingStep; device?: Device }
  | { type: 'ping' };

export type ServerFrame = PushFrame | Reply;

/* ------------------------------------------------------------- requests */

export interface SubscribeResult {
  session: Session;
  events: SessionEvent[];
  resync: boolean;
  /** Amendment A6: the latest queue snapshot, absent when the gateway saw none. */
  queue?: { pending: QueuedMessage[] };
}

export interface CreateSessionParams {
  device_id: string;
  agent: AgentId;
  cwd: string;
  model?: string;
  permission_mode?: string;
  effort?: string;
  /** A21: a tier id from `AgentInfo.speeds`; omitted for the standard speed. */
  speed?: string;
  worktree?: boolean;
  first_message?: string;
  title?: string;
}

export type SendMode = 'auto' | 'queue' | 'interrupt';

export interface SendParams {
  session_id: string;
  text: string;
  attachments?: OutgoingAttachment[];
  mode: SendMode;
}

export interface SendResult {
  accepted: 'sent' | 'queued' | 'steered';
  queued_id?: string;
}

export interface HistoryResult {
  events: SessionEvent[];
  has_more: boolean;
}

export interface DirEntry {
  name: string;
  path: string;
  is_git: boolean;
}

export interface DirsResult {
  path: string;
  parent: string | null;
  entries: DirEntry[];
  recent: { path: string; last_used: number }[];
}

export interface GitResult {
  is_repo: boolean;
  branch?: string;
  dirty?: boolean;
  ahead?: number;
  behind?: number;
}

export interface AgentsResult {
  agents: AgentInfo[];
}

export interface SessionResult {
  session: Session;
}

export interface BlockResult {
  event: SessionEvent;
}

/** A22: the device took the update and names the build it is leaving. */
export interface UpdateAcceptedResult {
  accepted: boolean;
  from?: string | null;
}

/** Request type -> (params, result) mapping used by the typed socket client. */
export interface RequestMap {
  'session.subscribe': [{ session_id: string; since_seq?: number }, SubscribeResult];
  'session.create': [CreateSessionParams, SessionResult];
  'session.send': [SendParams, SendResult];
  'session.stop': [{ session_id: string }, Record<string, never>];
  'session.approve': [
    { session_id: string; request_id: string; option_id: string; message?: string },
    Record<string, never>,
  ];
  'session.answer': [
    { session_id: string; request_id: string; answers: QuestionAnswers },
    Record<string, never>,
  ];
  'session.set': [
    {
      session_id: string;
      model?: string;
      permission_mode?: string;
      effort?: string;
      /** A21: a tier id from `AgentInfo.speeds`, or null for the standard speed. */
      speed?: string | null;
      title?: string;
    },
    SessionResult,
  ];
  'session.history': [
    { session_id: string; before_seq?: number; limit?: number },
    HistoryResult,
  ];
  'session.block': [{ session_id: string; block_id: string }, BlockResult];
  'session.queue_remove': [{ session_id: string; queued_id: string }, Record<string, never>];
  'session.takeover': [{ session_id: string }, SessionResult];
  'session.archive': [{ session_id: string; archived: boolean }, SessionResult];
  'session.delete': [{ session_id: string }, Record<string, never>];
  'device.dirs': [{ device_id: string; path?: string }, DirsResult];
  'device.git': [{ device_id: string; path: string }, GitResult];
  'device.agents': [{ device_id: string }, AgentsResult];
  /** A22: bring the device to the build the gateway serves. */
  'device.update': [{ device_id: string; build: string }, UpdateAcceptedResult];
}

export type RequestType = keyof RequestMap;
export type RequestParams<T extends RequestType> = RequestMap[T][0];
export type RequestResult<T extends RequestType> = RequestMap[T][1];

export interface GitProbe extends GitResult {
  path: string;
}
export type { GitInfo };
