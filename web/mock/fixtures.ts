/**
 * Fixtures for the mock gateway and the unit tests. Every object follows
 * PROTOCOL-FROZEN.md §3 / §4 exactly.
 */
import type {
  AgentInfo,
  Device,
  Session,
  SessionEvent,
} from '../src/protocol/types';

const now = Date.now();
const minutes = (n: number) => now - n * 60_000;

export const claudeAgent: AgentInfo = {
  agent: 'claude',
  available: true,
  version: '2.1.266',
  path: '/Users/me/.local/bin/claude',
  models: [
    { id: 'claude-sonnet-4-5', label: 'Sonnet 4.5' },
    { id: 'claude-opus-4-1', label: 'Opus 4.1' },
    { id: 'claude-haiku-4-5', label: 'Haiku 4.5' },
  ],
  default_model: 'claude-sonnet-4-5',
  permission_modes: [
    { id: 'default', label: 'Ask before edits' },
    { id: 'acceptEdits', label: 'Auto-accept edits' },
    { id: 'plan', label: 'Plan mode' },
    { id: 'bypassPermissions', label: 'Bypass permissions' },
  ],
  default_permission_mode: 'acceptEdits',
  efforts: [
    { id: 'low', label: 'Low' },
    { id: 'medium', label: 'Medium' },
    { id: 'high', label: 'High' },
  ],
  default_effort: 'high',
  capabilities: [
    'worktree',
    'takeover',
    'interrupt',
    'queue',
    'attachments',
    'effort',
    'history',
  ],
};

export const codexAgent: AgentInfo = {
  agent: 'codex',
  available: true,
  version: '0.153.4',
  path: '/opt/homebrew/bin/codex',
  models: [
    { id: 'gpt-5.4-codex', label: 'GPT-5.4 Codex' },
    { id: 'gpt-5.4', label: 'GPT-5.4' },
  ],
  default_model: 'gpt-5.4-codex',
  permission_modes: [
    { id: 'untrusted', label: 'Ask for everything' },
    { id: 'on-request', label: 'Ask when needed' },
    { id: 'never', label: 'Never ask' },
  ],
  default_permission_mode: 'on-request',
  efforts: [],
  default_effort: null,
  capabilities: ['interrupt', 'queue', 'steer', 'attachments', 'history'],
};

const codexMissing: AgentInfo = {
  ...codexAgent,
  available: false,
  version: null,
  path: null,
};

export const devices: Device[] = [
  {
    device_id: 'dev-mac',
    name: 'mac-studio-office',
    platform: 'macos',
    hostname: 'mac-studio.local',
    arch: 'arm64',
    client_version: '0.1.0',
    online: true,
    last_seen: now,
    created_at: minutes(60 * 24 * 9),
    latency_ms: 18,
    agents: [claudeAgent, codexAgent],
  },
  {
    device_id: 'dev-ci',
    name: 'ci-runner-01',
    platform: 'linux',
    hostname: 'ci-runner-01',
    arch: 'x86_64',
    client_version: '0.1.0',
    online: true,
    last_seen: minutes(2),
    created_at: minutes(60 * 24 * 30),
    latency_ms: 42,
    agents: [claudeAgent, codexMissing],
  },
];

export const HOME: Record<string, string> = {
  'dev-mac': '/Users/me',
  'dev-ci': '/home/ci',
};

function session(partial: Partial<Session> & Pick<Session, 'session_id' | 'device_id' | 'title' | 'cwd'>): Session {
  return {
    agent: 'claude',
    git: { branch: 'main', dirty: false, ahead: 0, behind: 0, worktree: false },
    state: 'idle',
    state_detail: null,
    origin: 'remote',
    control: 'remote',
    model: 'claude-sonnet-4-5',
    permission_mode: 'acceptEdits',
    effort: 'high',
    created_at: minutes(120),
    updated_at: minutes(4),
    last_seq: 0,
    archived: false,
    turn: null,
    todos: null,
    usage: null,
    queued: 0,
    ...partial,
  };
}

export const sessions: Session[] = [
  session({
    session_id: 'ses-flaky',
    device_id: 'dev-mac',
    title: 'Fix flaky auth test',
    cwd: '/Users/me/dev/remote-control/gateway',
    state: 'running',
    updated_at: minutes(4),
    turn: { turn_id: 'turn-1', started_at: minutes(4) },
    todos: { total: 4, done: 1 },
    usage: {
      input_tokens: 39_400,
      output_tokens: 8_800,
      total_tokens: 48_200,
      context_used: 61_000,
      context_window: 200_000,
      cost_usd: 0.42,
    },
    last_seq: 0,
  }),
  session({
    session_id: 'ses-vite',
    device_id: 'dev-mac',
    title: 'Migrate web to Vite 6',
    cwd: '/Users/me/dev/remote-control/web',
    state: 'needs_approval',
    state_detail: 'Waiting on a shell command',
    updated_at: minutes(12),
    git: { branch: 'feat/vite', dirty: true, ahead: 2, behind: 0, worktree: false },
  }),
  session({
    session_id: 'ses-push',
    device_id: 'dev-mac',
    title: 'iOS push tokens',
    cwd: '/Users/me/dev/remote-control/ios',
    state: 'idle',
    updated_at: minutes(180),
  }),
  session({
    session_id: 'ses-terminal',
    device_id: 'dev-mac',
    // Amendment A7: a mirrored session reports `running` while the terminal
    // drives the turn; `readonly` only once it is idle.
    title: 'Refactor relay routing',
    cwd: '/Users/me/dev/remote-control/client',
    state: 'running',
    origin: 'terminal',
    control: 'terminal',
    turn: { turn_id: 'terminal-turn', started_at: minutes(1) },
    updated_at: minutes(1),
  }),
  session({
    session_id: 'ses-otlp',
    device_id: 'dev-ci',
    title: 'Add OTLP traces',
    cwd: '/home/ci/work/api',
    agent: 'codex',
    model: 'gpt-5.4-codex',
    permission_mode: 'on-request',
    effort: null,
    state: 'idle',
    updated_at: minutes(60),
    git: { branch: 'main', dirty: false, ahead: 0, behind: 1, worktree: false },
  }),
];

/** Final-form history events (what `session.history` must return). */
export function historyFor(sessionId: string): SessionEvent[] {
  switch (sessionId) {
    case 'ses-flaky':
      return flakyHistory();
    case 'ses-vite':
      return viteHistory();
    case 'ses-terminal':
      return terminalHistory();
    case 'ses-otlp':
      return codexHistory();
    default:
      return [];
  }
}

function flakyHistory(): SessionEvent[] {
  const base = minutes(30);
  return [
    { seq: 1, ts: base, kind: 'turn_started', turn_id: 'turn-0', trigger: 'remote' },
    {
      seq: 2,
      ts: base + 100,
      kind: 'user_message',
      block_id: 'blk-u0',
      source: 'remote',
      text: 'Set up the repro harness for the auth suite first.',
    },
    {
      seq: 3,
      ts: base + 4_000,
      kind: 'assistant_text',
      block_id: 'blk-a0',
      done: true,
      text: 'Added a `--count` harness to the auth suite so the flake reproduces locally.\n\n| run | failures |\n| --- | --- |\n| 20 | 1 |\n| 100 | 6 |',
    },
    {
      seq: 4,
      ts: base + 5_000,
      kind: 'turn_completed',
      turn_id: 'turn-0',
      stop_reason: 'completed',
      duration_ms: 4_900,
    },
  ];
}

function viteHistory(): SessionEvent[] {
  const base = minutes(20);
  return [
    {
      seq: 1,
      ts: base,
      kind: 'user_message',
      block_id: 'v-u1',
      source: 'remote',
      text: 'Bump the web app to Vite 6 and keep the build green.',
    },
    {
      seq: 2,
      ts: base + 2_000,
      kind: 'assistant_text',
      block_id: 'v-a1',
      done: true,
      text: 'Upgrading the toolchain. I need to run the install before I can verify the build.',
    },
    {
      seq: 3,
      ts: base + 3_000,
      kind: 'approval',
      block_id: 'v-ap1',
      request_id: 'req-vite-1',
      tool: 'Bash',
      tool_kind: 'shell',
      title: 'npm install --no-audit',
      input: { command: 'npm install --no-audit', cwd: '/Users/me/dev/remote-control/web' },
      status: 'pending',
      options: [
        { id: 'allow', label: 'Allow once', style: 'primary' },
        { id: 'allow_session', label: 'Allow for session', style: 'secondary' },
        { id: 'deny', label: 'Deny', style: 'danger' },
      ],
    },
  ];
}

function terminalHistory(): SessionEvent[] {
  const base = minutes(15);
  return [
    {
      seq: 1,
      ts: base,
      kind: 'user_message',
      block_id: 't-u1',
      source: 'terminal',
      text: 'split the relay router into per-domain handlers',
    },
    {
      seq: 2,
      ts: base + 1_500,
      kind: 'assistant_text',
      block_id: 't-a1',
      done: true,
      text: 'Splitting `router.py` into `router/devices.py` and `router/sessions.py`.',
    },
    {
      seq: 3,
      ts: base + 2_000,
      kind: 'notice',
      level: 'info',
      text: 'This session is driven from a terminal on mac-studio-office.',
    },
  ];
}

function codexHistory(): SessionEvent[] {
  const base = minutes(70);
  return [
    {
      seq: 1,
      ts: base,
      kind: 'user_message',
      block_id: 'c-u1',
      source: 'remote',
      text: 'Add OTLP span exporters to the ingest path.',
    },
    {
      seq: 2,
      ts: base + 3_000,
      kind: 'tool_call',
      block_id: 'c-t1',
      tool: 'shell',
      tool_kind: 'shell',
      title: 'rg -n "tracer" src/',
      status: 'succeeded',
      started_at: base + 2_000,
      ended_at: base + 3_000,
      duration_ms: 1_000,
      output: 'src/ingest.py:12:tracer = trace.get_tracer(__name__)\nsrc/api.py:8:tracer = trace.get_tracer(__name__)',
    },
    {
      seq: 3,
      ts: base + 6_000,
      kind: 'assistant_text',
      block_id: 'c-a1',
      done: true,
      text: 'Both entry points already build a tracer. I wired the OTLP exporter in `src/telemetry.py` and pointed the collector at `$OTEL_EXPORTER_OTLP_ENDPOINT`.',
    },
    {
      seq: 4,
      ts: base + 6_500,
      kind: 'turn_completed',
      turn_id: 'c-turn-1',
      stop_reason: 'completed',
      duration_ms: 6_400,
    },
  ];
}

export const directories: Record<string, { path: string; parent: string | null; entries: string[] }[]> = {};

export const recentDirs = [
  { path: '/Users/me/dev/remote-control/gateway', last_used: minutes(30) },
  { path: '/Users/me/dev/remote-control/web', last_used: minutes(120) },
  { path: '/Users/me/work/api', last_used: minutes(60 * 26) },
];
