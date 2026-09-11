/**
 * Single English string catalog. Keep every user-visible string here so the app
 * can be localised later without touching components.
 */
export const strings = {
  productName: 'Remote Control',

  nav: {
    devices: 'Devices',
    sessions: 'Sessions',
    settings: 'Settings',
    backToSessions: 'Back to sessions',
  },

  common: {
    cancel: 'Cancel',
    close: 'Close',
    continue: 'Continue',
    copy: 'Copy',
    copied: 'Copied',
    retry: 'Retry',
    remove: 'Remove',
    save: 'Save',
    rename: 'Rename',
    revoke: 'Revoke',
    search: 'Search',
    loading: 'Loading…',
    expand: 'expand',
    collapse: 'collapse',
    showMore: 'Show more',
    showLess: 'Show less',
    dismiss: 'Dismiss',
    unknown: 'Unknown',
    none: 'None',
    ok: 'OK',
    done: 'Done',
    of: 'of',
  },

  login: {
    title: 'Remote Control',
    subtitle: 'Sign in to reach your devices.',
    password: 'Gateway password',
    passwordPlaceholder: 'Password',
    submit: 'Sign in',
    signingIn: 'Signing in…',
    failed: 'Wrong password.',
    unreachable: 'Cannot reach the gateway.',
    rateLimited: 'Too many attempts. Wait a minute and try again.',
  },

  devices: {
    title: 'Devices',
    subtitleCount: (online: number, total: number) =>
      `${online} connected · ${total} ${total === 1 ? 'device' : 'devices'}`,
    empty: 'No devices yet.',
    emptyHint: 'Add the machine where Claude Code or Codex is installed.',
    add: 'Add device',
    sessionsCount: (n: number) => `${n} ${n === 1 ? 'session' : 'sessions'}`,
    noSessions: 'idle',
    offline: 'offline',
    lastSeen: (rel: string) => `last seen ${rel}`,
    renameTitle: 'Rename device',
    renameLabel: 'Device name',
    revokeTitle: 'Revoke device',
    revokeBody: (name: string) =>
      `Revoke ${name}? Its token stops working and its sessions leave this gateway. The machine keeps its agents and transcripts.`,
    revokeConfirm: 'Revoke device',
    noAgents: 'No agents detected',
  },

  pairing: {
    title: 'Add device',
    intro:
      'Run one command on the machine where your agents live. It dials out to the gateway — nothing is exposed on the host.',
    macos: 'macOS',
    linux: 'Linux',
    singleUse: 'single use',
    expiresIn: (clock: string) => `expires in ${clock}`,
    expired: 'expired',
    newCode: 'New code',
    waiting: 'Waiting for this device',
    listening: (clock: string) => `listening ${clock}`,
    stepGateway: 'Gateway ready',
    stepHandshake: 'Device handshake',
    stepAgents: 'Detect installed agents',
    connected: (name: string) => `${name} connected`,
    noCurl: 'No curl on the host?',
    manualInstall: 'Manual install',
    manualTitle: 'Manual install',
    manualSteps: [
      'Install the client: python3 -m pip install --user rc-client',
      'Pair it with this gateway using the code below.',
      'The client keeps running in the background and dials out only.',
    ],
    manualPairCommand: (origin: string, code: string) =>
      `rc-client pair --gateway ${origin} --code ${code}`,
    createFailed: 'Could not create a pairing code.',
  },

  sessions: {
    title: 'Sessions',
    new: 'New session',
    searchPlaceholder: 'Search sessions',
    empty: 'No sessions yet.',
    emptyHint: 'Start one from a paired device, or open a terminal session on the machine.',
    noMatches: 'Nothing matches that search.',
    allDevices: 'All devices',
    allAgents: 'All',
    agentFilter: 'Filter by agent',
    archive: 'Archive',
    unarchive: 'Unarchive',
    archiveGroup: (n: number) => `Archive · ${n}`,
    archived: 'Archived',
    deviceOffline: 'Device offline',
    open: 'Open session',
  },

  newSession: {
    title: 'New session',
    continuingOn: (device: string) => `Continuing on ${device}`,
    pickDevice: 'Pick a device to start on',
    device: 'Device',
    agent: 'Agent',
    agentUnavailable: 'not installed',
    workingDirectory: 'Working directory',
    browse: 'Browse…',
    dirExists: 'exists',
    dirMissing: 'not found',
    dirChecking: 'checking…',
    recent: 'Recent',
    git: 'Git',
    gitClean: 'clean',
    gitDirty: 'uncommitted changes',
    gitAhead: (n: number) => `${n} ahead`,
    gitBehind: (n: number) => `${n} behind`,
    notARepo: 'Not a git repository',
    isolateWorktree: 'Isolate in worktree',
    start: 'Start session',
    starting: 'Starting…',
    startFailed: 'Could not start the session.',
    noDevices: 'No device is online.',
    browseTitle: 'Choose a directory',
    browseUp: 'Up one level',
    browseUse: 'Use this directory',
    browseEmpty: 'No subdirectories.',
  },

  chat: {
    newSession: 'New session',
    searchPlaceholder: 'Search',
    sidebarFooter: (devices: number, waiting: number) =>
      `${devices} ${devices === 1 ? 'device' : 'devices'} · ${waiting} waiting`,
    todos: (done: number, total: number) => `Todos ${done}/${total}`,
    todosTitle: 'Todos',
    stop: 'Stop',
    stopping: 'Stopping…',
    takeOver: 'Take over',
    backToLatest: 'Back to latest',
    newUpdates: (n: number) => `${n} ${n === 1 ? 'update' : 'updates'}`,
    loadingHistory: 'Loading earlier messages…',
    historyStart: 'Start of the conversation',
    thoughtFor: (s: string) => `Thought for ${s}`,
    thinking: 'Thinking…',
    openFullOutput: 'Open full output',
    outputTruncated: 'output truncated',
    inputTruncated: 'input truncated',
    input: 'Input',
    output: 'Output',
    patchTruncated: 'patch truncated',
    subtasks: (n: number) => `${n} ${n === 1 ? 'step' : 'steps'}`,
    attachments: (n: number) => `${n} ${n === 1 ? 'attachment' : 'attachments'}`,
    running: 'running',
    failed: 'failed',
    cancelled: 'cancelled',
    queuedLabel: 'Queued',
    sending: 'Sending…',
    steering: 'the agent will read it at its next step',
    deliveryPending: 'waiting for the terminal',
    deliveryAbsorbed: 'will be re-sent',
    attachHintChannel: 'Start claude through the Remote Control shim to control it from here',
    attachHintDaemon: 'Start the Codex app-server daemon on this device to control it from here',
    attachHintRestart:
      'This terminal session was started without the attachment; restart it to control it from here',
    queuedRemove: 'Remove from queue',
    approvalNeeded: 'Needs your approval',
    approvalResolved: (by: string, label: string) => `${label} · decided by ${by}`,
    approvalElsewhere: 'Answered in the terminal',
    approvalExpired: 'This request expired.',
    questionResolved: 'Answered',
    questionExpired: 'This question expired.',
    submitAnswer: 'Submit',
    freeTextPlaceholder: 'Type your answer…',
    secretPlaceholder: 'Value is not stored or logged',
    sessionErrored: 'The session reported an error.',
    emptyTimeline: 'No messages yet. Say something to get started.',
    turnInterrupted: 'Turn interrupted',
    turnFailed: 'Turn failed',
    copyCode: 'Copy code',
  },

  status: {
    working: (agent: string) => `${agent} is working`,
    workingQueued: (agent: string) => `${agent} is working · your message will be queued`,
    workingSteer: (agent: string) => `${agent} is working · your message will steer the turn`,
    needsApproval: 'Needs your approval',
    needsInput: 'Waiting for your answer',
    terminalControlled: 'Controlled by the terminal',
    terminalBusy: 'a turn is running there',
    starting: 'Starting the agent…',
    stopped: 'Stopped',
    errored: 'Errored',
    idle: 'Idle',
    offline: 'Device offline',
  },

  composer: {
    placeholder: 'Message the agent…',
    placeholderQueued: 'Message will be queued…',
    placeholderSteer: 'Message will steer the turn…',
    placeholderTerminal: 'Controlled by the terminal · take over to send',
    placeholderOffline: 'Device is offline',
    send: 'Send',
    queue: 'Queue',
    interruptAndSend: 'Interrupt & send',
    sendOptions: 'Send options',
    attach: 'Attach files',
    attachTooMany: (max: number) => `At most ${max} attachments.`,
    attachTooLarge: (name: string, max: string) => `${name} is larger than ${max}.`,
    attachFailed: (name: string) => `Could not read ${name}.`,
    textTooLong: 'Message is too long (64 KiB limit).',
    deliveryUnconfirmed: 'Delivery unconfirmed',
    deliveryUnconfirmedBody: 'The gateway did not confirm your last message.',
    micStart: 'Start voice input',
    model: 'Model',
    permissionMode: 'Permission mode',
    effort: 'Effort',
    language: 'Voice language',
    sendFailed: 'Could not send the message.',
  },

  voice: {
    transcribing: 'Transcribing live · edit before sending',
    connecting: 'Connecting…',
    cancel: 'Cancel',
    done: 'Done',
    listeningFor: (elapsed: string) => `Listening for ${elapsed}`,
    denied: 'Microphone permission was denied.',
    unsupported: 'This browser cannot capture audio.',
    failed: 'Transcription failed.',
  },

  settings: {
    title: 'Settings',
    account: 'Account',
    signedInAs: 'Signed in as',
    signOut: 'Sign out',
    notifications: 'Notifications',
    pushEnable: 'Push notifications',
    pushDescription: 'Get a notification when an agent needs you or finishes a turn.',
    pushEnabled: 'On',
    pushDisabled: 'Off',
    pushBlocked: 'Blocked in browser settings',
    pushUnsupported: 'This browser does not support push notifications.',
    pushServerDisabled: 'The gateway has web push disabled.',
    pushEnableAction: 'Enable',
    pushDisableAction: 'Turn off',
    voice: 'Voice',
    voiceLanguage: 'Default language',
    voiceServerDisabled: 'Speech-to-text is not configured on this gateway.',
    about: 'About',
    gatewayVersion: 'Gateway version',
    protocolVersion: 'Protocol',
    origin: 'Gateway origin',
    connection: 'Connection',
    connected: 'Connected',
    connecting: 'Connecting…',
    offline: 'Offline',
  },

  connection: {
    reconnecting: 'Reconnecting…',
    offline: 'Connection lost. Reconnecting…',
    restored: 'Reconnected',
  },

  errors: {
    generic: 'Something went wrong.',
    stopFailed: 'Could not stop the turn.',
    approveFailed: 'Could not send that decision.',
    answerFailed: 'Could not send that answer.',
    expandFailed: 'Could not load the full output.',
    queueRemoveFailed: 'Could not remove the queued message.',
    setFailed: 'Could not change that setting.',
    takeoverFailed: 'Could not take over the session.',
    notFound: 'Not found.',
    sessionMissing: 'This session is no longer available.',
    deviceOffline: 'That device is offline.',
    conflictTerminal: 'This session is controlled by the terminal. Take over first.',
    timeout: 'The device did not answer in time.',
    unsupported: 'Not supported by this device.',
    tooLarge: 'That payload is too large.',
  },

  a11y: {
    statusDot: (state: string) => `Status: ${state}`,
    openMenu: 'Open menu',
    closeDialog: 'Close dialog',
    toolRow: 'Tool call',
    expandRow: 'Expand row',
    collapseRow: 'Collapse row',
  },
} as const;

export const agentLabels: Record<string, string> = {
  claude: 'Claude Code',
  codex: 'Codex',
};

const agentMarks: Record<string, string> = {
  claude: 'C',
  codex: 'X',
};

export function agentLabel(agent: string): string {
  return agentLabels[agent] ?? agent;
}

/** Single-character mark used by the agent picker. */
export function agentMark(agent: string): string {
  return agentMarks[agent] ?? agent.charAt(0).toUpperCase();
}

export const stateLabels: Record<string, string> = {
  starting: 'starting',
  idle: 'idle',
  running: 'running',
  needs_approval: 'needs approval',
  needs_input: 'needs input',
  error: 'error',
  stopped: 'stopped',
  readonly: 'terminal',
};

export function stateLabel(state: string): string {
  return stateLabels[state] ?? state;
}

/**
 * The tooltip on a session dot. It names the tone `dotTone` picked, while the
 * dot's accessibility label stays the raw state. The table is in
 * `docs/DESIGN.md`.
 */
export const dotToneLabels: Record<string, string> = {
  working: 'Working',
  waiting: 'Waiting for you',
  live: 'Live',
  off: 'Off',
  failed: 'Failed',
};

export function dotToneLabel(tone: string): string {
  return dotToneLabels[tone] ?? tone;
}

/**
 * Row label for a session. Amendment A7 lets a terminal-controlled session
 * report `running`, so `control` has to stay visible in the list. A10 adds
 * `shared`: a terminal session the device is attached to.
 */
export function sessionStateLabel(session: { state: string; control: string }): string {
  if (session.control === 'shared') return 'terminal · attached';
  if (session.control !== 'terminal') return stateLabel(session.state);
  const busy =
    session.state === 'running' ||
    session.state === 'starting' ||
    session.state === 'needs_approval' ||
    session.state === 'needs_input';
  return busy ? `terminal · ${stateLabel(session.state)}` : 'terminal';
}

export const languageLabels: Record<string, string> = {
  auto: 'Auto',
  zh: '中文',
  en: 'English',
  ja: '日本語',
  ko: '한국어',
  de: 'Deutsch',
  fr: 'Français',
  es: 'Español',
};

export function languageLabel(code: string): string {
  return languageLabels[code] ?? code.toUpperCase();
}
