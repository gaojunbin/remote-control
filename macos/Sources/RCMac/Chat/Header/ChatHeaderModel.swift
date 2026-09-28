import Foundation
import RCCore

/// What `web/src/features/chat/ChatHeader.tsx` works out before it draws: the
/// Stop button, the todo chip's counts and the usage chip's words.
public struct ChatHeaderModel: Equatable, Sendable {
    /// Stop is offered: a turn this app may end is running.
    public let offersStop: Bool
    /// "Todos 1/4", or nil when the chip is not drawn.
    public let todos: TodoCounts?
    /// "48.2k · 1m 12s", or nil when neither half has anything to say.
    public let usage: String?

    public init(session: Session, agent: AgentInfo?, todos items: [TodoItem], detail: TimelineDetail,
                now: Int64 = Format.nowMillis) {
        // A7: a terminal-driven turn also reports `running`, but only the
        // terminal can stop it — the user has to take over first. A10: an
        // attached session can be stopped only when the device says the
        // attachment carries an interrupt.
        let stoppable = session.control == .shared ? Attach.canInterruptShared(agent)
                                                   : session.control != .terminal
        offersStop = (session.state == .running || session.state == .starting) && stoppable

        // A checklist is part of the agent's workings: Simple does not draw it.
        let doneCount = items.filter { $0.status == .completed }.count
        let total = session.todos?.total ?? items.count
        let done = items.isEmpty ? (session.todos?.done ?? 0) : doneCount
        todos = detail == .detailed && total > 0 ? TodoCounts(total: total, done: done) : nil

        let tokens = session.usage?.totalTokens ?? 0
        let elapsed = session.turn.map { Format.duration(Double(now - $0.startedAt)) } ?? ""
        switch (tokens > 0, !elapsed.isEmpty) {
        case (true, true): usage = "\(Format.compactNumber(Double(tokens))) · \(elapsed)"
        case (true, false): usage = Format.compactNumber(Double(tokens))
        case (false, true): usage = elapsed
        case (false, false): usage = nil
        }
    }
}
