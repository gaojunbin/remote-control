import AppKit
import Observation

/// Where the Dock icon's badge goes: the app's own tile in the app, and a
/// stand-in wherever there is no Dock icon to draw on — the renderer and the
/// test runner. The keeper talks to this and nothing else.
@MainActor
protocol DockBadgePlatform: AnyObject {
    /// The label on the tile, or nil for none.
    func show(_ label: String?)
}

/// The app's Dock tile. It asks nothing of the person: the badge is the app's
/// own while it runs, and goes with it when it quits.
@MainActor
final class SystemDockBadge: DockBadgePlatform {
    func show(_ label: String?) { NSApp.dockTile.badgeLabel = label }
}

/// The badge of a process that has no Dock icon: kept, and shown to nobody.
@MainActor
final class InertDockBadge: DockBadgePlatform {
    private(set) var labels: [String?] = []
    func show(_ label: String?) { labels.append(label) }
}

@MainActor
enum DockBadges {
    /// The app's tile inside the app bundle, the stand-in everywhere else, as
    /// `NotificationPlatforms` chooses.
    static func make() -> any DockBadgePlatform {
        let bundle = Bundle.main
        guard bundle.bundleIdentifier != nil, bundle.bundleURL.pathExtension == "app" else {
            return InertDockBadge()
        }
        return SystemDockBadge()
    }
}

/// Amendment A47: the Dock icon's badge, kept at the number of sessions with a
/// red dot while the app runs (`docs/DESIGN.md` § "A red dot for a session
/// that stopped and waits for you"): the number, or no badge at all at zero.
@MainActor
final class DockBadgeKeeper {
    /// The count the tile shows, or nil before the keeper has shown anything.
    private(set) var shown: Int?

    private let platform: any DockBadgePlatform
    private let count: @MainActor () -> Int
    private var isFollowing = false

    /// `count` is read under observation, so what it reads of the model is followed.
    init(platform: any DockBadgePlatform, count: @escaping @MainActor () -> Int) {
        self.platform = platform
        self.count = count
    }

    /// The label a count is drawn as.
    static func label(for count: Int) -> String? { count > 0 ? "\(count)" : nil }

    func start() {
        guard !isFollowing else { return }
        isFollowing = true
        follow()
    }

    private func follow() {
        let value = withObservationTracking {
            count()
        } onChange: { [weak self] in
            Task { @MainActor in self?.follow() }
        }
        guard value != shown else { return }
        shown = value
        platform.show(Self.label(for: value))
    }
}
