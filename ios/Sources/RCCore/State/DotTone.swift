import Foundation

/// How a session's status dot looks, on a session row, in the chat header and
/// anywhere else the dot appears.
///
/// The tone is a pure function of the three facts the reader cares about: what
/// the agent is doing, who still owns the session, and whether the machine is
/// reachable at all. `state` alone is not enough — a finished turn on a live
/// session and a session whose CLI exited both report `idle`, and they must not
/// look the same. On a session row the colour is the whole of the state, and
/// `DotLegend` is what says so in words; the chat header still spells the state
/// out beside its dot.
///
/// The colour carries the meaning on its own: green means working — leave it;
/// amber means there is something for you, a finished turn to read or a
/// question to answer. Only `waiting` moves, so the one state that needs the
/// person is the one that asks for a glance.
///
/// `docs/DESIGN.md` states the same table for both apps.
public enum DotTone: String, Sendable, Hashable, CaseIterable {
    /// Green, solid: a turn is under way and needs nobody.
    case working
    /// Amber, pulsing: the agent is blocked on the user.
    case waiting
    /// Amber, solid: alive and quiet — a turn finished and its result is there
    /// to be looked at, a terminal is still open, or the device holds the
    /// session.
    case live
    /// Grey: nothing owns it any more, it was stopped, or the machine is gone.
    case off
    /// Red, solid: the agent reported an error.
    case failed

    /// The whole rule, in the order the cases are decided.
    ///
    /// A machine that cannot be reached says so before anything else: whatever
    /// the device last reported about a session is history, not status.
    public static func of(state: SessionState, control: SessionControl, online: Bool) -> DotTone {
        guard online else { return .off }
        switch state {
        case .error: return .failed
        case .starting, .running: return .working
        case .needsApproval, .needsInput: return .waiting
        case .idle, .readonly: return control == .none ? .off : .live
        default: return .off
        }
    }
}

extension Session {
    /// The dot this session shows, given whether its device is reachable.
    public func dotTone(online: Bool) -> DotTone {
        DotTone.of(state: state, control: control, online: online)
    }
}
