import RCCore
import SwiftUI

/// What the rows of a transcript can ask of the page: the requests the web's
/// `TimelineHandlers` carry. Each reports its own failure in the page's banner.
@MainActor
struct TranscriptHandlers {
    let openFull: (_ blockID: String) async -> Void
    let approve: (_ requestID: String, _ optionID: String) async -> Void
    /// True when the device took the answer; a refusal keeps the card as it is.
    let answer: (_ requestID: String, _ answers: [String: QuestionAnswer]) async -> Bool
}

/// `ItemView` in `Timeline.tsx`: one row of the transcript, by kind.
struct TranscriptRow: View {
    let item: TranscriptItem
    let children: [TimelineEntry]
    let followsTool: Bool
    let chat: ChatStore
    let handlers: TranscriptHandlers

    var body: some View {
        switch item {
        case .pending(let message):
            UserMessageRow(message: UserMessagePayload(text: message.text, attachments: message.attachments,
                                                       source: .remote),
                           pending: message)
        case .entry(let entry):
            EntryRow(entry: entry, children: children, followsTool: followsTool, chat: chat, handlers: handlers)
        }
    }

    /// Whether the row draws anything at all. The web's rows render `null` for
    /// an answer with no text yet and for a kind this app does not know, and a
    /// row that renders nothing takes no gap in the column.
    static func draws(_ item: TranscriptItem) -> Bool {
        guard case .entry(let entry) = item else { return true }
        switch entry.body {
        case .assistantText: return !entry.text.isEmpty
        case .userMessage, .thinking, .toolCall, .approval, .question, .notice, .error, .turnCompleted: return true
        case .resume(let payload): return ResumeWords.rowText(payload) != nil
        default: return false
        }
    }

    /// `.tool + .tool`.
    static func isTool(_ item: TranscriptItem) -> Bool {
        if case .entry(let entry) = item, entry.toolCall != nil { return true }
        return false
    }
}

private struct EntryRow: View {
    let entry: TimelineEntry
    let children: [TimelineEntry]
    let followsTool: Bool
    let chat: ChatStore
    let handlers: TranscriptHandlers

    var body: some View {
        switch entry.body {
        case .userMessage(let message):
            // A34: nobody typed these words, so they are drawn with the agent's
            // own output rather than in the person's bubble.
            if message.source == .agent { AgentMessageRow(text: message.text) } else { UserMessageRow(message: message) }
        case .assistantText:
            if !entry.text.isEmpty { MarkdownText(text: entry.text).equatable() }
        case .thinking(let thinking):
            ThinkingRow(text: entry.text, thinking: thinking)
        case .toolCall(let tool):
            let nested = children.filter { TranscriptRow.draws(.entry($0)) }
            ToolRow(blockID: entry.id, tool: tool, followsTool: followsTool, hasNested: !nested.isEmpty,
                    onOpenFull: handlers.openFull) {
                ForEach(Array(nested.enumerated()), id: \.element.id) { index, child in
                    // The rows under a sub-agent's tool call draw none of their own.
                    EntryRow(entry: child, children: [],
                             followsTool: index > 0 && nested[index - 1].toolCall != nil,
                             chat: chat, handlers: handlers)
                }
            }
        case .approval(let approval):
            ApprovalCard(approval: approval, onDecide: handlers.approve)
        case .question(let question):
            QuestionCard(question: question, chat: chat, onAnswer: handlers.answer)
        case .notice(let notice):
            NoticeRow(notice: notice)
        case .error(let error):
            ErrorRow(error: error)
        case .turnCompleted(let turn):
            TurnEndRow(turn: turn)
        case .resume(let resume):
            ResumeStepRow(resume: resume)
        default:
            EmptyView()
        }
    }
}
