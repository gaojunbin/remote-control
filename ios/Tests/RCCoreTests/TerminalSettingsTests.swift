import Testing
import Foundation
@testable import RCCore

/// Amendment A17: a session a terminal holds shows what the device read from
/// the agent's own transcript, where the live controls would be. Nothing the
/// app can send would change them, so the store hands the composer text rather
/// than an action. Amendment A21: model, effort and speed are one control, so
/// they are one chip, and the tier rides on it.
@Suite("Amendment A17, what the terminal chose")
struct TerminalSettingsTests {
    @MainActor
    private func store(control: SessionControl, agent: AgentInfo? = DemoFixtures.claude,
                       model: String? = "claude-sonnet-4-5", permissionMode: String? = "auto",
                       effort: String? = "high", speed: String? = nil) -> ChatStore {
        let session = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T",
                              cwd: "/tmp", state: .idle, control: control, model: model,
                              permissionMode: permissionMode, effort: effort, speed: speed)
        let chat = ChatStore(session: session, channel: DemoGateway())
        chat.agent = agent
        return chat
    }

    // MARK: - Which sessions show rather than offer

    @Test("A terminal session shows all of what it chose, and gives up none of it")
    @MainActor
    func terminalHeldSessionsAreTuned() {
        let terminal = store(control: .terminal)
        #expect(!terminal.allowsModelCardChanges)
        #expect(SharedSetting.allCases.allSatisfy { !terminal.allowsSettingsChanges(for: $0) })
        // An attachment that names no subset keeps every live picker.
        let codex = store(control: .shared, agent: DemoFixtures.codex)
        #expect(codex.allowsModelCardChanges)
        #expect(SharedSetting.allCases.allSatisfy(codex.allowsSettingsChanges(for:)))
        for control in [SessionControl.remote, .none] {
            let ours = store(control: control)
            #expect(ours.allowsModelCardChanges)
            #expect(SharedSetting.allCases.allSatisfy(ours.allowsSettingsChanges(for:)))
        }
    }

    /// Amendment A40: the rule is per setting now. A shared Claude session is
    /// typed into for the model and the effort, so its card is a control while
    /// the permission mode is still a value.
    @Test("A shared Claude session offers what the device can type and shows the rest")
    @MainActor
    func sharedClaudeSplitsTheTwo() {
        let chat = store(control: .shared)
        #expect(chat.allowsSettingsChanges(for: .model))
        #expect(chat.allowsSettingsChanges(for: .effort))
        #expect(!chat.allowsSettingsChanges(for: .permissionMode))
        #expect(!chat.allowsSettingsChanges(for: .speed))
        #expect(chat.allowsModelCardChanges)
        #expect(chat.terminalSettings.map(\.id) == ["permissionMode"])
        #expect(chat.terminalSetting(.modelCard) == nil)
        #expect(chat.terminalSetting(.permissionMode)?.text == "auto")

        // The same agent on a machine with no shim types nothing at all.
        let bare = store(control: .shared, agent: DemoFixtures.claudeWithoutShim)
        #expect(!bare.allowsModelCardChanges)
        #expect(bare.terminalSettings.map(\.id) == ["modelCard", "permissionMode"])
    }

    /// The card is one control, so it is live only where every setting it
    /// carries is. An agent that shared the model but not the effort would
    /// draw a card that half works, which is what this forbids.
    @Test("The model card needs every setting it carries, not just the model")
    @MainActor
    func theCardIsAllOrNothing() {
        let half = AgentInfo(agent: "claude", available: true,
                             models: DemoFixtures.claude.models,
                             efforts: DemoFixtures.claude.efforts,
                             attach: .channel, attachReady: true,
                             sharedSettings: true, sharedSettingsKeys: ["model"])
        let chat = store(control: .shared, agent: half)
        #expect(chat.allowsSettingsChanges(for: .model))
        #expect(!chat.allowsSettingsChanges(for: .effort))
        #expect(!chat.allowsModelCardChanges)
        #expect(chat.terminalSetting(.modelCard)?.text == "Sonnet 4.5 High")
    }

    @Test("A session is either offered a setting or shown it, never both")
    @MainActor
    func chipsAndPickersAreExclusive() {
        for control in [SessionControl.terminal, .shared, .remote, .none] {
            for agent in [DemoFixtures.claude, DemoFixtures.codex, nil] {
                let chat = store(control: control, agent: agent)
                #expect((chat.terminalSetting(.modelCard) != nil) == !chat.allowsModelCardChanges)
                #expect((chat.terminalSetting(.permissionMode) != nil)
                        == !chat.allowsSettingsChanges(for: .permissionMode))
            }
        }
    }

    @Test("An unknown agent is still a terminal the app cannot retune")
    @MainActor
    func unknownAgentOnAnAttachedSession() {
        let chat = store(control: .shared, agent: nil)
        #expect(!chat.allowsModelCardChanges)
        #expect(!chat.allowsSettingsChanges(for: .permissionMode))
        #expect(chat.terminalSettings.map(\.text) == ["claude-sonnet-4-5 high", "auto"])
    }

    // MARK: - What each chip says

    @Test("Each value is labelled by the agent's list, or shown by its raw id")
    @MainActor
    func labelsComeFromTheAgent() {
        let chips = store(control: .terminal).terminalSettings
        #expect(chips.map(\.id) == ["modelCard", "permissionMode"])
        #expect(chips.map(\.text) == ["Sonnet 4.5 High", "auto"])
        #expect(chips.map(\.field.label) == ["Model", "Permissions"])
    }

    @Test("A value the device has not seen draws nothing at all")
    @MainActor
    func nilValuesAreLeftOut() {
        #expect(store(control: .terminal, effort: nil).terminalSettings.map(\.text)
                == ["Sonnet 4.5", "auto"])
        #expect(store(control: .terminal, model: nil, permissionMode: nil).terminalSettings.map(\.text)
                == ["High"])
        #expect(store(control: .terminal, model: nil, permissionMode: nil, effort: nil)
                    .terminalSettings.isEmpty)
    }

    @Test("Effort is shown whether or not the agent advertises the capability")
    @MainActor
    func effortNeedsNoCapability() {
        // The agent lists its levels but cannot be retuned from this app, so
        // the capability is absent. The chip still says which level it is on.
        let bare = AgentInfo(agent: "claude", available: true,
                             models: DemoFixtures.claude.models,
                             permissionModes: DemoFixtures.claude.permissionModes,
                             efforts: DemoFixtures.claude.efforts)
        #expect(!bare.supports(.effort))
        #expect(store(control: .terminal, agent: bare).terminalSettings.map(\.text)
                == ["Sonnet 4.5 High", "auto"])
    }

    /// Amendment A25: an empty list is the agent saying it has no such setting,
    /// so nothing stands where the control would have been — not even the id
    /// the device reported.
    @Test("An agent that lists neither drops both, whatever the session carries")
    @MainActor
    func emptyListsDrawNothing() {
        let bare = AgentInfo(agent: "claude", available: true,
                             models: DemoFixtures.claude.models)
        #expect(store(control: .terminal, agent: bare).terminalSettings.map(\.text)
                == ["Sonnet 4.5"])
    }

    // MARK: - Following the terminal

    @Test("A meta with a model moves the chip without a reload")
    @MainActor
    func metaUpdatesTheChip() throws {
        let chat = store(control: .terminal)
        let frame = try AppFrame(json: ["type": "session.event", "session_id": "s",
                                        "event": ["seq": 7, "ts": 7,
                                                  "kind": .string(SessionEvent.metaKind),
                                                  "model": "claude-opus-4-1"]])
        chat.receive(frame)
        #expect(chat.session.model == "claude-opus-4-1")
        #expect(chat.terminalSettings.map(\.text) == ["Opus 4.1 High", "auto"])
        // A meta that says nothing about the other two leaves them alone.
        #expect(chat.session.permissionMode == "auto")
        #expect(chat.session.effort == "high")
    }

    // MARK: - The demo

    @Test("The demo carries a terminal session and an attached one with all three")
    @MainActor
    func demoFixtures() {
        let sessions = DemoFixtures.sessions
        guard let terminal = sessions.first(where: { $0.sessionID == DemoFixtures.terminalSessionID }),
              let shared = sessions.first(where: { $0.sessionID == DemoFixtures.sharedSessionID }) else {
            Issue.record("the demo is missing an A17 session")
            return
        }
        #expect(TerminalSetting.all(for: terminal, agent: DemoFixtures.claude).map(\.text)
                == ["Sonnet 4.5 High", "Ask before edits"])
        // `auto` is a permission mode the transcript has and the agent does not
        // advertise, so the demo carries the raw-id case on screen.
        #expect(TerminalSetting.all(for: shared, agent: DemoFixtures.claude).map(\.text)
                == ["Sonnet 4.5 High", "auto"])
    }

    @Test("The demo's terminal switches model while the session is open")
    @MainActor
    func demoRetunesTheSharedSession() async throws {
        let gateway = DemoGateway()
        let session = Session(sessionID: DemoFixtures.sharedSessionID, deviceID: DemoFixtures.macDeviceID,
                              agent: "claude", title: "Tidy the release notes", cwd: "/tmp",
                              state: .idle, origin: .terminal, control: .shared,
                              model: "claude-sonnet-4-5", permissionMode: "auto", effort: "high")
        let chat = ChatStore(session: session, channel: gateway)
        chat.agent = DemoFixtures.claude
        let events = gateway.events
        let pump = Task { @MainActor in
            for await event in events where !Task.isCancelled {
                if case .frame(let frame) = event { chat.receive(frame) }
            }
        }
        defer { pump.cancel() }

        await chat.open()
        let deadline = Date().addingTimeInterval(10)
        while chat.session.model == "claude-sonnet-4-5", Date() < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        // Amendment A40: the card here is a live control, and it follows the
        // terminal's own `/model` in place; only the mode is still a chip.
        #expect(TerminalSetting.modelCardText(for: chat.session, agent: chat.agent)
                == "Opus 4.1 High")
        #expect(chat.terminalSettings.map(\.text) == ["auto"])
    }

    // MARK: - Amendment A21, the tier on the same chip

    @Test("A terminal-held Codex thread reports its speed tier like anything else")
    @MainActor
    func speedRidesOnTheModelChip() {
        let fast = Session(sessionID: "s", deviceID: "d", agent: "codex", title: "T", cwd: "/tmp",
                           state: .idle, control: .terminal, model: "gpt-5.4-codex",
                           permissionMode: "on-request", effort: "high", speed: "priority")
        let chips = TerminalSetting.all(for: fast, agent: DemoFixtures.codex)
        #expect(chips.map(\.text) == ["GPT-5.4 Codex High", "Ask when needed"])
        #expect(chips.first?.speed == "Fast")
        #expect(chips.first?.spokenValue == "GPT-5.4 Codex High, Fast")
        #expect(chips.last?.speed == nil)

        // The standard speed adds nothing at all, not even a word.
        var standard = fast
        standard.speed = nil
        #expect(TerminalSetting.all(for: standard, agent: DemoFixtures.codex).first?.speed == nil)
    }
}
