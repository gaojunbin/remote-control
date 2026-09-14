import Testing
import Foundation
@testable import RCCore

/// Amendments A25 and A26: the agents beside Claude and Codex, and the shape
/// the app had not met — an agent with no effort levels of its own. Nothing on
/// the wire changed, so what is checked here is what the app makes of an
/// `AgentInfo`: the name it draws and the controls an empty list takes away.
/// Amendment A26 withdrew Cursor and gave pi the device's own permission modes.
@Suite("Agents (A25, A26)")
struct AgentsTests {
    // MARK: - Names

    @Test("Every agent the device knows is named", arguments: [
        ("claude", "Claude Code"), ("codex", "Codex"), ("grok", "Grok Build"), ("pi", "pi")
    ])
    func names(agent: String, name: String) {
        #expect(AgentLabel.name(agent) == name)
    }

    @Test("An agent nobody knows renders as itself, marked by its first letter")
    func unknownAgent() {
        #expect(AgentLabel.name("aider") == "aider")
        #expect(AgentLabel.initial("aider") == "A")
        #expect(AgentLabel.initial("") == "")
    }

    /// Amendment A26: Cursor is no longer an agent id a device reports, so it
    /// falls through to the rule for an id nobody knows.
    @Test("Cursor is gone, and reads as any other unknown id would")
    func cursorIsWithdrawn() {
        #expect(AgentLabel.name("cursor") == "cursor")
        #expect(AgentLabel.initial("cursor") == "C")
    }

    // MARK: - What the device advertises

    @Test("The two new agents decode exactly as the protocol's worked examples")
    func fixturesDecode() throws {
        let grok = try agentFixture("agent.grok.json")
        #expect(grok.agent == "grok")
        #expect(grok.version == "1.0.30")
        #expect(grok.models.map(\.id) == ["grok-4.6", "grok-4.5"])
        #expect(grok.permissionModes.map(\.id)
                == ["default", "acceptEdits", "auto", "dontAsk", "plan", "bypassPermissions"])
        #expect(grok.efforts.map(\.id) == ["low", "medium", "high", "xhigh"])
        #expect(grok.supports(.effort))
        // Amendment A28: Grok Build attaches through the leader its terminals
        // join. The leader relays an interrupt and the session settings to
        // every client of it; a Grok prompt carries no images.
        #expect(grok.attach == .leader)
        #expect(grok.attachReady)
        #expect(grok.sharedInterrupt && grok.sharedSettings)
        #expect(!grok.sharedAttachments)

        // Amendment A26: pi's three permission modes are the device's own, and
        // the extension that enforces them attaches its terminal sessions too.
        let pi = try agentFixture("agent.pi.json")
        #expect(pi.permissionModes.map(\.id) == ["untrusted", "on-request", "never"])
        #expect(pi.defaultPermissionMode == "on-request")
        #expect(pi.efforts.map(\.id) == ["off", "low", "medium", "high"])
        #expect(pi.supports(.steer))
        #expect(pi.supports(.attachments))
        #expect(pi.attach == .extension)
        #expect(pi.attachReady)
        #expect(pi.sharedInterrupt && pi.sharedSettings && pi.sharedAttachments)
    }

    @Test("The demo device advertises all four, exactly as the fixtures do")
    func demoDeviceCarriesFour() throws {
        let mac = try #require(DemoFixtures.devices.first { $0.deviceID == DemoFixtures.macDeviceID })
        #expect(mac.availableAgents.map(\.agent) == ["claude", "codex", "grok", "pi"])
        for name in ["agent.grok.json", "agent.pi.json"] {
            let fixture = try agentFixture(name)
            let demo = try #require(mac.agent(fixture.agent))
            #expect(demo.models == fixture.models)
            #expect(demo.defaultModel == fixture.defaultModel)
            #expect(demo.permissionModes == fixture.permissionModes)
            #expect(demo.defaultPermissionMode == fixture.defaultPermissionMode)
            #expect(demo.efforts == fixture.efforts)
            #expect(demo.defaultEffort == fixture.defaultEffort)
            // Amendment A27 put `commands` on both of these, so the demo device
            // carries it too or the panel would never open on either agent.
            #expect(demo.capabilities == fixture.capabilities)
            #expect(demo.supports(.commands))
            #expect(demo.attach == fixture.attach)
            #expect(demo.attachReady == fixture.attachReady)
            // Amendment A28: what an attachment relays is half the contract,
            // and the demo has to report the same three answers or the composer
            // would offer a control the real device refuses.
            #expect(demo.sharedInterrupt == fixture.sharedInterrupt)
            #expect(demo.sharedSettings == fixture.sharedSettings)
            #expect(demo.sharedAttachments == fixture.sharedAttachments)
        }
    }

    /// Amendment A27: three of the four agents take slash commands from an app,
    /// and Claude never does — a channel carries user text and nothing else.
    @Test("Three agents take commands, and Claude does not")
    func commandCapability() {
        #expect(DemoFixtures.codex.supports(.commands))
        #expect(DemoFixtures.grok.supports(.commands))
        #expect(DemoFixtures.pi.supports(.commands))
        #expect(!DemoFixtures.claude.supports(.commands))
        #expect(!DemoFixtures.claudeWithoutShim.supports(.commands))
        // The capability belongs to the agent, not to its attachment: a device
        // whose daemon is not running still takes commands on a session it runs.
        #expect(DemoFixtures.codexWithoutDaemon.supports(.commands))
    }

    @Test("And carries one demo session of each new agent")
    func demoSessions() throws {
        let sessions = DemoFixtures.sessions
        let grok = try #require(sessions.first { $0.sessionID == DemoFixtures.grokSessionID })
        #expect(grok.agent == "grok")
        #expect(grok.control == .terminal)
        #expect(grok.origin == .terminal)
        #expect(grok.permissionMode == nil)

        let pi = try #require(sessions.first { $0.sessionID == DemoFixtures.piSessionID })
        #expect(pi.agent == "pi")
        #expect(pi.permissionMode == "on-request")
    }

    /// Amendment A28: the terminal-held Grok session lives on the machine that
    /// leaves `[cli] use_leader` off, because that is the only way a Grok
    /// session is still terminal-held — everywhere else it is shared.
    @Test("A Grok terminal is watched only where the leader is off, and shared where it is on")
    func grokSessionsFollowTheLeader() throws {
        let sessions = DemoFixtures.sessions
        let devices = DemoFixtures.devices
        let watched = try #require(sessions.first { $0.sessionID == DemoFixtures.grokSessionID })
        let laptop = try #require(devices.first { $0.deviceID == watched.deviceID })
        #expect(laptop.agent("grok")?.attach == .leader)
        #expect(laptop.agent("grok")?.attachReady == false)

        let shared = try #require(sessions.first { $0.sessionID == DemoFixtures.grokSharedSessionID })
        #expect(shared.agent == "grok")
        #expect(shared.control == .shared)
        #expect(shared.origin == .terminal)
        #expect(shared.state == .running)
        let mac = try #require(devices.first { $0.deviceID == shared.deviceID })
        #expect(mac.agent("grok")?.attachReady == true)
    }

    @Test("And no session of an agent the protocol withdrew")
    func noCursorSessionSurvives() {
        #expect(!DemoFixtures.sessions.contains { $0.agent == "cursor" })
        #expect(!DemoFixtures.devices.contains { $0.agents.contains { $0.agent == "cursor" } })
    }

    // MARK: - What an empty list takes away

    /// Amendment A26: pi's modes are the device's own, so the chip an earlier
    /// round drew nothing for is now drawn exactly as Codex's is — and by the
    /// same code, with nothing in the app to change.
    @Test("pi now shows its permission chip, and its picker")
    func piShowsThePermissionControl() throws {
        let session = try #require(DemoFixtures.sessions
            .first { $0.sessionID == DemoFixtures.piSessionID })
        let chips = TerminalSetting.all(for: session, agent: DemoFixtures.pi)
        #expect(chips.map(\.id) == ["modelCard", "permissionMode"])
        #expect(chips.map(\.text) == ["Claude Sonnet 4.5 Medium", "Ask when needed"])
        #expect(TerminalSetting.permissionText(for: session, agent: DemoFixtures.pi)
                == "Ask when needed")
        #expect(!DemoFixtures.pi.permissionModes.isEmpty,
                "which is what the new-session form reads before it draws its Permissions row")
    }

    /// Amendment A17 is unchanged: an agent that lists no modes still draws no
    /// chip, whatever the session reports.
    @Test("An agent with no permission system still shows no chip")
    func anEmptyListStillTakesTheChipAway() {
        let bare = AgentInfo(agent: "aider", available: true,
                             models: [AgentOption(id: "m", label: "M")], defaultModel: "m")
        let session = Session(sessionID: "s", deviceID: "d", agent: "aider", title: "Port",
                              cwd: "/tmp", control: .terminal, model: "m",
                              permissionMode: "default", updatedAt: 0)
        #expect(TerminalSetting.permissionText(for: session, agent: bare) == nil)
        #expect(TerminalSetting.all(for: session, agent: bare).map(\.id) == ["modelCard"])
    }

    @Test("An agent with no effort levels reads the model alone")
    func anAgentWithoutEffortsReadsTheModelAlone() {
        let bare = AgentInfo(agent: "aider", available: true,
                             models: [AgentOption(id: "auto", label: "Auto")], defaultModel: "auto",
                             permissionModes: [AgentOption(id: "default", label: "Ask when needed")],
                             defaultPermissionMode: "default")
        let session = Session(sessionID: "s", deviceID: "d", agent: "aider", title: "Storybook",
                              cwd: "/tmp", control: .terminal, model: "auto",
                              permissionMode: "default", effort: "high", updatedAt: 0)
        #expect(TerminalSetting.effortText(for: session, agent: bare) == nil)
        #expect(TerminalSetting.modelCardText(for: session, agent: bare) == "Auto")
        #expect(TerminalSetting.all(for: session, agent: bare).map(\.text)
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
                == ["claude", "codex", "grok", "pi"])
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
