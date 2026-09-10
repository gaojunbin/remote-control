import Foundation
import Observation

/// A `session.send` the app issued and what it knows about its fate.
public struct PendingSend: Identifiable, Sendable, Equatable {
    public enum Status: Sendable, Equatable { case sending, accepted(SendAcceptance), uncertain, failed(String) }
    public let id: String
    public let text: String
    /// The bytes are kept until the send is accepted or dismissed, so a retry
    /// sends the same message rather than the text without its attachments.
    public let attachments: [OutboundAttachment]
    public let mode: SendMode
    public var status: Status

    public init(id: String, text: String, attachments: [OutboundAttachment],
                mode: SendMode, status: Status) {
        self.id = id
        self.text = text
        self.attachments = attachments
        self.mode = mode
        self.status = status
    }

    public var attachmentInfo: [AttachmentInfo] { attachments.map(\.info) }

    public var isUnconfirmed: Bool {
        if case .uncertain = status { return true }
        return false
    }
}

/// One open conversation: its transcript, its composer state, and the requests
/// it has in flight.
///
/// Created when a chat opens and torn down when it closes; the subscription and
/// the frame handler live exactly as long as this object's scope.
@MainActor
@Observable
public final class ChatStore {
    public private(set) var session: Session
    public private(set) var timeline = Timeline()
    public private(set) var isLoadingHistory = false
    /// True while a resubscribe is in flight, and while a detected gap is being
    /// repaired, so neither is issued twice.
    public private(set) var isStale = false
    @ObservationIgnored private var isSubscribing = false
    public private(set) var errorMessage: String?
    public private(set) var pendingSends: [PendingSend] = []
    public private(set) var expandedBlockIDs: Set<String> = []
    /// Rows that arrived while the user was reading further up.
    public private(set) var updatesWhileAway = 0
    /// What the device did with the most recent accepted message.
    public private(set) var lastAcceptance: SendAcceptance?
    public var isFollowingTail = true { didSet { if isFollowingTail { updatesWhileAway = 0 } } }
    public var draft = ""
    /// Set by the view layer, which is what knows about the socket and the
    /// device inventory. A send that cannot succeed is refused with a reason
    /// rather than failing after the draft has been cleared.
    public var connectionReady = true
    public var deviceOnline = true

    @ObservationIgnored private let channel: any GatewayChannel
    @ObservationIgnored private let onSessionChange: @MainActor (Session) -> Void

    public var sessionID: String { session.sessionID }
    public var deviceID: String { session.deviceID }
    public var key: String { session.id }

    public init(session: Session, channel: any GatewayChannel,
                onSessionChange: @escaping @MainActor (Session) -> Void = { _ in }) {
        self.session = session
        self.channel = channel
        self.onSessionChange = onSessionChange
    }

    // MARK: - Derived state

    /// A turn is in progress, whoever started it. Amendment A7: a terminal
    /// session reports `running` while its turn runs and `readonly` only when
    /// it is idle, so this is true for terminal-driven work too.
    public var isRunning: Bool { session.state.isWorking }

    /// Whether this app may type. Amendment A7: read-only follows `control`,
    /// never `state` — a terminal session is locked whether it is running or
    /// idle, and unlocking it means taking over.
    public var isReadOnly: Bool { session.isControlledByTerminal }

    /// Stopping a turn the terminal owns is not ours to do.
    public var canStop: Bool { isRunning && !isReadOnly }
    public var canSend: Bool {
        sendBlockReason == nil && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Why the composer cannot send right now, in the words the user sees.
    public var sendBlockReason: String? {
        if isReadOnly { return "Controlled by the terminal" }
        if !connectionReady { return "Offline · your draft is saved" }
        if !deviceOnline { return "That device is offline" }
        if unconfirmedSend != nil { return "Delivery unconfirmed · retry or dismiss first" }
        return nil
    }
    public var unconfirmedSend: PendingSend? { pendingSends.first(where: \.isUnconfirmed) }

    /// The status line under the transcript.
    public var statusLine: String? {
        // The same line whether the terminal turn is running or idle: what the
        // user needs to know is that typing here requires taking over.
        if isReadOnly { return "Controlled by the terminal · Take over to send" }
        switch session.state {
        case .needsApproval: return "Waiting for your approval"
        case .needsInput: return "Waiting for your answer"
        case .running:
            return session.queued > 0
                ? "Working · \(session.queued) message\(session.queued == 1 ? "" : "s") queued"
                : "Working · your message will be queued"
        case .starting: return "Starting the agent"
        case .error: return session.stateDetail ?? "The agent reported an error"
        case .stopped: return "Stopped"
        default: return nil
        }
    }

    public var elapsedSinceTurnStart: TimeInterval? {
        guard let turn = session.turn else { return nil }
        return max(0, Date().timeIntervalSince1970 - Double(turn.startedAt) / 1000)
    }

    public func isExpanded(_ blockID: String) -> Bool { expandedBlockIDs.contains(blockID) }

    public func toggleExpanded(_ blockID: String) {
        if expandedBlockIDs.contains(blockID) { expandedBlockIDs.remove(blockID) }
        else { expandedBlockIDs.insert(blockID) }
    }

    // MARK: - Lifecycle

    /// Paint cached history, subscribe from the cursor, and page history when
    /// the gateway buffer could not cover it.
    public func open(cached: [SessionEvent] = []) async {
        if !cached.isEmpty, timeline.entries.isEmpty {
            timeline.prependHistory(cached, hasMore: true)
            // History does not advance the cursor, so adopt the newest cached
            // seq explicitly. Without it every warm open would resync and blank
            // the transcript for a round trip.
            if let newest = cached.map(\.seq).max() { timeline.adoptCursor(newest) }
        }
        await subscribe()
    }

    public func close() async {
        _ = try? await channel.request(.unsubscribe(sessionID: sessionID))
    }

    /// Section 7's reconnect order: subscribe from the cursor, and page history
    /// only when the gateway says the buffer could not cover it.
    public func subscribe() async {
        guard !isSubscribing else { return }
        isSubscribing = true
        defer { isSubscribing = false }
        let since = timeline.lastSeq > 0 ? timeline.lastSeq : nil
        do {
            let result = try await channel.request(.subscribe(sessionID: sessionID, sinceSeq: since),
                                                   as: SubscribeResult.self)
            guard !Task.isCancelled else { return }
            update(session: result.session)
            if result.resync || since == nil {
                timeline.reset()
                await loadHistory()
            }
            for event in result.events { ingest(event) }
            // Amendment A6: the reply may carry the queue snapshot directly.
            if let queue = result.queue { timeline.applySubscribedQueue(queue.pending) }
            timeline.clearGap()
            mirrorSnapshots()
            isStale = false
        } catch {
            errorMessage = describe(error)
        }
    }

    /// Keep the session summary in step with snapshots that arrived through a
    /// subscribe reply or a history page rather than a live frame.
    private func mirrorSnapshots() {
        if !timeline.todos.isEmpty || session.todos != nil {
            session.todos = TodoCounts(total: timeline.todos.count,
                                       done: timeline.todos.filter { $0.status == .completed }.count)
        }
        session.queued = timeline.queue.count
        onSessionChange(session)
    }

    /// Live frames arrive through the connection store's fan-out.
    ///
    /// A `hello` means the socket came back and the gateway has forgotten this
    /// connection's subscriptions, so the transcript resubscribes from its own
    /// cursor. Without this the screen keeps updating from `session.updated`
    /// while the transcript silently stops receiving events.
    public func receive(_ frame: AppFrame) {
        switch frame {
        case .hello:
            Task { [weak self] in await self?.subscribe() }
        case .sessionEvent(let sessionID, _, let event) where sessionID == self.sessionID:
            ingest(event)
            if timeline.hasGap { repairGap() }
        case .sessionUpdated(let session) where session.id == key:
            update(session: session)
        default:
            break
        }
    }

    /// An event never arrived. Refill from the gateway rather than render a
    /// transcript that is quietly missing a step.
    private func repairGap() {
        guard !isStale else { return }
        isStale = true
        Task { [weak self] in await self?.subscribe() }
    }

    private func ingest(_ event: SessionEvent) {
        let before = timeline.entries.count
        guard timeline.apply(event) else { return }
        if case .status(let payload) = event.body {
            session.state = payload.state
            session.stateDetail = payload.detail
            onSessionChange(session)
        }
        if case .meta(let payload) = event.body {
            applyMeta(payload)
        }
        if case .turnCompleted(let payload) = event.body {
            session.turn = nil
            if let usage = payload.usage { session.usage = usage }
            onSessionChange(session)
        }
        if case .turnStarted(let payload) = event.body {
            session.turn = TurnMarker(turnID: payload.turnID, startedAt: event.ts)
            onSessionChange(session)
        }
        if case .todos(let payload) = event.body {
            session.todos = payload.counts
            onSessionChange(session)
        }
        if case .queue(let payload) = event.body {
            session.queued = payload.pending.count
            onSessionChange(session)
        }
        if timeline.entries.count > before, !isFollowingTail { updatesWhileAway += 1 }
    }

    private func applyMeta(_ payload: MetaPayload) {
        if let title = payload.title { session.title = title }
        if let model = payload.model { session.model = model }
        if let mode = payload.permissionMode { session.permissionMode = mode }
        if let effort = payload.effort { session.effort = effort }
        if let cwd = payload.cwd { session.cwd = cwd }
        if let git = payload.git { session.git = git }
        if let control = payload.control { session.control = control }
        onSessionChange(session)
    }

    private func update(session value: Session) {
        session = value
        onSessionChange(value)
    }

    // MARK: - History

    public func loadHistory() async {
        guard !isLoadingHistory, timeline.hasMoreHistory else { return }
        isLoadingHistory = true
        defer { isLoadingHistory = false }
        do {
            let result = try await channel.request(
                .history(sessionID: sessionID, beforeSeq: timeline.oldestSeq),
                as: HistoryResult.self)
            guard !Task.isCancelled else { return }
            timeline.prependHistory(result.events, hasMore: result.hasMore)
            mirrorSnapshots()
        } catch {
            errorMessage = describe(error)
        }
    }

    /// Fetch the untruncated version of one block for "Open full output".
    public func loadFullBlock(_ blockID: String) async {
        do {
            let result = try await channel.request(.block(sessionID: sessionID, blockID: blockID),
                                                   as: BlockResult.self)
            guard !Task.isCancelled else { return }
            timeline.replaceBlock(with: result.event)
        } catch {
            errorMessage = describe(error)
        }
    }

    // MARK: - Requests

    /// Send the draft. On an uncertain delivery the message stays visible with
    /// a Retry action that reuses this same request id.
    public func send(mode: SendMode = .auto, attachments: [OutboundAttachment] = []) async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        draft = ""
        await deliver(id: UUID().uuidString, text: text, attachments: attachments, mode: mode)
    }

    public func retry(_ pending: PendingSend) async {
        await deliver(id: pending.id, text: pending.text,
                      attachments: pending.attachments, mode: pending.mode)
    }

    private func deliver(id: String, text: String, attachments: [OutboundAttachment], mode: SendMode) async {
        let record = PendingSend(id: id, text: text, attachments: attachments,
                                 mode: mode, status: .sending)
        if let index = pendingSends.firstIndex(where: { $0.id == id }) { pendingSends[index] = record }
        else { pendingSends.append(record) }
        do {
            let request = try GatewayRequest.send(id: id, sessionID: sessionID, text: text,
                                                  attachments: attachments, mode: mode)
            let result = try await channel.request(request, as: SendResult.self)
            mark(id: id, status: .accepted(result.accepted))
            isFollowingTail = true
        } catch let error as TransportError where error == .deliveryUncertain || error == .requestTimedOut {
            mark(id: id, status: .uncertain)
        } catch {
            mark(id: id, status: .failed(describe(error)))
            errorMessage = describe(error)
        }
    }

    /// An accepted message is owned by the transcript and the queue snapshot;
    /// only an in-flight, uncertain or failed one stays in `pendingSends`.
    private func mark(id: String, status: PendingSend.Status) {
        guard let index = pendingSends.firstIndex(where: { $0.id == id }) else { return }
        if case .accepted(let acceptance) = status {
            lastAcceptance = acceptance
            pendingSends.remove(at: index)
        } else {
            pendingSends[index].status = status
        }
    }

    public func dismiss(_ pending: PendingSend) {
        pendingSends.removeAll { $0.id == pending.id }
    }

    public func stop() async {
        await perform { try await self.channel.request(.stop(sessionID: self.sessionID)) }
    }

    public func approve(requestID: String, optionID: String, message: String? = nil) async {
        await perform {
            try await self.channel.request(.approve(sessionID: self.sessionID, requestID: requestID,
                                                    optionID: optionID, message: message))
        }
    }

    public func answer(requestID: String, answers: [String: QuestionAnswer]) async {
        await perform {
            try await self.channel.request(.answer(sessionID: self.sessionID, requestID: requestID,
                                                   answers: answers))
        }
    }

    public func set(model: String? = nil, permissionMode: String? = nil,
                    effort: String? = nil, title: String? = nil) async {
        do {
            let result = try await channel.request(
                .set(sessionID: sessionID, model: model, permissionMode: permissionMode,
                     effort: effort, title: title),
                as: SessionResult.self)
            update(session: result.session)
        } catch {
            errorMessage = describe(error)
        }
    }

    public func takeover() async {
        do {
            let result = try await channel.request(.takeover(sessionID: sessionID), as: SessionResult.self)
            update(session: result.session)
        } catch {
            errorMessage = describe(error)
        }
    }

    public func removeQueued(_ queuedID: String) async {
        await perform {
            try await self.channel.request(.queueRemove(sessionID: self.sessionID, queuedID: queuedID))
        }
    }

    public func setArchived(_ archived: Bool) async {
        do {
            let result = try await channel.request(.archive(sessionID: sessionID, archived: archived),
                                                   as: SessionResult.self)
            update(session: result.session)
        } catch {
            errorMessage = describe(error)
        }
    }

    private func perform(_ operation: @escaping () async throws -> Void) async {
        do { try await operation() } catch { errorMessage = describe(error) }
    }

    public func clearError() { errorMessage = nil }

    private func describe(_ error: any Error) -> String {
        if let gateway = error as? GatewayErrorBody { return gateway.message }
        if let transport = error as? TransportError { return transport.errorDescription ?? "\(transport)" }
        return error.localizedDescription
    }
}
