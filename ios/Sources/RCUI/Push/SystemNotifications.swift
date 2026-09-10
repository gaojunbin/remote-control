import Foundation
import RCCore
#if os(iOS)
import UIKit
import UserNotifications
#endif

/// The APNs token for this process, and nothing else.
///
/// A device token is never written to defaults, a projection, a log, a
/// diagnostic report or a URL. It travels once, in an authenticated body.
@MainActor
public final class SystemNotifications: NotificationPlatform {
    public static let shared = SystemNotifications()

    public var onChange: (() -> Void)?
    public var onOpen: ((PushRoute) -> Void)? {
        didSet {
            if let pending, let onOpen { self.pending = nil; onOpen(pending) }
        }
    }
    public private(set) var token: String?
    private var pending: PushRoute?
    private var registrationRequested = false
    private var registrationFailed = false

    private init() {}

    public var supported: Bool {
        #if os(iOS)
        true
        #else
        false
        #endif
    }

    /// The environment comes from the Info.plist key the build configuration
    /// fills in, so a debug build cannot register a production token.
    public var environment: String? {
        switch Bundle.main.object(forInfoDictionaryKey: "RCAPNSEnvironment") as? String {
        case "development": "sandbox"
        case "production": "production"
        default: nil
        }
    }

    public func authorization() async -> PushAuthorization {
        #if os(iOS)
        // The completion handler returns only a Sendable value.
        return await withCheckedContinuation { continuation in
            UNUserNotificationCenter.current().getNotificationSettings { settings in
                let value: PushAuthorization = switch settings.authorizationStatus {
                case .notDetermined: .notDetermined
                case .denied: .denied
                case .authorized: .authorized
                case .provisional, .ephemeral: .provisional
                @unknown default: .notDetermined
                }
                continuation.resume(returning: value)
            }
        }
        #else
        return .unsupported
        #endif
    }

    public func requestAuthorization() async throws -> Bool {
        #if os(iOS)
        return try await withCheckedThrowingContinuation { continuation in
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: granted) }
            }
        }
        #else
        return false
        #endif
    }

    public func register() {
        #if os(iOS)
        guard !registrationRequested || registrationFailed else { return }
        registrationRequested = true
        registrationFailed = false
        UIApplication.shared.registerForRemoteNotifications()
        #endif
    }

    public func unregister() {
        token = nil
        registrationRequested = false
        registrationFailed = false
        #if os(iOS)
        UIApplication.shared.unregisterForRemoteNotifications()
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        #endif
    }

    public func openSettings() {
        #if os(iOS)
        if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
            UIApplication.shared.open(url)
        }
        #endif
    }

    public func didRegister(_ data: Data) {
        guard registrationRequested else { return }
        do {
            token = try APNSRegistration.tokenHex(data)
            registrationFailed = false
        } catch {
            token = nil
            registrationFailed = true
        }
        onChange?()
    }

    public func didFailRegistration() {
        guard registrationRequested else { return }
        token = nil
        registrationFailed = true
        onChange?()
    }

    fileprivate func received(_ data: Data) {
        guard let route = try? PushRoute(userInfo: data) else { return }
        if let onOpen { onOpen(route) } else { pending = route }
    }
}

#if os(iOS)
/// UIKit hands the token to the app delegate; this forwards it and nothing else.
@MainActor
public final class RemoteNotificationAppDelegate: NSObject, UIApplicationDelegate {
    private let notificationDelegate = NotificationDelegate()

    public override init() { super.init() }

    public func application(_ application: UIApplication,
                            didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = notificationDelegate
        return true
    }

    public func application(_ application: UIApplication,
                            didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        SystemNotifications.shared.didRegister(deviceToken)
    }

    public func application(_ application: UIApplication,
                            didFailToRegisterForRemoteNotificationsWithError error: any Error) {
        SystemNotifications.shared.didFailRegistration()
    }
}

/// The payload is serialized to `Data` before crossing an actor boundary, so no
/// UserNotifications object ever does.
private final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping @Sendable () -> Void) {
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier,
              let data = try? JSONSerialization.data(withJSONObject: response.notification.request.content.userInfo) else {
            completionHandler()
            return
        }
        Task { @MainActor in
            SystemNotifications.shared.received(data)
            completionHandler()
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping @Sendable (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
#endif
