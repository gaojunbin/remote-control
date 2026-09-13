import Testing
import Foundation
@testable import RCCore

/// Amendment A25: three more agents, and two shapes the app had not met — an
/// agent with no permission system (pi) and one with no effort levels (Cursor).
/// Nothing on the wire changed, so what is checked here is what the app makes
/// of an `AgentInfo`: the name it draws, the mark that stands in for it, and
/// the controls an empty list takes away.
@Suite("Agents (A25)")
struct AgentsTests {
    // MARK: - Names and marks

    @Test("Every agent the device knows is named", arguments: [
        ("claude", "Claude Code"), ("codex", "Codex"), ("grok", "Grok Build"),
        ("cursor", "Cursor"), ("pi", "pi")
    ])
    func names(agent: String, name: String) {
        #expect(AgentLabel.name(agent) == name)
    }

    @Test("And marked where a name does not fit", arguments: [
        ("claude", "C"), ("codex", "X"), ("grok", "G"), ("cursor", "Cu"), ("pi", "π")
    ])
    func marks(agent: String, mark: String) {
        #expect(AgentLabel.mark(agent) == mark)
    }

    @Test("An agent nobody knows renders as itself, marked by its first letter")
    func unknownAgent() {
        #expect(AgentLabel.name("aider") == "aider")
        #expect(AgentLabel.mark("aider") == "A")
        #expect(AgentLabel.mark("") == "")
    }

    // MARK: - What the device advertises

    @Test("The three new agents decode exactly as the protocol's worked examples")
    func fixturesDecode() throws {
        let grok = try agentFixture("agent.grok.json")
        #expect(grok.agent == "grok")
        #expect(grok.version == "1.0.25")
        #expect(grok.models.map(\.id) == ["grok-4.6", "grok-4.5"])
        #expect(grok.permissionModes.map(\.id)
                == ["default", "acceptEdits", "auto", "dontAsk", "plan", "bypassPermissions"])
        #expect(grok.efforts.map(\.id) == ["low", "medium", "high", "xhigh"])
        #expect(grok.supports(.effort))
        #expect(grok.attach == nil)

        let cursor = try agentFixture("agent.cursor.json")
        #expect(cursor.permissionModes.map(\.id) == ["default", "force", "plan", "ask"])
        #expect(cursor.efforts.isEmpty)
        #expect(cursor.defaultEffort == nil)
        #expect(!cursor.supports(.effort))

        let pi = try agentFixture("agent.pi.json")
        #expect(pi.permissionModes.isEmpty)
        #expect(pi.defaultPermissionMode == nil)
        #expect(pi.efforts.map(\.id) == ["off", "low", "medium", "high"])
        #expect(pi.supports(.steer))
    }

    @Test("The demo device advertises all five, exactly as the fixtures do")
    func demoDeviceCarriesFive() throws {
        let mac = try #require(DemoFixtures.devices.first { $0.deviceID == DemoFixtures.macDeviceID })
        #expect(mac.availableAgents.map(\.agent) == ["claude", "codex", "grok", "cursor", "pi"])
        for name in ["agent.grok.json", "agent.cursor.json", "agent.pi.json"] {
            let fixture = try agentFixture(name)
            let demo = try #require(mac.agent(fixture.agent))
            #expect(demo.models == fixture.models)
            #expect(demo.defaultModel == fixture.defaultModel)
            #expect(demo.permissionModes == fixture.permissionModes)
            #expect(demo.defaultPermissionMode == fixture.defaultPermissionMode)
            #expect(demo.efforts == fixture.efforts)
            #expect(demo.defaultEffort == fixture.defaultEffort)
            #expect(demo.capabilities == fixture.capabilities)
        }
    }

    @Test("And carries one demo session of each new agent")
    func demoSessions() throws {
        let sessions = DemoFixtures.sessions
        let grok = try #require(sessions.first { $0.sessionID == DemoFixtures.grokSessionID })
        #expect(grok.agent == "grok")
        #expect(grok.control == .terminal)
        #expect(grok.origin == .terminal)
        #expect(grok.permissionMode == nil)

        let cursor = try #require(sessions.first { $0.sessionID == DemoFixtures.cursorSessionID })
        #expect(cursor.agent == "cursor")
        #expect(cursor.effort == nil)

        let pi = try #require(sessions.first { $0.sessionID == DemoFixtures.piSessionID })
        #expect(pi.agent == "pi")
        #expect(pi.permissionMode == nil)
    }

    // MARK: - What an empty list takes away

    @Test("An agent with no permission system shows no permission chip")
    func piHasNoPermissionChip() {
        let session = Session(sessionID: "s", deviceID: "d", agent: "pi", title: "Parser",
                              cwd: "/tmp", control: .terminal,
                              model: "anthropic/claude-sonnet-4-5", effort: "medium",
                              updatedAt: 0)
        let chips = TerminalSetting.all(for: session, agent: DemoFixtures.pi)
        #expect(chips.map(\.id) == ["modelCard"])
        #expect(chips.map(\.text) == ["Claude Sonnet 4.5 Medium"])
        #expect(TerminalSetting.permissionText(for: session, agent: DemoFixtures.pi) == nil)
    }

    @Test("Even when a session somehow carries one")
    func piIgnoresAStrayPermissionMode() {
        let session = Session(sessionID: "s", deviceID: "d", agent: "pi", title: "Parser",
                              cwd: "/tmp", control: .terminal, permissionMode: "default",
                              updatedAt: 0)
        #expect(TerminalSetting.permissionText(for: session, agent: DemoFixtures.pi) == nil)
        #expect(TerminalSetting.all(for: session, agent: DemoFixtures.pi).isEmpty)
    }

    @Test("An agent with no effort levels reads the model alone")
    func cursorReadsTheModelAlone() {
        let session = Session(sessionID: "s", deviceID: "d", agent: "cursor", title: "Storybook",
                              cwd: "/tmp", control: .terminal, model: "auto",
                              permissionMode: "default", effort: "high", updatedAt: 0)
        #expect(TerminalSetting.effortText(for: session, agent: DemoFixtures.cursor) == nil)
        #expect(TerminalSetting.modelCardText(for: session, agent: DemoFixtures.cursor) == "Auto")
        #expect(TerminalSetting.all(for: session, agent: DemoFixtures.cursor).map(\.text)
                == ["Auto", "Ask when needed"])
    }

    /// Amendment A17 still holds for an agent the app has never met: with no
    /// `AgentInfo` at hand there is no list to say the setting does not exist,
    /// so the raw ids are shown rather than dropped.
    @Test("An unknown agent keeps whatever the device reported")
    func unknownAgentKeepsItsIDs() {
        let session = Session(sessionID: "s", deviceID: "d", agent: "aider", title: "Port",
                              cwd: "/tmp", control: .terminal, model: "some-model",
                              permissionMode: "yolo", effort: "high", updatedAt: 0)
        #expect(TerminalSetting.all(for: session, agent: nil).map(\.text)
                == ["some-model high", "yolo"])
    }

    /// A Grok session mirrored from the terminal reports the model and the
    /// effort its summary carries, and no permission mode at all — so one chip
    /// stands where three would have.
    @Test("A mirrored Grok session shows what its update log knows")
    func grokTerminalChips() throws {
        let session = try #require(DemoFixtures.sessions
            .first { $0.sessionID == DemoFixtures.grokSessionID })
        let chips = TerminalSetting.all(for: session, agent: DemoFixtures.grok)
        #expect(chips.map(\.id) == ["modelCard"])
        #expect(chips.map(\.text) == ["Grok 4.6 High"])
    }

    // MARK: - What a terminal-held session offers

    /// `docs/DESIGN.md` § "The composer": the way out of a terminal-held
    /// session is named only where the agent has one, and only on the status
    /// line. The disabled field says the short sentence for every agent.
    @Test("A terminal-held session names a takeover only where there is one", arguments: [
        ("claude", true), ("codex", false), ("grok", false)
    ])
    @MainActor
    func terminalControlNotice(agent id: String, offersTakeover: Bool) {
        let info: AgentInfo = switch id {
        case "claude": DemoFixtures.claude
        case "codex": DemoFixtures.codex
        default: DemoFixtures.grok
        }
        let session = Session(sessionID: "s", deviceID: "d", agent: id, title: "T",
                              cwd: "/tmp", state: .readonly, control: .terminal, updatedAt: 0)
        let chat = ChatStore(session: session, channel: DemoGateway())
        chat.agent = info
        chat.draft = "hello"

        #expect(chat.isReadOnly)
        #expect(chat.canTakeover == offersTakeover)
        let expected = offersTakeover ? "Controlled by the terminal · take over to send"
                                      : "Controlled by the terminal"
        #expect(chat.terminalControlNotice == expected)
        #expect(chat.statusLine == expected)
        #expect(chat.sendBlockReason == "Controlled by the terminal")
        #expect(!chat.canSend)
    }

    /// The demo's own Grok session, rather than one built for the occasion.
    @Test("The demo's mirrored Grok session invites no tap that would be refused")
    @MainActor
    func grokSessionNeverPromisesATakeover() throws {
        let session = try #require(DemoFixtures.sessions
            .first { $0.sessionID == DemoFixtures.grokSessionID })
        let chat = ChatStore(session: session, channel: DemoGateway())
        chat.agent = DemoFixtures.grok
        #expect(!DemoFixtures.grok.supports(.takeover))
        #expect(chat.sendBlockReason == "Controlled by the terminal")
        #expect(chat.statusLine == "Controlled by the terminal")
    }

    // MARK: - Lists

    @Test("The agent filter offers every agent the list runs, in label order")
    func filterOptions() {
        #expect(SessionListLayout.agents(in: DemoFixtures.sessions)
                == ["claude", "codex", "cursor", "grok", "pi"])
    }

    @Test("And a session of a new agent is found by its name as well as its id")
    func searchByName() throws {
        let session = try #require(DemoFixtures.sessions
            .first { $0.sessionID == DemoFixtures.grokSessionID })
        #expect(SessionListLayout.matches(session, query: "grok"))
        #expect(SessionListLayout.matches(session, query: "grok build"))
    }

    // MARK: - Helpers

    /// One of the protocol's worked examples, read from the frozen fixtures so
    /// a change there fails here rather than on a phone.
    private func agentFixture(_ name: String) throws -> AgentInfo {
        let url = URL(filePath: #filePath)
            .deletingLastPathComponent()   // ios/Tests/RCCoreTests
            .deletingLastPathComponent()   // ios/Tests
            .deletingLastPathComponent()   // ios
            .deletingLastPathComponent()   // repository root
            .appending(path: "protocol/fixtures/objects/\(name)")
        return try JSONDecoder().decode(AgentInfo.self, from: try Data(contentsOf: url))
    }
}
