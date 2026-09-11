import Testing
import Foundation
@testable import RCCore

/// Amendment A10: a live CLI owns the session and the device is attached to it.
/// The app types, approves and queues as it would for a session it runs itself,
/// and never offers takeover, Stop, attachments or the terminal's own settings.
@Suite("Amendment A10, shared control")
struct SharedControlTests {
    @MainActor
    private func store(state: SessionState, control: SessionControl,
                       agent: AgentInfo? = DemoFixtures.claude, queued: Int = 0) -> ChatStore {
        let session = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T",
                              cwd: "/tmp", state: state, control: control, queued: queued)
        let chat = ChatStore(session: session, channel: DemoGateway())
        chat.agent = agent
        chat.draft = "hello"
        return chat
    }

    // MARK: - Decoding

    @Test("control accepts shared and keeps an unknown value distinct")
    func controlDecoding() throws {
        let shared: JSONValue = ["session_id": "s", "device_id": "d", "control": "shared"]
        let session = try shared.decode(Session.self)
        #expect(session.control == .shared)
        #expect(session.isAttached)
        #expect(!session.isControlledByTerminal)

        let unknown: JSONValue = ["session_id": "s", "device_id": "d", "control": "attached"]
        let other = try unknown.decode(Session.self)
        #expect(other.control.rawValue == "attached")
        #expect(!other.isAttached)
        #expect(!other.isControlledByTerminal)
    }

    @Test("An agent reports how and whether it can be attached")
    func agentAttachDecoding() throws {
        let json: JSONValue = ["agent": "claude", "available": true,
                               "attach": "channel", "attach_ready": true, "shared_interrupt": false]
        let agent = try json.decode(AgentInfo.self)
        #expect(agent.attach == .channel)
        #expect(agent.attachReady)
        #expect(!agent.sharedInterrupt)

        let bare = try JSONValue.object(["agent": "codex", "available": true]).decode(AgentInfo.self)
        #expect(bare.attach == nil)
        #expect(!bare.attachReady)
        #expect(!bare.sharedInterrupt)
    }

    @Test("delivery decodes, defaults to absent, and survives a re-encode",
          arguments: ["pending", "delivered", "absorbed"])
    func deliveryDecoding(value: String) throws {
        let json: JSONValue = ["seq": 1, "ts": 1, "kind": "user_message", "block_id": "u1",
                               "text": "later", "source": "remote", "delivery": .string(value)]
        let event = try json.decode(SessionEvent.self)
        #expect(event.userMessage?.delivery?.rawValue == value)
        #expect(try JSONValue.encode(event)["delivery"]?.stringValue == value)

        let plain: JSONValue = ["seq": 1, "ts": 1, "kind": "user_message", "text": "now"]
        #expect(try plain.decode(SessionEvent.self).userMessage?.delivery == nil)
    }

    // MARK: - Composer state

    @Test("An attached session types exactly like one this app runs")
    @MainActor
    func attachedComposerIsOpen() {
        let chat = store(state: .idle, control: .shared)
        #expect(!chat.isReadOnly)
        #expect(chat.isAttached)
        #expect(chat.canSend)
        #expect(chat.sendBlockReason == nil)
        #expect(chat.statusLine == nil)
    }

    @Test("An attached session never offers takeover")
    @MainActor
    func attachedHidesTakeover() {
        #expect(!store(state: .idle, control: .shared).canTakeover)
        #expect(!store(state: .running, control: .shared).canTakeover)
    }

    @Test("Takeover is offered only when the agent advertises it")
    @MainActor
    func takeoverNeedsTheCapability() {
        #expect(store(state: .readonly, control: .terminal).canTakeover)
        let without = store(state: .readonly, control: .terminal,
                            agent: AgentInfo(agent: "claude", available: true))
        #expect(!without.canTakeover)
        #expect(without.statusLine == "Controlled by the terminal")
        #expect(store(state: .readonly, control: .terminal, agent: nil).canTakeover == false)
    }

    @Test("Stop needs the interrupt capability and an attachment that can interrupt")
    @MainActor
    func attachedStopNeedsSharedInterrupt() {
        // Claude lists `interrupt` but its channel cannot interrupt a turn.
        #expect(!store(state: .running, control: .shared).canStop)

        let codex = AgentInfo(agent: "codex", available: true, capabilities: [.interrupt],
                              attach: .daemon, attachReady: true, sharedInterrupt: true)
        #expect(store(state: .running, control: .shared, agent: codex).canStop)
        #expect(!store(state: .idle, control: .shared, agent: codex).canStop)

        // `shared_interrupt` alone is not enough: the agent must list interrupt.
        let noCapability = AgentInfo(agent: "codex", available: true, capabilities: [],
                                     attach: .daemon, attachReady: true, sharedInterrupt: true)
        #expect(!store(state: .running, control: .shared, agent: noCapability).canStop)

        // Nor is the capability alone.
        let noAttachment = AgentInfo(agent: "codex", available: true, capabilities: [.interrupt],
                                     attach: .daemon, attachReady: true)
        #expect(!store(state: .running, control: .shared, agent: noAttachment).canStop)

        // An unknown agent never offers a stop the device may refuse.
        #expect(!store(state: .running, control: .shared, agent: nil).canStop)
    }

    @Test("The terminal keeps the model, the permission mode and the effort")
    @MainActor
    func attachedSettingsStayInTheTerminal() {
        #expect(!store(state: .idle, control: .shared).allowsSettingsChanges)
        #expect(store(state: .idle, control: .remote).allowsSettingsChanges)
    }

    @Test("Attachments cannot be relayed into a live CLI")
    @MainActor
    func attachedRefusesAttachments() {
        #expect(!store(state: .idle, control: .shared).allowsAttachments)
        #expect(!store(state: .readonly, control: .terminal).allowsAttachments)
        #expect(store(state: .idle, control: .remote).allowsAttachments)
    }

    @Test("A question the CLI asked is answered in the terminal, an approval is not")
    @MainActor
    func attachedMirrorsQuestions() {
        #expect(!store(state: .needsInput, control: .shared).allowsAnswers)
        #expect(!store(state: .needsInput, control: .terminal).allowsAnswers)
        #expect(store(state: .needsInput, control: .remote).allowsAnswers)
        // Approvals are relayed, so they stay answerable while attached.
        #expect(store(state: .needsApproval, control: .shared).canSend)
    }

    /// The header already reads `terminal · attached`, so the line above the
    /// composer is left to what the header cannot say: what becomes of a
    /// message typed into a turn that is already running.
    @Test("An attached session says what happens to a message, and nothing else")
    @MainActor
    func attachedStatusLine() {
        #expect(store(state: .running, control: .shared).statusLine
                == "Working · your message will be queued")
        #expect(store(state: .running, control: .shared, queued: 1).statusLine
                == "Working · 1 message queued")
        #expect(store(state: .running, control: .shared, queued: 2).statusLine
                == "Working · 2 messages queued")
        #expect(store(state: .needsApproval, control: .shared).statusLine == nil)
        #expect(store(state: .needsInput, control: .shared).statusLine == nil)
        #expect(store(state: .idle, control: .shared).statusLine == nil)
    }

    // MARK: - Transitions

    /// A `meta` event carries `control`; a `status` event rides along only when
    /// `state` changed too. The composer follows `control`, never `state`.
    private func controlChange(_ seq: Int, _ control: String) throws -> AppFrame {
        try AppFrame(json: ["type": "session.event", "session_id": "s",
                            "event": ["seq": .integer(Int64(seq)), "ts": .integer(Int64(seq)),
                                      "kind": .string(SessionEvent.metaKind),
                                      "control": .string(control)]])
    }

    @Test("Attaching and detaching arrive as meta events and move the composer")
    @MainActor
    func controlTransitionsThroughMeta() throws {
        let chat = store(state: .readonly, control: .terminal)
        #expect(chat.isReadOnly)
        #expect(!chat.canSend)

        chat.receive(try controlChange(2, "shared"))
        #expect(chat.isAttached)
        #expect(!chat.isReadOnly)
        #expect(chat.canSend)
        #expect(chat.attachHint == nil)
        // The meta event says nothing about state, so state is left alone.
        #expect(chat.session.state == .readonly)

        chat.receive(try controlChange(3, "terminal"))
        #expect(!chat.isAttached)
        #expect(chat.isReadOnly)
        #expect(chat.attachHint == .restartSession)

        chat.receive(try controlChange(4, "none"))
        #expect(!chat.isAttached)
        #expect(!chat.isReadOnly)
        #expect(chat.canSend)
        #expect(chat.attachHint == nil)
    }

    @Test("A held message is accepted as queued with its own id, never a flag")
    @MainActor
    func sendResultShape() throws {
        let queued = try JSONValue.object(["accepted": "queued", "queued_id": "req-1"])
            .decode(SendResult.self)
        #expect(queued.accepted == .queued)
        #expect(queued.queuedID == "req-1")

        let sent = try JSONValue.object(["accepted": "sent"]).decode(SendResult.self)
        #expect(sent.accepted == .sent)
        #expect(sent.queuedID == nil)
    }

    // MARK: - Terminal hints

    @Test("A terminal session says how to make the next run controllable")
    @MainActor
    func attachHints() {
        #expect(store(state: .readonly, control: .terminal,
                      agent: DemoFixtures.claudeWithoutShim).attachHint == .installShim)
        #expect(store(state: .readonly, control: .terminal).attachHint == .restartSession)
        let codex = AgentInfo(agent: "codex", available: true, attach: .daemon)
        #expect(store(state: .readonly, control: .terminal, agent: codex).attachHint == .startDaemon)
    }

    @Test("An agent that cannot be attached says nothing about it")
    @MainActor
    func noHintWithoutAnAttachment() {
        #expect(store(state: .readonly, control: .terminal,
                      agent: AgentInfo(agent: "claude", available: true)).attachHint == nil)
        #expect(store(state: .readonly, control: .terminal, agent: nil).attachHint == nil)
        #expect(store(state: .idle, control: .shared).attachHint == nil)
        #expect(store(state: .idle, control: .remote).attachHint == nil)
    }

    // MARK: - The delivery chip

    @Test("A replacement event moves the same block from pending to delivered")
    func deliveryReplacesTheBlock() throws {
        func message(_ seq: Int, delivery: String, firstSeq: Int? = nil) throws -> SessionEvent {
            var fields: [String: JSONValue] = [
                "seq": .integer(Int64(seq)), "ts": .integer(Int64(seq)),
                "kind": .string(SessionEvent.userMessageKind), "block_id": "u-held",
                "text": "drop I, L, O and U", "source": "remote", "delivery": .string(delivery)
            ]
            if let firstSeq { fields["first_seq"] = .integer(Int64(firstSeq)) }
            return try JSONValue.object(fields).decode(SessionEvent.self)
        }

        var timeline = Timeline()
        timeline.apply(try message(12, delivery: "pending"))
        #expect(timeline.entry(id: "u-held")?.userMessage?.delivery == .pending)

        timeline.apply(try message(18, delivery: "delivered", firstSeq: 12))
        #expect(timeline.entries.count == 1)
        #expect(timeline.entry(id: "u-held")?.userMessage?.delivery == .delivered)
        // A8 still holds: the block keeps the place it was first shown in.
        #expect(timeline.entry(id: "u-held")?.seq == 12)
    }

    @Test("An absorbed message keeps its block so the re-send replaces it")
    func absorbedKeepsTheBlock() throws {
        func message(_ seq: Int, delivery: String) throws -> SessionEvent {
            try JSONValue.object([
                "seq": .integer(Int64(seq)), "ts": .integer(Int64(seq)),
                "kind": .string(SessionEvent.userMessageKind), "block_id": "u-absorbed",
                "text": "also update the docstring", "source": "remote", "delivery": .string(delivery)
            ]).decode(SessionEvent.self)
        }

        var timeline = Timeline()
        timeline.apply(try message(24, delivery: "absorbed"))
        #expect(timeline.entry(id: "u-absorbed")?.userMessage?.delivery == .absorbed)
        timeline.apply(try message(31, delivery: "delivered"))
        #expect(timeline.entries.count == 1)
        #expect(timeline.entry(id: "u-absorbed")?.userMessage?.delivery == .delivered)
    }

    // MARK: - The demo

    @Test("The demo carries an attached session and a terminal one that cannot attach")
    @MainActor
    func demoFixtures() {
        let sessions = DemoFixtures.sessions
        guard let shared = sessions.first(where: { $0.sessionID == DemoFixtures.sharedSessionID }),
              let hinted = sessions.first(where: { $0.sessionID == DemoFixtures.attachHintSessionID }) else {
            Issue.record("the demo is missing an A10 session")
            return
        }
        #expect(shared.control == .shared)
        #expect(shared.state == .idle)
        #expect(hinted.control == .terminal)

        let devices = DemoFixtures.devices
        #expect(devices.first { $0.deviceID == shared.deviceID }?.agent("claude")?.attachReady == true)
        #expect(devices.first { $0.deviceID == hinted.deviceID }?.agent("claude")?.attachReady == false)
    }

    @Test("The demo holds a message, then injects it into the attached session")
    @MainActor
    func demoInjection() async throws {
        let gateway = DemoGateway()
        let session = Session(sessionID: DemoFixtures.sharedSessionID, deviceID: DemoFixtures.macDeviceID,
                              agent: "claude", title: "Tidy the release notes", cwd: "/tmp",
                              state: .idle, origin: .terminal, control: .shared)
        let chat = ChatStore(session: session, channel: gateway)
        chat.agent = DemoFixtures.claude
        let events = gateway.events
        let pump = Task { @MainActor in
            for await event in events where !Task.isCancelled {
                if case .frame(let frame) = event { chat.receive(frame) }
            }
        }
        defer { pump.cancel() }

        chat.draft = "mention the iOS app too"
        await chat.send()
        try await settle(timeout: 10) {
            chat.timeline.entries.contains { $0.userMessage?.delivery == .pending }
        }
        try await settle(timeout: 10) {
            chat.timeline.entries.contains { $0.userMessage?.delivery == .delivered }
        }
        #expect(chat.timeline.entries.filter { $0.userMessage != nil }.count == 1)

        try await settle(timeout: 10) { chat.timeline.pendingRequest != nil }
        let approval = chat.timeline.pendingRequest?.approval
        #expect(approval?.options.map(\.id) == ["allow", "deny"])
        #expect(approval?.diff == nil)
    }

    @MainActor
    private func settle(timeout: TimeInterval = 3, _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
    }
}
