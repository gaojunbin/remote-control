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

/// Amendment A29: where the polish of the words just dictated has got to.
///
/// The words themselves are in the field the instant dictation ends, whatever
/// this says; the phase is only about the request that may replace them.
public enum PolishPhase: Sendable, Equatable {
    case idle
    case polishing
    /// The answer is in the field. The span and the text that went into it are
    /// kept so Undo can put the dictated words back.
    case polished(DictationSpan, String)
    case failed
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
    /// The resubscribe a `hello` or a detected gap asks for. Held so closing
    /// the conversation cancels it.
    @ObservationIgnored private var resubscription: Task<Void, Never>?
    /// Set by `close()`. A conversation that has said `session.unsubscribe`
    /// must not subscribe again: nothing draws what the gateway would stream,
    /// and it streams until the next reconnect.
    @ObservationIgnored private var isClosed = false
    public private(set) var errorMessage: String?
    public private(set) var pendingSends: [PendingSend] = []
    /// Amendment A27: the slash commands this session offers, as the device
    /// last listed them. Empty for an agent without the capability, and empty
    /// until the first answer arrives.
    public private(set) var commands: [Command] = []
    @ObservationIgnored private var commandsReadAt: Date?
    @ObservationIgnored private var isLoadingCommands = false
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
    public var draft = "" { didSet { forgetPolishOnEdit() } }
    /// Amendment A29: what dictation polish is doing to the draft right now.
    public private(set) var polishPhase: PolishPhase = .idle
    /// Where a dictation is polished. Set by the view layer, which is what
    /// knows the gateway; nil where no polish model is configured or the user
    /// has not turned the feature on, and then nothing here runs.
    @ObservationIgnored public var polishService: (@MainActor (PolishRequest) async throws -> String)?
    @ObservationIgnored private var polishTask: Task<Void, Never>?
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
    /// Bumped whenever the device's own copy of the session replaces this one:
    /// a `session.updated` frame, a `meta` event carrying settings, or the
    /// reply to a request. An optimistic change is rolled back only while this
    /// has not moved.
    @ObservationIgnored private var sessionGeneration = 0

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
    ///
    /// SwiftUI reads this on every render pass and the filter runs over the
    /// whole transcript, so the answer is kept until the transcript or the
    /// level moves. `roots(at:)` reads only the entries and the unconfirmed
    /// sends, and every mutation of either bumps the timeline's version.
    public var rows: [TimelineEntry] {
        let detail = detail
        if let cached = cachedRows, cached.version == timeline.version, cached.detail == detail {
            return cached.rows
        }
        let rows = timeline.roots(at: detail)
        cachedRows = (timeline.version, detail, rows)
        rowsBuilt += 1
        return rows
    }

    @ObservationIgnored
    private var cachedRows: (version: Int, detail: TimelineDetail, rows: [TimelineEntry])?
    /// How many times `rows` has had to filter the transcript, which is what
    /// the tests read to prove a redraw does not.
    @ObservationIgnored private(set) var rowsBuilt = 0

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

    /// `docs/DESIGN.md` § "The composer": the status line of a session a
    /// terminal holds. The way out is named only where the agent has one —
    /// Codex and Grok Build advertise no `takeover`, so nothing on their
    /// terminal-held sessions invites a tap that would be refused.
    ///
    /// The clause belongs to this line alone. The disabled field says the short
    /// sentence whatever the agent is, because a placeholder that repeats the
    /// line above it word for word, and then truncates, says less than half of
    /// it would.
    public var terminalControlNotice: String {
        L10n.string(canTakeover ? "Controlled by the terminal · take over to send"
                                : "Controlled by the terminal")
    }

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
        /// Codex: the standalone build the shared daemon runs from is not installed.
        case startDaemon
        /// Amendment A26: pi's extension is not installed on the device.
        case installExtension
        /// Amendment A28: Grok Build's configuration on the device does not put
        /// its terminals in the leader, so there is nothing to join.
        case enableLeader
        /// The device is prepared, but this CLI was started without it.
        case restartSession
    }

    public var attachHint: AttachHint? {
        guard isReadOnly, let agent, let attach = agent.attach else { return nil }
        if agent.attachReady { return .restartSession }
        switch attach {
        case .daemon: return .startDaemon
        case .extension: return .installExtension
        case .leader: return .enableLeader
        default: return .installShim
        }
    }

    /// Section 5: `auto` means "send now if idle, otherwise steer or queue".
    /// An agent that lists `steer` joins the running turn instead of waiting
    /// behind it, so the composer and the status line say so.
    public var steersRunningTurn: Bool { isRunning && agent?.supports(.steer) == true }

    public var canSend: Bool {
        guard sendBlockReason == nil,
              !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        // Amendment A27: a command runs between turns, never inside one. The
        // panel says so in its footer; Send simply does not act.
        return !(draftCommand != nil && isRunning)
    }

    // MARK: - Slash commands (A27)

    /// Whether this session's agent takes commands at all. An agent without the
    /// capability draws no panel: `/` is an ordinary character there, and
    /// nothing on the screen explains the difference.
    public var offersCommands: Bool { agent?.supports(.commands) == true }

    /// The draft read as a command, or nil when it is ordinary text. A session
    /// the terminal holds takes nothing from here, and a question outranks
    /// everything: while one is open the field is the answer field (A20).
    public var commandDraft: SlashDraft? {
        guard offersCommands, !isReadOnly, pendingQuestion == nil else { return nil }
        return SlashDraft.parse(draft)
    }

    /// The rows the panel draws. Empty means no panel at all: the draft is not
    /// a command draft, the name is already finished, or nothing matches.
    public var commandRows: [Command] {
        guard let draft = commandDraft, !draft.isComplete else { return [] }
        return SlashDraft.filter(commands, query: draft.name)
    }

    /// Those rows grouped, with headers only where there is more than one group.
    public var commandSections: [CommandSection] { CommandSection.build(commandRows) }

    /// The command the draft would run, matched on its whole first word. A word
    /// that names nothing is a message, which is how a terminal reads it too.
    public var draftCommand: Command? {
        guard let draft = commandDraft else { return nil }
        return SlashDraft.match(commands, name: draft.name)
    }

    /// What a complete command draft is still missing, for the line under the
    /// panel. Nil when the draft names no command.
    public var commandHint: Command? {
        guard let draft = commandDraft, draft.isComplete else { return nil }
        return SlashDraft.match(commands, name: draft.name)
    }

    /// A turn is running, so every row is dimmed and the footer says why.
    public var commandsWaitForTurn: Bool { isRunning }

    /// Taking a row from the panel. The trailing space is what closes the panel
    /// and shows where the argument goes; a command that takes none is left
    /// ready to run on the next tap of Send.
    public func take(_ command: Command) {
        draft = command.takesArgument ? command.slash + " " : command.slash
    }

    /// Ask the device what this session offers now. Called when the
    /// conversation opens; a failure keeps the last list rather than raising a
    /// banner, because nobody asked for this request.
    public func loadCommands() async {
        guard offersCommands, !isLoadingCommands else { return }
        isLoadingCommands = true
        defer { isLoadingCommands = false }
        guard let result = try? await channel.request(.commands(sessionID: sessionID),
                                                      as: CommandsResult.self) else { return }
        guard !Task.isCancelled else { return }
        commands = result.commands
        commandsReadAt = Date()
    }

    /// The refresh `/` asks for: only when the last answer is stale or was
    /// empty, so the panel is on screen the moment it is wanted rather than a
    /// round trip later.
    public func refreshCommands(now: Date = Date()) async {
        guard offersCommands else { return }
        if !commands.isEmpty, let read = commandsReadAt,
           now.timeIntervalSince(read) < Self.commandsStaleAfter { return }
        await loadCommands()
    }

    /// How long an answer stands before `/` asks for another one.
    public static let commandsStaleAfter: TimeInterval = 60

    /// Run what the draft names. Amendment A27: the request id is the block id
    /// the device echoes the command under, so the row is in the transcript
    /// before the request leaves, exactly as a message is (A12). The result is
    /// `{}` — what the command did arrives as ordinary events.
    public func runCommand() async {
        guard let command = draftCommand, let parsed = commandDraft, !isRunning else { return }
        let typed = draft
        let id = UUID().uuidString
        cancelPolish()
        draft = ""
        isFollowingTail = true
        timeline.addOptimistic(OptimisticMessage(id: id, text: command.line(argument: parsed.argument)))
        do {
            _ = try await channel.request(.command(id: id, sessionID: sessionID,
                                                   name: command.name, argument: parsed.argument))
        } catch let error as TransportError where error == .deliveryUncertain || error == .requestTimedOut {
            // The device may well have run it, so nothing is retracted and
            // nothing is sent again: the row says so for itself after a minute.
            errorMessage = error.errorDescription
        } catch {
            // A reply means the request was read and refused, so the row goes
            // and the words come back to the field the user was typing in.
            timeline.removeOptimistic(id)
            errorMessage = describe(error)
            if draft.isEmpty { draft = typed }
        }
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
        // Amendment A29: the model is working on the words that just landed in
        // the field. That is the news, and it is over in a second or two.
        if polishPhase == .polishing { return L10n.string("Polishing…") }
        if !deviceOnline { return L10n.string("Device offline") }
        if isReadOnly { return terminalControlNotice }
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

    // MARK: - Dictation polish (A29)

    /// Pass the words a dictation just produced through the gateway's polish
    /// model, with the conversation they were spoken into.
    ///
    /// The words are already in the field: this replaces the dictated span and
    /// nothing else, and only while the field still holds exactly what the
    /// recogniser left there.
    public func polish(span: DictationSpan, model: String, strength: PolishStrength,
                       language: String) {
        guard let polishService, !model.isEmpty, DictationPolish.canPolish(span.dictated) else { return }
        polishTask?.cancel()
        polishPhase = .polishing
        let request = DictationPolish.request(span: span, model: model, strength: strength,
                                              language: language,
                                              context: DictationPolish.context(timeline))
        polishTask = Task { [weak self] in
            do {
                let text = try await polishService(request)
                guard !Task.isCancelled else { return }
                self?.applyPolished(span: span, text: text)
            } catch {
                guard !Task.isCancelled else { return }
                self?.polishPhase = .failed
            }
        }
    }

    private func applyPolished(span: DictationSpan, text: String) {
        let polished = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let next = DictationPolish.applyPolished(current: draft, span: span, polished: polished) else {
            // The field has moved on — typed, sent, or dictated over — and what
            // is in it is the person's. There is nothing to say about that.
            polishPhase = .idle
            return
        }
        // The phase is set first: writing the draft is what tells the note it
        // is looking at the model's words rather than at an edit.
        polishPhase = .polished(span, polished)
        draft = next
    }

    /// Put the dictated words back. The note goes with them.
    public func undoPolish() {
        guard case .polished(let span, let text) = polishPhase else { return }
        let dictated = DictationPolish.undoPolished(current: draft, span: span, polished: text)
        polishPhase = .idle
        if let dictated { draft = dictated }
    }

    /// A send, or anything else that ends this dictation's claim on the field.
    /// A late answer is dropped rather than pasted over what was sent.
    public func cancelPolish() {
        polishTask?.cancel()
        polishTask = nil
        polishPhase = .idle
    }

    /// The one line about a failure, once it has been read.
    public func clearPolishNote() {
        if polishPhase == .failed { polishPhase = .idle }
    }

    /// "Polished · Undo" stands until the next edit or send. An edit is any
    /// draft that is no longer what the model wrote; the words arriving from
    /// the model are not one.
    ///
    /// While the request is out the only thing that can write the draft is the
    /// person: `applyPolished` sets `.polished` before it writes, `send` cancels
    /// first, and the dictation that started the request stops touching the
    /// field the moment it sees a draft it did not put there. So an edit here
    /// is the person typing over the wait, and their words win: the request is
    /// dropped and Send comes back at once.
    private func forgetPolishOnEdit() {
        switch polishPhase {
        case .idle:
            return
        case .polishing:
            cancelPolish()
        case .polished(let span, let text):
            if draft != span.polishedDraft(text) { polishPhase = .idle }
        case .failed:
            polishPhase = .idle
        }
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
        // Amendment A27: fetched when the conversation opens, so the panel is
        // there for the first `/` rather than a round trip after it.
        await loadCommands()
    }

    public func close() async {
        isClosed = true
        resubscription?.cancel()
        resubscription = nil
        cancelPolish()
        _ = try? await channel.request(.unsubscribe(sessionID: sessionID))
    }

    /// Section 7's reconnect order: subscribe from the cursor, and page history
    /// only when the gateway says the buffer could not cover it.
    public func subscribe() async {
        guard !isClosed, !isSubscribing else { return }
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
            resubscribe()
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
        resubscribe()
    }

    /// Subscribe again, out of band. The task is held so `close()` can cancel
    /// it: a `hello` or a gap noticed at the moment a conversation is closed
    /// would otherwise resubscribe it behind the screen that has gone.
    private func resubscribe() {
        guard !isClosed else { return }
        resubscription?.cancel()
        resubscription = Task { [weak self] in await self?.subscribe() }
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
        // What the device read off the agent itself replaces an optimistic
        // value, so a refusal landing afterwards must not put the older one
        // back over it.
        if payload.model != nil || payload.permissionMode != nil
            || payload.effort != nil || payload.speed != nil {
            sessionGeneration += 1
        }
        onSessionChange(session)
    }

    private func update(session value: Session) {
        session = value
        sessionGeneration += 1
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
    /// What became of a send, so the composer can put back what a refusal
    /// took (`docs/DESIGN.md` § "The composer" → **A draft belongs to its
    /// session**): the words come back here, the attachments are the view's.
    public enum SendOutcome: Sendable, Equatable {
        /// Nothing was sent: the field held no words.
        case empty
        /// The device took it (now, queued or steered).
        case accepted
        /// The request may have landed; the row stays and offers Retry.
        case uncertain
        /// The request was read and refused; nothing is on its way.
        case refused
    }

    @discardableResult
    public func send(mode: SendMode = .auto, attachments: [OutboundAttachment] = []) async -> SendOutcome {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return .empty }
        // Amendment A29: what goes is what is in the field — the words as
        // dictated while a polish is still out — and that answer is dropped.
        cancelPolish()
        draft = ""
        return await deliver(id: UUID().uuidString, text: text, attachments: attachments, mode: mode)
    }

    @discardableResult
    public func retry(_ pending: PendingSend) async -> SendOutcome {
        await deliver(id: pending.id, text: pending.text,
                      attachments: pending.attachments, mode: pending.mode)
    }

    /// Amendment A12: the request id is the block id the device will echo, so
    /// the message is in the transcript before the request has left, and the
    /// device's own event replaces it in place. Nothing here waits for a round
    /// trip that the user can feel.
    private func deliver(id: String, text: String, attachments: [OutboundAttachment],
                         mode: SendMode) async -> SendOutcome {
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
            return .accepted
        } catch let error as TransportError where error == .deliveryUncertain || error == .requestTimedOut {
            // The message may well have landed, so the row stays and Retry
            // reuses this id rather than sending the agent a second copy.
            mark(id: id, status: .uncertain)
            return .uncertain
        } catch let refusal as GatewayErrorBody {
            // A reply means the request was read and refused: it will never
            // arrive, so the row goes and the words come back to the draft.
            reject(id: id, text: text, reason: refusal.message)
            return .refused
        } catch {
            reject(id: id, text: text, reason: describe(error))
            return .refused
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
        cancelPolish()
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

    /// `docs/DESIGN.md` § "The model card": every change made from the card is
    /// drawn the moment it is made. The patch is applied before the request
    /// leaves, the device's reply confirms it, and a refusal puts the previous
    /// value back with the error — unless a newer session replaced the
    /// optimistic one while the request was in flight, in which case the older
    /// value must not be written over it.
    public func set(model: String? = nil, permissionMode: String? = nil,
                    effort: String? = nil, speed: SpeedChange? = nil,
                    title: String? = nil) async {
        let previous = session
        applyLocally(model: model, permissionMode: permissionMode, effort: effort,
                     speed: speed, title: title)
        let generation = sessionGeneration
        do {
            let result = try await channel.request(
                .set(sessionID: sessionID, model: model, permissionMode: permissionMode,
                     effort: effort, speed: speed, title: title),
                as: SessionResult.self)
            update(session: result.session)
        } catch {
            errorMessage = describe(error)
            guard sessionGeneration == generation else { return }
            update(session: previous)
        }
    }

    /// The card's own copy of the change, drawn before the round trip. Only the
    /// fields the request carries are touched, so one chip's change never
    /// rewrites what another one says.
    private func applyLocally(model: String?, permissionMode: String?, effort: String?,
                              speed: SpeedChange?, title: String?) {
        if let model { session.model = model }
        if let permissionMode { session.permissionMode = permissionMode }
        if let effort { session.effort = effort }
        if let speed { session.speed = speed.id }
        if let title { session.title = title }
        onSessionChange(session)
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
