import Foundation
import Observation

/// Filtering, grouping and the list state that outlives a launch. It owns no
/// network state; the sessions themselves live in `ConnectionStore` and are
/// corrected by every `hello`. The rule that builds the groups is
/// `SessionListLayout`, a pure function this class only feeds.
@MainActor
@Observable
public final class SessionStore {
    private enum Key {
        static let collapsedDevices = "sessions.collapsedDevices"
        static let archiveExpanded = "sessions.archiveExpanded"
    }

    @ObservationIgnored private let defaults: UserDefaults

    public var searchText = ""
    /// An agent id, or nil for every agent. A view of the list rather than a
    /// setting, so it starts at All on every launch and is never written down.
    public var agentFilter: String?

    /// The machines the reader folded away, by device id.
    public private(set) var collapsedDevices: Set<String>
    /// The machines whose Archive the reader opened, by device id.
    public private(set) var expandedArchives: Set<String>

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        collapsedDevices = Set(defaults.stringArray(forKey: Key.collapsedDevices) ?? [])
        expandedArchives = Set(defaults.stringArray(forKey: Key.archiveExpanded) ?? [])
    }

    public func groups(_ sessions: [Session], devices: [Device]) -> [DeviceGroup] {
        SessionListLayout.build(sessions: sessions, devices: devices,
                                agentFilter: agentFilter, query: searchText,
                                collapsedDevices: collapsedDevices,
                                archiveExpanded: expandedArchives)
    }

    /// The agents the filter offers, read from the whole list rather than from
    /// the filtered one, so choosing Codex never hides Claude Code.
    public func agentOptions(_ sessions: [Session]) -> [String] {
        SessionListLayout.agents(in: sessions)
    }

    public func isCollapsed(_ deviceID: String) -> Bool { collapsedDevices.contains(deviceID) }

    public func toggleCollapsed(_ deviceID: String) {
        collapsedDevices.formSymmetricDifference([deviceID])
        defaults.set(collapsedDevices.sorted(), forKey: Key.collapsedDevices)
    }

    public func toggleArchive(_ deviceID: String) {
        expandedArchives.formSymmetricDifference([deviceID])
        defaults.set(expandedArchives.sorted(), forKey: Key.archiveExpanded)
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
