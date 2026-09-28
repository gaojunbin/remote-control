import Testing
import Foundation
@testable import RCCore

/// Amendment A43: a queued message is edited by taking it out of the line with
/// `session.queue_remove`, editing its words in the composer, and sending them
/// back with `mode: "queue"` under the `ts` the entry had. These cover the
/// store's half of it; `docs/DESIGN.md` § "The composer" → **Up next** is the
/// ruling.
@Suite("Amendment A43, editing a queued message")
struct QueuedEditTests {
    private static let queue = [
        QueuedMessage(id: "q-a", text: "Then run the full test suite.", ts: 1_000),
        QueuedMessage(id: "q-b", text: "Add a regression test.", ts: 2_000),
        QueuedMessage(id: "q-c", text: "Here are the screenshots.", ts: 3_000, attachments: 2)
    ]

    private static let steering = AgentInfo(agent: "codex", available: true,
                                            capabilities: [.interrupt, .queue, .steer, .commands])

    @MainActor
    private func store(state: SessionState = .running, control: SessionControl = .remote,
                       agent: AgentInfo = DemoFixtures.claude) -> (ChatStore, QueueChannel) {
        let session = Session(sessionID: "s", deviceID: "d", agent: agent.agent, title: "T",
                              cwd: "/tmp", state: state, control: control)
        let channel = QueueChannel()
        let chat = ChatStore(session: session, channel: channel)
        chat.agent = agent
        chat.receive(.sessionEvent(sessionID: "s", deviceID: "d",
                                   event: SessionEvent(seq: 1, ts: 1, kind: SessionEvent.queueKind,
                                                       body: .queue(QueuePayload(pending: Self.queue)))))
        return (chat, channel)
    }

    // MARK: - The wire

    @Test("An entry says how many files it holds, and says nothing when it holds none")
    func attachmentsDecode() throws {
        let held: JSONValue = ["id": "q", "text": "t", "ts": 1, "attachments": 2]
        let entry = try held.decode(QueuedMessage.self)
        #expect(entry.attachments == 2)
        #expect(entry.carriesFiles)

        let bare = try JSONValue.object(["id": "q", "text": "t", "ts": 1]).decode(QueuedMessage.self)
        #expect(bare.attachments == nil)
        #expect(!bare.carriesFiles)
        #expect(try JSONValue.encode(bare)["attachments"] == nil, "and an absent count stays absent")
    }

    @Test("session.send carries queue_ts only for a message going back to its place")
    func queueTsIsOptional() throws {
        let back = try GatewayRequest.send(sessionID: "s", text: "t", mode: .queue, queueTs: 2_000)
        #expect(back.json["queue_ts"] == .integer(2_000))
        #expect(try GatewayRequest.send(sessionID: "s", text: "t").json["queue_ts"] == nil)
    }

    // MARK: - Taking it out

    @Test("Remove takes the row out of the list before the device answers")
    @MainActor
    func removeLeavesTheListAtOnce() async {
        let (chat, channel) = store()
        let removing = chat.removeQueued("q-b")
        #expect(chat.timeline.queue.map(\.id) == ["q-a", "q-c"],
                "a swipe has already taken the row off the screen")
        #expect(chat.session.queued == 2, "and the chip counts one fewer")
        await removing.value
        #expect(channel.requests.map(\.type) == ["session.queue_remove"])
        #expect(channel.requests.first?.body["queued_id"]?.stringValue == "q-b")
    }

    @Test("A Remove that finds the message gone says so the way an edit does")
    @MainActor
    func removeNotFoundSaysAlreadySent() async {
        let (chat, channel) = store()
        channel.removal = GatewayErrorBody(code: .notFound, message: "that message is not queued")
        await chat.removeQueued("q-a").value
        #expect(chat.errorMessage == "That message has already been sent.",
                "never the device's bare not_found")
        #expect(chat.timeline.queue.map(\.id) == ["q-b", "q-c"], "and the row stays gone")
    }

    @Test("A Remove the device refuses puts the row back where it stood")
    @MainActor
    func refusedRemoveComesBack() async {
        let failures: [any Error] = [
            GatewayErrorBody(code: .deviceOffline, message: "That device is offline."),
            GatewayErrorBody(code: .conflict, message: "busy"),
            TransportError.requestTimedOut
        ]
        for failure in failures {
            let (chat, channel) = store()
            channel.removal = failure
            let removing = chat.removeQueued("q-b")
            #expect(chat.timeline.queue.map(\.id) == ["q-a", "q-c"])
            await removing.value
            #expect(chat.timeline.queue.map(\.id) == ["q-a", "q-b", "q-c"],
                    "the device still holds it, and nothing else would bring it back: \(failure)")
            #expect(chat.session.queued == 3)
            #expect(chat.errorMessage != nil)
        }
    }

    @Test("A snapshot that arrived while the Remove was out knows better than the row")
    @MainActor
    func refusedRemoveDefersToANewerSnapshot() async throws {
        let (chat, channel) = store()
        channel.removal = GatewayErrorBody(code: .deviceOffline, message: "That device is offline.")
        channel.holding = ["session.queue_remove"]
        let removing = chat.removeQueued("q-b")
        try await settle { channel.requests.count == 1 }
        chat.receive(.sessionEvent(sessionID: "s", deviceID: "d",
                                   event: SessionEvent(seq: 2, ts: 2, kind: SessionEvent.queueKind,
                                                       body: .queue(QueuePayload(pending: [Self.queue[2]])))))
        channel.release()
        await removing.value
        #expect(chat.timeline.queue.map(\.id) == ["q-c"], "the newer snapshot stands")
        #expect(chat.errorMessage == "That device is offline.")
    }

    @Test("A tap takes the message out of the line first, then puts its words in the field")
    @MainActor
    func beginTakesItOutFirst() async {
        let (chat, channel) = store()
        chat.draft = "a note of my own"
        await chat.beginEdit(Self.queue[1])

        #expect(channel.requests.map(\.type) == ["session.queue_remove"])
        #expect(channel.requests.first?.body["queued_id"]?.stringValue == "q-b")
        #expect(chat.queuedEdit == QueuedEdit(ts: 2_000, original: "Add a regression test.",
                                              aside: "a note of my own"))
        #expect(chat.draft == "Add a regression test.", "the entry's words, not appended to the draft")
    }

    @Test("A message the device took first opens nothing, and one line says so")
    @MainActor
    func notFoundOpensNothing() async {
        let (chat, channel) = store()
        channel.removal = GatewayErrorBody(code: .notFound, message: "that message is not queued")
        chat.draft = "mine"
        await chat.beginEdit(Self.queue[0])

        #expect(chat.errorMessage == "That message has already been sent.")
        #expect(chat.queuedEdit == nil)
        #expect(chat.draft == "mine", "the field is untouched")
    }

    @Test("A message that carries files is removed, never edited")
    @MainActor
    func filesAreRemoveOnly() async {
        let (chat, channel) = store()
        #expect(!chat.canEdit(Self.queue[2]))
        await chat.beginEdit(Self.queue[2])
        #expect(channel.requests.isEmpty, "nothing is taken out of the line")
        #expect(chat.queuedEdit == nil)
    }

    @Test("Where the composer cannot send, every row is Remove only")
    @MainActor
    func disabledComposerIsRemoveOnly() {
        let (chat, _) = store()
        #expect(chat.canEdit(Self.queue[0]))
        chat.deviceOnline = false
        #expect(!chat.canEdit(Self.queue[0]), "the device is offline")

        let (held, _) = store(control: .terminal)
        #expect(!held.canEdit(Self.queue[0]), "the terminal holds the session")
    }

    @Test("One message is edited at a time")
    @MainActor
    func oneAtATime() async {
        let (chat, channel) = store()
        await chat.beginEdit(Self.queue[0])
        #expect(!chat.canEdit(Self.queue[1]))
        await chat.beginEdit(Self.queue[1])
        #expect(channel.requests.count == 1, "the second tap takes nothing out")
        #expect(chat.queuedEdit?.ts == 1_000)
    }

    // MARK: - Putting it back

    @Test("Send puts the edited words back under the entry's ts, even behind a steering agent")
    @MainActor
    func sendRequeuesInPlace() async {
        let (chat, channel) = store(agent: Self.steering)
        chat.draft = "a note of my own"
        await chat.beginEdit(Self.queue[1])
        #expect(!chat.steersRunningTurn, "neither the status line nor the placeholder promises a steer")
        #expect(chat.editingSendLabel == "Queue", "the primary names what it will do")

        chat.draft = "Add a regression test for the logout race too."
        let outcome = await chat.send()

        #expect(outcome == .accepted)
        let body = channel.sends.last?.body
        #expect(body?["mode"]?.stringValue == "queue")
        #expect(body?["queue_ts"] == .integer(2_000))
        #expect(body?["text"]?.stringValue == "Add a regression test for the logout race too.")
        #expect(chat.queuedEdit == nil)
        #expect(chat.draft == "a note of my own", "and the words set aside come back")
    }

    @Test("On an idle session the primary reads Send, and the edit still goes as a queued message")
    @MainActor
    func idleSendsAtOnce() async {
        let (chat, channel) = store(state: .idle)
        await chat.beginEdit(Self.queue[0])
        #expect(chat.editingSendLabel == "Send")
        await chat.send()
        #expect(channel.sends.last?.body["mode"]?.stringValue == "queue")
        #expect(channel.sends.last?.body["queue_ts"] == .integer(1_000))
    }

    @Test("While the words are on their way back, nothing can send them twice")
    @MainActor
    func putBackHoldsStill() async throws {
        let (chat, channel) = store()
        chat.draft = "mine"
        await chat.beginEdit(Self.queue[1])
        chat.draft = "edited"
        channel.holding = ["session.send"]
        let sending = Task { await chat.send() }
        try await settle { channel.sends.count == 1 }

        #expect(chat.isReturningEdit)
        #expect(chat.draft == "edited", "the field keeps the words while they are out")
        #expect(!chat.canSend, "the button's place is a spinner")
        #expect(ComposerPrimarySlot.of(voice: .idle, polish: chat.polishPhase,
                                       returning: chat.isReturningEdit) == .working)
        #expect(!chat.canCancelEdit, "and Cancel waits with it")
        await chat.cancelEdit()
        #expect(channel.sends.count == 1, "a Cancel tapped meanwhile sends nothing")

        channel.release()
        await sending.value
        #expect(channel.sends.count == 1, "exactly one session.send went out")
        #expect(!chat.isReturningEdit)
        #expect(chat.queuedEdit == nil)
        #expect(chat.draft == "mine")
    }

    @Test("While editing, the status line says queued, as the button does, even on a steering agent")
    @MainActor
    func statusLineAgreesWithTheButton() async {
        let (chat, _) = store(agent: Self.steering)
        await chat.beginEdit(Self.queue[0])
        // The device's snapshot once the line has emptied behind the edit.
        chat.receive(.sessionEvent(sessionID: "s", deviceID: "d",
                                   event: SessionEvent(seq: 2, ts: 2, kind: SessionEvent.queueKind,
                                                       body: .queue(QueuePayload(pending: [])))))
        #expect(chat.statusLine == "Working · your message will be queued")
        await chat.cancelEdit()
        #expect(chat.statusLine == "Working · your message will steer the turn",
                "and once the edit is over the agent steers again")
    }

    @Test("A refused send keeps editing, the words in the field and the other draft aside")
    @MainActor
    func refusalKeepsEditing() async {
        let (chat, channel) = store()
        chat.draft = "mine"
        await chat.beginEdit(Self.queue[1])
        chat.draft = "edited"
        channel.sending = .failure(GatewayErrorBody(code: .deviceOffline, message: "That device is offline."))

        #expect(await chat.send() == .refused)
        #expect(chat.queuedEdit?.aside == "mine")
        #expect(chat.draft == "edited")
        #expect(chat.errorMessage == "That device is offline.")
    }

    @Test("Cancel puts the original words back in their place, and the other draft in the field")
    @MainActor
    func cancelRequeuesTheOriginal() async {
        let (chat, channel) = store()
        chat.draft = "mine"
        await chat.beginEdit(Self.queue[1])
        chat.draft = "half edited"
        await chat.cancelEdit()

        let body = channel.sends.last?.body
        #expect(body?["text"]?.stringValue == "Add a regression test.")
        #expect(body?["mode"]?.stringValue == "queue")
        #expect(body?["queue_ts"] == .integer(2_000))
        #expect(chat.queuedEdit == nil)
        #expect(chat.draft == "mine", "the edited words go; the ones set aside return")
    }

    @Test("A refused Cancel changes nothing")
    @MainActor
    func refusedCancelChangesNothing() async {
        let (chat, channel) = store()
        chat.draft = "mine"
        await chat.beginEdit(Self.queue[1])
        chat.draft = "half edited"
        channel.sending = .failure(GatewayErrorBody(code: .conflict, message: "No."))
        await chat.cancelEdit()

        #expect(chat.queuedEdit?.aside == "mine")
        #expect(chat.draft == "half edited")
    }

    @Test("Interrupt & send leaves the line: no queue_ts, and the edit is over")
    @MainActor
    func interruptLeavesTheLine() async {
        let (chat, channel) = store()
        chat.draft = "mine"
        await chat.beginEdit(Self.queue[1])
        chat.draft = "now, please"
        channel.sending = .success(SendResult(accepted: .sent))
        await chat.send(mode: .interrupt)

        let body = channel.sends.last?.body
        #expect(body?["mode"]?.stringValue == "interrupt")
        #expect(body?["queue_ts"] == nil)
        #expect(chat.queuedEdit == nil)
        #expect(chat.draft == "mine")
    }

    @Test("An unconfirmed send ends the edit, and Retry keeps the message's place")
    @MainActor
    func retryKeepsQueueTs() async throws {
        let (chat, channel) = store()
        chat.draft = "mine"
        await chat.beginEdit(Self.queue[1])
        chat.draft = "edited"
        channel.sending = .failure(TransportError.deliveryUncertain)

        #expect(await chat.send() == .uncertain)
        #expect(chat.queuedEdit == nil)
        #expect(chat.draft == "mine")
        let pending = try #require(chat.unconfirmedSend)
        #expect(pending.queueTs == 2_000)

        channel.sending = .success(SendResult(accepted: .queued, queuedID: pending.id))
        await chat.retry(pending)
        let retried = channel.sends.last
        #expect(retried?.id == pending.id, "the same request")
        #expect(retried?.body["mode"]?.stringValue == "queue")
        #expect(retried?.body["queue_ts"] == .integer(2_000), "going back to the same place")
    }

    // MARK: - A message and nothing else

    @Test("While editing, the command panel is not offered")
    @MainActor
    func noCommandPanelWhileEditing() async {
        let (chat, _) = store(agent: Self.steering)
        chat.draft = "/re"
        #expect(chat.commandDraft != nil, "a slash opens the panel on an ordinary draft")
        chat.draft = ""
        await chat.beginEdit(Self.queue[0])
        chat.draft = "/review the diff"
        #expect(chat.commandDraft == nil, "the edited message is a message and nothing else")
        #expect(chat.commandRows.isEmpty)
    }

    @Test("A pending question waits for the field rather than taking it")
    @MainActor
    func questionWaitsForTheField() async {
        let (chat, channel) = store(state: .needsInput)
        chat.receive(.sessionEvent(sessionID: "s", deviceID: "d",
                                   event: SessionEvent(seq: 2, ts: 2, kind: SessionEvent.questionKind,
                                                       blockID: "q-1",
                                                       body: .question(DemoFixtures.sharedQuestion))))
        #expect(chat.pendingQuestion != nil)

        await chat.beginEdit(Self.queue[0])
        #expect(chat.pendingQuestion == nil, "the field is the edited message")
        #expect(chat.allowsAnswers, "and the card itself stays live")
        chat.draft = "edited"
        await chat.send()
        #expect(channel.sends.count == 1, "Send sent the message rather than an answer")
        #expect(!channel.requests.contains { $0.type == "session.answer" })
        #expect(chat.pendingQuestion != nil, "once the edit is over the question has the field again")
    }

    @Test("An edit left open when the conversation closed comes back with it")
    @MainActor
    func resumedEditStillSendsInPlace() async {
        let (chat, channel) = store()
        chat.draft = "edited"
        chat.resumeEdit(QueuedEdit(ts: 2_000, original: "Add a regression test.", aside: "mine"))
        await chat.send()
        #expect(channel.sends.last?.body["queue_ts"] == .integer(2_000))
        #expect(chat.draft == "mine")
    }

    @MainActor
    private func settle(timeout: TimeInterval = 3, _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
    }
}

/// A channel that answers `session.queue_remove` and `session.send` as the test
/// says, and records every request it was given.
@MainActor
private final class QueueChannel: GatewayChannel {
    nonisolated let events: AsyncStream<GatewayEvent>
    private(set) var requests: [GatewayRequest] = []
    /// What `session.queue_remove` fails with, or nil for `{}`.
    var removal: (any Error)?
    /// What `session.send` answers.
    var sending: Result<SendResult, any Error> = .success(SendResult(accepted: .queued))
    /// Request types that wait for `release()` before they answer.
    var holding: Set<String> = []
    private var waiting: CheckedContinuation<Void, Never>?

    init() { events = AsyncStream<GatewayEvent>.makeStream(bufferingPolicy: .bufferingOldest(8)).stream }

    var sends: [GatewayRequest] { requests.filter { $0.type == "session.send" } }

    func release() {
        waiting?.resume()
        waiting = nil
    }

    func connect() async {}
    func disconnect() async {}

    @discardableResult
    func request(_ request: GatewayRequest) async throws -> JSONValue {
        requests.append(request)
        if holding.contains(request.type) { await withCheckedContinuation { waiting = $0 } }
        switch request.type {
        case "session.queue_remove":
            if let removal { throw removal }
            return .object([:])
        case "session.send":
            switch sending {
            case .success(let result): return try JSONValue.encode(result)
            case .failure(let error): throw error
            }
        default:
            return .object([:])
        }
    }
}
