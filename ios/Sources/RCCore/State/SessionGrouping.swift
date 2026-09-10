import Foundation

/// One device and the live sessions it still holds. A group with no sessions is
/// kept on purpose: the list says "No open sessions" under the machine rather
/// than dropping it, so the set of devices does not flicker as work finishes.
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

/// An archived row carries its device in the row itself, because the Archive is
/// one flat list rather than a group per machine.
public struct ArchivedSession: Identifiable, Sendable, Equatable {
    public let session: Session
    public let deviceName: String

    public var id: String { session.id }

    public init(session: Session, deviceName: String) {
        self.session = session
        self.deviceName = deviceName
    }
}

/// Which of the two groups a session belongs to, or neither.
public enum SessionBucket: Sendable, Equatable {
    /// Something still owns the session: a remote run, a terminal, or a shared
    /// attachment to one.
    case active
    /// Nothing owns it any more, or it was archived by hand and the list is
    /// showing archived sessions.
    case archive
    /// Archived by hand while the list is not showing archived sessions.
    case hidden
}

/// The whole session list, ready to render: Active grouped by device, Archive
/// flat underneath it.
public struct SessionList: Sendable, Equatable {
    public let active: [DeviceSessionGroup]
    public let archive: [ArchivedSession]
    public let isSearching: Bool

    public init(active: [DeviceSessionGroup], archive: [ArchivedSession], isSearching: Bool) {
        self.active = active
        self.archive = archive
        self.isSearching = isSearching
    }

    public var activeCount: Int { active.reduce(0) { $0 + $1.sessions.count } }
    public var archiveCount: Int { archive.count }
    public var isEmpty: Bool { activeCount == 0 && archive.isEmpty }

    /// A search whose match is inside the Archive opens it, whatever the stored
    /// preference says, because a result nobody can see is not a result.
    public var forcesArchiveOpen: Bool { isSearching && !archive.isEmpty }
}

/// Splitting, filtering and ordering for every session list. It is a pure
/// function of the arguments so the rule can be tested without a view, a store
/// or a connection, and so the web app can implement the same one.
public enum SessionListLayout {
    /// Active is what a CLI or a device still holds. Everything else falls to
    /// the Archive, except a hand-archived session while the list is hiding
    /// them.
    public static func bucket(_ session: Session, showsArchived: Bool) -> SessionBucket {
        if session.archived { return showsArchived ? .archive : .hidden }
        return session.control == .none ? .archive : .active
    }

    /// Title, working directory and agent. The device name is deliberately not
    /// searched: it is a group header in Active, and a filter on it would empty
    /// every other group.
    public static func matches(_ session: Session, query: String) -> Bool {
        guard !query.isEmpty else { return true }
        return session.title.lowercased().contains(query)
            || session.cwd.lowercased().contains(query)
            || session.agent.lowercased().contains(query)
    }

    /// Waiting on the user first, then working, then everything at rest. A
    /// `starting` agent counts as working; it is about to be.
    public static func urgency(_ state: SessionState) -> Int {
        if state.isBlockedOnUser { return 0 }
        if state == .running || state == .starting { return 1 }
        return 2
    }

    public static func build(sessions: [Session], devices: [Device],
                             query rawQuery: String = "", showsArchived: Bool = false) -> SessionList {
        let query = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let matching = sessions.filter { matches($0, query: query) }

        var activeByDevice: [String: [Session]] = [:]
        var archive: [Session] = []
        for session in matching {
            switch bucket(session, showsArchived: showsArchived) {
            case .active: activeByDevice[session.deviceID, default: []].append(session)
            case .archive: archive.append(session)
            case .hidden: continue
            }
        }

        let names = Dictionary(devices.map { ($0.deviceID, $0) }, uniquingKeysWith: { first, _ in first })
        // Device order comes from the gateway's list, not from the sessions, so
        // a group never jumps up the page because one of its turns started.
        var order = devices.map(\.deviceID)
        for id in activeByDevice.keys where !order.contains(id) { order.append(id) }

        let groups = order.compactMap { deviceID -> DeviceSessionGroup? in
            let held = (activeByDevice[deviceID] ?? []).sorted(by: ordered)
            // While searching, a machine with no match is noise rather than an
            // empty shelf worth naming.
            if held.isEmpty && !query.isEmpty { return nil }
            let device = names[deviceID]
            return DeviceSessionGroup(id: deviceID,
                                      deviceName: device?.name ?? deviceID,
                                      online: device?.online ?? false,
                                      sessions: held)
        }

        let archived = archive
            .sorted { $0.updatedAt > $1.updatedAt }
            .map { ArchivedSession(session: $0, deviceName: names[$0.deviceID]?.name ?? $0.deviceID) }

        return SessionList(active: groups, archive: archived, isSearching: !query.isEmpty)
    }

    private static func ordered(_ lhs: Session, _ rhs: Session) -> Bool {
        let left = urgency(lhs.state), right = urgency(rhs.state)
        if left != right { return left < right }
        return lhs.updatedAt > rhs.updatedAt
    }
}
