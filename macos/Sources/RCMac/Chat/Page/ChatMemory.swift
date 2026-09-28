import Foundation
import RCCore

/// What the chat keeps for the life of the app, beside the model it serves:
/// an unfinished edit of a queued message (A43) for each conversation it was
/// open in when that conversation closed — the message is out of the line and
/// the draft it replaced is aside, so both wait here for the conversation to
/// open again, in memory, as the web keeps its drafts — and the conversation
/// open now, which a sign-out closes first.
@MainActor
final class ChatMemory {
    var queuedEdits: [String: QueuedEdit] = [:]
    weak var open: ChatHost?

    private weak var model: MacAppModel?
    private static var all: [ChatMemory] = []

    private init(model: MacAppModel) { self.model = model }

    /// The memory of one model. A model the renderer has let go of takes its
    /// memory with it.
    static func of(_ model: MacAppModel) -> ChatMemory {
        all.removeAll { $0.model == nil }
        if let memory = all.first(where: { $0.model === model }) { return memory }
        let memory = ChatMemory(model: model)
        all.append(memory)
        return memory
    }

    /// `signOut.ts`: the conversation closes while the connection still names
    /// the account that is leaving, and nothing of it stays behind.
    func signOut(_ model: MacAppModel) async {
        await open?.close(model: model)
        queuedEdits.removeAll()
    }
}
