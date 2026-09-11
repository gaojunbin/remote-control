import Foundation

/// One machine and everything the session list shows under it: the sessions
/// something still holds, then the ones it is finished with. A device with
/// neither is never built, so the list carries no empty shelves.
public struct DeviceGroup: Identifiable, Sendable, Equatable {
    public let device: Device
    /// The reader folded this machine away. The rows are still here, so a count
    /// or a test can look inside without expanding anything.
    public let collapsed: Bool
    /// What a CLI or the device still holds, most urgent first.
    public let active: [Session]
    /// What nothing owns any more, plus what was archived by hand, newest first.
    public let archive: [Session]
    public let archiveExpanded: Bool

    public var id: String { device.deviceID }
    public var name: String { device.name }
    public var online: Bool { device.online }
    public var isEmpty: Bool { active.isEmpty && archive.isEmpty }

    public init(device: Device, collapsed: Bool, active: [Session],
                archive: [Session], archiveExpanded: Bool) {
        self.device = device
        self.collapsed = collapsed
        self.active = active
        self.archive = archive
        self.archiveExpanded = archiveExpanded
    }
}

/// Filtering, splitting and ordering for the session list: first by device, then
/// by whether the session is still live. It is a pure function of its arguments
/// so the rule can be tested without a view, a store or a connection, and so the
/// web app can implement the same one.
public enum SessionListLayout {
    /// A session belongs under its device's Archive when nothing owns it any
    /// more (`control == .none`) or when it was archived by hand. Everything a
    /// CLI or the device still holds stays above it.
    public static func isArchived(_ session: Session) -> Bool {
        session.archived || session.control == .none
    }

    /// Archiving is offered on exactly one kind of row: a session the device is
    /// driving — `control == .remote`, whoever created it — that is not
    /// archived. A row a terminal holds offers none, because the terminal owns
    /// it and it leaves Active by itself the moment the terminal exits; a row
    /// already in the Archive offers none either, because writing to it is what
    /// brings it back (A15).
    public static func offersArchive(_ session: Session) -> Bool {
        session.control == .remote && !session.archived
    }

    /// Title, working directory and agent, by id and by label. The device name
    /// is deliberately not searched: it is a group header, and matching on it
    /// would empty every other group.
    public static func matches(_ session: Session, query: String) -> Bool {
        guard !query.isEmpty else { return true }
        return session.title.lowercased().contains(query)
            || session.cwd.lowercased().contains(query)
            || session.agent.lowercased().contains(query)
            || session.agentLabel.lowercased().contains(query)
    }

    /// Waiting on the user first, then working, then everything at rest. A
    /// `starting` agent counts as working; it is about to be.
    public static func urgency(_ state: SessionState) -> Int {
        if state.isBlockedOnUser { return 0 }
        if state == .running || state == .starting { return 1 }
        return 2
    }

    /// The agent ids a list actually contains, in label order. The filter offers
    /// these and nothing else, so it never names an agent nobody is running.
    public static func agents(in sessions: [Session]) -> [String] {
        var seen: Set<String> = []
        let ids = sessions.map(\.agent).filter { !$0.isEmpty && seen.insert($0).inserted }
        return ids.sorted { AgentLabel.name($0).localizedCaseInsensitiveCompare(AgentLabel.name($1)) == .orderedAscending }
    }

    /// - Parameters:
    ///   - deviceFilter: a device id, or nil for every device.
    ///   - agentFilter: an agent id, or nil for every agent. Applied before the
    ///     grouping, so a machine whose sessions are all filtered out is gone.
    ///   - collapsedDevices: the device ids the reader folded away. A search
    ///     unfolds every group it matched, without touching the preference.
    ///   - archiveExpanded: the device ids whose Archive the reader opened. A
    ///     search opens any Archive with a match on top of these, and that
    ///     opening is never persisted.
    public static func build(sessions: [Session], devices: [Device],
                             deviceFilter: String? = nil, agentFilter: String? = nil,
                             query rawQuery: String = "",
                             collapsedDevices: Set<String> = [],
                             archiveExpanded: Set<String> = []) -> [DeviceGroup] {
        let query = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let searching = !query.isEmpty
        let matching = sessions.filter { session in
            if let deviceFilter, session.deviceID != deviceFilter { return false }
            if let agentFilter, session.agent != agentFilter { return false }
            return matches(session, query: query)
        }

        var active: [String: [Session]] = [:]
        var archive: [String: [Session]] = [:]
        for session in matching {
            if isArchived(session) {
                archive[session.deviceID, default: []].append(session)
            } else {
                active[session.deviceID, default: []].append(session)
            }
        }

        let known = Dictionary(devices.map { ($0.deviceID, $0) }, uniquingKeysWith: { first, _ in first })
        let groups = Set(active.keys).union(archive.keys).map { deviceID -> DeviceGroup in
            let held = (active[deviceID] ?? []).sorted(by: ordered)
            let done = (archive[deviceID] ?? []).sorted { $0.updatedAt > $1.updatedAt }
            // A search that found something inside the Archive opens it: a
            // result nobody can see is not a result.
            let open = archiveExpanded.contains(deviceID) || (searching && !done.isEmpty)
            // A group only exists here because something in it matched, so a
            // search unfolds it too. Clearing the query folds it back.
            return DeviceGroup(device: known[deviceID] ?? unlisted(deviceID),
                               collapsed: !searching && collapsedDevices.contains(deviceID),
                               active: held, archive: done, archiveExpanded: open)
        }

        return groups.sorted(by: precedes)
    }

    /// Machines with something live come first, then the rest, each by their
    /// most recent activity. A machine never jumps the page because its name
    /// sorts early, and never sinks because the gateway listed it late.
    private static func precedes(_ lhs: DeviceGroup, _ rhs: DeviceGroup) -> Bool {
        if lhs.active.isEmpty != rhs.active.isEmpty { return rhs.active.isEmpty }
        let left = activity(lhs), right = activity(rhs)
        if left != right { return left > right }
        if lhs.name != rhs.name { return lhs.name < rhs.name }
        return lhs.id < rhs.id
    }

    private static func activity(_ group: DeviceGroup) -> Int64 {
        (group.active + group.archive).map(\.updatedAt).max() ?? 0
    }

    private static func ordered(_ lhs: Session, _ rhs: Session) -> Bool {
        let left = urgency(lhs.state), right = urgency(rhs.state)
        if left != right { return left < right }
        return lhs.updatedAt > rhs.updatedAt
    }

    /// A session can name a device the gateway never listed. It still gets a
    /// group, under its own id, rather than disappearing from the list.
    private static func unlisted(_ deviceID: String) -> Device {
        Device(deviceID: deviceID, name: deviceID, platform: .linux, hostname: "", arch: "",
               clientVersion: "", online: false, lastSeen: 0, createdAt: 0)
    }
}
