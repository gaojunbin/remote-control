import Foundation
import RCCore

/// One row the transcript draws: a block the device sent, or a message this
/// app has sent that the device has not echoed yet (A12), which always sorts
/// last and carries no `seq`.
public enum TranscriptItem: Identifiable, Equatable, Sendable {
    case entry(TimelineEntry)
    case pending(OptimisticMessage)

    public var id: String {
        switch self {
        case .entry(let entry): entry.id
        case .pending(let message): message.id
        }
    }
}

/// `selectView` in `web/src/stores/timeline.ts`, over RCCore's transcript: the
/// top-level rows one detail level draws, and the rows nested under each tool
/// call (`docs/DESIGN.md` § "The timeline" → **Two levels of detail**).
///
/// The web keeps a row only for the kinds it draws — never `status`, `meta`,
/// `queue`, `todos` or `turn_started`, never a turn that simply completed and
/// never a resume that `fired` — so those are left out here whatever RCCore
/// holds for them. Simple drops the agent's workings along with everything
/// nested under them, except a card that needs an answer, which comes up to
/// the top level rather than going with the tool row that held it.
public struct TranscriptSelection: Equatable, Sendable {
    public private(set) var roots: [TranscriptItem] = []
    public private(set) var children: [String: [TimelineEntry]] = [:]
    /// How many rows the web's `timeline.order` would hold, whatever the level:
    /// it decides between "No messages yet" and "Start of the conversation".
    public private(set) var rowCount = 0
    /// The newest `seq` the transcript holds, live or from history: the web's
    /// `lastSeq`, which a first page of history moves and an older one does not.
    public private(set) var newestSeq = 0

    public init(timeline: Timeline, detail: TimelineDetail) {
        var drawn: [String: TimelineEntry] = [:]
        newestSeq = timeline.lastSeq
        for entry in timeline.entries {
            newestSeq = max(newestSeq, entry.latestSeq)
            guard Self.isRow(entry) else { continue }
            rowCount += 1
            drawn[entry.id] = entry
        }
        for entry in timeline.entries where Self.isRow(entry) && Self.isDrawn(entry, at: detail) {
            let parent = entry.parentID.flatMap { drawn[$0] }
            if let parent, Self.isDrawn(parent, at: detail) {
                children[parent.id, default: []].append(entry)
            } else if parent == nil || entry.needsReply {
                roots.append(.entry(entry))
            }
        }
        for message in timeline.optimistic where timeline.entry(id: message.id) == nil {
            roots.append(.pending(message))
        }
    }

    /// The key of the first drawn row, so a prepended history page can be told
    /// from a tail append.
    public var firstKey: String? { roots.first?.id }
    /// The key of the last drawn row: a change means a genuinely new block.
    public var lastKey: String? { roots.last?.id }

    /// `isRenderable`: whether the web keeps a row for this block at all.
    static func isRow(_ entry: TimelineEntry) -> Bool {
        switch entry.body {
        case .status, .meta, .queue, .todos, .turnStarted: false
        case .turnCompleted(let payload): payload.stopReason != .completed
        case .resume(let payload): payload.status != .fired
        default: true
        }
    }

    /// `drawnAt`: Detailed draws every row, Simple leaves out the agent's
    /// workings — thinking, tool calls that are not a slash command's own
    /// output (A27), and messages another agent put in the conversation (A34).
    static func isDrawn(_ entry: TimelineEntry, at detail: TimelineDetail) -> Bool {
        detail == .detailed || !isWorkings(entry)
    }

    static func isWorkings(_ entry: TimelineEntry) -> Bool {
        switch entry.body {
        case .thinking: true
        case .toolCall(let payload): !payload.tool.hasPrefix("/")
        case .userMessage(let payload): payload.source == .agent
        default: false
        }
    }
}
