import Foundation
import Observation
import RCCore
#if os(iOS)
import UserNotifications
#endif

/// Where the home-screen badge is set, so the rule can be driven without
/// UserNotifications in a check.
@MainActor
public protocol BadgePlatform: AnyObject {
    func setBadge(_ count: Int)
}

/// The system's own badge. Setting it asks nothing of the person: iOS draws
/// it only where notifications with badges are allowed, which Notify me asks
/// for together with alerts and sounds.
@MainActor
public final class SystemBadge: BadgePlatform {
    public init() {}

    public func setBadge(_ count: Int) {
        #if os(iOS)
        UNUserNotificationCenter.current().setBadgeCount(count, withCompletionHandler: nil)
        #endif
    }
}

/// Amendment A47: the home-screen badge, kept at the number of sessions with
/// a red dot while the app runs (`docs/DESIGN.md` § "A red dot for a session
/// that stopped and waits for you"). The gateway's pushes keep it while the
/// app does not, and every one of them carries the count, so the two agree.
@MainActor
public final class AppBadge {
    /// What the app last put on the icon, or nil before it has put anything.
    public private(set) var shown: Int?

    private let platform: any BadgePlatform
    private let count: @MainActor () -> Int
    private var isFollowing = false

    /// `count` is read under observation, so whatever it reads of an
    /// observable model is followed.
    public init(platform: any BadgePlatform, count: @escaping @MainActor () -> Int) {
        self.platform = platform
        self.count = count
    }

    /// Follow the count for the life of this keeper. A second call does nothing.
    public func start() {
        guard !isFollowing else { return }
        isFollowing = true
        follow()
    }

    /// Put the count on the icon again, whatever the app last put there: while
    /// it was away a push may have set another number, or one may never have
    /// arrived.
    public func reassert() {
        shown = nil
        show(count())
    }

    private func follow() {
        let value = withObservationTracking {
            count()
        } onChange: { [weak self] in
            Task { @MainActor in self?.follow() }
        }
        show(value)
    }

    private func show(_ value: Int) {
        guard value != shown else { return }
        shown = value
        platform.setBadge(value)
    }
}
