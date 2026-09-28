import Foundation
import RCCore
import Testing
@testable import RCMac

/// Agents as the mock gateway describes them (`web/mock/fixtures.ts`): what
/// the status line and the header read of them.
enum ChatAgents {
    static let claude = AgentInfo(agent: "claude", available: true,
                                  capabilities: [.worktree, .takeover, .interrupt, .queue, .attachments, .effort,
                                                 .history, .commands],
                                  attach: .channel, attachReady: true, sharedInterrupt: true, sharedSettings: true,
                                  sharedSettingsKeys: ["model", "effort"])
    static let codex = AgentInfo(agent: "codex", available: true,
                                 capabilities: [.worktree, .interrupt, .queue, .steer, .attachments, .effort,
                                                .history, .commands],
                                 attach: .daemon, attachReady: true, sharedInterrupt: true, sharedSettings: true,
                                 sharedAttachments: true)
    /// A device whose shim carries no interrupt (before A42).
    static let claudeWithoutInterrupt = AgentInfo(agent: "claude", available: true,
                                                  capabilities: [.takeover, .interrupt, .queue, .history],
                                                  attach: .channel, attachReady: true)

    static func session(state: SessionState = .idle, control: SessionControl = .remote, agent: String = "claude",
                        stateDetail: String? = nil) -> Session {
        Session(sessionID: "ses-1", deviceID: "dev-1", agent: agent, title: "Fix flaky auth test",
                cwd: "/Users/me/dev/gateway", state: state, stateDetail: stateDetail, control: control)
    }
}

extension LanguageSensitive {
    /// `web/tests/StatusLine.test.tsx`.
    @Suite("Chat status line") @MainActor
    struct ChatStatusLineTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        private func line(_ session: Session, _ agent: AgentInfo? = ChatAgents.claude, online: Bool = true,
                          editingQueued: Bool = false) -> StatusLineModel? {
            StatusLineModel.of(session: session, agent: agent, deviceOnline: online, editingQueued: editingQueued)
        }

        @Test func theTerminalIsInControlWhileItsTurnRuns() {
            let model = line(ChatAgents.session(state: .running, control: .terminal))
            #expect(model?.text == "Controlled by the terminal · a turn is running there · take over to send")
            #expect(model?.tone == .running)
            #expect(model?.offersTakeover == true)
        }

        @Test func anIdleTerminalSaysHowToWriteToIt() {
            let model = line(ChatAgents.session(state: .readonly, control: .terminal))
            #expect(model?.text == "Controlled by the terminal · take over to send")
            #expect(model?.tone == .muted)
        }

        @Test func noTakeoverWhereTheAgentHasNone() {
            let model = line(ChatAgents.session(state: .readonly, control: .terminal, agent: "codex"), ChatAgents.codex)
            #expect(model?.text == "Controlled by the terminal")
            #expect(model?.offersTakeover == false)
        }

        @Test func aRunningRemoteTurnQueues() {
            #expect(line(ChatAgents.session(state: .running))?.text
                    == "Claude Code is working · your message will be queued")
        }

        @Test func aSteeringAgentSteers() {
            #expect(line(ChatAgents.session(state: .running, agent: "codex"), ChatAgents.codex)?.text
                    == "Codex is working · your message will steer the turn")
        }

        @Test func anEditedQueuedMessageSaysQueued() {
            #expect(line(ChatAgents.session(state: .running, agent: "codex"), ChatAgents.codex,
                         editingQueued: true)?.text == "Codex is working · your message will be queued")
        }

        @Test func approvalsQuestionsAndErrors() {
            #expect(line(ChatAgents.session(state: .needsApproval))?.text == "Needs your approval")
            #expect(line(ChatAgents.session(state: .needsApproval))?.tone == .attention)
            #expect(line(ChatAgents.session(state: .needsInput))?.text == "Waiting for your answer")
            #expect(line(ChatAgents.session(state: .error, stateDetail: "the CLI exited with 1"))?.text
                    == "the CLI exited with 1")
            #expect(line(ChatAgents.session(state: .error))?.text == "Errored")
            #expect(line(ChatAgents.session(state: .stopped))?.text == "Stopped")
            #expect(line(ChatAgents.session(state: .starting))?.text == "Starting the agent…")
        }

        @Test func anOfflineDeviceComesBeforeAnythingElse() {
            #expect(line(ChatAgents.session(state: .running, control: .terminal), online: false)?.text
                    == "Device offline")
        }

        @Test func anIdleSessionHasNoLine() {
            #expect(line(ChatAgents.session()) == nil)
        }
    }

    /// `web/src/features/chat/ChatHeader.tsx`: Stop, the todo chip and the usage chip.
    @Suite("Chat header") @MainActor
    struct ChatHeaderTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        @Test func stopIsOfferedOnlyWhereThisAppMayEndTheTurn() {
            let running = ChatAgents.session(state: .running)
            #expect(ChatHeaderModel(session: running, agent: ChatAgents.claude, todos: [], detail: .simple).offersStop)
            var terminal = running
            terminal.control = .terminal
            #expect(!ChatHeaderModel(session: terminal, agent: ChatAgents.claude, todos: [], detail: .simple).offersStop)
            var shared = running
            shared.control = .shared
            #expect(ChatHeaderModel(session: shared, agent: ChatAgents.claude, todos: [], detail: .simple).offersStop)
            #expect(!ChatHeaderModel(session: shared, agent: ChatAgents.claudeWithoutInterrupt, todos: [],
                                     detail: .simple).offersStop)
            shared.agent = "codex"
            #expect(ChatHeaderModel(session: shared, agent: ChatAgents.codex, todos: [], detail: .simple).offersStop)
            #expect(!ChatHeaderModel(session: ChatAgents.session(state: .idle), agent: ChatAgents.claude, todos: [],
                                     detail: .simple).offersStop)
        }

        @Test func theTodoChipIsTheAgentsWorkingsSoSimpleDrawsNone() {
            var session = ChatAgents.session(state: .running)
            session.todos = TodoCounts(total: 4, done: 1)
            #expect(ChatHeaderModel(session: session, agent: nil, todos: [], detail: .simple).todos == nil)
            let counts = ChatHeaderModel(session: session, agent: nil, todos: [], detail: .detailed).todos
            #expect(counts == TodoCounts(total: 4, done: 1))
            let items = [TodoItem(id: "1", text: "a", status: .completed), TodoItem(id: "2", text: "b", status: .pending)]
            #expect(ChatHeaderModel(session: session, agent: nil, todos: items, detail: .detailed).todos
                    == TodoCounts(total: 4, done: 1))
        }

        @Test func theUsageChipSaysTokensAndTheTurnsTime() {
            var session = ChatAgents.session(state: .running)
            #expect(ChatHeaderModel(session: session, agent: nil, todos: [], detail: .simple).usage == nil)
            session.usage = SessionUsage(totalTokens: 48_200)
            #expect(ChatHeaderModel(session: session, agent: nil, todos: [], detail: .simple).usage == "48.2k")
            session.turn = TurnMarker(turnID: "t", startedAt: 1_000)
            #expect(ChatHeaderModel(session: session, agent: nil, todos: [], detail: .simple, now: 73_000).usage
                    == "48.2k · 1m 12s")
            session.usage = nil
            #expect(ChatHeaderModel(session: session, agent: nil, todos: [], detail: .simple, now: 7_400).usage
                    == "6.4s")
        }
    }
}
