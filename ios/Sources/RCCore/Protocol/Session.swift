import Foundation

public struct GitInfo: Codable, Sendable, Hashable {
    public let branch: String?
    public let dirty: Bool
    public let ahead: Int
    public let behind: Int
    public let worktree: Bool

    public init(branch: String?, dirty: Bool = false, ahead: Int = 0, behind: Int = 0, worktree: Bool = false) {
        self.branch = branch
        self.dirty = dirty
        self.ahead = ahead
        self.behind = behind
        self.worktree = worktree
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        branch = try values.decodeIfPresent(String.self, forKey: .branch)
        dirty = try values.decodeIfPresent(Bool.self, forKey: .dirty) ?? false
        ahead = try values.decodeIfPresent(Int.self, forKey: .ahead) ?? 0
        behind = try values.decodeIfPresent(Int.self, forKey: .behind) ?? 0
        worktree = try values.decodeIfPresent(Bool.self, forKey: .worktree) ?? false
    }
}

public struct TurnMarker: Codable, Sendable, Hashable {
    public let turnID: String
    public let startedAt: Int64

    public init(turnID: String, startedAt: Int64) {
        self.turnID = turnID
        self.startedAt = startedAt
    }

    enum CodingKeys: String, CodingKey {
        case turnID = "turn_id"
        case startedAt = "started_at"
    }
}

public struct TodoCounts: Codable, Sendable, Hashable {
    public let total: Int
    public let done: Int

    public init(total: Int, done: Int) {
        self.total = total
        self.done = done
    }
}

public struct SessionUsage: Codable, Sendable, Hashable {
    public let inputTokens: Int
    public let outputTokens: Int
    public let totalTokens: Int
    public let contextUsed: Int?
    public let contextWindow: Int?
    public let costUSD: Double?

    public init(inputTokens: Int = 0, outputTokens: Int = 0, totalTokens: Int = 0,
                contextUsed: Int? = nil, contextWindow: Int? = nil, costUSD: Double? = nil) {
        self.inputTokens = inputTokens
        self.outputTokens = outputTokens
        self.totalTokens = totalTokens
        self.contextUsed = contextUsed
        self.contextWindow = contextWindow
        self.costUSD = costUSD
    }

    /// 0…1, or nil when the device did not report a window.
    public var contextFraction: Double? {
        guard let contextUsed, let contextWindow, contextWindow > 0 else { return nil }
        return min(1, max(0, Double(contextUsed) / Double(contextWindow)))
    }

    enum CodingKeys: String, CodingKey {
        case inputTokens = "input_tokens"
        case outputTokens = "output_tokens"
        case totalTokens = "total_tokens"
        case contextUsed = "context_used"
        case contextWindow = "context_window"
        case costUSD = "cost_usd"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        inputTokens = try values.decodeIfPresent(Int.self, forKey: .inputTokens) ?? 0
        outputTokens = try values.decodeIfPresent(Int.self, forKey: .outputTokens) ?? 0
        totalTokens = try values.decodeIfPresent(Int.self, forKey: .totalTokens) ?? 0
        contextUsed = try values.decodeIfPresent(Int.self, forKey: .contextUsed)
        contextWindow = try values.decodeIfPresent(Int.self, forKey: .contextWindow)
        costUSD = try values.decodeIfPresent(Double.self, forKey: .costUSD)
    }
}

/// One conversation with one agent on one device. Global identity is
/// (`deviceID`, `sessionID`); `sessionID` alone is only device-local.
public struct Session: Codable, Sendable, Hashable, Identifiable {
    public var sessionID: String
    public var deviceID: String
    public var agent: String
    public var title: String
    public var cwd: String
    public var git: GitInfo?
    public var state: SessionState
    public var stateDetail: String?
    public var origin: EventSource
    public var control: SessionControl
    public var model: String?
    public var permissionMode: String?
    public var effort: String?
    public var createdAt: Int64
    public var updatedAt: Int64
    public var lastSeq: Int
    public var archived: Bool
    public var turn: TurnMarker?
    public var todos: TodoCounts?
    public var usage: SessionUsage?
    public var queued: Int

    /// Unique across devices, unlike `sessionID`.
    public var id: String { "\(deviceID)/\(sessionID)" }

    /// The composer is disabled while a live CLI process owns the input.
    /// Amendment A10: `shared` is deliberately excluded — a live CLI owns the
    /// session but the device is attached to it, so this app may still type.
    public var isControlledByTerminal: Bool { control == .terminal }

    /// Amendment A10: a live CLI process owns the session and the device is
    /// attached to it. Composer, approvals and queue behave as for `remote`.
    public var isAttached: Bool { control == .shared }

    /// Last path component of the working directory, for a compact subtitle.
    public var folderName: String {
        let trimmed = cwd.hasSuffix("/") ? String(cwd.dropLast()) : cwd
        return trimmed.split(separator: "/").last.map(String.init) ?? trimmed
    }

    public init(sessionID: String, deviceID: String, agent: String, title: String, cwd: String,
                git: GitInfo? = nil, state: SessionState = .idle, stateDetail: String? = nil,
                origin: EventSource = .remote, control: SessionControl = .none,
                model: String? = nil, permissionMode: String? = nil, effort: String? = nil,
                createdAt: Int64 = 0, updatedAt: Int64 = 0, lastSeq: Int = 0, archived: Bool = false,
                turn: TurnMarker? = nil, todos: TodoCounts? = nil, usage: SessionUsage? = nil,
                queued: Int = 0) {
        self.sessionID = sessionID
        self.deviceID = deviceID
        self.agent = agent
        self.title = title
        self.cwd = cwd
        self.git = git
        self.state = state
        self.stateDetail = stateDetail
        self.origin = origin
        self.control = control
        self.model = model
        self.permissionMode = permissionMode
        self.effort = effort
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.lastSeq = lastSeq
        self.archived = archived
        self.turn = turn
        self.todos = todos
        self.usage = usage
        self.queued = queued
    }

    enum CodingKeys: String, CodingKey {
        case agent, title, cwd, git, state, origin, control, model, effort, archived, turn, todos, usage, queued
        case sessionID = "session_id"
        case deviceID = "device_id"
        case stateDetail = "state_detail"
        case permissionMode = "permission_mode"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case lastSeq = "last_seq"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        sessionID = try values.decode(String.self, forKey: .sessionID)
        deviceID = try values.decode(String.self, forKey: .deviceID)
        agent = try values.decodeIfPresent(String.self, forKey: .agent) ?? ""
        title = try values.decodeIfPresent(String.self, forKey: .title) ?? ""
        cwd = try values.decodeIfPresent(String.self, forKey: .cwd) ?? ""
        git = try values.decodeIfPresent(GitInfo.self, forKey: .git)
        state = try values.decodeIfPresent(SessionState.self, forKey: .state) ?? .idle
        stateDetail = try values.decodeIfPresent(String.self, forKey: .stateDetail)
        origin = try values.decodeIfPresent(EventSource.self, forKey: .origin) ?? .remote
        control = try values.decodeIfPresent(SessionControl.self, forKey: .control) ?? .none
        model = try values.decodeIfPresent(String.self, forKey: .model)
        permissionMode = try values.decodeIfPresent(String.self, forKey: .permissionMode)
        effort = try values.decodeIfPresent(String.self, forKey: .effort)
        createdAt = try values.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        updatedAt = try values.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        lastSeq = try values.decodeIfPresent(Int.self, forKey: .lastSeq) ?? 0
        archived = try values.decodeIfPresent(Bool.self, forKey: .archived) ?? false
        turn = try values.decodeIfPresent(TurnMarker.self, forKey: .turn)
        todos = try values.decodeIfPresent(TodoCounts.self, forKey: .todos)
        usage = try values.decodeIfPresent(SessionUsage.self, forKey: .usage)
        queued = try values.decodeIfPresent(Int.self, forKey: .queued) ?? 0
    }
}
