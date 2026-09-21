import Testing
import Foundation
@testable import RCCore

/// Amendment A28: Grok Build shares a terminal session through its leader, one
/// backend process per machine that the TUI joins and the device joins as
/// another client of. The app learns this the way it learns every attachment:
/// from `attach` and the three booleans beside it, and from `control`.
@Suite("Amendment A28, the Grok leader")
struct GrokLeaderTests {
    @MainActor
    private func store(state: SessionState = .running, agent: AgentInfo?,
                       control: SessionControl = .shared) -> ChatStore {
        let session = Session(sessionID: "s", deviceID: "d", agent: "grok", title: "T",
                              cwd: "/tmp", state: state, origin: .terminal, control: control)
        let chat = ChatStore(session: session, channel: DemoGateway())
        chat.agent = agent
        chat.draft = "hello"
        return chat
    }

    // MARK: - The wire

    @Test("The leader is an attach mode of its own, and an unknown one still decodes")
    func decoding() throws {
        let json: JSONValue = ["agent": "grok", "available": true, "attach": "leader",
                               "attach_ready": true, "shared_interrupt": true,
                               "shared_settings": true, "shared_attachments": false]
        let agent = try json.decode(AgentInfo.self)
        #expect(agent.attach == .leader)
        #expect(agent.attachReady)
        #expect(agent.sharedInterrupt && agent.sharedSettings)
        #expect(!agent.sharedAttachments)
        #expect(try JSONValue.encode(agent)["attach"]?.stringValue == "leader")

        // A mode this build has never heard of keeps its name rather than
        // becoming nil, which is what lets a hint be worded for it later.
        let later: JSONValue = ["agent": "grok", "available": true, "attach": "swarm"]
        #expect(try later.decode(AgentInfo.self).attach == AgentAttach(rawValue: "swarm"))
    }

    // MARK: - What the composer offers

    @Test("A session on the leader keeps every control except the attachment")
    @MainActor
    func sharedControls() {
        let chat = store(agent: DemoFixtures.grok)
        #expect(chat.isAttached)
        #expect(!chat.isReadOnly)
        #expect(chat.canSend)
        #expect(chat.canStop)
        #expect(chat.allowsModelCardChanges)
        #expect(chat.allowsSettingsChanges(for: .permissionMode))
        #expect(!chat.allowsAttachments)
        #expect(!chat.canTakeover)
        #expect(chat.attachHint == nil)
    }

    /// Stop needs both halves: the agent's own capability and an attachment
    /// that relays an interrupt. Grok has both, so the turn a TUI set off is
    /// stoppable from here.
    @Test("Stop needs the capability as well as the relay")
    @MainActor
    func stopNeedsBothHalves() {
        #expect(store(agent: DemoFixtures.grok).canStop)
        let noRelay = AgentInfo(agent: "grok", available: true, capabilities: [.interrupt],
                                attach: .leader, attachReady: true)
        #expect(!store(agent: noRelay).canStop)
        let noCapability = AgentInfo(agent: "grok", available: true,
                                     attach: .leader, attachReady: true, sharedInterrupt: true)
        #expect(!store(agent: noCapability).canStop)
    }

    // MARK: - The hint on a session that is still the terminal's

    @Test("A machine that is not in the leader says how to put it there")
    @MainActor
    func hintNamesTheSetupCommand() {
        let chat = store(state: .readonly, agent: DemoFixtures.grokWithoutLeader, control: .terminal)
        #expect(chat.attachHint == .enableLeader)
        #expect(chat.sendBlockReason == "Controlled by the terminal")
        #expect(!chat.canTakeover)
    }

    @Test("A prepared machine blames this grok instead")
    @MainActor
    func hintBlamesTheProcessWhenReady() {
        #expect(store(state: .readonly, agent: DemoFixtures.grok, control: .terminal).attachHint
                == .restartSession)
    }

    // MARK: - The demo

    @Test("The demo carries a Grok session the leader shares with a terminal")
    @MainActor
    func demoSharedSession() throws {
        let session = try #require(DemoFixtures.sessions
            .first { $0.sessionID == DemoFixtures.grokSharedSessionID })
        #expect(session.control == .shared)
        #expect(session.origin == .terminal)
        #expect(session.state.isWorking)

        let agent = try #require(DemoFixtures.devices
            .first { $0.deviceID == session.deviceID }?.agent("grok"))
        #expect(agent.attach == .leader)
        #expect(agent.attachReady)
        #expect(agent.sharedInterrupt && agent.sharedSettings)
        #expect(!agent.sharedAttachments)

        let history = DemoFixtures.history(for: session.sessionID)
        #expect(history.first?.userMessage?.source == .terminal)
        #expect(history.contains { $0.toolCall?.status == .running })
        // The turn is still running, which is what makes Stop worth drawing.
        #expect(history.allSatisfy { $0.kind != SessionEvent.turnCompletedKind })
    }

    /// The device is a real client of the leader, so a prompt runs in the
    /// conversation the TUI is in rather than being held for a shim to type.
    @Test("A message for a leader session is sent, not held")
    @MainActor
    func demoSendsThroughTheLeader() async throws {
        let gateway = DemoGateway()
        let session = try #require(DemoFixtures.sessions
            .first { $0.sessionID == DemoFixtures.grokSharedSessionID })
        let chat = ChatStore(session: session, channel: gateway)
        chat.agent = DemoFixtures.grok
        chat.draft = "cap it at thirty seconds instead"
        await chat.send(mode: .interrupt)
        #expect(chat.lastAcceptance == .sent)
        #expect(chat.errorMessage == nil)

        await chat.set(effort: "low")
        #expect(chat.session.effort == "low")
        #expect(chat.errorMessage == nil)
    }
}
