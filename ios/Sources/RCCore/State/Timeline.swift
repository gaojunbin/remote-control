import Foundation

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

    /// Top-level rows; sub-agent rows are fetched through `children(of:)`.
    public var roots: [TimelineEntry] { entries.filter { $0.isRenderable && !$0.isNested } }

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
    }

    /// Fold in the untruncated version of one block from `session.block`.
    public mutating func replaceBlock(with event: SessionEvent) {
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
