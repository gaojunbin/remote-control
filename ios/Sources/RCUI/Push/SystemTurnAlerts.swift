import Foundation
import RCCore
#if os(iOS)
import UserNotifications
#endif

/// Hands one local banner to the system, and nothing else.
///
/// The app asked for permission once, through `PushController`, and this reuses
/// it: a local alert and the gateway's push are one switch and one system
/// permission (`docs/DESIGN.md` § "Being told when a turn ends").
@MainActor
public final class SystemTurnAlerts: TurnAlertPlatform {
    public init() {}

    public func post(_ alert: TurnAlert) {
        #if os(iOS)
        let content = UNMutableNotificationContent()
        content.title = alert.title
        content.body = alert.body
        content.sound = .default
        content.threadIdentifier = alert.threadIdentifier
        content.userInfo = alert.userInfo
        // No trigger: the transition has already happened, so the banner is due
        // now. A push trigger is what tells the delegate a banner came from the
        // gateway instead, which is how the second one is dropped.
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: alert.identifier, content: content, trigger: nil))
        #endif
    }
}

/// Whether a notification arriving while the app is open is shown.
///
/// The app announces a transition itself the moment it arrives on the socket,
/// so the gateway's push for the same transition would be a second banner for
/// one event. It is dropped while the app is in the foreground and connected,
/// and shown in every other case. A local alert the app raised is always shown.
/// Background delivery is untouched: the delegate is not consulted there.
public enum ForegroundBanner {
    public static func shows(remote: Bool, suppressesRemote: Bool) -> Bool {
        !(remote && suppressesRemote)
    }
}
