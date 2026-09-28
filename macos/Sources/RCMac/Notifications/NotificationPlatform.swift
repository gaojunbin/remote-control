import Foundation

/// What this Mac lets the app do with notifications, in the three answers the
/// Notify me row tells apart: not asked yet, refused in System Settings, and
/// allowed (a provisional grant counts, because the system still delivers).
public enum NotificationPermission: Sendable, Equatable {
    case notDetermined
    case denied
    case authorized
}

/// The session a notification names, and the one a click opens.
struct NoticeTarget: Sendable, Hashable {
    let deviceID: String
    let sessionID: String
}

/// Where notifications go: the system's notification centre in the app, and a
/// stand-in wherever there is none. The notifier talks to this and nothing
/// else, so its rules are checked without posting anything.
@MainActor
protocol NotificationPlatform: AnyObject {
    func permission() async -> NotificationPermission
    /// Shows the system's own question. Only the Notify me switch calls it.
    func requestPermission() async -> NotificationPermission
    func post(_ notice: TurnNotice)
    /// What this app has posted and the person has not dismissed.
    func removeDelivered()
    /// A notification the person clicked.
    var onOpen: (@MainActor (NoticeTarget) -> Void)? { get set }
}

/// The platform of a process that is not the app — the renderer and the test
/// runner — where the system's notification centre is not there to be asked.
/// It never asks anyone anything: everything it is told is kept, and nothing
/// leaves the process.
@MainActor
final class InertNotificationPlatform: NotificationPlatform {
    /// What `permission()` answers. Allowed by default, so a render of the
    /// Notify me switch reads what the setting holds.
    var granted: NotificationPermission
    /// What the system's question would be answered with.
    var answer: NotificationPermission
    private(set) var posted: [TurnNotice] = []
    private(set) var requests = 0
    var onOpen: (@MainActor (NoticeTarget) -> Void)?

    init(granted: NotificationPermission = .authorized, answer: NotificationPermission = .authorized) {
        self.granted = granted
        self.answer = answer
    }

    func permission() async -> NotificationPermission { granted }

    func requestPermission() async -> NotificationPermission {
        requests += 1
        granted = answer
        return granted
    }

    func post(_ notice: TurnNotice) { posted.append(notice) }

    func removeDelivered() { posted.removeAll() }
}

@MainActor
enum NotificationPlatforms {
    /// The system's notification centre inside the app, and the inert stand-in
    /// everywhere else: `UNUserNotificationCenter` stops a process that is not
    /// an application bundle the moment it is touched.
    static func make() -> any NotificationPlatform {
        let bundle = Bundle.main
        guard bundle.bundleIdentifier != nil, bundle.bundleURL.pathExtension == "app" else {
            return InertNotificationPlatform()
        }
        return SystemNotificationPlatform()
    }
}
