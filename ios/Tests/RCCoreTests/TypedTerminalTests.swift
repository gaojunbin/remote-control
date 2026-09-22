import Testing
import Foundation
@testable import RCCore

/// Amendment A40: the device types into an attached Claude Code terminal. The
/// shim runs the CLI inside a pseudo-terminal the device owns, so `/model`,
/// `/effort` and `/compact` are typed in as the person at the keyboard would —
/// and nothing else is, because there is no command the device could type for
/// the permission mode.
///
/// What this app has to get right is the reading: `shared_settings_keys` says
/// which setting is a control and which is a value (A17), a refusal comes back
/// in the device's own words, and neither is guessed from the agent id.
@Suite("Amendment A40, typing into a Claude terminal")
struct TypedTerminalTests {
    // MARK: - The wire

    @Test("The worked example says Claude shares the model and the effort, and no more")
    func fixtureDecodes() throws {
        let agent = try agentFixture("agent.claude-attach.json")
        #expect(agent.attach == .channel)
        #expect(agent.attachReady)
        #expect(agent.sharedSettings)
        #expect(agent.sharedSettingsKeys == ["model", "effort"])
        #expect(agent.shares(.model) && agent.shares(.effort))
        #expect(!agent.shares(.permissionMode) && !agent.shares(.speed))
        // Stop rides the same pseudo-terminal (A42); bytes still cannot reach a live CLI.
        #expect(agent.sharedInterrupt && !agent.sharedAttachments)
        // The same typing runs one command, so the capability is there (A27).
        #expect(agent.supports(.commands))
    }

    @Test("The keys survive a re-encode, and are absent when nothing named them")
    func keysRoundTrip() throws {
        let agent = try agentFixture("agent.claude-attach.json")
        let encoded = try JSONValue.encode(agent)
        #expect(encoded["shared_settings_keys"]?.arrayValue?.compactMap(\.stringValue)
                == ["model", "effort"])
        #expect(try encoded.decode(AgentInfo.self).sharedSettingsKeys == ["model", "effort"])

        let bare = AgentInfo(agent: "codex", available: true, sharedSettings: true)
        #expect(try JSONValue.encode(bare)["shared_settings_keys"] == nil,
                "an attachment that carries all four says nothing about the subset")
    }

    @Test("An attachment that names no keys carries all four; one that carries none shares none")
    func absentKeysMeanEverything() throws {
        let everything: JSONValue = ["agent": "codex", "available": true, "shared_settings": true]
        let all = try everything.decode(AgentInfo.self)
        #expect(all.sharedSettingsKeys == nil)
        #expect(SharedSetting.allCases.allSatisfy(all.shares))

        let nothing: JSONValue = ["agent": "claude", "available": true]
        let none = try nothing.decode(AgentInfo.self)
        #expect(!SharedSetting.allCases.contains(where: none.shares))
    }

    /// The keys are opaque words, not a closed set this build owns: a device
    /// that names one nobody here knows changes nothing about the four.
    @Test("A key this build never heard of is simply not one of ours")
    func unknownKeysAreIgnored() throws {
        let json: JSONValue = ["agent": "claude", "available": true, "shared_settings": true,
                               "shared_settings_keys": ["model", "sandbox"]]
        let agent = try json.decode(AgentInfo.self)
        #expect(agent.sharedSettingsKeys == ["model", "sandbox"])
        #expect(agent.shares(.model))
        #expect(!agent.shares(.effort) && !agent.shares(.permissionMode))
    }

    @Test("The demo's Claude is the worked example, and its shimless twin shares nothing")
    func demoMatchesTheFixture() throws {
        let fixture = try agentFixture("agent.claude-attach.json")
        #expect(DemoFixtures.claude.sharedSettings == fixture.sharedSettings)
        #expect(DemoFixtures.claude.sharedSettingsKeys == fixture.sharedSettingsKeys)
        #expect(DemoFixtures.claude.sharedInterrupt == fixture.sharedInterrupt)
        #expect(DemoFixtures.claude.sharedAttachments == fixture.sharedAttachments)
        #expect(DemoFixtures.claude.supports(.commands) == fixture.supports(.commands))

        #expect(!DemoFixtures.claudeWithoutShim.sharedSettings)
        #expect(DemoFixtures.claudeWithoutShim.sharedSettingsKeys == nil,
                "the field is never present without the boolean")
        #expect(!SharedSetting.allCases.contains(where: DemoFixtures.claudeWithoutShim.shares))
    }

    // MARK: - What the device does with a change

    @Test("A key the terminal can be typed waits, then reads what it now runs")
    @MainActor
    func typedSettingLands() async throws {
        let gateway = DemoGateway()
        let chat = try Self.chat(gateway)
        let before = chat.session.model

        // The demo takes as long as typing the command and reading the answer
        // back does, so the wait is a state the card really passes through.
        let setting = Task { await chat.set(model: "claude-opus-4-1") }
        try await Self.settle { chat.isSettingPending }
        #expect(chat.pendingSettings == [.model], "the card waits while the device types")
        #expect(chat.session.model == before, "and reads the model the terminal is still on")

        await setting.value
        #expect(chat.session.model == "claude-opus-4-1", "the reply is what it follows")
        #expect(!chat.isSettingPending)
        #expect(chat.errorMessage == nil)

        await chat.set(effort: "medium")
        #expect(chat.session.effort == "medium")
        #expect(chat.errorMessage == nil)
    }

    @Test("A key outside the list is refused, and the old value comes back with the words")
    @MainActor
    func unsharedSettingIsRefused() async throws {
        let gateway = DemoGateway()
        let chat = try Self.chat(gateway)
        let before = chat.session.permissionMode
        await chat.set(permissionMode: "plan")
        #expect(chat.session.permissionMode == before)
        #expect(!chat.isSettingPending)
        #expect(chat.errorMessage == "Change it in the terminal.")
    }

    /// The device never types over somebody else's keyboard: while a turn runs
    /// or a dialog is open there, the change is refused and nothing is queued.
    /// The demo's terminal raises a question of its own shortly after the
    /// session opens, which is that dialog.
    @Test("A busy terminal refuses the change in the device's own words")
    @MainActor
    func busyTerminalConflicts() async throws {
        let gateway = DemoGateway()
        let chat = try Self.chat(gateway)
        let pump = Self.pump(gateway, into: chat)
        defer { pump.cancel() }
        await chat.open()
        try await Self.settle(timeout: 10) { chat.session.state == .needsInput }
        #expect(chat.session.state == .needsInput, "the terminal is asking something of its own")

        let before = chat.session.effort
        await chat.set(effort: "medium")
        #expect(chat.session.effort == before, "nothing was drawn, so nothing moved")
        #expect(!chat.isSettingPending, "and the control stops waiting")
        #expect(chat.errorMessage == "the terminal is busy; try again in a moment")
    }

    // MARK: - The one command

    @Test("The session lists /compact, and running it echoes the line and the compaction")
    @MainActor
    func compactIsTypedIn() async throws {
        let gateway = DemoGateway()
        let chat = try Self.chat(gateway)
        let pump = Self.pump(gateway, into: chat)
        defer { pump.cancel() }

        await chat.loadCommands()
        #expect(chat.commands.map(\.name) == ["compact"])

        chat.draft = "/compact"
        await chat.runCommand()
        try await Self.settle { chat.timeline.optimistic.isEmpty }
        #expect(chat.timeline.roots.filter { $0.userMessage?.text == "/compact" }.count == 1,
                "the device's echo replaces the row the app drew under the same id")
        try await Self.settle { chat.timeline.roots.contains { $0.notice != nil } }
        #expect(chat.timeline.roots.contains { $0.notice?.text.contains("compacted") == true },
                "and the compaction itself reads as a notice")
    }

    /// A command is typing too, so it waits for the same quiet terminal. The
    /// composer has already closed the panel while the dialog is open (A20),
    /// so the rule is read off the device rather than off the field.
    @Test("A busy terminal refuses the command in the same words")
    @MainActor
    func compactOnABusyTerminal() async throws {
        let gateway = DemoGateway()
        let chat = try Self.chat(gateway)
        let pump = Self.pump(gateway, into: chat)
        defer { pump.cancel() }
        await chat.open()
        try await Self.settle(timeout: 10) { chat.session.state == .needsInput }

        do {
            _ = try await gateway.request(.command(id: "c-1", sessionID: DemoFixtures.sharedSessionID,
                                                   name: "compact", argument: nil))
            Issue.record("a busy terminal took the command")
        } catch let error as GatewayErrorBody {
            #expect(error.code == .conflict)
            #expect(error.message == "the terminal is busy; try again in a moment")
        }
    }

    // MARK: - Helpers

    @MainActor
    private static func chat(_ gateway: DemoGateway) throws -> ChatStore {
        let session = try #require(DemoFixtures.sessions.first {
            $0.sessionID == DemoFixtures.sharedSessionID
        })
        let chat = ChatStore(session: session, channel: gateway)
        chat.agent = DemoFixtures.claude
        return chat
    }

    @MainActor
    private static func pump(_ gateway: DemoGateway, into chat: ChatStore) -> Task<Void, Never> {
        let events = gateway.events
        return Task { @MainActor in
            for await event in events where !Task.isCancelled {
                if case .frame(let frame) = event { chat.receive(frame) }
            }
        }
    }

    @MainActor
    private static func settle(timeout: TimeInterval = 3, _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
    }

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
