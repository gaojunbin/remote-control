import Foundation
import RCCore

/// When this Mac posts, and when it keeps quiet: the moments the gateway pushes
/// for (`transition_kind` and `resume_kind` in `gateway/rc_gateway/push.py`,
/// cued by the hub's transition and resume hooks), and the one rule of the Mac
/// app's own — nothing about the conversation open in the frontmost window.
enum TurnNoticeRule {
    /// What a session's newer version is news of, in the order it is posted.
    /// A state change is `TurnAlerts`' table, which is the gateway's. A resume
    /// the device has just scheduled is the pause the gateway pushes for; it is
    /// read from the session, which every app socket of the account is sent,
    /// rather than from the `resume` event, which only the socket watching that
    /// session receives.
    static func kinds(previous: Session, current: Session) -> [PushKind] {
        var kinds: [PushKind] = []
        if let kind = TurnAlerts.kind(previous: previous, current: current) { kinds.append(kind) }
        if previous.id == current.id, previous.resume == nil, current.resume != nil {
            kinds.append(.limitReached)
        }
        return kinds
    }

    /// A `resume` event's news, for the two statuses a session's own fields
    /// cannot tell from a person's cancel: the resume ran, or it was given up.
    /// A scheduled one is the session's pause above, and a rescheduled or a
    /// cancelled one is not news.
    static func kind(resume status: ResumeStatus) -> PushKind? {
        switch status {
        case .fired: .resumed
        case .dropped: .resumeDropped
        default: nil
        }
    }

    /// Whether a notice is posted: the switch is on, the system allows it, and
    /// the conversation it names is not open in the frontmost window.
    static func posts(_ target: NoticeTarget, enabled: Bool, permission: NotificationPermission,
                      route: Route, windowActive: Bool) -> Bool {
        guard enabled, permission == .authorized else { return false }
        return !(windowActive && route == .chat(deviceId: target.deviceID, sessionId: target.sessionID))
    }

    /// Whether a clicked notification can open its conversation at once: an
    /// account is signed in, its list has arrived and the landing rule has had
    /// its turn. Before that the click waits for the moment all three hold.
    static func opensNow(signedIn: Bool, hasSnapshot: Bool, route: Route) -> Bool {
        signedIn && hasSnapshot && route != .landing
    }
}
