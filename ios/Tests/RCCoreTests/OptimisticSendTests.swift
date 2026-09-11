import Testing
import Foundation
@testable import RCCore

/// Amendment A12: the app mints the `session.send` request id, the device
/// echoes it as the `user_message` block id, and the message is therefore on
/// screen before the request has left. These cover what happens to that row for
/// every outcome the send can have.
@Suite("Optimistic send")
struct OptimisticSendTests {
    private func session(state: SessionState = .idle) -> Session {
        Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T", cwd: "/tmp",
                state: state, control: .remote)
    }

    private func message(_ text: String, id: String, source: EventSource = .remote,
                         seq: Int) -> SessionEvent {
        SessionEvent(seq: seq, ts: 0, kind: SessionEvent.userMessageKind, blockID: id,
                     body: .userMessage(UserMessagePayload(text: text, source: source)))
    }

    // MARK: - The row appears before the request is answered

    @Test("The message is in the transcript while the send is still in flight")
    @MainActor
    func shownBeforeTheReply() async {
        let channel = HeldSendChannel()
        let chat = ChatStore(session: session(), channel: channel)
        chat.draft = "ship it"

        let sending = Task { await chat.send() }
        await channel.waitForSend()

        #expect(chat.draft.isEmpty)
        #expect(chat.timeline.roots.count == 1)
        let row = chat.timeline.roots.last
        #expect(row?.pending?.text == "ship it")
        #expect(row?.userMessage?.text == "ship it")
        #expect(row?.userMessage?.source == .remote)
        // The row is the request id, which is what the device will echo.
        #expect(row?.id == channel.lastSendID)

        channel.release(.success(SendResult(accepted: .sent)))
        await sending.value
        #expect(chat.timeline.optimistic.count == 1, "still waiting for the device's own event")
    }

    @Test("Attachment metadata rides along, and the bytes do not")
    @MainActor
    func attachmentMetadata() async {
        let channel = HeldSendChannel()
        let chat = ChatStore(session: session(), channel: channel)
        chat.draft = "look at this"

        let sending = Task {
            await chat.send(attachments: [OutboundAttachment(name: "shot.png", mime: "image/png",
                                                             data: Data([1, 2, 3, 4]))])
        }
        await channel.waitForSend()
        let attachment = chat.timeline.roots.last?.userMessage?.attachments.first
        #expect(attachment?.name == "shot.png")
        #expect(attachment?.mime == "image/png")
        #expect(attachment?.size == 4)

        channel.release(.success(SendResult(accepted: .sent)))
        await sending.value
    }

    // MARK: - Reconciliation

    @Test("The device's event under the same id replaces the row rather than adding one")
    @MainActor
    func replacedByID() {
        var timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id: "req-1", text: "ship it"))
        #expect(timeline.roots.count == 1)

        timeline.apply(message("ship it", id: "req-1", seq: 1))
        #expect(timeline.optimistic.isEmpty)
        #expect(timeline.roots.count == 1)
        #expect(timeline.roots.first?.pending == nil)
        #expect(timeline.roots.first?.id == "req-1")
    }

    @Test("A device that mints its own ids is reconciled by text and source")
    @MainActor
    func reconciledByText() {
        var timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id: "req-1", text: "ship it"))

        timeline.apply(message("ship it", id: "device-block", seq: 1))
        #expect(timeline.optimistic.isEmpty)
        #expect(timeline.roots.count == 1)
        #expect(timeline.roots.first?.id == "device-block")
    }

    @Test("Only one row is reconciled per event, and only a remote message reconciles at all")
    @MainActor
    func reconcilesOneRowAtATime() {
        var timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id: "req-1", text: "again"))
        timeline.addOptimistic(OptimisticMessage(id: "req-2", text: "again"))

        // The same words typed in the terminal are a different message.
        timeline.apply(message("again", id: "t-1", source: .terminal, seq: 1))
        #expect(timeline.optimistic.count == 2)

        timeline.apply(message("again", id: "device-block", seq: 2))
        #expect(timeline.optimistic.map(\.id) == ["req-2"])
    }

    @Test("Adding the same send twice keeps one row, so Retry reuses it")
    @MainActor
    func addIsIdempotent() {
        var timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id: "req-1", text: "ship it"))
        timeline.addOptimistic(OptimisticMessage(id: "req-1", text: "ship it"))
        #expect(timeline.optimistic.count == 1)

        timeline.apply(message("ship it", id: "req-1", seq: 1))
        timeline.addOptimistic(OptimisticMessage(id: "req-1", text: "ship it"))
        #expect(timeline.optimistic.isEmpty, "a block the device has already sent is never re-added")
        #expect(timeline.roots.count == 1)
    }

    // MARK: - Outcomes

    @Test("A queued send hands its row to the queue")
    @MainActor
    func queuedRemovesTheRow() async {
        let channel = HeldSendChannel()
        let chat = ChatStore(session: session(state: .running), channel: channel)
        chat.draft = "then run the suite"

        let sending = Task { await chat.send() }
        await channel.waitForSend()
        #expect(chat.timeline.roots.count == 1)

        channel.release(.success(SendResult(accepted: .queued, queuedID: channel.lastSendID)))
        await sending.value
        #expect(chat.timeline.optimistic.isEmpty)
        #expect(chat.timeline.roots.isEmpty, "the queue row above the composer stands for it now")
    }

    @Test("A queue snapshot retires the row of a message it names")
    @MainActor
    func queueSnapshotRetiresTheRow() {
        var timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id: "req-1", text: "then run the suite"))
        timeline.apply(SessionEvent(seq: 1, ts: 0, kind: SessionEvent.queueKind,
                                    body: .queue(QueuePayload(pending: [
                                        QueuedMessage(id: "req-1", text: "then run the suite", ts: 0)
                                    ]))))
        #expect(timeline.optimistic.isEmpty)
        #expect(timeline.queue.count == 1)
    }

    @Test("A refusal takes the row away and gives the words back")
    @MainActor
    func refusalRestoresTheDraft() async {
        let channel = HeldSendChannel()
        let chat = ChatStore(session: session(), channel: channel)
        chat.draft = "take over first"

        let sending = Task { await chat.send() }
        await channel.waitForSend()
        channel.release(.failure(GatewayErrorBody(code: .conflict,
                                                  message: "Controlled by the terminal; take over first.")))
        await sending.value

        #expect(chat.timeline.optimistic.isEmpty)
        #expect(chat.timeline.roots.isEmpty)
        #expect(chat.errorMessage == "Controlled by the terminal; take over first.")
        #expect(chat.draft == "take over first")
    }

    @Test("A refusal never overwrites what is already being typed")
    @MainActor
    func refusalLeavesANewDraftAlone() async {
        let channel = HeldSendChannel()
        let chat = ChatStore(session: session(), channel: channel)
        chat.draft = "the first one"

        let sending = Task { await chat.send() }
        await channel.waitForSend()
        chat.draft = "already typing the next one"
        channel.release(.failure(GatewayErrorBody(code: .unsupported, message: "No.")))
        await sending.value

        #expect(chat.draft == "already typing the next one")
        #expect(chat.timeline.roots.isEmpty)
    }

    @Test("An uncertain delivery keeps the row, and Retry reuses it")
    @MainActor
    func uncertainKeepsTheRow() async {
        let channel = HeldSendChannel()
        let chat = ChatStore(session: session(), channel: channel)
        chat.draft = "did that land"

        let sending = Task { await chat.send() }
        await channel.waitForSend()
        let id = channel.lastSendID
        channel.release(.failure(TransportError.deliveryUncertain))
        await sending.value

        #expect(chat.timeline.roots.count == 1, "the message may well have landed, so it stays")
        #expect(chat.unconfirmedSend?.id == id)
        #expect(chat.draft.isEmpty, "the words are in the row, not back in the field")

        guard let pending = chat.unconfirmedSend else { return }
        let retry = Task { await chat.retry(pending) }
        await channel.waitForSend()
        #expect(channel.lastSendID == id, "a retry reuses the request id")
        #expect(chat.timeline.roots.count == 1, "and does not add a second row")
        channel.release(.success(SendResult(accepted: .sent)))
        await retry.value
    }

    @Test("Giving up on an unconfirmed send takes its row with it")
    @MainActor
    func dismissRemovesTheRow() async {
        let channel = HeldSendChannel()
        let chat = ChatStore(session: session(), channel: channel)
        chat.draft = "never mind"

        let sending = Task { await chat.send() }
        await channel.waitForSend()
        channel.release(.failure(TransportError.requestTimedOut))
        await sending.value

        guard let pending = chat.unconfirmedSend else { return #expect(Bool(false)) }
        chat.dismiss(pending)
        #expect(chat.timeline.roots.isEmpty)
        #expect(chat.unconfirmedSend == nil)
    }

    // MARK: - Waiting too long

    @Test("A send unconfirmed for a minute says so")
    func unconfirmedAfterAMinute() {
        let now = Date()
        let fresh = OptimisticMessage(id: "a", text: "x", sentAt: now.addingTimeInterval(-5))
        #expect(!fresh.isUnconfirmed(at: now))
        #expect(fresh.remainingBeforeUnconfirmed(at: now) == 55)

        let stale = OptimisticMessage(id: "b", text: "y", sentAt: now.addingTimeInterval(-61))
        #expect(stale.isUnconfirmed(at: now))
        #expect(stale.remainingBeforeUnconfirmed(at: now) == 0)

        var timeline = Timeline()
        timeline.addOptimistic(fresh)
        timeline.addOptimistic(stale)
        #expect(timeline.unconfirmedOptimistic(at: now).map(\.id) == ["b"])
    }

    // MARK: - Reconnects

    @Test("A resync keeps the row, and the reloaded history does not duplicate it")
    @MainActor
    func resyncNeitherDropsNorDuplicates() {
        var timeline = Timeline()
        timeline.apply(message("an older one", id: "old", seq: 1))
        timeline.addOptimistic(OptimisticMessage(id: "req-1", text: "ship it"))

        timeline.reset()
        #expect(timeline.entries.isEmpty)
        #expect(timeline.optimistic.count == 1, "a message the user just sent does not vanish")
        #expect(timeline.roots.count == 1)

        timeline.prependHistory([message("an older one", id: "old", seq: 1),
                                 message("ship it", id: "req-1", seq: 2)], hasMore: false)
        #expect(timeline.optimistic.isEmpty)
        #expect(timeline.roots.count == 2, "the reloaded copy is the only copy")
    }

    @Test("A pending row never becomes a history cursor or moves the replay cursor")
    @MainActor
    func pendingRowsCarryNoCursor() {
        var timeline = Timeline()
        timeline.apply(message("an older one", id: "old", seq: 4))
        timeline.addOptimistic(OptimisticMessage(id: "req-1", text: "ship it"))
        #expect(timeline.lastSeq == 4)
        #expect(timeline.oldestSeq == 4)
    }
}

/// A channel that stops on `session.send` until the test says what happened, so
/// the state of the transcript mid-flight is observable rather than guessed at.
@MainActor
private final class HeldSendChannel: GatewayChannel {
    nonisolated let events: AsyncStream<GatewayEvent>
    private let continuation: AsyncStream<GatewayEvent>.Continuation
    private var held: CheckedContinuation<Result<SendResult, any Error>, Never>?
    private var arrived: CheckedContinuation<Void, Never>?
    private var sendPending = false
    private(set) var lastSendID = ""

    init() {
        let stream = AsyncStream<GatewayEvent>.makeStream(bufferingPolicy: .bufferingOldest(8))
        events = stream.stream
        continuation = stream.continuation
    }

    func connect() async {}
    func disconnect() async {}

    @discardableResult
    func request(_ request: GatewayRequest) async throws -> JSONValue {
        guard request.type == "session.send" else { return .object([:]) }
        lastSendID = request.id
        sendPending = true
        arrived?.resume()
        arrived = nil
        let outcome = await withCheckedContinuation { (c: CheckedContinuation<Result<SendResult, any Error>, Never>) in
            held = c
        }
        switch outcome {
        case .success(let result): return try JSONValue.encode(result)
        case .failure(let error): throw error
        }
    }

    /// Returns once the send has reached the channel and is waiting there.
    func waitForSend() async {
        guard !sendPending else { sendPending = false; return }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            if sendPending { c.resume() } else { arrived = c }
        }
        sendPending = false
    }

    func release(_ outcome: Result<SendResult, any Error>) {
        held?.resume(returning: outcome)
        held = nil
    }
}
