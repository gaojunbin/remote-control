import Foundation

/// A labelled choice offered by an agent (model, permission mode, effort).
/// The id is opaque and is echoed back verbatim in `session.set`.
public struct AgentOption: Codable, Sendable, Hashable, Identifiable {
    public let id: String
    public let label: String

    public init(id: String, label: String) {
        self.id = id
        self.label = label
    }
}

/// What one agent installed on one device can do.
public struct AgentInfo: Codable, Sendable, Hashable, Identifiable {
    public let agent: String
    public let available: Bool
    public let version: String?
    public let path: String?
    public let models: [AgentOption]
    public let defaultModel: String?
    public let permissionModes: [AgentOption]
    public let defaultPermissionMode: String?
    public let efforts: [AgentOption]
    public let defaultEffort: String?
    /// Amendment A21: the tiers this agent can run a session at beyond its
    /// standard speed, such as Codex's `priority`. Empty when it has none, and
    /// an agent with an empty list draws no speed control at all.
    public let speeds: [AgentOption]
    public let capabilities: [AgentCapability]
    /// Amendment A10: how this agent's terminal sessions can be attached, or
    /// nil when they can only be taken over or resumed.
    public let attach: AgentAttach?
    /// Whether the device is prepared to attach the next terminal session.
    /// Apps use it only to word the hint on a `terminal` session.
    public let attachReady: Bool
    /// Whether `session.stop` works on a `shared` session.
    public let sharedInterrupt: Bool
    /// Amendment A11: whether `session.set` reaches the live CLI on a `shared`
    /// session. The Codex daemon applies model, permission mode and effort to
    /// the running thread; a Claude channel cannot.
    public let sharedSettings: Bool
    /// Amendment A11: whether `session.send` attachments are delivered on a
    /// `shared` session. The Codex daemon takes image inputs; a Claude channel
    /// has no way to hand bytes to a live CLI.
    public let sharedAttachments: Bool

    public var id: String { agent }

    /// Unknown agent ids render generically with the id as the label.
    public var displayName: String { AgentLabel.name(agent) }

    public func supports(_ capability: AgentCapability) -> Bool { capabilities.contains(capability) }

    public func modelLabel(_ id: String?) -> String? {
        guard let id else { return nil }
        return models.first { $0.id == id }?.label ?? id
    }

    public func permissionModeLabel(_ id: String?) -> String? {
        guard let id else { return nil }
        return permissionModes.first { $0.id == id }?.label ?? id
    }

    public func effortLabel(_ id: String?) -> String? {
        guard let id else { return nil }
        return efforts.first { $0.id == id }?.label ?? id
    }

    /// The label for a tier, or nil for the standard speed.
    public func speedLabel(_ id: String?) -> String? {
        guard let id else { return nil }
        return speeds.first { $0.id == id }?.label ?? id
    }

    public init(agent: String, available: Bool, version: String? = nil, path: String? = nil,
                models: [AgentOption] = [], defaultModel: String? = nil,
                permissionModes: [AgentOption] = [], defaultPermissionMode: String? = nil,
                efforts: [AgentOption] = [], defaultEffort: String? = nil,
                speeds: [AgentOption] = [], capabilities: [AgentCapability] = [],
                attach: AgentAttach? = nil, attachReady: Bool = false,
                sharedInterrupt: Bool = false, sharedSettings: Bool = false,
                sharedAttachments: Bool = false) {
        self.agent = agent
        self.available = available
        self.version = version
        self.path = path
        self.models = models
        self.defaultModel = defaultModel
        self.permissionModes = permissionModes
        self.defaultPermissionMode = defaultPermissionMode
        self.efforts = efforts
        self.defaultEffort = defaultEffort
        self.speeds = speeds
        self.capabilities = capabilities
        self.attach = attach
        self.attachReady = attachReady
        self.sharedInterrupt = sharedInterrupt
        self.sharedSettings = sharedSettings
        self.sharedAttachments = sharedAttachments
    }

    enum CodingKeys: String, CodingKey {
        case agent, available, version, path, models, efforts, speeds, capabilities, attach
        case defaultModel = "default_model"
        case permissionModes = "permission_modes"
        case defaultPermissionMode = "default_permission_mode"
        case defaultEffort = "default_effort"
        case attachReady = "attach_ready"
        case sharedInterrupt = "shared_interrupt"
        case sharedSettings = "shared_settings"
        case sharedAttachments = "shared_attachments"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        agent = try values.decode(String.self, forKey: .agent)
        available = try values.decodeIfPresent(Bool.self, forKey: .available) ?? false
        version = try values.decodeIfPresent(String.self, forKey: .version)
        path = try values.decodeIfPresent(String.self, forKey: .path)
        models = try values.decodeIfPresent([AgentOption].self, forKey: .models) ?? []
        defaultModel = try values.decodeIfPresent(String.self, forKey: .defaultModel)
        permissionModes = try values.decodeIfPresent([AgentOption].self, forKey: .permissionModes) ?? []
        defaultPermissionMode = try values.decodeIfPresent(String.self, forKey: .defaultPermissionMode)
        efforts = try values.decodeIfPresent([AgentOption].self, forKey: .efforts) ?? []
        defaultEffort = try values.decodeIfPresent(String.self, forKey: .defaultEffort)
        speeds = try values.decodeIfPresent([AgentOption].self, forKey: .speeds) ?? []
        capabilities = try values.decodeIfPresent([AgentCapability].self, forKey: .capabilities) ?? []
        attach = try values.decodeIfPresent(AgentAttach.self, forKey: .attach)
        attachReady = try values.decodeIfPresent(Bool.self, forKey: .attachReady) ?? false
        sharedInterrupt = try values.decodeIfPresent(Bool.self, forKey: .sharedInterrupt) ?? false
        sharedSettings = try values.decodeIfPresent(Bool.self, forKey: .sharedSettings) ?? false
        sharedAttachments = try values.decodeIfPresent(Bool.self, forKey: .sharedAttachments) ?? false
    }
}

/// A machine running the client daemon.
public struct Device: Codable, Sendable, Hashable, Identifiable {
    public let deviceID: String
    public let name: String
    public let platform: DevicePlatform
    public let hostname: String
    public let arch: String
    public let clientVersion: String
    public let online: Bool
    public let lastSeen: Int64
    public let createdAt: Int64
    public let latencyMS: Int?
    public let agents: [AgentInfo]

    public var id: String { deviceID }

    public var availableAgents: [AgentInfo] { agents.filter(\.available) }

    public func agent(_ id: String?) -> AgentInfo? {
        guard let id else { return nil }
        return agents.first { $0.agent == id }
    }

    public init(deviceID: String, name: String, platform: DevicePlatform, hostname: String, arch: String,
                clientVersion: String, online: Bool, lastSeen: Int64, createdAt: Int64,
                latencyMS: Int? = nil, agents: [AgentInfo] = []) {
        self.deviceID = deviceID
        self.name = name
        self.platform = platform
        self.hostname = hostname
        self.arch = arch
        self.clientVersion = clientVersion
        self.online = online
        self.lastSeen = lastSeen
        self.createdAt = createdAt
        self.latencyMS = latencyMS
        self.agents = agents
    }

    enum CodingKeys: String, CodingKey {
        case name, platform, hostname, arch, online, agents
        case deviceID = "device_id"
        case clientVersion = "client_version"
        case lastSeen = "last_seen"
        case createdAt = "created_at"
        case latencyMS = "latency_ms"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        deviceID = try values.decode(String.self, forKey: .deviceID)
        name = try values.decodeIfPresent(String.self, forKey: .name) ?? deviceID
        platform = try values.decodeIfPresent(DevicePlatform.self, forKey: .platform) ?? .linux
        hostname = try values.decodeIfPresent(String.self, forKey: .hostname) ?? ""
        arch = try values.decodeIfPresent(String.self, forKey: .arch) ?? ""
        clientVersion = try values.decodeIfPresent(String.self, forKey: .clientVersion) ?? ""
        online = try values.decodeIfPresent(Bool.self, forKey: .online) ?? false
        lastSeen = try values.decodeIfPresent(Int64.self, forKey: .lastSeen) ?? 0
        createdAt = try values.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        latencyMS = try values.decodeIfPresent(Int.self, forKey: .latencyMS)
        agents = try values.decodeIfPresent([AgentInfo].self, forKey: .agents) ?? []
    }
}
