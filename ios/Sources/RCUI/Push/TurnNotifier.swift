import Foundation
import RCCore

/// One banner the app raises for itself when a turn ends
/// (`docs/DESIGN.md` § "Being told when a turn ends").
///
/// It carries the gateway's own push payload, so tapping it takes exactly the
/// path a push takes: `NotificationDelegate` → `SystemNotifications.received`
/// → `PushRoute` → `AppModel.handle(link:)`, and opens the session.
public struct TurnAlert: Equatable {
    /// The device's name, over the status word.
    public let title: String
    /// One of the four words from the status vocabulary, and nothing else: no
    /// prompt text, no output, no file name.
    public let body: String
    /// The session key, unique across devices, so a machine's alerts stack
    /// under the conversation they belong to.
    public let threadIdentifier: String
    public let identifier: String
    public let route: PushRoute

    public init(kind: PushKind, deviceName: String, session: Session) {
        let word = kind.alertWord
        title = deviceName
        body = word
        threadIdentifier = session.id
        identifier = "turn/\(session.id)/\(kind.rawValue)/\(session.updatedAt)"
        route = PushRoute(kind: kind, deviceID: session.deviceID, sessionID: session.sessionID,
                          deviceName: deviceName, title: "\(deviceName): \(word)")
    }

    /// The payload shape of `build_payload()` in `gateway/rc_gateway/push.py`,
    /// ready for `UNMutableNotificationContent.userInfo`.
    public var userInfo: [String: Any] {
        ["rc": ["v": route.version,
                "kind": route.kind.rawValue,
                "device_id": route.deviceID,
                "session_id": route.sessionID,
                "device_name": route.deviceName,
                "title": route.title]]
    }
}

/// The posting side of a local banner, so a check drives the rule without
/// UserNotifications — the same shape as `NotificationPlatform`.
@MainActor
public protocol TurnAlertPlatform: AnyObject {
    func post(_ alert: TurnAlert)
}

/// Raises a banner for a finished or waiting turn while the app is open.
///
/// Three gates, all of which must hold: the app is in the foreground, the
/// Notifications switch is on, and the system has granted permission. The
/// transition table itself is `TurnAlerts` in RCCore, which is the gateway's.
@MainActor
public final class TurnNotifier {
    /// The last banner raised, for previews and checks to read back.
    public private(set) var lastAlert: TurnAlert?

    private let platform: any TurnAlertPlatform

    public init(platform: any TurnAlertPlatform = SystemTurnAlerts()) {
        self.platform = platform
    }

    @discardableResult
    public func announce(previous: Session, current: Session, deviceName: String,
                         sceneActive: Bool, enabled: Bool,
                         authorization: PushAuthorization) -> TurnAlert? {
        guard sceneActive, enabled, authorization.raisesBanners,
              let kind = TurnAlerts.kind(previous: previous, current: current) else { return nil }
        let alert = TurnAlert(kind: kind, deviceName: deviceName, session: current)
        lastAlert = alert
        platform.post(alert)
        return alert
    }
}

extension PushAuthorization {
    /// Provisional counts: the system still delivers, quietly.
    public var raisesBanners: Bool { self == .authorized || self == .provisional }
}

extension PushKind {
    /// The status vocabulary of `docs/DESIGN.md`, which is the same word in
    /// both apps and in a notification.
    var alertWord: String {
        switch self {
        case .turnCompleted: L10n.string("Turn finished")
        case .needsApproval: L10n.string("Needs your approval")
        case .needsInput: L10n.string("Waiting for your answer")
        case .error: L10n.string("Errored")
        default: rawValue
        }
    }
}
