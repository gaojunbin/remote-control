import Foundation
import RCCore

/// One notification this Mac posts: the push the gateway would have sent, raised
/// by the app itself.
///
/// It reads as the web's service worker shows a push — "Remote Control" over
/// the line the gateway writes in `rc.title`, the device's name and one generic
/// phrase, never anything the agent wrote — and it carries the payload of
/// `build_payload()` in `gateway/rc_gateway/push.py`. The phrase is the
/// gateway's own English, whatever language the app reads, as the browser's
/// notification is.
struct TurnNotice: Sendable, Hashable {
    let kind: PushKind
    let target: NoticeTarget
    let deviceName: String

    var title: String { S.productName }

    var body: String { "\(deviceName): \(Self.phrase(kind))" }

    /// The web's `tag`: one notification per session, the newest in its place.
    var identifier: String { "rc-\(target.sessionID)" }

    /// `build_payload()`'s shape, for the notification's `userInfo`.
    var payload: [String: Any] {
        ["rc": ["v": 1, "kind": kind.rawValue, "device_id": target.deviceID, "session_id": target.sessionID,
                "device_name": deviceName, "title": body]]
    }

    /// `_TEXTS` in `push.py`, and its fallback for a kind it does not know.
    static func phrase(_ kind: PushKind) -> String {
        switch kind {
        case .needsApproval: "approval needed"
        case .needsInput: "waiting for your answer"
        case .turnCompleted: "turn finished"
        case .error: "session error"
        case .limitReached: "paused by the usage limit"
        case .resumed: "resumed after the limit reset"
        case .resumeDropped: "not resumed"
        default: "update"
        }
    }

    /// The session a clicked notification names, read back from its payload.
    static func target(in userInfo: [AnyHashable: Any]) -> NoticeTarget? {
        guard let rc = userInfo["rc"] as? [String: Any],
              let deviceID = rc["device_id"] as? String, !deviceID.isEmpty,
              let sessionID = rc["session_id"] as? String, !sessionID.isEmpty else { return nil }
        return NoticeTarget(deviceID: deviceID, sessionID: sessionID)
    }
}
