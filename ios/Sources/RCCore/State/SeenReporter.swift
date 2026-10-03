import Foundation
import Observation

/// Amendment A47: `session.seen` for the conversation the person has in front
/// of them, so a session already on screen when its turn ends never keeps a
/// red dot (`docs/DESIGN.md` § "A red dot for a session that stopped and waits
/// for you").
///
/// Each app says what "in front" means — the phone's open conversation while
/// the app is active and unlocked, the Mac's while its window has focus — as
/// the key of that conversation, or nil. This watches that answer and the
/// session's mark together, so the three moments the protocol names (the
/// conversation opening in front, its window coming to the front, a mark
/// arriving while it is there) are one moment here, and so is a `hello` that
/// brings a mark set while the app was away.
@MainActor
public final class SeenReporter {
    private let connection: ConnectionStore
    private let inFront: @MainActor () -> String?
    private var isWatching = false

    /// `inFront` returns the `Session.id` of the conversation in front of the
    /// person, or nil; it is read under observation, so whatever it reads of
    /// an observable model is watched.
    public init(connection: ConnectionStore, inFront: @escaping @MainActor () -> String?) {
        self.connection = connection
        self.inFront = inFront
    }

    /// Start watching, for the life of this reporter. A second call does nothing.
    public func start() {
        guard !isWatching else { return }
        isWatching = true
        watch()
    }

    private func watch() {
        let due = withObservationTracking {
            markedConversationInFront()
        } onChange: { [weak self] in
            Task { @MainActor in self?.watch() }
        }
        guard let due else { return }
        Task { [connection] in await connection.markSeen(deviceID: due.deviceID, sessionID: due.sessionID) }
    }

    /// The conversation in front of the person, when its copy carries the mark.
    private func markedConversationInFront() -> Session? {
        guard let key = inFront() else { return nil }
        return connection.sessions.first { $0.id == key && $0.unseen }
    }
}
