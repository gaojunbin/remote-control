import Foundation

/// Amendment A47: the red dot of a session that stopped working and waits for
/// the person, and the app icon's badge that counts them
/// (`docs/DESIGN.md` § "A red dot for a session that stopped and waits for you").
///
/// The mark is the gateway's: it sees every transition while every app may be
/// closed, and one person opening the session on one app clears it on all of
/// them. The apps draw `Session.unseen` and count it; the offline demo keeps it
/// by the same rule the gateway does, which is the rest of this type.
public enum UnseenMark {
    /// A session works while a turn is under way. Unlike
    /// `SessionState.isWorking`, a turn that stopped to ask for something is
    /// not working — that is the moment the person is wanted — and nor is a
    /// session that only started: its dot is green too, but it has done
    /// nothing to look at yet.
    public static func works(_ state: SessionState) -> Bool {
        state == .running
    }

    /// A session waits for the person while it asks for an approval or an
    /// answer, or rests with something still holding it: the amber dot. A CLI
    /// that exited and a session that errored are not waiting for anyone.
    public static func waits(state: SessionState, control: SessionControl) -> Bool {
        switch state {
        case .needsApproval, .needsInput: true
        case .idle, .readonly: control != .none
        default: false
        }
    }

    /// The mark `current` carries once it has replaced `previous`, read from
    /// the states alone, as the gateway reads them: whether the device was
    /// reachable has no say, because a turn that ended while it was offline
    /// still ended. Archiving clears it, working again clears it, and only a
    /// move from working to waiting sets it; anything else leaves it as it was.
    public static func next(previous: Session, current: Session) -> Bool {
        if current.archived || works(current.state) { return false }
        if works(previous.state), waits(state: current.state, control: current.control) { return true }
        return previous.unseen
    }

    /// The app icon's badge: the unarchived sessions that carry the mark, less
    /// the conversation in front of the person (its `Session.id`), which is
    /// being looked at and draws no dot while the `session.seen` that clears
    /// it on every app is on its way.
    public static func count(in sessions: [Session], excluding front: String? = nil) -> Int {
        sessions.filter { $0.unseen && !$0.archived && $0.id != front }.count
    }
}
