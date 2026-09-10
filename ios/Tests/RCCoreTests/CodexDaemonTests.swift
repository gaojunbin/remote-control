import Testing
import Foundation
@testable import RCCore

/// Amendment A11: Codex shares a terminal thread through the app-server daemon.
/// The attachment carries more than a Claude channel does, and it says so with
/// `shared_settings` and `shared_attachments`. Two requests that A10 kept in
/// the terminal come back to the app when those are true.
@Suite("Amendment A11, the shared Codex daemon")
struct CodexDaemonTests {
    @MainActor
    private func store(state: SessionState = .running, agent: AgentInfo?,
                       control: SessionControl = .shared) -> ChatStore {
        let session = Session(sessionID: "s", deviceID: "d", agent: "codex", title: "T",
                              cwd: "/tmp", state: state, origin: .terminal, control: control)
        let chat = ChatStore(session: session, channel: DemoGateway())
        chat.agent = agent
        chat.draft = "hello"
        return chat
    }

    private var daemon: AgentInfo {
        AgentInfo(agent: "codex", available: true,
                  capabilities: [.interrupt, .queue, .steer, .attachments, .effort, .history],
                  attach: .daemon, attachReady: true, sharedInterrupt: true,
                  sharedSettings: true, sharedAttachments: true)
    }

    // MARK: - The two booleans

    @Test("An agent reports whether a shared session keeps its settings and attachments")
    func decoding() throws {
        let json: JSONValue = ["agent": "codex", "available": true, "attach": "daemon",
                               "attach_ready": true, "shared_interrupt": true,
                               "shared_settings": true, "shared_attachments": true]
        let agent = try json.decode(AgentInfo.self)
        #expect(agent.sharedSettings)
        #expect(agent.sharedAttachments)
        #expect(try JSONValue.encode(agent)["shared_settings"]?.boolValue == true)
        #expect(try JSONValue.encode(agent)["shared_attachments"]?.boolValue == true)
    }

    @Test("Both default to false, so an older device loses nothing and gains nothing")
    func decodingDefaults() throws {
        let bare = try JSONValue.object(["agent": "codex", "available": true])
            .decode(AgentInfo.self)
        #expect(!bare.sharedSettings)
        #expect(!bare.sharedAttachments)
    }

    @Test("A boolean that is not a boolean is refused rather than coerced")
    func decodingRejectsAString() {
        let json: JSONValue = ["agent": "codex", "available": true, "shared_settings": "yes"]
        #expect(throws: (any Error).self) { try json.decode(AgentInfo.self) }
    }

    @Test("shared_settings decides whether the pickers open on a shared session")
    @MainActor
    func settingsFollowTheBoolean() {
        #expect(store(agent: daemon).allowsSettingsChanges)
        #expect(!store(agent: DemoFixtures.claude).allowsSettingsChanges)
        // An agent the device did not describe grants nothing.
        #expect(!store(agent: nil).allowsSettingsChanges)
        // And a session no CLI owns is unaffected either way.
        #expect(store(agent: DemoFixtures.claude, control: .remote).allowsSettingsChanges)
    }

    @Test("shared_attachments decides whether the attachment button opens")
    @MainActor
    func attachmentsFollowTheBoolean() {
        #expect(store(agent: daemon).allowsAttachments)
        #expect(!store(agent: DemoFixtures.claude).allowsAttachments)
        #expect(!store(agent: nil).allowsAttachments)
        #expect(store(agent: DemoFixtures.claude, control: .remote).allowsAttachments)
        // A terminal session still takes no input at all, whatever it reports.
        #expect(!store(state: .readonly, agent: daemon, control: .terminal).allowsAttachments)
    }

    @Test("Stop stays on the interrupt capability and shared_interrupt, not the new pair")
    @MainActor
    func stopIsUnchanged() {
        #expect(store(agent: daemon).canStop)
        let noInterrupt = AgentInfo(agent: "codex", available: true, capabilities: [.interrupt],
                                    attach: .daemon, attachReady: true,
                                    sharedSettings: true, sharedAttachments: true)
        #expect(!store(agent: noInterrupt).canStop)
    }

    @Test("A running turn is steered by an agent that lists steer, queued otherwise")
    @MainActor
    func steerWording() {
        let steering = store(agent: daemon)
        #expect(steering.steersRunningTurn)
        #expect(steering.statusLine == "terminal · attached · working")

        let remote = store(state: .running, agent: daemon, control: .remote)
        #expect(remote.statusLine == "Working · your message will steer the turn")

        // Claude does not list `steer`, so its wording is unchanged.
        let queueing = store(state: .running, agent: DemoFixtures.claude, control: .remote)
        #expect(!queueing.steersRunningTurn)
        #expect(queueing.statusLine == "Working · your message will be queued")

        // A message already waiting still says how many are waiting.
        #expect(!store(state: .idle, agent: daemon, control: .remote).steersRunningTurn)
    }

    // MARK: - Protocol send modes on a shared thread

    @MainActor
    private func sharedCodexChat(_ gateway: DemoGateway) -> ChatStore? {
        guard let session = DemoFixtures.sessions.first(where: {
            $0.sessionID == DemoFixtures.codexSharedSessionID
        }) else {
            Issue.record("the demo is missing the shared Codex thread")
            return nil
        }
        let chat = ChatStore(session: session, channel: gateway)
        chat.agent = DemoFixtures.codex
        return chat
    }

    @Test("auto on a running shared thread steers it, and says steered")
    @MainActor
    func autoSteersTheSharedThread() async throws {
        let gateway = DemoGateway()
        guard let chat = sharedCodexChat(gateway) else { return }
        chat.draft = "also check the drawer's tests"
        await chat.send(mode: .auto)
        #expect(chat.lastAcceptance == .steered)
        #expect(chat.errorMessage == nil)
    }

    @Test("queue on the same thread holds the message instead")
    @MainActor
    func queueHoldsTheSharedThread() async throws {
        let gateway = DemoGateway()
        guard let chat = sharedCodexChat(gateway) else { return }
        chat.draft = "and then run the linter"
        await chat.send(mode: .queue)
        #expect(chat.lastAcceptance == .queued)
    }

    @Test("An option the block never offered is refused rather than relayed")
    @MainActor
    func approveRefusesAnUnofferedOption() async throws {
        let gateway = DemoGateway()
        guard let chat = sharedCodexChat(gateway) else { return }
        await chat.approve(requestID: "demo-approval-codex",
                           optionID: ApprovalPayload.elsewhereOptionID)
        #expect(chat.errorMessage != nil)
        chat.clearError()
        await chat.approve(requestID: "demo-approval-codex", optionID: "allow_session")
        #expect(chat.errorMessage == nil)
    }

    // MARK: - The `elsewhere` decision

    private func approval(decision: ApprovalDecision?) -> ApprovalPayload {
        ApprovalPayload(
            requestID: "r", tool: "shell", kind: .shell, title: "npm run typecheck",
            options: [ApprovalOption(id: "allow", label: "Allow", style: .primary),
                      ApprovalOption(id: "allow_session", label: "Allow for this session",
                                     style: .secondary),
                      ApprovalOption(id: "allow_always", label: "Always allow commands like this",
                                     style: .secondary),
                      ApprovalOption(id: "deny", label: "Deny", style: .danger)],
            status: decision == nil ? .pending : .resolved, decision: decision)
    }

    @Test("A request answered elsewhere has no option to name")
    func elsewhereNamesNothing() {
        let resolved = approval(decision: ApprovalDecision(optionID: ApprovalPayload.elsewhereOptionID,
                                                           by: .terminal))
        #expect(resolved.resolvedOptionLabel == nil)
        // And `elsewhere` is never one of the choices the card can offer.
        #expect(!resolved.options.contains { $0.id == ApprovalPayload.elsewhereOptionID })
    }

    @Test("An option the block did offer is named by its label")
    func knownOptionIsNamed() {
        let resolved = approval(decision: ApprovalDecision(optionID: "allow_session", by: .remote))
        #expect(resolved.resolvedOptionLabel == "Allow for this session")
    }

    @Test("An option id from a newer device renders verbatim rather than blanking the card")
    func unknownOptionRendersVerbatim() {
        let resolved = approval(decision: ApprovalDecision(optionID: "allow_next_week", by: .terminal))
        #expect(resolved.resolvedOptionLabel == "allow_next_week")
    }

    @Test("A pending request names nothing, because nothing was decided")
    func pendingNamesNothing() {
        #expect(approval(decision: nil).resolvedOptionLabel == nil)
    }

    @Test("The four decisions place one primary, one danger and two in between")
    func fourOptionsKeepTheirPlaces() {
        let pending = approval(decision: nil)
        #expect(pending.primaryOption?.id == "allow")
        #expect(pending.dangerOption?.id == "deny")
        #expect(pending.otherOptions.map(\.id) == ["allow_session", "allow_always"])
    }

    @Test("An elsewhere decision decodes off the wire with its source")
    func elsewhereDecoding() throws {
        let json: JSONValue = ["seq": 17, "ts": 1, "kind": "approval", "block_id": "ap",
                               "request_id": "r", "tool": "shell", "tool_kind": "shell",
                               "title": "npm run typecheck",
                               "options": [["id": "allow", "label": "Allow", "style": "primary"]],
                               "status": "resolved",
                               "decision": ["option_id": "elsewhere", "by": "terminal"]]
        let event = try json.decode(SessionEvent.self)
        #expect(event.approval?.decision?.optionID == ApprovalPayload.elsewhereOptionID)
        #expect(event.approval?.decision?.by == .terminal)
        #expect(event.approval?.resolvedOptionLabel == nil)
    }

    // MARK: - The demo

    @Test("The demo carries a shared Codex thread the app drives in full")
    @MainActor
    func demoFixtures() {
        guard let session = DemoFixtures.sessions.first(where: {
            $0.sessionID == DemoFixtures.codexSharedSessionID
        }) else {
            Issue.record("the demo is missing the shared Codex thread")
            return
        }
        #expect(session.agent == "codex")
        #expect(session.control == .shared)
        #expect(session.origin == .terminal)
        #expect(session.state.isWorking)

        let agent = DemoFixtures.devices.first { $0.deviceID == session.deviceID }?.agent("codex")
        #expect(agent?.attach == .daemon)
        #expect(agent?.attachReady == true)
        #expect(agent?.sharedInterrupt == true)
        #expect(agent?.sharedSettings == true)
        #expect(agent?.sharedAttachments == true)

        let approval = DemoFixtures.codexSharedHistory().compactMap(\.approval).last
        #expect(approval?.options.count == 4)
        #expect(approval?.status == .pending)
    }

    @Test("The daemon-less Codex still explains how to make the next run controllable")
    @MainActor
    func withoutTheDaemon() {
        let agent = DemoFixtures.codexWithoutDaemon
        #expect(agent.attach == .daemon)
        #expect(!agent.attachReady)
        #expect(!agent.sharedSettings)
        #expect(!agent.sharedAttachments)
        let chat = store(state: .readonly, agent: agent, control: .terminal)
        #expect(chat.attachHint == .startDaemon)
    }

    @Test("The demo applies session.set to a shared thread whose device relays it")
    @MainActor
    func demoRetunesTheThread() async throws {
        let gateway = DemoGateway()
        guard let session = DemoFixtures.sessions.first(where: {
            $0.sessionID == DemoFixtures.codexSharedSessionID
        }) else {
            Issue.record("the demo is missing the shared Codex thread")
            return
        }
        let chat = ChatStore(session: session, channel: gateway)
        chat.agent = DemoFixtures.codex
        await chat.set(effort: "high")
        #expect(chat.session.effort == "high")
        #expect(chat.errorMessage == nil)

        // The same request on a Claude channel is still refused.
        guard let claudeShared = DemoFixtures.sessions.first(where: {
            $0.sessionID == DemoFixtures.sharedSessionID
        }) else {
            Issue.record("the demo is missing the attached Claude session")
            return
        }
        let relayed = ChatStore(session: claudeShared, channel: gateway)
        relayed.agent = DemoFixtures.claude
        await relayed.set(effort: "high")
        #expect(relayed.session.effort != "high")
        #expect(relayed.errorMessage != nil)
    }

    @Test("The demo interrupts a shared thread only when the attachment can")
    @MainActor
    func demoStopsTheThread() async throws {
        let gateway = DemoGateway()
        guard let session = DemoFixtures.sessions.first(where: {
            $0.sessionID == DemoFixtures.codexSharedSessionID
        }) else {
            Issue.record("the demo is missing the shared Codex thread")
            return
        }
        let chat = ChatStore(session: session, channel: gateway)
        chat.agent = DemoFixtures.codex
        let events = gateway.events
        let pump = Task { @MainActor in
            for await event in events where !Task.isCancelled {
                if case .frame(let frame) = event { chat.receive(frame) }
            }
        }
        defer { pump.cancel() }

        #expect(chat.canStop)
        await chat.stop()
        #expect(chat.errorMessage == nil)
        try await settle(timeout: 10) { !chat.isRunning }
        #expect(!chat.isRunning)

        // The same request on a channel that cannot interrupt is refused.
        guard let relayedSession = DemoFixtures.sessions.first(where: {
            $0.sessionID == DemoFixtures.sharedSessionID
        }) else {
            Issue.record("the demo is missing the attached Claude session")
            return
        }
        let relayed = ChatStore(session: relayedSession, channel: gateway)
        relayed.agent = DemoFixtures.claude
        await relayed.stop()
        #expect(relayed.errorMessage != nil)
    }

    @MainActor
    private func settle(timeout: TimeInterval = 3, _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
    }
}
