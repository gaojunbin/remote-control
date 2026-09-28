import Foundation
import RCCore

extension TimelineEntry {
    /// A storable event rebuilt from a row, for the transcript cache a
    /// conversation opens on next time (the iPhone app keeps this in RCUI,
    /// which the Mac does not link).
    ///
    /// Streaming rows are stored in their finished form, so a cached transcript
    /// holds whole answers rather than the last delta that happened to arrive.
    var sourceEvent: SessionEvent? {
        let stored: SessionEventBody
        switch body {
        case .status, .meta, .queue:
            return nil
        case .assistantText:
            stored = .assistantText(StreamTextPayload(text: text, done: true))
        case .thinking(let payload):
            stored = .thinking(StreamTextPayload(text: text, done: true, durationMS: payload.durationMS))
        default:
            stored = body
        }
        return SessionEvent(seq: seq, ts: ts, kind: wireKind, blockID: id.hasPrefix("seq:") ? nil : id,
                            parentBlockID: parentID, body: stored)
    }

    private var wireKind: String {
        switch body {
        case .userMessage: SessionEvent.userMessageKind
        case .assistantText: SessionEvent.assistantTextKind
        case .thinking: SessionEvent.thinkingKind
        case .toolCall: SessionEvent.toolCallKind
        case .todos: SessionEvent.todosKind
        case .approval: SessionEvent.approvalKind
        case .question: SessionEvent.questionKind
        case .turnStarted: SessionEvent.turnStartedKind
        case .turnCompleted: SessionEvent.turnCompletedKind
        case .status: SessionEvent.statusKind
        case .meta: SessionEvent.metaKind
        case .queue: SessionEvent.queueKind
        case .notice: SessionEvent.noticeKind
        case .error: SessionEvent.errorKind
        case .resume: SessionEvent.resumeKind
        case .unknown(let kind, _): kind
        }
    }
}
