import Foundation

/// Amendment A12: a message this app has sent that the device has not echoed
/// back yet. The app mints the `session.send` request id and the device returns
/// it as the `user_message` block id, so the row can be shown at once and
/// replaced in place when the event arrives.
public struct OptimisticMessage: Identifiable, Sendable, Equatable {
    /// The `session.send` request id, which becomes the device's `block_id`.
    public let id: String
    public let text: String
    public let attachments: [AttachmentInfo]
    /// When the send left the app, for the unconfirmed timeout.
    public let sentAt: Date

    public init(id: String, text: String, attachments: [AttachmentInfo] = [], sentAt: Date = Date()) {
        self.id = id
        self.text = text
        self.attachments = attachments
        self.sentAt = sentAt
    }

    /// How long a send may wait before the row stops claiming it is on its way.
    /// Well past any request timeout: what has not been confirmed by now is not
    /// slow, it is lost.
    public static let unconfirmedAfter: TimeInterval = 60

    public func isUnconfirmed(at now: Date = Date()) -> Bool {
        now.timeIntervalSince(sentAt) >= Self.unconfirmedAfter
    }

    /// Seconds left before this row says so, or zero once it has.
    public func remainingBeforeUnconfirmed(at now: Date = Date()) -> TimeInterval {
        max(0, Self.unconfirmedAfter - now.timeIntervalSince(sentAt))
    }
}

/// One renderable row. Block events collapse into a single entry keyed by
/// `block_id`; everything else gets a synthetic id derived from its `seq`.
public struct TimelineEntry: Identifiable, Sendable, Equatable {
    public let id: String
    /// Position in the transcript. Amendment A8: a block that keeps streaming
    /// takes the seq of its first appearance, so it holds its place while its
    /// `seq` rises.
    public private(set) var seq: Int
    /// The raw seq of the event that created this entry, which is what a
    /// history cursor may be built from.
    public private(set) var eventSeq: Int
    /// The newest seq applied to this entry, for late-frame rejection.
    public private(set) var latestSeq: Int
    public private(set) var ts: Int64
    public let parentID: String?
    public private(set) var body: SessionEventBody
    /// Accumulated streaming text for `assistant_text` and `thinking`.
    public private(set) var text: String
    /// Amendment A12: set on a row this app has sent and the device has not
    /// confirmed. Such a row holds no device `seq` and is never stored in the
    /// transcript, so nothing it does can move the replay cursor.
    public private(set) var pending: OptimisticMessage?

    init(event: SessionEvent, id: String) {
        self.id = id
        seq = event.orderSeq
        eventSeq = event.seq
        latestSeq = event.seq
        ts = event.ts
        parentID = event.parentBlockID
        body = event.body
        text = Self.initialText(event.body)
    }

    /// The row a sent message takes until the device's own event replaces it.
    init(pending: OptimisticMessage) {
        id = pending.id
        // Nothing the device sent can sort after this, and nothing here can be
        // mistaken for a real event seq.
        seq = Int.max
        eventSeq = Int.max
        latestSeq = Int.max
        ts = Int64(pending.sentAt.timeIntervalSince1970 * 1000)
        parentID = nil
        body = .userMessage(UserMessagePayload(text: pending.text,
                                               attachments: pending.attachments,
                                               source: .remote))
        text = ""
        self.pending = pending
    }

    private static func initialText(_ body: SessionEventBody) -> String {
        switch body {
        case .assistantText(let payload), .thinking(let payload):
            payload.text ?? payload.delta ?? ""
        default:
            ""
        }
    }

    /// Apply a later event for the same block. A `delta` appends; anything else
    /// replaces the entry wholesale, per the protocol's replacement rule.
    mutating func merge(_ event: SessionEvent) {
        latestSeq = event.seq
        // A later event may be the first to name the block's origin, and an
        // earlier one may arrive out of order; the earliest position wins.
        seq = min(seq, event.orderSeq)
        eventSeq = min(eventSeq, event.seq)
        ts = event.ts
        switch event.body {
        case .assistantText(let payload), .thinking(let payload):
            if let delta = payload.delta, payload.text == nil {
                text += delta
            } else if let full = payload.text {
                text = full
            }
        default:
            text = Self.initialText(event.body)
        }
        body = event.body
    }

    public var userMessage: UserMessagePayload? {
        if case .userMessage(let payload) = body { payload } else { nil }
    }
    public var assistantText: StreamTextPayload? {
        if case .assistantText(let payload) = body { payload } else { nil }
    }
    public var thinking: StreamTextPayload? {
        if case .thinking(let payload) = body { payload } else { nil }
    }
    public var toolCall: ToolCallPayload? {
        if case .toolCall(let payload) = body { payload } else { nil }
    }
    public var approval: ApprovalPayload? {
        if case .approval(let payload) = body { payload } else { nil }
    }
    public var question: QuestionPayload? {
        if case .question(let payload) = body { payload } else { nil }
    }
    public var notice: NoticePayload? {
        if case .notice(let payload) = body { payload } else { nil }
    }
    public var errorPayload: ErrorPayload? {
        if case .error(let payload) = body { payload } else { nil }
    }
    public var turnStarted: TurnStartedPayload? {
        if case .turnStarted(let payload) = body { payload } else { nil }
    }
    public var turnCompleted: TurnCompletedPayload? {
        if case .turnCompleted(let payload) = body { payload } else { nil }
    }

    /// Rows a sub-agent produced hang under their parent tool call.
    public var isNested: Bool { parentID != nil }

    /// Entries that carry no visible content of their own.
    public var isRenderable: Bool {
        switch body {
        case .todos, .status, .meta, .queue: false
        case .assistantText, .thinking: !text.isEmpty || !isStreaming
        default: true
        }
    }

    public var isStreaming: Bool {
        switch body {
        case .assistantText(let payload), .thinking(let payload): !payload.done
        case .toolCall(let payload): payload.status == .running
        default: false
        }
    }
}

/// The ordered transcript of one session, plus the snapshots that ride along
/// with it (todos, queue). Pure value type: the store owns one and the
/// verification executable drives it directly.
public struct Timeline: Sendable, Equatable {
    public private(set) var entries: [TimelineEntry] = []
    /// Highest seq applied from the live stream. Late or duplicate frames at or
    /// below this are dropped.
    public private(set) var lastSeq = 0
    public private(set) var todos: [TodoItem] = []
    public private(set) var queue: [QueuedMessage] = []
    public private(set) var hasMoreHistory = true
    public private(set) var historyLoaded = false
    /// True when a `seq` was skipped, so the transcript is missing an event.
    /// The gateway drops frames over 64 KiB and a stream buffer can overrun, so
    /// a hole is part of the design and has to be noticed and repaired.
    public private(set) var hasGap = false
    /// Amendment A12: sent, not yet echoed by the device. Kept apart from
    /// `entries` because these rows carry no `seq`: they must not move the
    /// replay cursor, must not be a history boundary, and always sort last.
    public private(set) var optimistic: [OptimisticMessage] = []

    private var index: [String: Int] = [:]
    /// Children keyed by parent block, so a tool row does not scan the whole
    /// transcript on every delta.
    private var childIndex: [String: [String]] = [:]
    /// The seq each snapshot came from. A snapshot found while paging history
    /// is older than the live one and must not overwrite it (amendment A6).
    private var todosSeq = 0
    private var queueSeq = 0

    public init() {}

    /// The cursor for `session.history`: the oldest real event seq held, not a
    /// block's position, so paging cannot skip an event.
    public var oldestSeq: Int? { entries.map(\.eventSeq).min() }

    public var renderable: [TimelineEntry] { entries.filter(\.isRenderable) }

    /// Top-level rows, then the sends the device has not confirmed yet. A
    /// pending row carries the `user_message` it is about to become, so the
    /// transcript renders it like any other (amendment A12).
    public var roots: [TimelineEntry] {
        var rows = entries.filter { $0.isRenderable && !$0.isNested }
        rows.append(contentsOf: optimistic.filter { index[$0.id] == nil }.map(TimelineEntry.init(pending:)))
        return rows
    }

    public func children(of blockID: String) -> [TimelineEntry] {
        (childIndex[blockID] ?? []).compactMap(entry(id:)).filter(\.isRenderable)
    }

    public func entry(id: String) -> TimelineEntry? {
        index[id].map { entries[$0] }
    }

    /// The newest approval or question still waiting on the user.
    public var pendingRequest: TimelineEntry? {
        entries.last { entry in
            entry.approval?.status.isActionable == true || entry.question?.status.isActionable == true
        }
    }

    /// Apply one live event. Returns false when the event was dropped as stale.
    ///
    /// A seq that skips ahead means an event never arrived. The entry is still
    /// applied, but the timeline is marked so the store can refill from the
    /// gateway rather than render a transcript that is quietly missing a step.
    @discardableResult
    public mutating func apply(_ event: SessionEvent) -> Bool {
        guard event.seq > lastSeq else { return false }
        if lastSeq > 0, event.seq > lastSeq + 1 { hasGap = true }
        lastSeq = event.seq
        reconcileOptimistic(with: event)
        absorb(event)
        return true
    }

    /// Called once the store has refilled from `session.subscribe` or history.
    public mutating func clearGap() { hasGap = false }

    /// Adopt a cursor recovered from the offline cache, so a warm open can
    /// subscribe with `since_seq` instead of throwing the transcript away.
    public mutating func adoptCursor(_ seq: Int) {
        guard seq > lastSeq else { return }
        lastSeq = seq
    }

    /// Merge one page of older history. History never advances `lastSeq`, and
    /// an entry already held from the live stream wins over an older copy.
    public mutating func prependHistory(_ events: [SessionEvent], hasMore: Bool) {
        for event in events.sorted(by: { $0.seq < $1.seq }) {
            reconcileOptimistic(with: event)
            let key = Self.key(for: event)
            if let position = index[key] {
                guard event.seq > entries[position].latestSeq else { continue }
                entries[position].merge(event)
            } else {
                index[key] = entries.count
                entries.append(TimelineEntry(event: event, id: key))
            }
            absorbSnapshot(event)
        }
        sortEntries()
        hasMoreHistory = hasMore
        historyLoaded = true
    }

    /// Replace the whole transcript, used when the gateway reports `resync`.
    ///
    /// The unconfirmed sends survive it: a message the user just typed must not
    /// vanish because the socket came back and the gateway asked for a reload.
    /// They are reconciled again as the reloaded events come through.
    public mutating func reset() {
        entries.removeAll()
        index.removeAll()
        childIndex.removeAll()
        hasGap = false
        todos.removeAll()
        queue.removeAll()
        lastSeq = 0
        todosSeq = 0
        queueSeq = 0
        hasMoreHistory = true
        historyLoaded = false
    }

    /// The queue snapshot from a `session.subscribe` reply, which has no seq of
    /// its own: it describes the session as of the current cursor.
    public mutating func applySubscribedQueue(_ pending: [QueuedMessage]) {
        guard lastSeq >= queueSeq else { return }
        queue = pending
        queueSeq = lastSeq
        dropQueuedOptimistic()
    }

    // MARK: - Amendment A12: sends the device has not confirmed

    /// Show a message the moment it is sent. Idempotent, so a Retry under the
    /// same request id reuses the row rather than adding a second one.
    public mutating func addOptimistic(_ message: OptimisticMessage) {
        guard index[message.id] == nil, !optimistic.contains(where: { $0.id == message.id }) else { return }
        optimistic.append(message)
    }

    /// Take a pending row away: the send was refused, or the queue owns it now.
    public mutating func removeOptimistic(_ id: String) {
        optimistic.removeAll { $0.id == id }
    }

    /// The pending rows that have waited too long to still claim they are on
    /// their way. Pure; the caller supplies the clock.
    public func unconfirmedOptimistic(at now: Date = Date()) -> [OptimisticMessage] {
        optimistic.filter { $0.isUnconfirmed(at: now) }
    }

    /// A queued message is represented by the queue row above the composer
    /// until the device dequeues it and emits the `user_message` under the same
    /// id, so showing both would show it twice.
    private mutating func dropQueuedOptimistic() {
        guard !optimistic.isEmpty, !queue.isEmpty else { return }
        let queued = Set(queue.map(\.id))
        optimistic.removeAll { queued.contains($0.id) }
    }

    /// Retire the pending row an incoming event confirms. Matching on the block
    /// id is amendment A12; matching one row by text is the fallback for a
    /// device that still mints its own ids, and never retires more than one.
    private mutating func reconcileOptimistic(with event: SessionEvent) {
        guard !optimistic.isEmpty else { return }
        let key = Self.key(for: event)
        if optimistic.contains(where: { $0.id == key }) {
            optimistic.removeAll { $0.id == key }
            return
        }
        guard case .userMessage(let payload) = event.body, payload.source == .remote,
              let position = optimistic.firstIndex(where: { $0.text == payload.text }) else { return }
        optimistic.remove(at: position)
    }

    /// Fold in the untruncated version of one block from `session.block`.
    public mutating func replaceBlock(with event: SessionEvent) {
        reconcileOptimistic(with: event)
        let key = Self.key(for: event)
        if let position = index[key] {
            entries[position].merge(event)
        } else {
            absorb(event)
        }
    }

    public mutating func markHistoryExhausted() {
        hasMoreHistory = false
        historyLoaded = true
    }

    private mutating func absorb(_ event: SessionEvent) {
        absorbSnapshot(event)
        switch event.body {
        case .todos, .status, .meta, .queue:
            return
        default:
            break
        }
        let key = Self.key(for: event)
        if let position = index[key] {
            let before = entries[position].seq
            entries[position].merge(event)
            if entries[position].seq != before { sortEntries() }
        } else {
            let entry = TimelineEntry(event: event, id: key)
            // The common case is an append at the end; only an out-of-order
            // position pays for a sort.
            let inOrder = entries.last.map { $0.seq <= entry.seq } ?? true
            index[key] = entries.count
            entries.append(entry)
            if let parent = event.parentBlockID { childIndex[parent, default: []].append(key) }
            if !inOrder { sortEntries() }
        }
    }

    private mutating func sortEntries() {
        entries.sort { ($0.seq, $0.eventSeq) < ($1.seq, $1.eventSeq) }
        reindex()
    }

    /// Snapshots replace the previous list, but only when they are at least as
    /// new as the one already held. Replayed events, history pages and live
    /// frames all pass through here, so the newest wins regardless of arrival
    /// order (amendment A6).
    private mutating func absorbSnapshot(_ event: SessionEvent) {
        switch event.body {
        case .todos(let payload):
            guard event.seq >= todosSeq else { return }
            todos = payload.items
            todosSeq = event.seq
        case .queue(let payload):
            guard event.seq >= queueSeq else { return }
            queue = payload.pending
            queueSeq = event.seq
            dropQueuedOptimistic()
        default:
            break
        }
    }

    private mutating func reindex() {
        index.removeAll(keepingCapacity: true)
        childIndex.removeAll(keepingCapacity: true)
        for (position, entry) in entries.enumerated() {
            index[entry.id] = position
            if let parent = entry.parentID { childIndex[parent, default: []].append(entry.id) }
        }
    }

    private static func key(for event: SessionEvent) -> String {
        event.blockID ?? "seq:\(event.seq)"
    }
}
