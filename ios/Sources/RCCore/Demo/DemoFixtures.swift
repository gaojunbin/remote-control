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
            attach: .channel, attachReady: true, sharedInterrupt: false)
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
            attach: .channel, attachReady: false, sharedInterrupt: false)
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
            capabilities: [.worktree, .interrupt, .queue, .steer, .attachments, .effort, .history],
            attach: .daemon, attachReady: true, sharedInterrupt: true,
            sharedSettings: true, sharedAttachments: true)
    }

    /// The same agent on a machine where the app-server daemon is not running,
    /// so its terminal threads cannot be attached (amendments A10 and A11).
    public static var codexWithoutDaemon: AgentInfo {
        AgentInfo(
            agent: "codex", available: true, version: codex.version, path: codex.path,
            models: codex.models, defaultModel: codex.defaultModel,
            permissionModes: codex.permissionModes, defaultPermissionMode: codex.defaultPermissionMode,
            efforts: codex.efforts, defaultEffort: codex.defaultEffort,
            capabilities: codex.capabilities,
            attach: .daemon, attachReady: false)
    }

    public static var devices: [Device] {
        [
            Device(deviceID: macDeviceID, name: "mac-studio-office", platform: .macos,
                   hostname: "mac-studio.local", arch: "arm64", clientVersion: "0.1.0",
                   online: true, lastSeen: now, createdAt: now - 8_640_000, latencyMS: 18,
                   agents: [claude, codex]),
            Device(deviceID: laptopDeviceID, name: "macbook-air", platform: .macos,
                   hostname: "macbook-air.local", arch: "arm64", clientVersion: "0.1.0",
                   online: true, lastSeen: now, createdAt: now - 4_320_000, latencyMS: 41,
                   agents: [claudeWithoutShim]),
            Device(deviceID: ciDeviceID, name: "ci-runner-01", platform: .linux,
                   hostname: "ci-runner-01", arch: "x86_64", clientVersion: "0.1.0",
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
                                                                   stopReason: .completed, durationMS: 31_000)))
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

    public static func history(for sessionID: String) -> [SessionEvent] {
        switch sessionID {
        case liveSessionID: liveHistory()
        case approvalSessionID: approvalHistory()
        case sharedSessionID: sharedHistory()
        case codexSharedSessionID: codexSharedHistory()
        case attachHintSessionID: attachHintHistory()
        case erroredSessionID: erroredHistory()
        case revivedSessionID: revivedHistory()
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

    public static var directoryListing: DirectoryListing {
        DirectoryListing(
            path: "/Users/me/dev", parent: "/Users/me",
            entries: [
                DirectoryEntry(name: "remote-control", path: "/Users/me/dev/remote-control", isGit: true),
                DirectoryEntry(name: "gateway", path: "/Users/me/dev/gateway", isGit: true),
                DirectoryEntry(name: "notes", path: "/Users/me/dev/notes", isGit: false)
            ],
            recent: [
                RecentDirectory(path: "/Users/me/dev/remote-control/gateway", lastUsed: now - 7_200_000),
                RecentDirectory(path: "/Users/me/dev/remote-control/web", lastUsed: now - 86_400_000)
            ])
    }

    public static var config: GatewayConfig {
        GatewayConfig(publicOrigin: "https://demo.remote-control.invalid",
                      stt: STTConfig(enabled: true, languages: ["auto", "en", "zh"]),
                      push: PushConfig(webEnabled: true, apnsEnabled: true),
                      version: "0.1.0-demo")
    }

    public static var pairingGrant: PairingGrant {
        PairingGrant(code: "RC-7K42-QX9M", expiresAt: now + 600_000,
                     install: InstallCommands(
                        macos: "curl -fsSL https://demo.remote-control.invalid/install.sh | sh -s -- --pair RC-7K42-QX9M",
                        linux: "curl -fsSL https://demo.remote-control.invalid/install.sh | sh -s -- --pair RC-7K42-QX9M"))
    }
}
