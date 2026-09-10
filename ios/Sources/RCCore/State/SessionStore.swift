import Foundation
import Observation

/// Filtering, grouping and the one piece of list state that outlives a launch.
/// It owns no network state; the list itself lives in `ConnectionStore` and is
/// corrected by every `hello`. The rule that splits Active from Archive is
/// `SessionListLayout`, a pure function this class only feeds.
@MainActor
@Observable
public final class SessionStore {
    private enum Key {
        static let archiveExpanded = "sessions.archiveExpanded"
    }

    @ObservationIgnored private let defaults: UserDefaults

    public var searchText = ""
    /// The existing toggle: hand-archived sessions join the Archive group only
    /// while this is on.
    public var showsArchived = false
    /// Whether the Archive group is open. Persisted per client, so the choice
    /// survives a relaunch.
    public var isArchiveExpanded: Bool {
        didSet { defaults.set(isArchiveExpanded, forKey: Key.archiveExpanded) }
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        isArchiveExpanded = defaults.bool(forKey: Key.archiveExpanded)
    }

    public func list(_ sessions: [Session], devices: [Device]) -> SessionList {
        SessionListLayout.build(sessions: sessions, devices: devices,
                                query: searchText, showsArchived: showsArchived)
    }

    /// What the Archive group renders as: open because the reader opened it, or
    /// because a search found something inside.
    public func showsArchiveContents(of list: SessionList) -> Bool {
        isArchiveExpanded || list.forcesArchiveOpen
    }
}

/// Compact relative time for list rows: "4m", "3h", "yesterday".
public enum RelativeTime {
    public static func short(since milliseconds: Int64, now: Date = Date()) -> String {
        guard milliseconds > 0 else { return "" }
        let seconds = now.timeIntervalSince1970 - Double(milliseconds) / 1000
        if seconds < 45 { return "now" }
        if seconds < 3600 { return "\(Int(seconds / 60))m" }
        if seconds < 86_400 { return "\(Int(seconds / 3600))h" }
        if seconds < 172_800 { return "yesterday" }
        return "\(Int(seconds / 86_400))d"
    }

    /// "6.4s", "1m 12s" for durations reported in milliseconds.
    public static func duration(milliseconds: Int) -> String {
        let seconds = Double(milliseconds) / 1000
        if seconds < 10 { return String(format: "%.1fs", seconds) }
        if seconds < 60 { return "\(Int(seconds.rounded()))s" }
        let minutes = Int(seconds) / 60
        return "\(minutes)m \(Int(seconds) % 60)s"
    }

    /// "48.2k" for a token count.
    public static func compactCount(_ value: Int) -> String {
        if value < 1000 { return "\(value)" }
        if value < 1_000_000 { return String(format: "%.1fk", Double(value) / 1000) }
        return String(format: "%.1fM", Double(value) / 1_000_000)
    }

    /// "9:47" countdown for a pairing code.
    public static func countdown(to milliseconds: Int64, now: Date = Date()) -> String {
        let remaining = max(0, Double(milliseconds) / 1000 - now.timeIntervalSince1970)
        return String(format: "%d:%02d", Int(remaining) / 60, Int(remaining) % 60)
    }
}
