import Foundation

/// Typed fixtures for the offline demo and for previews. They mirror the
/// examples in the frozen protocol document.
public enum DemoFixtures {
    public static let macDeviceID = "demo-mac-studio"
    public static let ciDeviceID = "demo-ci-runner"
    public static let laptopDeviceID = "demo-macbook-air"
    public static let liveSessionID = "demo-session-auth"
    public static let approvalSessionID = "demo-session-vite"
    public static let doneSessionID = "demo-session-otlp"
    /// A session a terminal owns outright, which the app can only read.
    public static let terminalSessionID = "demo-session-push"
    /// Amendment A10: a terminal session this device is attached to.
    public static let sharedSessionID = "demo-session-shared"
    /// Amendment A10: a terminal session on a device whose shim is not
    /// installed, so the app can only explain how to make it controllable.
    public static let attachHintSessionID = "demo-session-rename"
    /// Amendment A11: a Codex thread shared through the app-server daemon. The
    /// attachment carries settings, attachments and an interrupt, so the app
    /// drives it as fully as one it started itself.
    public static let codexSharedSessionID = "demo-session-typecheck"
    /// A turn that ended badly on a machine that is still reachable, so the
    /// list carries the red dot as well as the other four.
    public static let erroredSessionID = "demo-session-toolchain"
    /// Amendment A15: a session the user archived by hand, which the demo
    /// device brings back to life shortly after the list opens.
    public static let revivedSessionID = "demo-session-changelog"
    /// Amendment A25: a Grok Build session a terminal started, mirrored from
    /// the update log Grok keeps, so the app reads it and cannot write to it.
    /// Amendment A28: it runs on the machine whose Grok is configured without
    /// the leader, which is the only way a Grok session is still terminal-held.
    public static let grokSessionID = "demo-session-migrations"
    /// Amendment A28: a Grok Build session a terminal started inside the
    /// leader, which the device joined as another client of the same process.
    /// The leader relays an interrupt and the session settings but takes no
    /// images, so the composer keeps every control except the attachment.
    public static let grokSharedSessionID = "demo-session-retries"
    /// Amendment A26: a pi session. pi's permission modes are the device's own,
    /// enforced by the extension it loads, so the composer row carries the
    /// permission chip exactly as Codex's does.
    public static let piSessionID = "demo-session-parser"
    /// Amendment A35: a session the five-hour window stopped, with a resume the
    /// device scheduled for a minute after the window resets. It is attached,
    /// which is the case that can be resumed and the case that can be dropped.
    public static let pausedSessionID = "demo-session-indexer"

    public static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    public static var claude: AgentInfo {
        AgentInfo(
            agent: "claude", available: true, version: "2.1.266", path: "/usr/local/bin/claude",
            models: [AgentOption(id: "claude-sonnet-4-5", label: "Sonnet 4.5"),
                     AgentOption(id: "claude-opus-4-1", label: "Opus 4.1")],
            defaultModel: "claude-sonnet-4-5",
            permissionModes: [AgentOption(id: "default", label: "Ask before edits"),
                              AgentOption(id: "acceptEdits", label: "Auto-accept edits"),
                              AgentOption(id: "plan", label: "Plan mode"),
                              AgentOption(id: "bypassPermissions", label: "Bypass permissions")],
            defaultPermissionMode: "acceptEdits",
            efforts: [AgentOption(id: "medium", label: "Medium"), AgentOption(id: "high", label: "High")],
            defaultEffort: "high",
            capabilities: [.worktree, .takeover, .interrupt, .queue, .attachments, .effort, .history],
            attach: .channel, attachReady: true, sharedInterrupt: false,
            accounts: [AgentAccount(provider: "anthropic", method: .account, plan: "max",
                                    tier: "Max 5x", email: "me@example.com")])
    }

    /// The same agent on a machine where the `claude` shim was never installed,
    /// so its terminal sessions cannot be attached (amendment A10).
    public static var claudeWithoutShim: AgentInfo {
        AgentInfo(
            agent: "claude", available: true, version: "2.1.266", path: "/usr/local/bin/claude",
            models: claude.models, defaultModel: claude.defaultModel,
            permissionModes: claude.permissionModes, defaultPermissionMode: "default",
            efforts: claude.efforts, defaultEffort: claude.defaultEffort,
            capabilities: claude.capabilities,
            attach: .channel, attachReady: false, sharedInterrupt: false,
            accounts: [AgentAccount(provider: "anthropic", method: .account, plan: "pro",
                                    email: "me@example.com")])
    }

    /// Amendment A11: Codex behind a running app-server daemon. Everything the
    /// daemon relays is true here — an interrupt, the thread settings and image
    /// inputs — so a `shared` session keeps every control.
    public static var codex: AgentInfo {
        AgentInfo(
            agent: "codex", available: true, version: "0.154.0",
            path: "/Users/me/.codex/packages/standalone/current/bin/codex",
            models: [AgentOption(id: "gpt-5.4-codex", label: "GPT-5.4 Codex"),
                     AgentOption(id: "gpt-5.4-codex-mini", label: "GPT-5.4 Codex mini")],
            defaultModel: "gpt-5.4-codex",
            permissionModes: [AgentOption(id: "untrusted", label: "Ask for everything"),
                              AgentOption(id: "on-request", label: "Ask when needed"),
                              AgentOption(id: "never", label: "Never ask")],
            defaultPermissionMode: "on-request",
            efforts: [AgentOption(id: "low", label: "Low"),
                      AgentOption(id: "medium", label: "Medium"),
                      AgentOption(id: "high", label: "High")],
            defaultEffort: "medium",
            speeds: [AgentOption(id: "priority", label: "Fast")],
            capabilities: [.worktree, .interrupt, .queue, .steer, .attachments, .effort, .history,
                           .commands],
            attach: .daemon, attachReady: true, sharedInterrupt: true,
            sharedSettings: true, sharedAttachments: true,
            accounts: [AgentAccount(provider: "openai", method: .account, plan: "pro",
                                    email: "me@example.com")])
    }

    /// The same agent on a machine where the app-server daemon is not running,
    /// so its terminal threads cannot be attached (amendments A10 and A11).
    public static var codexWithoutDaemon: AgentInfo {
        AgentInfo(
            agent: "codex", available: true, version: codex.version, path: codex.path,
            models: codex.models, defaultModel: codex.defaultModel,
            permissionModes: codex.permissionModes, defaultPermissionMode: codex.defaultPermissionMode,
            efforts: codex.efforts, defaultEffort: codex.defaultEffort, speeds: codex.speeds,
            capabilities: codex.capabilities,
            attach: .daemon, attachReady: false,
            accounts: [AgentAccount(provider: "openai", method: .account, plan: "plus",
                                    email: "me@example.com")])
    }

    /// Amendment A25: Grok Build, driven over its ACP JSON-RPC. Amendment A28:
    /// its terminals join one leader process per machine, which the device
    /// joins too, so `session/cancel` and `session/set_config_option` from here
    /// act on the session everyone is in — and a prompt carries no images.
    /// Exactly what `protocol/fixtures/objects/agent.grok.json` advertises.
    public static var grok: AgentInfo {
        AgentInfo(
            agent: "grok", available: true, version: "1.0.30", path: "/Users/me/.grok/bin/agent",
            models: [AgentOption(id: "grok-4.6", label: "Grok 4.6"),
                     AgentOption(id: "grok-4.5", label: "Grok 4.5")],
            defaultModel: "grok-4.6",
            permissionModes: [AgentOption(id: "default", label: "Ask when needed"),
                              AgentOption(id: "acceptEdits", label: "Auto-accept edits"),
                              AgentOption(id: "auto", label: "Auto mode"),
                              AgentOption(id: "dontAsk", label: "Deny unless allowed"),
                              AgentOption(id: "plan", label: "Plan mode"),
                              AgentOption(id: "bypassPermissions", label: "Bypass permissions")],
            defaultPermissionMode: "default",
            efforts: [AgentOption(id: "low", label: "Low"),
                      AgentOption(id: "medium", label: "Medium"),
                      AgentOption(id: "high", label: "High"),
                      AgentOption(id: "xhigh", label: "Extra high")],
            defaultEffort: "high",
            capabilities: [.worktree, .interrupt, .queue, .effort, .history, .commands],
            attach: .leader, attachReady: true, sharedInterrupt: true, sharedSettings: true,
            accounts: [AgentAccount(provider: "xai", method: .account, plan: nil,
                                    email: "me@example.com")])
    }

    /// Amendment A28: the same agent on a machine whose `~/.grok/config.toml`
    /// leaves `[cli] use_leader` off, so a `grok` started there runs its own
    /// backend and nothing can join it. What the leader relays is a property of
    /// the leader, not of this machine, so only the readiness differs.
    public static var grokWithoutLeader: AgentInfo {
        AgentInfo(
            agent: "grok", available: true, version: grok.version, path: grok.path,
            models: grok.models, defaultModel: grok.defaultModel,
            permissionModes: grok.permissionModes, defaultPermissionMode: grok.defaultPermissionMode,
            efforts: grok.efforts, defaultEffort: grok.defaultEffort,
            capabilities: grok.capabilities,
            attach: .leader, attachReady: false, sharedInterrupt: true, sharedSettings: true,
            // Amendment A33: installed, signed in nowhere.
            accounts: [])
    }

    /// Amendment A26: the pi coding agent behind the device's own extension,
    /// which pi loads into every session it runs. The extension enforces pi's
    /// three permission modes and carries an interrupt, the session settings
    /// and image inputs, so a `shared` pi session keeps every control.
    /// Exactly what `protocol/fixtures/objects/agent.pi.json` advertises.
    public static var pi: AgentInfo {
        AgentInfo(
            agent: "pi", available: true, version: "0.85.1", path: "/Users/me/.local/bin/pi",
            models: [AgentOption(id: "anthropic/claude-sonnet-4-5", label: "Claude Sonnet 4.5"),
                     AgentOption(id: "openai/gpt-5", label: "GPT-5")],
            defaultModel: "anthropic/claude-sonnet-4-5",
            permissionModes: [AgentOption(id: "untrusted", label: "Ask for everything"),
                              AgentOption(id: "on-request", label: "Ask when needed"),
                              AgentOption(id: "never", label: "Never ask")],
            defaultPermissionMode: "on-request",
            efforts: [AgentOption(id: "off", label: "Off"),
                      AgentOption(id: "low", label: "Low"),
                      AgentOption(id: "medium", label: "Medium"),
                      AgentOption(id: "high", label: "High")],
            defaultEffort: "medium",
            capabilities: [.worktree, .interrupt, .queue, .steer, .attachments, .effort, .history,
                           .commands],
            attach: .extension, attachReady: true, sharedInterrupt: true,
            sharedSettings: true, sharedAttachments: true,
            // Amendment A33: pi signs in per provider, so it holds one
            // credential per vendor — here a subscription and a relayed key.
            accounts: [AgentAccount(provider: "anthropic", method: .account, plan: "max",
                                    email: "me@example.com"),
                       AgentAccount(provider: "openai", method: .apiKey,
                                    endpoint: "api.relay.example")])
    }

    // MARK: - Accounts and quota (A33)

    /// What `device.agents` answers for one machine: the same credentials the
    /// device list already carries, with the rate-limit windows the device read
    /// for them. Nothing here reaches the stored device, which is the whole
    /// point of asking for them on demand.
    ///
    /// The four shapes a page has to draw are spread across the demo machines:
    /// an account with windows, an account whose vendor exposes none (Grok
    /// Build), an account the device could not read (an expired token), and a
    /// key, which never has a window to measure.
    public static func agentsWithQuota(deviceID: String) -> [AgentInfo]? {
        switch deviceID {
        case macDeviceID:
            return [claude.with(accounts: [anthropicMax.with(limits: claudeWindows)]),
                    codex.with(accounts: [openAIPro.with(limits: codexWindows)]),
                    grok,
                    pi.with(accounts: [piAnthropic.with(limits: piWindows),
                                       relayedKey])]
        case laptopDeviceID:
            return [claudeWithoutShim.with(accounts: [
                        anthropicPro.with(limitsError: "signed-in token expired; open Claude Code once to refresh it")
                    ]),
                    grokWithoutLeader]
        default:
            return nil
        }
    }

    private static var anthropicMax: AgentAccount {
        AgentAccount(provider: "anthropic", method: .account, plan: "max",
                     tier: "Max 5x", email: "me@example.com")
    }

    private static var anthropicPro: AgentAccount {
        AgentAccount(provider: "anthropic", method: .account, plan: "pro", email: "me@example.com")
    }

    private static var openAIPro: AgentAccount {
        AgentAccount(provider: "openai", method: .account, plan: "pro", email: "me@example.com")
    }

    private static var piAnthropic: AgentAccount {
        AgentAccount(provider: "anthropic", method: .account, plan: "max", email: "me@example.com")
    }

    private static var relayedKey: AgentAccount {
        AgentAccount(provider: "openai", method: .apiKey, endpoint: "api.relay.example")
    }

    /// A five-hour window, a week, and the week one model is confined to.
    private static var claudeWindows: [AgentLimit] {
        [AgentLimit(windowMinutes: 300, usedPercent: 16, resetsAt: now + 7_200_000),
         AgentLimit(windowMinutes: 10080, usedPercent: 54, resetsAt: now + 205_200_000),
         AgentLimit(windowMinutes: 10080, scope: "Fable", usedPercent: 64,
                    resetsAt: now + 205_200_000)]
    }

    /// The shared daemon's two, the second of them nearly spent.
    private static var codexWindows: [AgentLimit] {
        [AgentLimit(windowMinutes: 300, usedPercent: 37, resetsAt: now + 5_400_000),
         AgentLimit(windowMinutes: 10080, usedPercent: 93, resetsAt: now + 291_600_000)]
    }

    private static var piWindows: [AgentLimit] {
        [AgentLimit(windowMinutes: 300, usedPercent: 8, resetsAt: now + 7_200_000),
         AgentLimit(windowMinutes: 10080, usedPercent: 100, resetsAt: now + 205_200_000)]
    }

    /// When this demo device says it read the windows: the moment it answers.
    static var checkedNow: Int64 { now }

    // MARK: - Slash commands (A27)

    /// What each agent offers the moment `/` is typed, in the shape the device
    /// reports it: Codex a fixed table with one source and so no groups, Grok
    /// Build the list its agent advertises over ACP, pi its prompt templates,
    /// its skills, its extension commands and the device's own `compact`.
    /// Claude offers none at all and never lists the capability.
    public static func commands(for agent: String) -> [Command] {
        switch agent {
        case "codex": codexCommands
        case "grok": grokCommands
        case "pi": piCommands
        default: []
        }
    }

    public static let codexCommands = [
        Command(name: "compact", description: "Summarise the conversation to free up context"),
        Command(name: "review", description: "Review the working tree's changes and report issues",
                argument: "instructions"),
        Command(name: "init", description: "Write an AGENTS.md for this repository"),
        Command(name: "status", description: "Show the session's model, settings and token use"),
        Command(name: "usage", description: "Show account usage and when the limits reset"),
        Command(name: "skills", description: "List the skills this session can use"),
        Command(name: "hooks", description: "List the lifecycle hooks this session runs"),
        Command(name: "mcp", description: "List the MCP servers and the tools they bring")
    ]

    public static let grokCommands = [
        Command(name: "compact", description: "Compress the conversation so far"),
        Command(name: "context", description: "Show what is filling the context window"),
        Command(name: "session-info", description: "Show this session's id, model and token use"),
        Command(name: "hooks-list", description: "List the hooks this project runs"),
        Command(name: "hooks-add", description: "Add a hook to this project", argument: "event:command"),
        Command(name: "plugins", description: "List the installed plugins"),
        Command(name: "goal", description: "Set the goal for a long task", argument: "goal"),
        Command(name: "loop", description: "Repeat a task until it passes", argument: "instructions"),
        Command(name: "workflow", description: "Run a saved workflow", argument: "name"),
        Command(name: "deep-research", description: "Research a question across the web",
                argument: "question"),
        Command(name: "review", description: "Review the working tree's changes"),
        Command(name: "implement", description: "Implement a plan step by step", argument: "plan")
    ]

    public static let piCommands = [
        Command(name: "release-notes", description: "Draft release notes from the commits since a tag",
                argument: "tag", group: "Prompts"),
        Command(name: "changelog", description: "Write the changelog entry for today's work",
                group: "Prompts"),
        Command(name: "standup", description: "Summarise yesterday's work for standup", group: "Prompts"),
        Command(name: "skill:pdf-tables", description: "Extract tables from a PDF into CSV",
                group: "Skills"),
        Command(name: "skill:web-research", description: "Search the web and summarise what it finds",
                argument: "question", group: "Skills"),
        Command(name: "skill:screenshot", description: "Take a screenshot of a running page",
                argument: "url", group: "Skills"),
        Command(name: "remote-control:status",
                description: "Show what the remote-control extension is attached to",
                group: "Extensions"),
        Command(name: "remote-control:handoff", description: "Hand this session back to the terminal",
                group: "Extensions"),
        Command(name: "compact", description: "Summarise the conversation to free up context",
                argument: "instructions", group: "Built-in")
    ]

    /// Amendment A22: the build this demo gateway serves, and the older one the
    /// laptop is still on so a machine the gateway could not bring forward can
    /// be looked at (A36).
    public static let servedBuild = "3f2b4a9c1d8e7f60a5b4c3d2e1f0918273645a5b6c7d8e9f0a1b2c3d4e5f6a7b"
    public static let outdatedBuild = "9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9e8d"
    /// The versions those two builds are, so the demo's rows and its Update
    /// confirmation name a client the way a real gateway would. A round ships
    /// all four components on one version, so what the demo gateway serves is
    /// whatever this app is: the demo cannot fall a round behind.
    public static let servedClientVersion = AppBuild.version
    public static let outdatedClientVersion = "1.3.0"
    /// Why the gateway's own attempt on the laptop did not finish (A36). It is
    /// the message the gateway keeps when a device never comes back, and it is
    /// what puts Retry update on that one row.
    public static let updateFailure = "the device did not come back"

    public static var devices: [Device] {
        [
            Device(deviceID: macDeviceID, name: "mac-studio-office", platform: .macos,
                   hostname: "mac-studio.local", arch: "arm64",
                   clientVersion: servedClientVersion, clientBuild: servedBuild,
                   online: true, lastSeen: now, createdAt: now - 8_640_000, latencyMS: 18,
                   // Amendment A26: one machine with all four agents on it, so
                   // the picker, the card and a session of each can be seen.
                   agents: [claude, codex, grok, pi]),
            Device(deviceID: laptopDeviceID, name: "macbook-air", platform: .macos,
                   hostname: "macbook-air.local", arch: "arm64",
                   clientVersion: outdatedClientVersion, clientBuild: outdatedBuild,
                   // The one machine the gateway could not bring to its wheel,
                   // so the notice and Retry update have somewhere to be seen.
                   updateState: .failed, updateMessage: updateFailure,
                   online: true, lastSeen: now, createdAt: now - 4_320_000, latencyMS: 41,
                   // Amendment A28: the machine that is prepared for neither
                   // attachment, so both hints can be read on a real session.
                   agents: [claudeWithoutShim, grokWithoutLeader]),
            Device(deviceID: ciDeviceID, name: "ci-runner-01", platform: .linux,
                   hostname: "ci-runner-01", arch: "x86_64",
                   clientVersion: outdatedClientVersion, clientBuild: outdatedBuild,
                   online: false, lastSeen: now - 3_600_000, createdAt: now - 86_400_000,
                   latencyMS: nil, agents: [codexWithoutDaemon])
        ]
    }

    public static var sessions: [Session] {
        [
            Session(sessionID: liveSessionID, deviceID: macDeviceID, agent: "claude",
                    title: "Fix flaky auth test", cwd: "/Users/me/dev/remote-control/gateway",
                    git: GitInfo(branch: "main", dirty: true, ahead: 1),
                    state: .running, origin: .remote, control: .remote,
                    model: "claude-sonnet-4-5", permissionMode: "acceptEdits", effort: "high",
                    createdAt: now - 600_000, updatedAt: now - 4_000, lastSeq: 0,
                    turn: TurnMarker(turnID: "demo-turn-1", startedAt: now - 252_000),
                    todos: TodoCounts(total: 4, done: 1),
                    usage: SessionUsage(inputTokens: 32_000, outputTokens: 16_200, totalTokens: 48_200,
                                        contextUsed: 61_000, contextWindow: 200_000, costUSD: 0.42)),
            Session(sessionID: approvalSessionID, deviceID: macDeviceID, agent: "codex",
                    title: "Migrate web to Vite 6", cwd: "/Users/me/dev/remote-control/web",
                    git: GitInfo(branch: "vite-6", dirty: true),
                    state: .needsApproval, origin: .terminal, control: .remote,
                    model: "gpt-5.4-codex", permissionMode: "on-request",
                    createdAt: now - 1_800_000, updatedAt: now - 60_000, lastSeq: 0),
            // Amendment A7: a terminal session reports `running` while its turn
            // runs and `readonly` only when idle. Both are locked to the app.
            // Amendment A17: the device read all three settings out of the
            // transcript, so the composer can show what this terminal chose.
            Session(sessionID: terminalSessionID, deviceID: macDeviceID, agent: "claude",
                    title: "iOS push tokens", cwd: "/Users/me/dev/remote-control/ios",
                    git: GitInfo(branch: "main", dirty: false),
                    state: .running, origin: .terminal, control: .terminal,
                    model: "claude-sonnet-4-5", permissionMode: "default", effort: "high",
                    createdAt: now - 10_800_000, updatedAt: now - 120_000, lastSeq: 0,
                    turn: TurnMarker(turnID: "demo-turn-terminal", startedAt: now - 120_000)),
            // Amendment A10: a CLI still owns this session, but the device is
            // attached to it, so the composer and approvals work as usual.
            // Amendment A17: the channel carries no `session.set`, so the three
            // settings are shown rather than offered — and `auto` is a
            // permission mode the transcript has but the agent never lists, so
            // the chip shows the raw id.
            Session(sessionID: sharedSessionID, deviceID: macDeviceID, agent: "claude",
                    title: "Tidy the release notes", cwd: "/Users/me/dev/remote-control/docs",
                    git: GitInfo(branch: "main", dirty: true),
                    state: .idle, origin: .terminal, control: .shared,
                    model: "claude-sonnet-4-5", permissionMode: "auto", effort: "high",
                    createdAt: now - 5_400_000, updatedAt: now - 90_000, lastSeq: 0),
            // Amendment A11: a Codex thread the terminal started, shared through
            // the app-server daemon. The attachment carries an interrupt, the
            // thread settings and image inputs, so nothing here is dimmed.
            Session(sessionID: codexSharedSessionID, deviceID: macDeviceID, agent: "codex",
                    title: "Typecheck the web app", cwd: "/Users/me/dev/remote-control/web",
                    git: GitInfo(branch: "feat/settings-drawer", dirty: true, ahead: 2),
                    state: .running, stateDetail: "Typed in the terminal",
                    origin: .terminal, control: .shared,
                    model: "gpt-5.4-codex", permissionMode: "on-request", effort: "medium",
                    createdAt: now - 900_000, updatedAt: now - 12_000, lastSeq: 0,
                    turn: TurnMarker(turnID: "demo-turn-codex", startedAt: now - 42_000),
                    usage: SessionUsage(inputTokens: 14_980, outputTokens: 1_740, totalTokens: 16_720,
                                        contextUsed: 19_300, contextWindow: 272_000)),
            // Amendment A10: the same CLI on a machine without the shim. The
            // app can only say how to make the next run controllable.
            Session(sessionID: attachHintSessionID, deviceID: laptopDeviceID, agent: "claude",
                    title: "Rename the pairing flow", cwd: "/Users/me/dev/remote-control/client",
                    git: GitInfo(branch: "pairing", dirty: true),
                    state: .readonly, origin: .terminal, control: .terminal,
                    model: "claude-sonnet-4-5", permissionMode: "default",
                    createdAt: now - 2_700_000, updatedAt: now - 300_000, lastSeq: 0),
            // The agent stopped on an error, and the machine is still there to
            // say so: a red dot, told apart from the grey of a session nothing
            // owns any more.
            Session(sessionID: erroredSessionID, deviceID: macDeviceID, agent: "claude",
                    title: "Bump the Swift toolchain", cwd: "/Users/me/dev/remote-control/ios",
                    git: GitInfo(branch: "toolchain", dirty: true),
                    state: .error, stateDetail: "The agent exited before the build finished",
                    origin: .remote, control: .remote,
                    model: "claude-sonnet-4-5", permissionMode: "default",
                    createdAt: now - 1_200_000, updatedAt: now - 30_000, lastSeq: 0),
            // Amendment A15: archived by hand and no longer owned, until a
            // terminal attaches to it again and the device clears the flag.
            Session(sessionID: revivedSessionID, deviceID: macDeviceID, agent: "claude",
                    title: "Draft the changelog", cwd: "/Users/me/dev/remote-control",
                    git: GitInfo(branch: "main"),
                    state: .stopped, origin: .terminal, control: .none,
                    model: "claude-sonnet-4-5", permissionMode: "default",
                    createdAt: now - 9_000_000, updatedAt: now - 5_400_000, lastSeq: 0,
                    archived: true),
            // Amendment A25: Grok Build writes its own update log, so a session
            // started in a terminal is mirrored and read here. The summary
            // carries the model and the effort but never a permission mode, so
            // the composer shows one chip where three would have stood (A17).
            // Amendment A28: this `grok` was started on the machine that leaves
            // `[cli] use_leader` off, so it runs its own backend and stays
            // terminal-held however long the app looks at it.
            Session(sessionID: grokSessionID, deviceID: laptopDeviceID, agent: "grok",
                    title: "Squash the pending migrations", cwd: "/Users/me/dev/remote-control/gateway",
                    git: GitInfo(branch: "migrations", dirty: true),
                    state: .readonly, origin: .terminal, control: .terminal,
                    model: "grok-4.6", effort: "high",
                    createdAt: now - 3_000_000, updatedAt: now - 240_000, lastSeq: 0),
            // Amendment A28: a Grok session a terminal started inside the
            // leader. The device joined the same process, so the turn the TUI
            // set off is stoppable from here and the settings are live; only
            // the attachment button is gone, because a prompt takes no images.
            Session(sessionID: grokSharedSessionID, deviceID: macDeviceID, agent: "grok",
                    title: "Trim the gateway's retry budget",
                    cwd: "/Users/me/dev/remote-control/gateway",
                    git: GitInfo(branch: "retries", dirty: true, ahead: 1),
                    state: .running, stateDetail: "Typed in the terminal",
                    origin: .terminal, control: .shared,
                    model: "grok-4.6", permissionMode: "default", effort: "high",
                    createdAt: now - 720_000, updatedAt: now - 8_000, lastSeq: 0,
                    turn: TurnMarker(turnID: "demo-turn-grok-shared", startedAt: now - 36_000)),
            // Amendment A26: pi's permission modes are the device's own, so the
            // composer row carries the model card and the permission chip.
            Session(sessionID: piSessionID, deviceID: macDeviceID, agent: "pi",
                    title: "Rewrite the config parser", cwd: "/Users/me/dev/remote-control/client",
                    git: GitInfo(branch: "config-parser", dirty: true, ahead: 3),
                    state: .idle, origin: .remote, control: .remote,
                    model: "anthropic/claude-sonnet-4-5", permissionMode: "on-request",
                    effort: "medium",
                    createdAt: now - 1_200_000, updatedAt: now - 150_000, lastSeq: 0),
            // Amendment A35: stopped at the five-hour window with a resume
            // pending. The dot stays amber — the session is idle and the notice
            // above the transcript is what carries the pause.
            Session(sessionID: pausedSessionID, deviceID: macDeviceID, agent: "claude",
                    title: "Reindex the search corpus",
                    cwd: "/Users/me/dev/remote-control/gateway",
                    git: GitInfo(branch: "search-index", dirty: true),
                    state: .idle, origin: .terminal, control: .shared,
                    model: "claude-sonnet-4-5", permissionMode: "acceptEdits", effort: "high",
                    createdAt: now - 21_600_000, updatedAt: now - 1_800_000, lastSeq: 0,
                    usage: SessionUsage(inputTokens: 128_400, outputTokens: 9_600,
                                        totalTokens: 138_000, contextUsed: 151_000,
                                        contextWindow: 200_000, costUSD: 1.84),
                    resume: pendingResume),
            Session(sessionID: doneSessionID, deviceID: ciDeviceID, agent: "codex",
                    title: "Add OTLP traces", cwd: "/work/api",
                    git: nil, state: .idle, origin: .remote, control: .none,
                    model: "gpt-5.4-codex", permissionMode: "never",
                    createdAt: now - 7_200_000, updatedAt: now - 3_600_000, lastSeq: 0)
        ]
    }

    /// The transcript the live demo session opens with.
    public static func liveHistory(base: Int64 = now - 300_000) -> [SessionEvent] {
        var seq = 0
        func next() -> Int { seq += 1; return seq }
        return [
            SessionEvent(seq: next(), ts: base, kind: SessionEvent.turnStartedKind,
                         body: .turnStarted(TurnStartedPayload(turnID: "demo-turn-1", trigger: .remote))),
            SessionEvent(seq: next(), ts: base + 100, kind: SessionEvent.userMessageKind, blockID: "u-1",
                         body: .userMessage(UserMessagePayload(
                            text: "test_refresh_flow fails ~1 in 5 on CI, never locally. Find the race and fix it."))),
            SessionEvent(seq: next(), ts: base + 900, kind: SessionEvent.thinkingKind, blockID: "t-1",
                         body: .thinking(StreamTextPayload(
                            text: "The failure only appears under parallel execution, so shared module state is the first suspect.",
                            done: true, durationMS: 12_000))),
            SessionEvent(seq: next(), ts: base + 1_400, kind: SessionEvent.assistantTextKind, blockID: "a-1",
                         body: .assistantText(StreamTextPayload(
                            text: """
                            Reproducing first — the refresh test shares a module-level clock, so a scheduled \
                            expiry can leak between tests.
                            """, done: true))),
            SessionEvent(seq: next(), ts: base + 2_000, kind: SessionEvent.toolCallKind, blockID: "tool-1",
                         body: .toolCall(ToolCallPayload(
                            tool: "Read", kind: .read, title: "4 files in tests/ and auth/", status: .succeeded,
                            input: ["paths": .array([.string("tests/test_auth.py"), .string("auth/session.py")])],
                            output: "tests/test_auth.py (218 lines)\nauth/session.py (94 lines)",
                            startedAt: base + 2_000, endedAt: base + 3_200, durationMS: 1_200))),
            SessionEvent(seq: next(), ts: base + 3_400, kind: SessionEvent.toolCallKind, blockID: "tool-2",
                         body: .toolCall(ToolCallPayload(
                            tool: "Bash", kind: .shell, title: "pytest -k refresh --count 20", status: .failed,
                            input: ["command": "pytest -k refresh --count 20"],
                            output: """
                            ============================= test session starts =============================
                            collected 20 items

                            tests/test_auth.py ....F..............F                                  [100%]

                            ================================== FAILURES ===================================
                            AssertionError: token expired earlier than scheduled
                            2 failed, 18 passed in 6.40s
                            """,
                            summary: "2 failed",
                            startedAt: base + 3_400, endedAt: base + 9_800, durationMS: 6_400))),
            SessionEvent(seq: next(), ts: base + 10_000, kind: SessionEvent.toolCallKind, blockID: "tool-3",
                         body: .toolCall(ToolCallPayload(
                            tool: "Edit", kind: .edit, title: "auth/session.py", status: .succeeded,
                            diff: DiffPayload(path: "auth/session.py", additions: 12, deletions: 4, patch: """
                            @@ -18,7 +18,15 @@ class SessionStore:
                            -    clock = time.monotonic
                            +    clock = _test_clock or time.monotonic
                            +
                            +    def _guarded_refresh(self, token):
                            +        with self._lock:
                            +            return self._refresh(token)
                            """),
                            startedAt: base + 10_000, endedAt: base + 10_900, durationMS: 900))),
            SessionEvent(seq: next(), ts: base + 11_000, kind: SessionEvent.toolCallKind, blockID: "tool-4",
                         body: .toolCall(ToolCallPayload(
                            tool: "Edit", kind: .edit, title: "tests/conftest.py", status: .succeeded,
                            diff: DiffPayload(path: "tests/conftest.py", additions: 8, deletions: 1),
                            startedAt: base + 11_000, endedAt: base + 11_400, durationMS: 400))),
            SessionEvent(seq: next(), ts: base + 12_000, kind: SessionEvent.assistantTextKind, blockID: "a-2",
                         body: .assistantText(StreamTextPayload(
                            text: """
                            Each test now gets its own frozen clock and refresh is guarded by the session \
                            lock. Re-running the suite 100× to confirm.

                            | Change | File | Effect |
                            | --- | --- | --- |
                            | Frozen clock | `tests/conftest.py` | no shared expiry |
                            | Locked refresh | `auth/session.py` | one writer at a time |
                            """, done: true))),
            SessionEvent(seq: next(), ts: base + 12_500, kind: SessionEvent.todosKind,
                         body: .todos(TodosPayload(items: [
                            TodoItem(id: "1", text: "Reproduce the flake", status: .completed),
                            TodoItem(id: "2", text: "Isolate the shared clock", status: .inProgress),
                            TodoItem(id: "3", text: "Guard refresh with the session lock", status: .pending),
                            TodoItem(id: "4", text: "Re-run the suite 100 times", status: .pending)
                         ]))),
            SessionEvent(seq: next(), ts: base + 13_000, kind: SessionEvent.toolCallKind, blockID: "tool-5",
                         body: .toolCall(ToolCallPayload(
                            tool: "Bash", kind: .shell, title: "pytest tests/test_auth.py --count 100 -q",
                            status: .running,
                            input: ["command": "pytest tests/test_auth.py --count 100 -q"],
                            output: "................................... 72%\n72 passed in 38.02s",
                            startedAt: base + 13_000)))
        ]
    }

    /// The transcript for the session that is waiting on an approval.
    public static func approvalHistory(base: Int64 = now - 180_000) -> [SessionEvent] {
        [
            SessionEvent(seq: 1, ts: base, kind: SessionEvent.userMessageKind, blockID: "u-1",
                         body: .userMessage(UserMessagePayload(text: "Upgrade the web app to Vite 6."))),
            SessionEvent(seq: 2, ts: base + 800, kind: SessionEvent.assistantTextKind, blockID: "a-1",
                         body: .assistantText(StreamTextPayload(
                            text: "The lockfile needs a clean reinstall. I need permission to remove it first.",
                            done: true))),
            SessionEvent(seq: 3, ts: base + 1_200, kind: SessionEvent.approvalKind, blockID: "ap-1",
                         body: .approval(ApprovalPayload(
                            requestID: "demo-approval-1", tool: "Bash", kind: .shell,
                            title: "rm -rf node_modules package-lock.json && npm install",
                            input: ["command": "rm -rf node_modules package-lock.json && npm install",
                                    "cwd": "/Users/me/dev/remote-control/web"],
                            options: [
                                ApprovalOption(id: "approved", label: "Approve once", style: .primary),
                                ApprovalOption(id: "approved_for_session", label: "Approve for this session",
                                               style: .secondary),
                                ApprovalOption(id: "denied", label: "Deny", style: .danger)
                            ],
                            status: .pending)))
        ]
    }

    /// The transcript of the attached terminal session: the developer typed in
    /// the CLI, and the app is reading along.
    public static func sharedHistory(base: Int64 = now - 240_000) -> [SessionEvent] {
        [
            SessionEvent(seq: 1, ts: base, kind: SessionEvent.userMessageKind, blockID: "u-1",
                         body: .userMessage(UserMessagePayload(
                            text: "Draft the release notes for 0.1.0 from the merged pull requests.",
                            source: .terminal))),
            SessionEvent(seq: 2, ts: base + 1_100, kind: SessionEvent.assistantTextKind, blockID: "a-1",
                         body: .assistantText(StreamTextPayload(
                            text: """
                            Drafted `docs/RELEASE-NOTES.md` from the 14 merged pull requests, grouped by \
                            gateway, device client and apps.
                            """, done: true))),
            // Amendment A20: an earlier question the person at the terminal
            // answered in their own dialog before this phone got to it.
            SessionEvent(seq: 3, ts: base + 1_200, kind: SessionEvent.questionKind, blockID: "q-shared-past",
                         body: .question(QuestionPayload(
                            requestID: "demo-question-shared-past",
                            questions: [QuestionItem(
                                id: "q1",
                                prompt: "Should the notes name every contributor, or only the changes?",
                                options: [QuestionOption(id: "changes", label: "Only the changes"),
                                          QuestionOption(id: "everyone", label: "Name every contributor")],
                                allowText: true)],
                            status: .resolved, answers: ["q1": .options(["changes"])], by: .terminal))),
            SessionEvent(seq: 4, ts: base + 1_400, kind: SessionEvent.turnCompletedKind,
                         body: .turnCompleted(TurnCompletedPayload(turnID: "demo-turn-shared",
                                                                   stopReason: .completed, durationMS: 31_000))),
            // Amendment A30: a teammate's report the Claude CLI filed as a user
            // turn. Nobody typed it, and the turn it started says so too.
            SessionEvent(seq: 5, ts: base + 60_000, kind: SessionEvent.userMessageKind, blockID: "u-agent",
                         body: .userMessage(UserMessagePayload(
                            text: "recon-ios: Recon complete. Fact sheet written to the scratchpad; "
                                + "three findings need a decision.",
                            source: .agent))),
            SessionEvent(seq: 6, ts: base + 60_100, kind: SessionEvent.turnStartedKind,
                         body: .turnStarted(TurnStartedPayload(turnID: "demo-turn-shared-agent",
                                                               trigger: .agent))),
            SessionEvent(seq: 7, ts: base + 62_000, kind: SessionEvent.assistantTextKind, blockID: "a-agent",
                         body: .assistantText(StreamTextPayload(
                            text: "Read the fact sheet. The three open findings are listed below with "
                                + "what each one costs.",
                            done: true))),
            SessionEvent(seq: 8, ts: base + 62_400, kind: SessionEvent.turnCompletedKind,
                         body: .turnCompleted(TurnCompletedPayload(turnID: "demo-turn-shared-agent",
                                                                   stopReason: .completed, durationMS: 2_300)))
        ]
    }

    /// Amendment A20: the question the attached Claude asks while this phone is
    /// looking at it. The terminal is showing its own dialog for the same one.
    public static var sharedQuestion: QuestionPayload {
        QuestionPayload(
            requestID: "demo-question-shared",
            questions: [QuestionItem(
                id: "q1",
                prompt: "The 0.1.0 notes still have no headline. What should it say?",
                options: [QuestionOption(id: "remote", label: "Remote control for your terminal agents"),
                          QuestionOption(id: "phone", label: "Your coding agent, from your phone")],
                allowText: true)])
    }

    /// The transcript of the shared Codex thread: typed in the terminal, and
    /// waiting on the four decisions the daemon offers for a command.
    public static func codexSharedHistory(base: Int64 = now - 300_000) -> [SessionEvent] {
        [
            SessionEvent(seq: 1, ts: base, kind: SessionEvent.userMessageKind, blockID: "u-1",
                         body: .userMessage(UserMessagePayload(
                            text: "Typecheck the web app and fix whatever the settings drawer broke.",
                            source: .terminal))),
            SessionEvent(seq: 2, ts: base + 1_300, kind: SessionEvent.assistantTextKind, blockID: "a-1",
                         body: .assistantText(StreamTextPayload(
                            text: """
                            `SessionSettings` lost its `effort` prop when the drawer moved. I will run \
                            the typecheck to see the full list first.
                            """, done: true))),
            SessionEvent(seq: 3, ts: base + 1_900, kind: SessionEvent.approvalKind, blockID: "ap-codex",
                         body: .approval(ApprovalPayload(
                            requestID: "demo-approval-codex", tool: "shell", kind: .shell,
                            title: "npm run typecheck",
                            input: ["command": "npm run typecheck",
                                    "cwd": "/Users/me/dev/remote-control/web"],
                            options: [
                                ApprovalOption(id: "allow", label: "Allow", style: .primary),
                                ApprovalOption(id: "allow_session", label: "Allow for this session",
                                               style: .secondary),
                                ApprovalOption(id: "allow_always", label: "Always allow commands like this",
                                               style: .secondary),
                                ApprovalOption(id: "deny", label: "Deny", style: .danger)
                            ],
                            status: .pending)))
        ]
    }

    /// The transcript of the terminal session the device cannot attach to.
    public static func attachHintHistory(base: Int64 = now - 300_000) -> [SessionEvent] {
        [
            SessionEvent(seq: 1, ts: base, kind: SessionEvent.userMessageKind, blockID: "u-1",
                         body: .userMessage(UserMessagePayload(
                            text: "Rename the pairing flow to enrolment across the client.",
                            source: .terminal))),
            SessionEvent(seq: 2, ts: base + 900, kind: SessionEvent.assistantTextKind, blockID: "a-1",
                         body: .assistantText(StreamTextPayload(
                            text: "Renamed 23 symbols and updated the install script.", done: true)))
        ]
    }

    /// Amendment A25: what the device read out of Grok Build's own update log
    /// while a person drove the terminal. Every message carries the terminal as
    /// its source, because none of it came from an app.
    public static func grokHistory(base: Int64 = now - 3_000_000) -> [SessionEvent] {
        [
            SessionEvent(seq: 1, ts: base, kind: SessionEvent.userMessageKind, blockID: "u-1",
                         body: .userMessage(UserMessagePayload(
                            text: "Squash the pending migrations into one and keep the down path working.",
                            source: .terminal))),
            SessionEvent(seq: 2, ts: base + 1_100, kind: SessionEvent.thinkingKind, blockID: "t-1",
                         body: .thinking(StreamTextPayload(
                            text: "Four migrations touch the same two tables, so the order they ran in matters.",
                            done: true, durationMS: 8_000))),
            SessionEvent(seq: 3, ts: base + 2_400, kind: SessionEvent.assistantTextKind, blockID: "a-1",
                         body: .assistantText(StreamTextPayload(
                            text: "Collapsed the four migrations into `0007_sessions.sql` and kept the reverse.",
                            done: true))),
            SessionEvent(seq: 4, ts: base + 2_500, kind: SessionEvent.turnCompletedKind,
                         body: .turnCompleted(TurnCompletedPayload(turnID: "demo-turn-grok",
                                                                   stopReason: .completed,
                                                                   durationMS: 31_000)))
        ]
    }

    /// Amendment A28: a turn the terminal set off inside the leader, read by
    /// the device as another client of the same session. The prompt is the
    /// terminal's, and the turn is still running, so the app can stop it.
    public static func grokSharedHistory(base: Int64 = now - 300_000) -> [SessionEvent] {
        [
            SessionEvent(seq: 1, ts: base, kind: SessionEvent.userMessageKind, blockID: "u-1",
                         body: .userMessage(UserMessagePayload(
                            text: "The gateway retries a failed publish forever. Give it a budget and a ceiling.",
                            source: .terminal))),
            SessionEvent(seq: 2, ts: base + 1_200, kind: SessionEvent.thinkingKind, blockID: "t-1",
                         body: .thinking(StreamTextPayload(
                            text: "The retry loop has no ceiling, so a device that never answers holds the queue open.",
                            done: true, durationMS: 9_000))),
            SessionEvent(seq: 3, ts: base + 2_600, kind: SessionEvent.assistantTextKind, blockID: "a-1",
                         body: .assistantText(StreamTextPayload(
                            text: "Five attempts with exponential backoff, capped at a minute. Writing it now.",
                            done: true))),
            SessionEvent(seq: 4, ts: base + 3_100, kind: SessionEvent.toolCallKind, blockID: "tool-grok-1",
                         body: .toolCall(ToolCallPayload(
                            tool: "Edit", kind: .edit, title: "gateway/publish.py",
                            status: .running, startedAt: base + 3_100)))
        ]
    }

    /// Amendment A25: a pi turn. pi reports its usage and its cost at the end
    /// of a turn, and asks for no approvals on the way.
    public static func piHistory(base: Int64 = now - 1_200_000) -> [SessionEvent] {
        [
            SessionEvent(seq: 1, ts: base, kind: SessionEvent.userMessageKind, blockID: "u-1",
                         body: .userMessage(UserMessagePayload(
                            text: "Rewrite the config parser so an unknown key is an error, not a warning."))),
            SessionEvent(seq: 2, ts: base + 1_500, kind: SessionEvent.assistantTextKind, blockID: "a-1",
                         body: .assistantText(StreamTextPayload(
                            text: "Unknown keys now raise `ConfigError` and name the line they came from.",
                            done: true))),
            SessionEvent(seq: 3, ts: base + 1_600, kind: SessionEvent.turnCompletedKind,
                         body: .turnCompleted(TurnCompletedPayload(
                            turnID: "demo-turn-pi", stopReason: .completed, durationMS: 19_000,
                            usage: SessionUsage(inputTokens: 8_100, outputTokens: 2_300,
                                                totalTokens: 10_400, costUSD: 0.06))))
        ]
    }

    /// The transcript of the turn that ended on an error.
    public static func erroredHistory(base: Int64 = now - 1_200_000) -> [SessionEvent] {
        [
            SessionEvent(seq: 1, ts: base, kind: SessionEvent.userMessageKind, blockID: "u-1",
                         body: .userMessage(UserMessagePayload(
                            text: "Move the package to the 6.3 toolchain and rebuild."))),
            SessionEvent(seq: 2, ts: base + 1_200, kind: SessionEvent.assistantTextKind, blockID: "a-1",
                         body: .assistantText(StreamTextPayload(
                            text: "Updated `swift-tools-version` and started the build.", done: true))),
            SessionEvent(seq: 3, ts: base + 44_000, kind: SessionEvent.errorKind,
                         body: .error(ErrorPayload(
                            message: "The agent exited before the build finished.",
                            code: "agent_exited"))),
            SessionEvent(seq: 4, ts: base + 44_100, kind: SessionEvent.turnCompletedKind,
                         body: .turnCompleted(TurnCompletedPayload(turnID: "demo-turn-toolchain",
                                                                   stopReason: .error,
                                                                   durationMS: 44_000)))
        ]
    }

    /// The short transcript the archived session carries before it is resumed.
    public static func revivedHistory(base: Int64 = now - 5_400_000) -> [SessionEvent] {
        [
            SessionEvent(seq: 1, ts: base, kind: SessionEvent.userMessageKind, blockID: "u-1",
                         body: .userMessage(UserMessagePayload(text: "Draft the 0.1.0 changelog."))),
            SessionEvent(seq: 2, ts: base + 2_000, kind: SessionEvent.assistantTextKind, blockID: "a-1",
                         body: .assistantText(StreamTextPayload(
                            text: "Drafted it from the commit log. The wording still needs a pass.",
                            done: true))),
            SessionEvent(seq: 3, ts: base + 3_000, kind: SessionEvent.turnCompletedKind,
                         body: .turnCompleted(TurnCompletedPayload(turnID: "demo-turn-changelog",
                                                                   stopReason: .completed, durationMS: 18_000)))
        ]
    }

    /// Amendment A35: the resume this demo's paused session is waiting on —
    /// three quarters of an hour out, from a five-hour window the vendor named
    /// a reset time for, so the notice reads a time rather than "about" one.
    public static var pendingResume: SessionResume {
        SessionResume(at: now + 2_700_000, estimated: false, attempts: 0, windowMinutes: 300)
    }

    /// The transcript of a turn the usage limit ended: the turn closes with
    /// `limit`, the vendor's own sentence goes out as an error rather than as
    /// the agent's words, and the device says what it scheduled.
    public static func pausedHistory(base: Int64 = now - 1_800_000) -> [SessionEvent] {
        let resume = pendingResume
        return [
            SessionEvent(seq: 1, ts: base, kind: SessionEvent.userMessageKind, blockID: "u-1",
                         body: .userMessage(UserMessagePayload(
                            text: "Reindex the corpus and report what changed."))),
            SessionEvent(seq: 2, ts: base + 1_800, kind: SessionEvent.assistantTextKind, blockID: "a-1",
                         body: .assistantText(StreamTextPayload(
                            text: "Walking the corpus now. I have three subagents on the shards.",
                            done: true))),
            SessionEvent(seq: 3, ts: base + 61_000, kind: SessionEvent.errorKind,
                         body: .error(ErrorPayload(
                            message: "You've hit your session limit · resets 10:20pm (Asia/Singapore)",
                            code: "rate_limit"))),
            SessionEvent(seq: 4, ts: base + 61_100, kind: SessionEvent.turnCompletedKind,
                         body: .turnCompleted(TurnCompletedPayload(
                            turnID: "demo-turn-indexer", stopReason: .error, durationMS: 61_000,
                            limit: LimitStop(windowMinutes: 300, resetsAt: resume.at - 60_000)))),
            SessionEvent(seq: 5, ts: base + 61_200, kind: SessionEvent.resumeKind,
                         body: .resume(ResumePayload(status: .scheduled, at: resume.at,
                                                     estimated: false)))
        ]
    }

    public static func history(for sessionID: String) -> [SessionEvent] {
        switch sessionID {
        case liveSessionID: liveHistory()
        case approvalSessionID: approvalHistory()
        case sharedSessionID: sharedHistory()
        case codexSharedSessionID: codexSharedHistory()
        case attachHintSessionID: attachHintHistory()
        case erroredSessionID: erroredHistory()
        case revivedSessionID: revivedHistory()
        case grokSessionID: grokHistory()
        case grokSharedSessionID: grokSharedHistory()
        case piSessionID: piHistory()
        case pausedSessionID: pausedHistory()
        default: [
            SessionEvent(seq: 1, ts: now - 3_600_000, kind: SessionEvent.userMessageKind, blockID: "u-1",
                         body: .userMessage(UserMessagePayload(text: "Add OTLP traces to the API."))),
            SessionEvent(seq: 2, ts: now - 3_599_000, kind: SessionEvent.assistantTextKind, blockID: "a-1",
                         body: .assistantText(StreamTextPayload(
                            text: "Done. Spans now cover the request handler and the database calls.",
                            done: true))),
            SessionEvent(seq: 3, ts: now - 3_598_000, kind: SessionEvent.turnCompletedKind,
                         body: .turnCompleted(TurnCompletedPayload(turnID: "demo-turn-otlp",
                                                                   stopReason: .completed, durationMS: 42_000)))
        ]
        }
    }

    public static func config(minimumAppVersion: String = AppBuild.version) -> GatewayConfig {
        GatewayConfig(publicOrigin: "https://demo.remote-control.invalid",
                      stt: STTConfig(enabled: true, languages: ["auto", "en", "zh"]),
                      push: PushConfig(webEnabled: true, apnsEnabled: true),
                      version: "0.1.0-demo",
                      polish: PolishInfo(enabled: true),
                      apps: apps(minimumAppVersion: minimumAppVersion),
                      client: ClientBuild(version: servedClientVersion, build: servedBuild,
                                          url: "/dist/rc_client-latest.whl"))
    }

    /// Amendment A31: what the demo gateway says the oldest app it works with
    /// is. It is this build by default, so the demo is never blocked; a demo
    /// asked for a higher one is how the blocking screen is driven.
    public static func apps(minimumAppVersion: String = AppBuild.version) -> AppsInfo {
        AppsInfo(ios: AppSupport(minimumVersion: minimumAppVersion,
                                 updateURL: "https://testflight.apple.com/join/EXAMPLE"))
    }

    /// One major version above this build, which is a minimum no installed app
    /// can meet.
    public static var laterAppVersion: String {
        "\(AppVersion(AppBuild.version).major + 1).0.0"
    }

    /// Amendment A29: the two models the demo's polish provider offers.
    public static var polishModels: PolishModelsResponse {
        PolishModelsResponse(models: [PolishModel(id: "gpt-4.1-mini", label: "gpt-4.1-mini"),
                                      PolishModel(id: "gpt-4.1", label: "gpt-4.1")])
    }

    /// A stand-in for the model: the fillers go, a doubled word goes, and the
    /// first letter is capitalised. Enough that the replacement can be watched
    /// happening, and it reaches nothing.
    public static func polished(_ text: String) -> String {
        let fillers: Set<String> = ["um", "uh", "erm", "like", "呃", "那个"]
        var words: [String] = []
        for word in text.split(separator: " ", omittingEmptySubsequences: true).map(String.init) {
            let bare = word.trimmingCharacters(in: .punctuationCharacters).lowercased()
            if fillers.contains(bare) { continue }
            if words.last?.lowercased() == word.lowercased() { continue }
            words.append(word)
        }
        let joined = words.joined(separator: " ")
        guard let first = joined.first else { return joined }
        return String(first).uppercased() + joined.dropFirst()
    }

    public static var pairingGrant: PairingGrant {
        PairingGrant(code: "RC-7K42-QX9M", expiresAt: now + 600_000,
                     install: InstallCommands(
                        macos: "curl -fsSL https://demo.remote-control.invalid/install.sh | sh -s -- --pair RC-7K42-QX9M",
                        linux: "curl -fsSL https://demo.remote-control.invalid/install.sh | sh -s -- --pair RC-7K42-QX9M"))
    }

    /// Amendment A23: the code a claimed host is given, and the link the host
    /// printed to ask for it.
    public static var pairingClaim: PairingClaim {
        PairingClaim(code: "RC-9M27-TB4K", expiresAt: now + 600_000)
    }

    public static let claimToken = "7ZK3M9Q2X5H8B1V4N6P0R2T4W6"

    public static var claimURL: String {
        "https://demo.remote-control.invalid/pair#\(claimToken)"
    }

    // MARK: - Accounts (A24)

    /// The demo signs in as the gateway's own operator, so every screen an
    /// admin has — including the Users screen, where this row is the one with
    /// no actions on it — is reachable from `--demo`.
    public static let adminUsername = AccountRules.operatorUsername
    /// A member, so signing in as one shows the Settings group without the
    /// Users row and the account routes answering `403`.
    public static let memberUsername = "alice"
    /// A disabled account, so the sign-in form's `403` can be read.
    public static let disabledUsername = "bob"

    public static var users: [UserRecord] {
        [
            UserRecord(username: adminUsername, role: .admin, state: .active,
                       createdAt: now - 8_640_000, lastLoginAt: now - 120_000, devices: 3),
            UserRecord(username: memberUsername, role: .member, state: .active,
                       createdAt: now - 4_320_000, lastLoginAt: now - 172_800_000, devices: 1),
            UserRecord(username: disabledUsername, role: .member, state: .disabled,
                       createdAt: now - 2_160_000, lastLoginAt: nil, devices: 0)
        ]
    }
}

/// Amendment A33: the two shapes a `device.agents` reply adds to a credential
/// the device list already carries — the windows it read, or why it could not.
/// They live here because only this scripted device builds them; a real device
/// sends the whole account at once.
extension AgentAccount {
    fileprivate func with(limits: [AgentLimit]) -> AgentAccount {
        AgentAccount(provider: provider, method: method, plan: plan, tier: tier,
                     email: email, endpoint: endpoint, limits: limits,
                     limitsCheckedAt: DemoFixtures.checkedNow)
    }

    fileprivate func with(limitsError: String) -> AgentAccount {
        AgentAccount(provider: provider, method: method, plan: plan, tier: tier,
                     email: email, endpoint: endpoint, limitsError: limitsError,
                     limitsCheckedAt: DemoFixtures.checkedNow)
    }
}
