/**
 * Wire protocol v1 types. Mirrors PROTOCOL-FROZEN.md exactly.
 * Unknown fields must be ignored, so every object type is open at the edges
 * only where the contract allows extension (agent ids, tool kinds, capabilities).
 */

export const PROTOCOL_VERSION = 1;

export type AgentId = 'claude' | 'codex' | (string & {});

export type Platform = 'macos' | 'linux';

export type ErrorCode =
  | 'bad_request'
  | 'unauthorized'
  | 'forbidden'
  | 'not_found'
  | 'device_offline'
  | 'agent_unavailable'
  | 'conflict'
  | 'timeout'
  | 'internal'
  | 'unsupported'
  | 'too_large';

export interface WireError {
  code: ErrorCode | string;
  message: string;
}

export interface Choice {
  id: string;
  label: string;
}

export type Capability =
  | 'worktree'
  | 'takeover'
  | 'interrupt'
  | 'queue'
  | 'steer'
  | 'attachments'
  | 'effort'
  | 'history'
  | (string & {});

/**
 * How a terminal-started session can be attached (amendment A10).
 * `channel` is the Claude channel shim, `daemon` the Codex shared app-server.
 */
export type AttachMode = 'channel' | 'daemon';

export interface AgentInfo {
  agent: AgentId;
  available: boolean;
  version: string | null;
  path: string | null;
  models: Choice[];
  default_model: string | null;
  permission_modes: Choice[];
  default_permission_mode: string | null;
  efforts: Choice[];
  default_effort: string | null;
  capabilities: Capability[];
  /** Amendment A10: how this agent's terminal sessions can be attached. */
  attach?: AttachMode | null;
  /** A10: whether the device is prepared to attach. Hints on `terminal` only. */
  attach_ready?: boolean;
  /** A10: whether `session.stop` works on `shared` sessions. Defaults to false. */
  shared_interrupt?: boolean;
  /**
   * A11: whether `session.set` for `model`, `permission_mode` and `effort`
   * works on `shared` sessions. Defaults to false.
   */
  shared_settings?: boolean;
  /**
   * A11: whether `session.send.attachments` are delivered on `shared`
   * sessions. Defaults to false.
   */
  shared_attachments?: boolean;
}

export interface Device {
  device_id: string;
  name: string;
  platform: Platform;
  hostname: string;
  arch: string;
  client_version: string;
  online: boolean;
  last_seen: number;
  created_at: number;
  latency_ms: number | null;
  agents: AgentInfo[];
}

export interface GitInfo {
  branch: string;
  dirty: boolean;
  ahead: number;
  behind: number;
  worktree: boolean;
}

export type SessionState =
  | 'starting'
  | 'idle'
  | 'running'
  | 'needs_approval'
  | 'needs_input'
  | 'error'
  | 'stopped'
  | 'readonly';

/**
 * Amendment A10: `shared` is a live CLI process the device is attached to.
 * Apps treat it like `remote` for the composer, approvals and the queue.
 */
export type ControlOwner = 'remote' | 'terminal' | 'shared' | 'none';

export interface Usage {
  input_tokens: number;
  output_tokens: number;
  total_tokens: number;
  /** Optional (amendment A2): Codex reports no cost and may omit context. */
  context_used?: number | null;
  context_window?: number | null;
  cost_usd?: number | null;
}

export interface TurnRef {
  turn_id: string;
  started_at: number;
}

export interface TodoCounts {
  total: number;
  done: number;
}

export interface Session {
  session_id: string;
  device_id: string;
  agent: AgentId;
  title: string;
  cwd: string;
  git: GitInfo | null;
  state: SessionState;
  state_detail: string | null;
  origin: 'remote' | 'terminal';
  control: ControlOwner;
  model: string | null;
  permission_mode: string | null;
  effort: string | null;
  created_at: number;
  updated_at: number;
  last_seq: number;
  archived: boolean;
  turn: TurnRef | null;
  todos: TodoCounts | null;
  usage: Usage | null;
  queued: number;
}

/* ---------------------------------------------------------------- events */

export type ToolKind =
  | 'shell'
  | 'read'
  | 'edit'
  | 'write'
  | 'search'
  | 'web'
  | 'mcp'
  | 'subagent'
  | 'todo'
  | 'other'
  | (string & {});

export type ToolStatus = 'running' | 'succeeded' | 'failed' | 'cancelled';

export interface DiffInfo {
  path: string;
  additions: number;
  deletions: number;
  patch?: string;
  patch_truncated?: boolean;
}

export interface Attachment {
  name: string;
  mime: string;
  size: number;
}

export interface OutgoingAttachment {
  name: string;
  mime: string;
  data_base64: string;
}

interface EventBase {
  seq: number;
  ts: number;
  parent_block_id?: string;
  /**
   * Amendment A8: the `seq` at which this block first appeared, set on block
   * events only (equal to `seq` on the first one). Apps order blocks by
   * `first_seq ?? seq` so a late-finishing tool call keeps its place.
   */
  first_seq?: number;
}

/**
 * Amendment A10, `shared` sessions only. `pending`: held by the device until
 * the terminal turn ends; `delivered`: injected into the CLI; `absorbed`: the
 * CLI read it as mid-turn data and the device will re-inject it.
 */
export type MessageDelivery = 'pending' | 'delivered' | 'absorbed';

export interface UserMessageEvent extends EventBase {
  kind: 'user_message';
  block_id: string;
  text: string;
  attachments?: Attachment[];
  source: 'remote' | 'terminal' | 'queue';
  delivery?: MessageDelivery;
}

export interface AssistantTextEvent extends EventBase {
  kind: 'assistant_text';
  block_id: string;
  delta?: string;
  text?: string;
  done: boolean;
}

export interface ThinkingEvent extends EventBase {
  kind: 'thinking';
  block_id: string;
  delta?: string;
  text?: string;
  done: boolean;
  duration_ms?: number;
}

export interface ToolCallEvent extends EventBase {
  kind: 'tool_call';
  block_id: string;
  tool: string;
  /** Tool category (amendment A1). Devices always send it. */
  tool_kind?: ToolKind;
  title: string;
  status: ToolStatus;
  input?: Record<string, unknown>;
  input_truncated?: boolean;
  output?: string;
  output_truncated?: boolean;
  summary?: string;
  diff?: DiffInfo;
  started_at: number;
  ended_at?: number;
  duration_ms?: number;
}

export interface TodoItem {
  id: string;
  text: string;
  status: 'pending' | 'in_progress' | 'completed';
}

export interface TodosEvent extends EventBase {
  kind: 'todos';
  items: TodoItem[];
}

export interface ApprovalOption {
  id: string;
  label: string;
  style: 'primary' | 'secondary' | 'danger';
}

export interface ApprovalEvent extends EventBase {
  kind: 'approval';
  block_id: string;
  request_id: string;
  tool: string;
  /** Tool category (amendment A1). */
  tool_kind?: ToolKind;
  title: string;
  input?: Record<string, unknown>;
  diff?: DiffInfo;
  options: ApprovalOption[];
  status: 'pending' | 'resolved' | 'expired';
  decision?: { option_id: string; by: 'remote' | 'terminal' | 'policy' };
}

export interface QuestionOption {
  id: string;
  label: string;
  description?: string;
}

export interface QuestionSpec {
  id: string;
  prompt: string;
  options: QuestionOption[];
  multi: boolean;
  allow_text: boolean;
  secret?: boolean;
}

export type QuestionAnswers = Record<string, string[] | string>;

export interface QuestionEvent extends EventBase {
  kind: 'question';
  block_id: string;
  request_id: string;
  questions: QuestionSpec[];
  status: 'pending' | 'resolved' | 'expired';
  answers?: QuestionAnswers;
}

export interface TurnStartedEvent extends EventBase {
  kind: 'turn_started';
  turn_id: string;
  trigger: 'remote' | 'terminal' | 'queue';
}

export interface TurnCompletedEvent extends EventBase {
  kind: 'turn_completed';
  turn_id: string;
  stop_reason: 'completed' | 'interrupted' | 'error';
  duration_ms: number;
  usage?: Usage;
}

export interface StatusEvent extends EventBase {
  kind: 'status';
  state: SessionState;
  detail?: string;
}

export interface MetaEvent extends EventBase {
  kind: 'meta';
  title?: string;
  model?: string;
  permission_mode?: string;
  effort?: string;
  cwd?: string;
  git?: GitInfo | null;
  control?: ControlOwner;
  agent_version?: string;
}

export interface QueuedMessage {
  id: string;
  text: string;
  ts: number;
}

export interface QueueEvent extends EventBase {
  kind: 'queue';
  pending: QueuedMessage[];
}

export interface NoticeEvent extends EventBase {
  kind: 'notice';
  level: 'info' | 'warn' | 'error';
  text: string;
}

export interface ErrorEvent extends EventBase {
  kind: 'error';
  message: string;
  code?: string;
  block_id?: string;
}

export type SessionEvent =
  | UserMessageEvent
  | AssistantTextEvent
  | ThinkingEvent
  | ToolCallEvent
  | TodosEvent
  | ApprovalEvent
  | QuestionEvent
  | TurnStartedEvent
  | TurnCompletedEvent
  | StatusEvent
  | MetaEvent
  | QueueEvent
  | NoticeEvent
  | ErrorEvent;

export type SessionEventKind = SessionEvent['kind'];
