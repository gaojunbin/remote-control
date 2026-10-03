import Foundation
import UserNotifications

/// The system's notification centre, for the app itself.
///
/// The Mac has no push channel of its own — the gateway's APNs topic is the
/// iPhone app's — so the running app posts what the gateway would have pushed
/// (`docs/DESIGN.md` § "The Mac app" → **Notify me posts from the app**). The
/// question the system asks is asked from the Notify me switch and from
/// nowhere else.
@MainActor
final class SystemNotificationPlatform: NotificationPlatform {
    private let clicks = NotificationClicks()

    var onOpen: (@MainActor (NoticeTarget) -> Void)? {
        get { clicks.onOpen }
        set { clicks.onOpen = newValue }
    }

    init() {
        // Set before launching finishes, so a click that launched the app is
        // still delivered here.
        UNUserNotificationCenter.current().delegate = clicks
    }

    func permission() async -> NotificationPermission {
        await withCheckedContinuation { continuation in
            UNUserNotificationCenter.current().getNotificationSettings { settings in
                continuation.resume(returning: Self.permission(of: settings.authorizationStatus))
            }
        }
    }

    /// Alerts, sounds and the badge in one question: the Dock icon's count of
    /// sessions waiting for the person (A47) is drawn where the person allows
    /// this app badges.
    func requestPermission() async -> NotificationPermission {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
                continuation.resume()
            }
        }
        return await permission()
    }

    func post(_ notice: TurnNotice) {
        let content = UNMutableNotificationContent()
        content.title = notice.title
        content.body = notice.body
        content.sound = .default
        content.threadIdentifier = notice.identifier
        content.userInfo = notice.payload
        // The web's `tag`: a newer notification about the same session takes
        // the older one's place rather than stacking under it.
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: notice.identifier, content: content, trigger: nil),
            withCompletionHandler: nil)
    }

    func removeDelivered() {
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }

    nonisolated private static func permission(of status: UNAuthorizationStatus) -> NotificationPermission {
        switch status {
        case .authorized, .provisional: .authorized
        case .denied: .denied
        default: .notDetermined
        }
    }
}

/// The centre's delegate: a click opens the session the notification names,
/// and one that arrives while the app is in front is still shown, because the
/// conversation it is about is not the one on screen (that one posts nothing).
@MainActor
private final class NotificationClicks: NSObject, UNUserNotificationCenterDelegate {
    var onOpen: (@MainActor (NoticeTarget) -> Void)?

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping @Sendable () -> Void) {
        // Only the identifiers cross to the main actor; the response stays here.
        let target = response.actionIdentifier == UNNotificationDefaultActionIdentifier
            ? TurnNotice.target(in: response.notification.request.content.userInfo) : nil
        Task { @MainActor in
            if let target { self.onOpen?(target) }
            completionHandler()
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping @Sendable (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }
}
