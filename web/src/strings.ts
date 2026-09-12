/**
 * The app's own words, in English, and the type every other table implements.
 *
 * `strings` is a view on whichever table the interface language names, so a
 * component keeps writing `strings.x.y` and follows the setting without holding
 * a copy of anything. `stores/settings.ts` owns the choice; `App` re-renders the
 * screens on it. Nothing a device reported is ever translated — agent output,
 * device names, paths, branches, model and permission ids, and the agent labels
 * below all stay as they arrived.
 */
import { useSettings } from './stores/settings';
import type { InterfaceLanguage } from './stores/settings';
import type { TimelineDetail } from './stores/timeline';
import { zhHans } from './strings.zh-Hans';

export const en = {
  productName: 'Remote Control',

  nav: {
    devices: 'Devices',
    sessions: 'Sessions',
    settings: 'Settings',
    backToSessions: 'Back to sessions',
    primary: 'Primary',
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
    clientBuild: (version: string, build: string) => `client ${version} · ${build}`,
    clientVersion: (version: string) => `client ${version}`,
    update: 'Update',
    updateAvailable: 'Update available',
    updating: 'Updating…',
    updateFailed: (message: string) => `Update failed · ${message}`,
    updateTitle: 'Update device',
    updateBody: (name: string) =>
      `Update ${name} to the gateway's client? Its service restarts; sessions it drives are stopped.`,
    updateConfirm: 'Update device',
    updateOffline: 'This device is offline.',
    updateInFlight: 'This device is already updating.',
    updateCurrent: 'This device runs the build the gateway serves.',
    updateNoBuild: 'This gateway is not serving a client build.',
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
    scanTitle: 'From your phone',
    scanBody: 'The host prints a QR code. Scan it with the phone app, or open its link here.',
    scanCommand: (origin: string) => `curl -fsSL ${origin}/install.sh | sh`,
    claimTitle: 'Pair this host',
    claimIntro: 'The host that printed this code is joining your gateway.',
    claiming: 'Claiming this code…',
    claimInvalid: 'This link carries no code.',
    claimExpired: 'This code has expired. Run the command again on the host.',
    claimUsed: 'This code was already used.',
    claimFailed: 'Could not claim this code.',
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
    archiveGroup: (n: number) => `Archive · ${n}`,
    archived: 'Archived',
    deviceOffline: 'Device offline',
    open: 'Open session',
    untitled: 'Untitled session',
  },

  newSession: {
    title: 'New session',
    continuingOn: (device: string) => `Continuing on ${device}`,
    pickDevice: 'Pick a device to start on',
    device: 'Device',
    agent: 'Agent',
    agentUnavailable: 'not installed',
    model: 'Model',
    effort: 'Effort',
    permissions: 'Permissions',
    speed: 'Speed',
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
    newUpdates: (n: number) => `${n} new`,
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
    /** A20: the question was answered in the CLI's own dialog, not from here. */
    questionAnsweredInTerminal: 'Answered in the terminal',
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
    /** A20: what Send becomes while the timeline holds a pending question. */
    answer: 'Answer',
    placeholderAnswer: 'Type your answer…',
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
    /** A21: the one chip that carries the model, the effort and the tier. */
    modelCard: 'Model and effort',
    /** A21: "Speed, Fast" / "Speed, Standard" on the tier toggle. */
    speed: (tier: string) => `Speed, ${tier}`,
    speedStandard: 'Standard',
    /** The accessible name of a control in the card: "Model, Opus 4.6". */
    option: (name: string, value: string) => `${name}, ${value}`,
    /** A17: what a terminal chose, shown where its picker would be. */
    setInTerminal: (name: string, value: string) => `${name} · ${value} · set in the terminal`,
    language: 'Voice language',
    sendFailed: 'Could not send the message.',
  },

  voice: {
    transcribing: 'Transcribing live · edit before sending',
    connecting: 'Connecting…',
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
    language: 'Language',
    timeline: 'Timeline',
    timelineDetail: 'Detail',
    timelineDetailNote:
      'Simple shows only what is written to you. Detailed adds thinking, tool calls and the task list.',
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

  /** Word lists the helpers below read. Unknown ids fall back to the id itself. */
  labels: {
    state: {
      starting: 'starting',
      idle: 'idle',
      running: 'running',
      needs_approval: 'needs approval',
      needs_input: 'needs input',
      error: 'error',
      stopped: 'stopped',
      readonly: 'terminal',
    } as Record<string, string>,
    /**
     * The tooltip on a session dot. It names the tone `dotTone` picked, while
     * the dot's accessibility label stays the raw state. The table is in
     * `docs/DESIGN.md`.
     */
    dotTone: {
      working: 'Working',
      waiting: 'Waiting for you',
      live: 'Live',
      off: 'Off',
      failed: 'Failed',
    } as Record<string, string>,
    /** Speech-to-text languages. Every name but "Auto" is its own endonym. */
    voiceLanguage: {
      auto: 'Auto',
      zh: '中文',
      en: 'English',
      ja: '日本語',
      ko: '한국어',
      de: 'Deutsch',
      fr: 'Français',
      es: 'Español',
    } as Record<string, string>,
    /** The two timeline detail levels, in the order Settings offers them. */
    timelineDetail: { simple: 'Simple', detailed: 'Detailed' } as Record<TimelineDetail, string>,
    terminal: 'terminal',
    terminalAttached: 'terminal · attached',
    terminalBusy: (state: string) => `terminal · ${state}`,
  },

  /** Relative times and durations. Numbers stay; only the words move. */
  format: {
    /** Passed to `toLocaleDateString` for dates older than a week. */
    dateLocale: 'en',
    now: 'now',
    minutes: (n: number) => `${n}m`,
    hours: (n: number) => `${n}h`,
    days: (n: number) => `${n}d`,
    justNow: 'just now',
    minutesAgo: (n: number) => `${n}m ago`,
    hoursAgo: (n: number) => `${n}h ago`,
    yesterday: 'yesterday',
    daysAgo: (n: number) => `${n}d ago`,
    millis: (n: number) => `${n}ms`,
    seconds: (s: string) => `${s}s`,
    minutesSeconds: (m: number, s: number) => `${m}m ${s}s`,
    hoursMinutes: (h: number, m: number) => `${h}h ${m}m`,
  },
};

/** Every table implements this. The compiler rejects an incomplete one. */
export type StringTable = typeof en;

export const stringTables: Record<InterfaceLanguage, StringTable> = { en, 'zh-Hans': zhHans };

function table(): StringTable {
  return stringTables[useSettings.getState().language];
}

/**
 * Reads through to the table the setting names, so every `strings.x.y` in the
 * app follows a language change without a single call site knowing about it.
 */
export const strings: StringTable = new Proxy({} as StringTable, {
  get: (_target, key) => table()[key as keyof StringTable],
  has: (_target, key) => key in table(),
});

/** Product names, never translated. */
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

/** The two interface languages, each written in its own script. */
export const interfaceLanguageLabels: Record<InterfaceLanguage, string> = {
  en: 'English',
  'zh-Hans': '中文',
};

export function stateLabel(state: string): string {
  return strings.labels.state[state] ?? state;
}

export function dotToneLabel(tone: string): string {
  return strings.labels.dotTone[tone] ?? tone;
}

export function languageLabel(code: string): string {
  return strings.labels.voiceLanguage[code] ?? code.toUpperCase();
}

export function timelineDetailLabel(detail: TimelineDetail): string {
  return strings.labels.timelineDetail[detail];
}

/**
 * Row label for a session. Amendment A7 lets a terminal-controlled session
 * report `running`, so `control` has to stay visible in the list. A10 adds
 * `shared`: a terminal session the device is attached to.
 */
export function sessionStateLabel(session: { state: string; control: string }): string {
  if (session.control === 'shared') return strings.labels.terminalAttached;
  if (session.control !== 'terminal') return stateLabel(session.state);
  const busy =
    session.state === 'running' ||
    session.state === 'starting' ||
    session.state === 'needs_approval' ||
    session.state === 'needs_input';
  return busy ? strings.labels.terminalBusy(stateLabel(session.state)) : strings.labels.terminal;
}

/**
 * The name every surface prints for a session. A thread the agent has not named
 * yet arrives with an empty `title`; the row, the chat header and the sidebar
 * all fall back to the same words in the title's own type, rather than leaving a
 * blank line above the meta (`docs/DESIGN.md` § "Session lists"). The session
 * search reads the same value, so an untitled row is found by those words too.
 */
export function sessionTitle(session: { title: string }): string {
  return session.title.trim() || strings.sessions.untitled;
}
