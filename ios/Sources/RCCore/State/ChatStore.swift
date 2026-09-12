import Foundation
import Observation

/// A `session.send` the app issued and what it knows about its fate.
public struct PendingSend: Identifiable, Sendable, Equatable {
    public enum Status: Sendable, Equatable { case sending, accepted(SendAcceptance), uncertain }
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
    /// Rows that arrived while the user was reading further up. Only rows the
    /// current detail level draws are counted: a burst of tool calls is nothing
    /// at all to someone who has chosen not to see them.
    public private(set) var updatesWhileAway = 0
    /// Where the detail level comes from. The transcript does not own the
    /// preference — the app does — so a change in Settings reaches an open
    /// conversation and its unread count at once, with no copy to fall out of
    /// step. The default matches the preference's own default.
    @ObservationIgnored public var detailSource: @MainActor () -> TimelineDetail = { .simple }
    /// What the device did with the most recent accepted message.
    public private(set) var lastAcceptance: SendAcceptance?
    /// Whether the reader is at the foot of the transcript. The view layer
    /// sets it from the scroll position and from nothing else, so "follows
    /// the newest content" and "is at the bottom" are the same thing.
    public var isFollowingTail = true { didSet { if isFollowingTail { updatesWhileAway = 0 } } }
    public var draft = ""
    /// Set by the view layer, which is what knows about the socket and the
    /// device inventory. A send that cannot succeed is refused with a reason
    /// rather than failing after the draft has been cleared.
    ///
    /// "Can reach", not "is connected": a socket that is reconnecting still
    /// gets there, because the transport holds the request until the hello
    /// lands. Returning to the foreground would otherwise show a dead Send
    /// button for as long as a TLS handshake and a subscribe take.
    public var canReachGateway = true
    public var deviceOnline = true
    /// What the device said this agent can do, from the same inventory. It
    /// decides whether takeover is offered and whether a `shared` session can
    /// be interrupted from here (amendment A10).
    public var agent: AgentInfo?

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

    /// How much of this transcript is drawn.
    public var detail: TimelineDetail { detailSource() }

    /// The rows the transcript draws, at the level the reader has chosen.
    public var rows: [TimelineEntry] { timeline.roots(at: detail) }

    /// The header's todo chip. A checklist is the agent's working note rather
    /// than something written to the reader, so Simple leaves it out.
    public var showsTodos: Bool {
        detail == .detailed && (session.todos?.total ?? 0) > 0
    }

    /// A turn is in progress, whoever started it. Amendment A7: a terminal
    /// session reports `running` while its turn runs and `readonly` only when
    /// it is idle, so this is true for terminal-driven work too.
    public var isRunning: Bool { session.state.isWorking }

    /// Whether this app may type. Amendment A7: read-only follows `control`,
    /// never `state` — a terminal session is locked whether it is running or
    /// idle, and unlocking it means taking over. Amendment A10: an attached
    /// session is never read-only, because the device can inject into it.
    public var isReadOnly: Bool { session.isControlledByTerminal }

    /// Amendment A10: a live CLI owns the session and the device is attached.
    public var isAttached: Bool { session.isAttached }

    /// Stopping a turn the terminal owns is not ours to do. A channel cannot
    /// interrupt a running turn either, so an attached session offers Stop only
    /// when the agent lists `interrupt` *and* the device reports that the
    /// attachment itself can interrupt.
    public var canStop: Bool {
        guard isRunning, !isReadOnly else { return false }
        guard isAttached else { return true }
        guard let agent else { return false }
        return agent.supports(.interrupt) && agent.sharedInterrupt
    }

    /// Amendment A10: takeover is a `terminal` affordance, and only when the
    /// agent advertises the capability.
    public var canTakeover: Bool { isReadOnly && agent?.supports(.takeover) == true }

    /// A terminal session takes no input from here at all. Amendment A10: a
    /// relay cannot hand bytes to a live CLI either. Amendment A11: an
    /// attachment that does carry them says so with `shared_attachments`.
    public var allowsAttachments: Bool {
        guard !isReadOnly else { return false }
        guard isAttached else { return true }
        return agent?.sharedAttachments == true
    }

    /// Amendment A17: a live CLI chose this session's model, permission mode
    /// and effort, and no request this app can send would change them — either
    /// `control` is `terminal`, or the attachment does not carry settings.
    public var isTunedByTerminal: Bool {
        if isReadOnly { return true }
        guard isAttached else { return false }
        return agent?.sharedSettings != true
    }

    /// Amendment A10: `session.set` is unsupported for model, permission mode
    /// and effort while a live CLI owns the session. Amendment A11: an
    /// attachment that can retune the live thread says so with
    /// `shared_settings`, and the pickers open again.
    public var allowsSettingsChanges: Bool { !isTunedByTerminal }

    /// Amendment A21: where one tap on the speed control moves this session —
    /// standard, then each tier the agent lists, then standard again. Nil when
    /// the agent lists no tier, which is when the control is not drawn at all.
    public var nextSpeed: SpeedChange? {
        let tiers = agent?.speeds.map(\.id) ?? []
        guard !tiers.isEmpty else { return nil }
        guard let current = session.speed, let index = tiers.firstIndex(of: current) else {
            return SpeedChange(id: tiers.first)
        }
        return index + 1 < tiers.count ? .tier(tiers[index + 1]) : .standard
    }

    /// Amendment A17: what the terminal chose, for the composer to show where
    /// it cannot offer. Empty on a session this app drives, because there the
    /// controls carry the same values and are live.
    public var terminalSettings: [TerminalSetting] {
        guard isTunedByTerminal else { return [] }
        return TerminalSetting.all(for: session, agent: agent)
    }

    /// Amendment A20: a question is answered where you are. The device raises
    /// the block from the hook Claude Code runs beside its own dialog and takes
    /// whichever answer arrives first, so an attached session's card is live
    /// here exactly as it is in the terminal. Only a session the terminal holds
    /// outright takes nothing from this app.
    public var allowsAnswers: Bool { !isReadOnly }

    /// Amendment A20: the question waiting on the reader, if one is. The
    /// composer's button reads Answer while this is set, and the draft in the
    /// message field is the free-text answer to the first question on it that
    /// has nothing chosen or typed for it yet.
    public var pendingQuestion: QuestionPayload? {
        guard allowsAnswers, let question = timeline.pendingRequest?.question,
              question.status.isActionable else { return nil }
        return question
    }

    /// What the pending card is holding. The card writes its choices here and
    /// the composer reads them, so the two submit the same answers.
    public private(set) var questionDraft = QuestionDraft(requestID: "")

    /// The card's state for one question block, empty when the block is not the
    /// one the draft belongs to.
    public func draft(for question: QuestionPayload) -> QuestionDraft {
        questionDraft.requestID == question.requestID ? questionDraft
            : QuestionDraft(requestID: question.requestID)
    }

    public func choose(_ optionID: String, of item: QuestionItem, in question: QuestionPayload) {
        var draft = draft(for: question)
        draft.toggle(optionID, of: item)
        questionDraft = draft
    }

    public func write(_ text: String, for itemID: String, in question: QuestionPayload) {
        var draft = draft(for: question)
        draft.setText(text, for: itemID)
        questionDraft = draft
    }

    /// Amendment A10: what a `terminal` session would need before this app
    /// could control it, or nil when the agent cannot be attached at all. The
    /// words belong to the app; this only says which case applies.
    public enum AttachHint: Sendable, Hashable {
        /// Claude: the `claude` shim is not installed on the device.
        case installShim
        /// Codex: the shared app-server daemon is not running.
        case startDaemon
        /// The device is prepared, but this CLI was started without it.
        case restartSession
    }

    public var attachHint: AttachHint? {
        guard isReadOnly, let agent, let attach = agent.attach else { return nil }
        if agent.attachReady { return .restartSession }
        return attach == .daemon ? .startDaemon : .installShim
    }

    /// Section 5: `auto` means "send now if idle, otherwise steer or queue".
    /// An agent that lists `steer` joins the running turn instead of waiting
    /// behind it, so the composer and the status line say so.
    public var steersRunningTurn: Bool { isRunning && agent?.supports(.steer) == true }

    public var canSend: Bool {
        sendBlockReason == nil && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Why the composer cannot send right now, in the words the user sees.
    /// Reconnecting is not among them: that request waits for the socket.
    public var sendBlockReason: String? {
        if isReadOnly { return L10n.string("Controlled by the terminal") }
        if !deviceOnline { return L10n.string("That device is offline") }
        if !canReachGateway { return L10n.string("Offline · your draft is saved") }
        if unconfirmedSend != nil { return L10n.string("Delivery unconfirmed · retry or dismiss first") }
        return nil
    }
    public var unconfirmedSend: PendingSend? { pendingSends.first(where: \.isUnconfirmed) }

    /// The one line between the transcript and the message field.
    ///
    /// It is drawn only when it says something the header above the transcript
    /// does not. The header already carries the dot and the state word, and on
    /// an attached session it reads `terminal · attached`, so repeating either
    /// costs the transcript a row and tells the reader nothing. What is left is
    /// what the header cannot say: that this machine cannot be reached, that
    /// typing here needs a takeover, what will become of a message typed into a
    /// running turn, and what the agent said when it failed.
    public var statusLine: String? {
        if !deviceOnline { return L10n.string("Device offline") }
        if isReadOnly {
            return L10n.string(canTakeover ? "Controlled by the terminal · Take over to send"
                                           : "Controlled by the terminal")
        }
        // Amendment A20: a question outranks the turn it interrupted. Nothing
        // is queued behind it, so what a message would become is not the news.
        if pendingQuestion != nil { return L10n.string("Waiting for your answer") }
        switch session.state {
        case .running:
            if session.queued > 0 {
                return L10n.string(session.queued == 1 ? "Working · %lld message queued"
                                                       : "Working · %lld messages queued",
                                   session.queued)
            }
            return L10n.string(steersRunningTurn ? "Working · your message will steer the turn"
                                                 : "Working · your message will be queued")
        // The word "error" is in the header; what the agent said about it is not.
        case .error: return session.stateDetail
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
        guard timeline.entries.count > before, !isFollowingTail else { return }
        guard timeline.entry(for: event)?.isDrawn(at: detail) == true else { return }
        updatesWhileAway += 1
    }

    private func applyMeta(_ payload: MetaPayload) {
        if let title = payload.title { session.title = title }
        if let model = payload.model { session.model = model }
        if let mode = payload.permissionMode { session.permissionMode = mode }
        if let effort = payload.effort { session.effort = effort }
        if let speed = payload.speed { session.speed = speed.id }
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

    /// Amendment A12: the request id is the block id the device will echo, so
    /// the message is in the transcript before the request has left, and the
    /// device's own event replaces it in place. Nothing here waits for a round
    /// trip that the user can feel.
    private func deliver(id: String, text: String, attachments: [OutboundAttachment], mode: SendMode) async {
        // Sending is a request to watch what happens next, so the
        // transcript returns to the tail before the message lands.
        isFollowingTail = true
        let record = PendingSend(id: id, text: text, attachments: attachments,
                                 mode: mode, status: .sending)
        if let index = pendingSends.firstIndex(where: { $0.id == id }) { pendingSends[index] = record }
        else { pendingSends.append(record) }
        timeline.addOptimistic(OptimisticMessage(id: id, text: text,
                                                 attachments: attachments.map(\.info)))
        do {
            let request = try GatewayRequest.send(id: id, sessionID: sessionID, text: text,
                                                  attachments: attachments, mode: mode)
            let result = try await channel.request(request, as: SendResult.self)
            // A queued message is represented by the queue row above the
            // composer until the device dequeues it and emits the
            // `user_message` under this same id; two rows would be one too many.
            if result.accepted == .queued { timeline.removeOptimistic(id) }
            // Amendment A14: a steered message is read by the agent at its next
            // step, so the device's block for it can be a whole turn away. The
            // row holds the foot of the transcript until then and never asks to
            // be sent again: the device already has it.
            if result.accepted == .steered { timeline.markSteered(id) }
            mark(id: id, status: .accepted(result.accepted))
        } catch let error as TransportError where error == .deliveryUncertain || error == .requestTimedOut {
            // The message may well have landed, so the row stays and Retry
            // reuses this id rather than sending the agent a second copy.
            mark(id: id, status: .uncertain)
        } catch let refusal as GatewayErrorBody {
            // A reply means the request was read and refused: it will never
            // arrive, so the row goes and the words come back to the draft.
            reject(id: id, text: text, reason: refusal.message)
        } catch {
            reject(id: id, text: text, reason: describe(error))
        }
    }

    /// A send that was definitely refused. The message leaves the transcript so
    /// nothing claims it is on its way, and the text returns to the field the
    /// user was typing in — unless they have already started typing the next
    /// one, which is theirs and not ours to overwrite.
    private func reject(id: String, text: String, reason: String) {
        timeline.removeOptimistic(id)
        // Nothing is left to retry or to hold bytes for, so the record goes too.
        pendingSends.removeAll { $0.id == id }
        errorMessage = reason
        if draft.isEmpty { draft = text }
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

    /// Giving up on an unconfirmed send. The row goes with it: the user has
    /// been told it is not confirmed and has chosen not to send it again.
    public func dismiss(_ pending: PendingSend) {
        pendingSends.removeAll { $0.id == pending.id }
        timeline.removeOptimistic(pending.id)
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

    /// The card's own Submit: everything chosen and typed on the card.
    public func submitAnswer(for question: QuestionPayload) async {
        let answers = draft(for: question).answers(for: question.questions)
        await answer(requestID: question.requestID, answers: answers)
        questionDraft = QuestionDraft(requestID: question.requestID)
    }

    /// Amendment A20: the composer's Answer. The message field is the free-text
    /// answer to the first question on the card still waiting for one, and goes
    /// with whatever was chosen for the others.
    ///
    /// Nothing optimistic is drawn and nothing is queued: an answer is not a
    /// message, and the card resolving is what says it arrived. A draft with
    /// nowhere to go — every question answered already, or the one waiting
    /// takes options and no words — is left in the field untouched.
    public func answerDraft() async {
        guard let question = pendingQuestion else { return }
        guard let answers = draft(for: question).answers(for: question.questions, composing: draft)
        else { return }
        let text = draft
        draft = ""
        do {
            try await channel.request(.answer(sessionID: sessionID, requestID: question.requestID,
                                              answers: answers))
            questionDraft = QuestionDraft(requestID: question.requestID)
        } catch {
            errorMessage = describe(error)
            if draft.isEmpty { draft = text }
        }
    }

    public func set(model: String? = nil, permissionMode: String? = nil,
                    effort: String? = nil, speed: SpeedChange? = nil,
                    title: String? = nil) async {
        do {
            let result = try await channel.request(
                .set(sessionID: sessionID, model: model, permissionMode: permissionMode,
                     effort: effort, speed: speed, title: title),
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
        timeline.removeOptimistic(queuedID)
        pendingSends.removeAll { $0.id == queuedID }
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
