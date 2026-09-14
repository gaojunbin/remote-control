import Foundation

/// The one moment a person who walked away from the phone asked to be told
/// about: a session that was working and now is not.
///
/// The table is the gateway's own — `gateway/rc_gateway/push.py`
/// `transition_kind` — so a banner this app raises for itself and a push the
/// gateway sends mean exactly the same thing and carry the same word.
/// `docs/DESIGN.md` § "Being told when a turn ends".
public enum TurnAlerts {
    /// The states a turn can be interrupted from. Returning to `idle` from one
    /// of them is a finished turn; returning to `idle` from anywhere else is a
    /// session that was already quiet.
    private static let active: Set<SessionState> = [.running, .needsApproval, .needsInput]

    public static func kind(previous: SessionState, current: SessionState) -> PushKind? {
        guard current != previous else { return nil }
        switch current {
        case .needsApproval: return .needsApproval
        case .needsInput: return .needsInput
        case .error: return .error
        case .idle: return active.contains(previous) ? .turnCompleted : nil
        default: return nil
        }
    }

    /// Nothing is announced for a session seen for the first time: the `hello`
    /// list is what the app knows, not something that just happened. A pair
    /// that names two different sessions is not a transition either.
    public static func kind(previous: Session?, current: Session) -> PushKind? {
        guard let previous, previous.id == current.id else { return nil }
        return kind(previous: previous.state, current: current.state)
    }
}
