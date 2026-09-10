import Foundation
import Observation

public struct DeviceSessionGroup: Identifiable, Sendable, Equatable {
    public let id: String
    public let deviceName: String
    public let online: Bool
    public let sessions: [Session]

    public init(id: String, deviceName: String, online: Bool, sessions: [Session]) {
        self.id = id
        self.deviceName = deviceName
        self.online = online
        self.sessions = sessions
    }
}

/// Filtering and grouping for the Sessions tab. It owns no network state; the
/// list itself lives in `ConnectionStore` and is corrected by every `hello`.
@MainActor
@Observable
public final class SessionStore {
    public var searchText = ""
    public var showsArchived = false

    public init() {}

    public func visible(_ sessions: [Session]) -> [Session] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return sessions
            .filter { showsArchived ? true : !$0.archived }
            .filter { session in
                guard !query.isEmpty else { return true }
                return session.title.lowercased().contains(query)
                    || session.cwd.lowercased().contains(query)
                    || session.agent.lowercased().contains(query)
            }
            .sorted { lhs, rhs in
                if lhs.state.isBlockedOnUser != rhs.state.isBlockedOnUser { return lhs.state.isBlockedOnUser }
                if lhs.state.isWorking != rhs.state.isWorking { return lhs.state.isWorking }
                return lhs.updatedAt > rhs.updatedAt
            }
    }

    public func grouped(_ sessions: [Session], devices: [Device]) -> [DeviceSessionGroup] {
        let ordered = visible(sessions)
        var seen: [String] = []
        for session in ordered where !seen.contains(session.deviceID) { seen.append(session.deviceID) }
        return seen.map { deviceID in
            let device = devices.first { $0.deviceID == deviceID }
            return DeviceSessionGroup(
                id: deviceID,
                deviceName: device?.name ?? deviceID,
                online: device?.online ?? false,
                sessions: ordered.filter { $0.deviceID == deviceID }
            )
        }
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
