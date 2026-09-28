import Foundation
import RCCore

/// `web/src/stores/sessions.ts`: the one grouping rule every session list
/// follows (`docs/DESIGN.md` § "Session lists: by device, then by activity") —
/// a group per device, its active rows first, then that device's own Archive.
/// The Sessions page and the chat sidebar both read it, so the two lists never
/// disagree.
///
/// It hands back RCCore's `DeviceGroup`, but it is the web's selector rather
/// than RCCore's `SessionListLayout`, and the two part where the web decides:
/// the search reads the title a row prints ("Untitled session" included), the
/// working directory and the device's name, and never the agent; the agent
/// filter offers the agents in id order; and a tie keeps the order the gateway
/// listed the sessions in, as the web's stable sorts do.
enum SessionLayout {
    /// Active is what a CLI or the device still holds: `control` is `remote`,
    /// `terminal` or `shared`. `control: "none"` means the CLI exited and
    /// nothing owns the session any more, so it belongs to the Archive with the
    /// sessions the reader archived by hand.
    static func isActive(_ session: Session) -> Bool {
        !session.archived && session.control != .none
    }

    /// Attention first, then a running turn, then the rest. Lower sorts earlier.
    static func activityRank(_ session: Session) -> Int {
        if session.state.isBlockedOnUser { return 0 }
        if session.state == .running || session.state == .starting { return 1 }
        return 2
    }

    /// - Parameters:
    ///   - deviceFilter: one device, or nil for all of them.
    ///   - agentFilter: one agent, or nil for all of them. Applied before the
    ///     grouping, so a device whose sessions it removes disappears with them.
    ///   - query: free text over the title, the working directory and the
    ///     device's name. A search opens every group it matched in and every
    ///     Archive a match landed in, without touching the stored choices.
    ///   - collapsedDevices: the devices the reader folded shut.
    ///   - archiveExpanded: the devices whose Archive the reader opened.
    static func build(sessions: [Session], devices: [Device], deviceFilter: String? = nil,
                      agentFilter: String? = nil, query: String = "",
                      collapsedDevices: Set<String> = [], archiveExpanded: Set<String> = []) -> [DeviceGroup] {
        let known = Dictionary(devices.map { ($0.deviceID, $0) }, uniquingKeysWith: { first, _ in first })
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let visible = sessions.filter { session in
            if let deviceFilter, session.deviceID != deviceFilter { return false }
            if let agentFilter, session.agent != agentFilter { return false }
            return matches(session, needle: needle, deviceName: known[session.deviceID]?.name ?? "")
        }

        // The groups in the order their first session was listed, which is
        // what a tie in the ordering below falls back to.
        var order: [String] = []
        var active: [String: [Session]] = [:]
        var archive: [String: [Session]] = [:]
        for session in visible {
            if active[session.deviceID] == nil && archive[session.deviceID] == nil { order.append(session.deviceID) }
            if isActive(session) {
                active[session.deviceID, default: []].append(session)
            } else {
                archive[session.deviceID, default: []].append(session)
            }
        }

        let searching = !needle.isEmpty
        let groups = order.map { deviceID -> DeviceGroup in
            let held = stableSorted(active[deviceID] ?? []) { lhs, rhs in
                let left = activityRank(lhs), right = activityRank(rhs)
                return left != right ? left < right : lhs.updatedAt > rhs.updatedAt
            }
            let done = stableSorted(archive[deviceID] ?? []) { $0.updatedAt > $1.updatedAt }
            return DeviceGroup(device: known[deviceID] ?? placeholder(deviceID),
                               collapsed: !searching && collapsedDevices.contains(deviceID),
                               active: held, archive: done,
                               archiveExpanded: archiveExpanded.contains(deviceID) || (searching && !done.isEmpty))
        }

        // Devices with something live first, each half by its most recent activity.
        return stableSorted(groups) { lhs, rhs in
            if lhs.active.isEmpty != rhs.active.isEmpty { return !lhs.active.isEmpty }
            return lastActivity(lhs) > lastActivity(rhs)
        }
    }

    /// The agents present in the list, once each, in id order, for the agent filter.
    static func agents(in sessions: [Session]) -> [String] {
        Array(Set(sessions.map(\.agent))).sorted()
    }

    /// Every session the reader has not archived, newest activity first.
    static func unarchived(_ sessions: [Session]) -> [Session] {
        stableSorted(sessions.filter { !$0.archived }) { $0.updatedAt > $1.updatedAt }
    }

    /// How many sessions are waiting on the person, for the sidebar's footer.
    static func countWaiting(_ sessions: [Session]) -> Int {
        sessions.filter(\.state.isBlockedOnUser).count
    }

    private static func matches(_ session: Session, needle: String, deviceName: String) -> Bool {
        guard !needle.isEmpty else { return true }
        return "\(S.sessionTitle(session)) \(session.cwd) \(deviceName)".lowercased().contains(needle)
    }

    private static func lastActivity(_ group: DeviceGroup) -> Int64 {
        (group.active + group.archive).map(\.updatedAt).max() ?? 0
    }

    /// A session on a device the gateway no longer lists still needs a group,
    /// so it gets one named after its own id rather than disappearing.
    private static func placeholder(_ deviceID: String) -> Device {
        Device(deviceID: deviceID, name: deviceID, platform: .linux, hostname: deviceID, arch: "",
               clientVersion: "", online: false, lastSeen: 0, createdAt: 0)
    }

    /// `Array.prototype.sort`, which keeps equal elements in the order they came.
    static func stableSorted<Element>(_ items: [Element], by precedes: (Element, Element) -> Bool) -> [Element] {
        items.enumerated().sorted { lhs, rhs in
            if precedes(lhs.element, rhs.element) { return true }
            if precedes(rhs.element, lhs.element) { return false }
            return lhs.offset < rhs.offset
        }
        .map(\.element)
    }
}
