import Foundation

/// Result of `session.subscribe`. `resync: true` means the gateway buffer no
/// longer covers `since_seq`, so the timeline must be rebuilt from history.
public struct SubscribeResult: Codable, Sendable {
    public let session: Session
    public let events: [SessionEvent]
    public let resync: Bool
    /// Amendment A6: the messages waiting behind the current turn, so a freshly
    /// opened chat shows the queue without waiting for the next snapshot event.
    public let queue: QueuePayload?

    public init(session: Session, events: [SessionEvent], resync: Bool, queue: QueuePayload? = nil) {
        self.session = session
        self.events = events
        self.resync = resync
        self.queue = queue
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        session = try values.decode(Session.self, forKey: .session)
        events = try values.decodeIfPresent([SessionEvent].self, forKey: .events) ?? []
        resync = try values.decodeIfPresent(Bool.self, forKey: .resync) ?? false
        queue = try values.decodeIfPresent(QueuePayload.self, forKey: .queue)
    }
}

public struct SessionResult: Codable, Sendable {
    public let session: Session
    public init(session: Session) { self.session = session }
}

public struct SendResult: Codable, Sendable {
    public let accepted: SendAcceptance
    public let queuedID: String?

    public init(accepted: SendAcceptance, queuedID: String? = nil) {
        self.accepted = accepted
        self.queuedID = queuedID
    }

    enum CodingKeys: String, CodingKey {
        case accepted
        case queuedID = "queued_id"
    }
}

public struct HistoryResult: Codable, Sendable {
    public let events: [SessionEvent]
    public let hasMore: Bool

    public init(events: [SessionEvent], hasMore: Bool) {
        self.events = events
        self.hasMore = hasMore
    }

    enum CodingKeys: String, CodingKey {
        case events
        case hasMore = "has_more"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        events = try values.decodeIfPresent([SessionEvent].self, forKey: .events) ?? []
        hasMore = try values.decodeIfPresent(Bool.self, forKey: .hasMore) ?? false
    }
}

public struct BlockResult: Codable, Sendable {
    public let event: SessionEvent
    public init(event: SessionEvent) { self.event = event }
}

public struct DirectoryEntry: Codable, Sendable, Hashable, Identifiable {
    public let name: String
    public let path: String
    public let isGit: Bool

    public var id: String { path }

    public init(name: String, path: String, isGit: Bool) {
        self.name = name
        self.path = path
        self.isGit = isGit
    }

    enum CodingKeys: String, CodingKey {
        case name, path
        case isGit = "is_git"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        name = try values.decodeIfPresent(String.self, forKey: .name) ?? ""
        path = try values.decode(String.self, forKey: .path)
        isGit = try values.decodeIfPresent(Bool.self, forKey: .isGit) ?? false
    }
}

public struct RecentDirectory: Codable, Sendable, Hashable, Identifiable {
    public let path: String
    public let lastUsed: Int64

    public var id: String { path }

    public init(path: String, lastUsed: Int64) {
        self.path = path
        self.lastUsed = lastUsed
    }

    enum CodingKeys: String, CodingKey {
        case path
        case lastUsed = "last_used"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        path = try values.decode(String.self, forKey: .path)
        lastUsed = try values.decodeIfPresent(Int64.self, forKey: .lastUsed) ?? 0
    }
}

public struct DirectoryListing: Codable, Sendable {
    public let path: String
    public let parent: String?
    public let entries: [DirectoryEntry]
    public let recent: [RecentDirectory]

    public init(path: String, parent: String?, entries: [DirectoryEntry], recent: [RecentDirectory]) {
        self.path = path
        self.parent = parent
        self.entries = entries
        self.recent = recent
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        path = try values.decode(String.self, forKey: .path)
        parent = try values.decodeIfPresent(String.self, forKey: .parent)
        entries = try values.decodeIfPresent([DirectoryEntry].self, forKey: .entries) ?? []
        recent = try values.decodeIfPresent([RecentDirectory].self, forKey: .recent) ?? []
    }
}

public struct GitStatus: Codable, Sendable, Hashable {
    public let isRepo: Bool
    public let branch: String?
    public let dirty: Bool?
    public let ahead: Int?
    public let behind: Int?

    public init(isRepo: Bool, branch: String? = nil, dirty: Bool? = nil, ahead: Int? = nil, behind: Int? = nil) {
        self.isRepo = isRepo
        self.branch = branch
        self.dirty = dirty
        self.ahead = ahead
        self.behind = behind
    }

    public var gitInfo: GitInfo? {
        guard isRepo else { return nil }
        return GitInfo(branch: branch, dirty: dirty ?? false, ahead: ahead ?? 0, behind: behind ?? 0)
    }

    enum CodingKeys: String, CodingKey {
        case branch, dirty, ahead, behind
        case isRepo = "is_repo"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        isRepo = try values.decodeIfPresent(Bool.self, forKey: .isRepo) ?? false
        branch = try values.decodeIfPresent(String.self, forKey: .branch)
        dirty = try values.decodeIfPresent(Bool.self, forKey: .dirty)
        ahead = try values.decodeIfPresent(Int.self, forKey: .ahead)
        behind = try values.decodeIfPresent(Int.self, forKey: .behind)
    }
}

public struct AgentsResult: Codable, Sendable {
    public let agents: [AgentInfo]
    public init(agents: [AgentInfo]) { self.agents = agents }
}
