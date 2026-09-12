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
  // Amendment A10: this device installs the `claude` shim, so a terminal
  // session started through it can be attached instead of taken over.
  attach: 'channel',
  attach_ready: true,
  shared_interrupt: false,
  // A11: the channel relays prompts and approvals only.
  shared_settings: false,
  shared_attachments: false,
};

/** The same agent on a device where the shim is not installed yet (A10). */
export const claudeNoShim: AgentInfo = {
  ...claudeAgent,
  attach_ready: false,
};

export const codexAgent: AgentInfo = {
  agent: 'codex',
  available: true,
  version: '0.154.0',
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
  efforts: [
    { id: 'low', label: 'Low' },
    { id: 'medium', label: 'Medium' },
    { id: 'high', label: 'High' },
  ],
  default_effort: 'medium',
  capabilities: ['worktree', 'interrupt', 'queue', 'steer', 'attachments', 'effort', 'history'],
  // A21: Codex's own service tier. The apps draw the lightning only because
  // this list is non-empty; Claude lists none and draws nothing.
  speeds: [{ id: 'priority', label: 'Fast' }],
  // Amendment A11: every bare `codex` TUI on this device runs inside the shared
  // app-server daemon, so an attached session carries the interrupt, the
  // settings and the image inputs as well as prompts and approvals.
  attach: 'daemon',
  attach_ready: true,
  shared_interrupt: true,
  shared_settings: true,
  shared_attachments: true,
};

/** A11: the same agent on a device where the daemon is not running yet. */
export const codexNoDaemon: AgentInfo = {
  ...codexAgent,
  attach_ready: false,
  shared_interrupt: false,
  shared_settings: false,
  shared_attachments: false,
};

/**
 * A22: the build the mock gateway serves as `/api/config` `client.build`.
 * `dev-mac` runs it and `dev-ci` runs the one before, so the device list shows
 * both a row with nothing to do and a row offering Update.
 */
export const CLIENT_VERSION = '0.1.0';
export const CLIENT_BUILD = '3f2b4a9c1d8e7f60a5b4c3d2e1f0918273645a5b6c7d8e9f0a1b2c3d4e5f6a7b';
export const OLD_CLIENT_BUILD =
  '9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9e8d';

export const devices: Device[] = [
  {
    device_id: 'dev-mac',
    name: 'mac-studio-office',
    platform: 'macos',
    hostname: 'mac-studio.local',
    arch: 'arm64',
    client_version: '0.1.0',
    client_build: CLIENT_BUILD,
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
    client_version: '0.0.9',
    client_build: OLD_CLIENT_BUILD,
    online: true,
    last_seen: minutes(2),
    created_at: minutes(60 * 24 * 30),
    latency_ms: 42,
    agents: [claudeNoShim, codexNoDaemon],
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
    // Amendment A10: a terminal session the device is attached to. The composer
    // and approvals work; the model and permission mode belong to the terminal.
    session_id: 'ses-shared',
    device_id: 'dev-mac',
    title: 'Wire the channel shim',
    cwd: '/Users/me/dev/remote-control/protocol',
    state: 'idle',
    origin: 'terminal',
    control: 'shared',
    // A17: read from the transcript, and `auto` is a real Claude permission
    // mode the device does not advertise, so the app shows it by its id.
    permission_mode: 'auto',
    effort: 'xhigh',
    updated_at: minutes(2),
  }),
  session({
    // Amendment A11: a bare `codex` TUI running inside the shared app-server
    // daemon. The attachment carries the interrupt, the settings and images,
    // so nothing in the composer is locked to the terminal.
    session_id: 'ses-codex-shared',
    device_id: 'dev-mac',
    title: 'Typecheck the web app',
    cwd: '/Users/me/dev/remote-control/web',
    agent: 'codex',
    model: 'gpt-5.4-codex',
    permission_mode: 'on-request',
    effort: 'medium',
    state: 'running',
    state_detail: 'Typed in the terminal',
    origin: 'terminal',
    control: 'shared',
    turn: { turn_id: 'codex-turn-live', started_at: minutes(1) },
    usage: {
      input_tokens: 14_980,
      output_tokens: 1_740,
      total_tokens: 16_720,
      context_used: 19_300,
      context_window: 272_000,
    },
    git: { branch: 'feat/settings-drawer', dirty: true, ahead: 2, behind: 0, worktree: false },
    updated_at: minutes(1),
  }),
  session({
    // A11: the daemon is not running on this device, so the hint asks for it.
    session_id: 'ses-codex-terminal',
    device_id: 'dev-ci',
    title: 'Bisect the ingest regression',
    cwd: '/home/ci/work/api',
    agent: 'codex',
    model: 'gpt-5.4-codex',
    permission_mode: 'on-request',
    effort: 'medium',
    // A21: `/fast` was typed in that terminal, and the chip says so.
    speed: 'priority',
    state: 'readonly',
    origin: 'terminal',
    control: 'terminal',
    updated_at: minutes(6),
  }),
  session({
    // A10: the shim is not installed on this device, so the hint asks for it.
    session_id: 'ses-attach',
    device_id: 'dev-ci',
    title: 'Nightly perf sweep',
    cwd: '/home/ci/work/api',
    state: 'readonly',
    origin: 'terminal',
    control: 'terminal',
    updated_at: minutes(8),
  }),
  session({
    // A question the agent is blocked on: the dot reads `waiting`, like the
    // pending approval above it, because both are the user's turn to answer.
    session_id: 'ses-answer',
    device_id: 'dev-ci',
    title: 'Split the ingest migration',
    cwd: '/home/ci/work/api',
    state: 'needs_input',
    state_detail: 'Waiting on an answer',
    updated_at: minutes(9),
    git: { branch: 'feat/ingest-split', dirty: true, ahead: 1, behind: 0, worktree: false },
  }),
  session({
    // The CLI failed but the session is still ours, so the row stays active and
    // its dot reads `failed` rather than `off`.
    session_id: 'ses-crash',
    device_id: 'dev-mac',
    title: 'Regenerate the API client',
    cwd: '/Users/me/dev/remote-control/gateway',
    agent: 'codex',
    model: 'gpt-5.4-codex',
    permission_mode: 'on-request',
    effort: 'medium',
    state: 'error',
    state_detail: 'the CLI exited with 1',
    updated_at: minutes(35),
  }),
  session({
    // The CLI exited, so nothing owns this session any more: it belongs to its
    // device's Archive rather than to its active rows.
    session_id: 'ses-exited',
    device_id: 'dev-mac',
    title: 'Rewrite the pairing docs',
    cwd: '/Users/me/dev/remote-control/docs',
    state: 'stopped',
    control: 'none',
    updated_at: minutes(50),
  }),
  session({
    // Archived by hand, so it sits in its device's Archive with an "Archived"
    // mark rather than in the active rows.
    session_id: 'ses-archived',
    device_id: 'dev-ci',
    title: 'Drop the legacy ingest path',
    cwd: '/home/ci/work/api',
    agent: 'codex',
    model: 'gpt-5.4-codex',
    permission_mode: 'on-request',
    effort: 'medium',
    state: 'idle',
    archived: true,
    updated_at: minutes(60 * 26),
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
  session({
    // A second exited Codex session, so a device's Archive holds more than one
    // row and both halves of it are visible in the mock.
    session_id: 'ses-exited-codex',
    device_id: 'dev-mac',
    title: 'Port the popover placement',
    cwd: '/Users/me/dev/remote-control/web',
    agent: 'codex',
    model: 'gpt-5.4-codex',
    permission_mode: 'on-request',
    effort: 'medium',
    state: 'stopped',
    control: 'none',
    updated_at: minutes(200),
  }),
  session({
    session_id: 'ses-exited-ci',
    device_id: 'dev-ci',
    title: 'Cache the wheel build',
    cwd: '/home/ci/work/infra',
    state: 'stopped',
    control: 'none',
    updated_at: minutes(320),
  }),
  session({
    // Archived by hand on the other device, so both devices show the mark.
    session_id: 'ses-archived-mac',
    device_id: 'dev-mac',
    title: 'Sketch the pairing QR flow',
    cwd: '/Users/me/dev/remote-control/docs',
    state: 'idle',
    archived: true,
    updated_at: minutes(60 * 40),
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
    case 'ses-shared':
      return sharedHistory();
    case 'ses-attach':
      return attachHistory();
    case 'ses-codex-shared':
      return codexSharedHistory();
    case 'ses-codex-terminal':
      return codexTerminalHistory();
    case 'ses-answer':
      return answerHistory();
    case 'ses-crash':
      return crashHistory();
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

/**
 * A10: a terminal-typed prompt on a session the device is attached to. A20:
 * the CLI asked a question while it ran, the app saw the card at the same
 * moment as the terminal saw its dialog, and the terminal answered first.
 */
function sharedHistory(): SessionEvent[] {
  const base = minutes(9);
  return [
    {
      seq: 1,
      ts: base,
      kind: 'user_message',
      block_id: 'sh-u0',
      source: 'terminal',
      text: 'add the channel handshake to the protocol doc',
    },
    {
      seq: 2,
      ts: base + 1_200,
      kind: 'question',
      block_id: 'sh-q0',
      request_id: 'req-shared-1',
      status: 'resolved',
      by: 'terminal',
      answers: { placement: ['section11'] },
      questions: [
        {
          id: 'placement',
          prompt: 'Where should the handshake go?',
          options: [
            { id: 'section11', label: 'Section 11, beside the amendments' },
            { id: 'section4', label: 'Section 4, with the device socket' },
          ],
          multi: false,
          allow_text: true,
        },
      ],
    },
    {
      seq: 3,
      ts: base + 2_400,
      kind: 'assistant_text',
      block_id: 'sh-a0',
      done: true,
      text: 'Documented the handshake in §11. Anything typed here also reaches the terminal.',
    },
    {
      seq: 4,
      ts: base + 2_600,
      kind: 'turn_completed',
      turn_id: 'shared-turn-0',
      stop_reason: 'completed',
      duration_ms: 2_500,
    },
  ];
}

/** A10: a terminal session that could be attached but was not. */
function attachHistory(): SessionEvent[] {
  const base = minutes(8);
  return [
    {
      seq: 1,
      ts: base,
      kind: 'user_message',
      block_id: 'at-u1',
      source: 'terminal',
      text: 'run the perf sweep and summarise the regressions',
    },
    {
      seq: 2,
      ts: base + 3_000,
      kind: 'assistant_text',
      block_id: 'at-a1',
      done: true,
      text: 'Sweep finished. Two endpoints regressed by more than 5%.',
    },
  ];
}

/**
 * A11: a Codex thread typed in the terminal that the device is attached to
 * through the daemon. The last approval was answered in the TUI, so it resolved
 * with the reserved `elsewhere` option id.
 */
function codexSharedHistory(): SessionEvent[] {
  const base = minutes(4);
  const options = [
    { id: 'allow', label: 'Allow', style: 'primary' as const },
    { id: 'allow_session', label: 'Allow for this session', style: 'secondary' as const },
    { id: 'allow_always', label: 'Always allow commands like this', style: 'secondary' as const },
    { id: 'deny', label: 'Deny', style: 'danger' as const },
  ];
  const approval = {
    kind: 'approval' as const,
    block_id: 'cs-ap0',
    request_id: 'req-codex-history',
    tool: 'shell',
    tool_kind: 'shell' as const,
    title: 'npm run lint',
    input: {
      command: "/bin/zsh -lc 'npm run lint'",
      cwd: '/Users/me/dev/remote-control/web',
      command_actions: [{ type: 'unknown', command: 'npm run lint' }],
    },
    options,
  };
  return [
    {
      seq: 1,
      ts: base,
      kind: 'user_message',
      block_id: 'cs-u0',
      source: 'terminal',
      text: 'run the typecheck and fix whatever it reports',
    },
    { ...approval, seq: 2, ts: base + 1_200, status: 'pending' },
    {
      ...approval,
      seq: 3,
      ts: base + 6_800,
      first_seq: 2,
      status: 'resolved',
      // A11 §5.7: the TUI answered first, so the device never decided.
      decision: { option_id: 'elsewhere', by: 'terminal' },
    },
    {
      seq: 4,
      ts: base + 9_000,
      kind: 'assistant_text',
      block_id: 'cs-a0',
      done: true,
      text: 'Lint is clean. Running `tsc --noEmit` next — it is slower, so I will report the first batch of errors as they come.',
    },
    { seq: 5, ts: base + 9_200, kind: 'turn_started', turn_id: 'codex-turn-live', trigger: 'terminal' },
    {
      seq: 6,
      ts: base + 12_000,
      kind: 'tool_call',
      block_id: 'cs-t0',
      tool: 'shell',
      tool_kind: 'shell',
      title: 'npm run typecheck',
      status: 'running',
      started_at: base + 11_000,
      input: { command: 'npm run typecheck' },
      output: '> tsc --noEmit\n',
    },
  ];
}

/** A11: a Codex TUI on a device where the daemon socket is absent. */
function codexTerminalHistory(): SessionEvent[] {
  const base = minutes(6);
  return [
    {
      seq: 1,
      ts: base,
      kind: 'user_message',
      block_id: 'ct-u1',
      source: 'terminal',
      text: 'bisect the ingest latency regression between v1.3 and main',
    },
    {
      seq: 2,
      ts: base + 4_000,
      kind: 'assistant_text',
      block_id: 'ct-a1',
      done: true,
      text: 'The regression lands on `feat/batch-ingest`. I need the collector logs to narrow it further.',
    },
  ];
}

/** A session blocked on a question, so the list shows the `waiting` tone. */
function answerHistory(): SessionEvent[] {
  const base = minutes(10);
  return [
    {
      seq: 1,
      ts: base,
      kind: 'user_message',
      block_id: 'an-u1',
      source: 'remote',
      text: 'Split the ingest migration into two steps.',
    },
    {
      seq: 2,
      ts: base + 2_000,
      kind: 'assistant_text',
      block_id: 'an-a1',
      done: true,
      text: 'Both halves are ready. I need to know which one runs first.',
    },
    {
      seq: 3,
      ts: base + 2_400,
      kind: 'question',
      block_id: 'an-q1',
      request_id: 'req-answer-1',
      status: 'pending',
      questions: [
        {
          id: 'order',
          prompt: 'Which migration should run first?',
          options: [
            { id: 'backfill', label: 'Backfill the new columns' },
            { id: 'swap', label: 'Swap the read path' },
          ],
          multi: false,
          allow_text: true,
        },
      ],
    },
  ];
}

/** A session whose CLI failed, so the list shows the `failed` tone. */
function crashHistory(): SessionEvent[] {
  const base = minutes(36);
  return [
    {
      seq: 1,
      ts: base,
      kind: 'user_message',
      block_id: 'cr-u1',
      source: 'remote',
      text: 'Regenerate the API client from the OpenAPI document.',
    },
    {
      seq: 2,
      ts: base + 1_800,
      kind: 'notice',
      level: 'error',
      text: 'The generator exited with 1: openapi.yaml is not valid YAML.',
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
