import Foundation

/// Amendment A33: how an agent is signed in with a vendor.
public struct AccountMethod: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    /// The vendor's own subscription account, signed in with OAuth.
    public static let account = AccountMethod(rawValue: "account")
    /// A key, possibly pointed at a third-party endpoint.
    public static let apiKey = AccountMethod(rawValue: "api_key")
}

/// Amendment A33: one rate-limit window of a vendor account, as the device read
/// it for a `device.agents` reply.
public struct AgentLimit: Codable, Sendable, Hashable {
    /// The window's length: 300 for five hours, 10080 for a week.
    public let windowMinutes: Int
    /// What the window is confined to when it is not everything, in the
    /// vendor's words — the model a weekly limit applies to.
    public let scope: String?
    public let usedPercent: Double
    public let resetsAt: Int64?

    public init(windowMinutes: Int, scope: String? = nil,
                usedPercent: Double, resetsAt: Int64? = nil) {
        self.windowMinutes = windowMinutes
        self.scope = scope
        self.usedPercent = usedPercent
        self.resetsAt = resetsAt
    }

    enum CodingKeys: String, CodingKey {
        case scope
        case windowMinutes = "window_minutes"
        case usedPercent = "used_percent"
        case resetsAt = "resets_at"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        windowMinutes = try values.decodeIfPresent(Int.self, forKey: .windowMinutes) ?? 0
        scope = try values.decodeIfPresent(String.self, forKey: .scope)
        usedPercent = try values.decodeIfPresent(Double.self, forKey: .usedPercent) ?? 0
        resetsAt = try values.decodeIfPresent(Int64.self, forKey: .resetsAt)
    }
}

/// Amendment A33: one credential an agent holds on a device — whose it is, how
/// it signs in, and in a `device.agents` reply what is left of its quota.
///
/// `limits` is present only in that reply: `hello` and `agents.updated` carry
/// accounts read from local files, which change rarely. An account with neither
/// `limits` nor `limits_error` after a reply is one whose vendor exposes no
/// windows the device can read, and the page draws no meter for it at all.
public struct AgentAccount: Codable, Sendable, Hashable {
    /// The vendor the credential belongs to, as the agent names it:
    /// `anthropic`, `openai`, `xai`, or another id.
    public let provider: String
    public let method: AccountMethod
    /// The plan word the vendor records, lowercase as reported. Null or absent
    /// when the agent records none.
    public let plan: String?
    /// A finer tier when the vendor exposes one, in words the device vouches
    /// for: "Max 5x" for Claude's rate-limit tier id. Shown exactly as it
    /// arrived, because the device already put it into words.
    public let tier: String?
    public let email: String?
    /// For an `api_key`: the host the key is sent to when it is not the
    /// vendor's own. Host only, never a path or a secret.
    public let endpoint: String?
    public let limits: [AgentLimit]?
    /// Why `limits` is missing after the device tried, in the device's own
    /// words, one line.
    public let limitsError: String?
    public let limitsCheckedAt: Int64?

    public init(provider: String, method: AccountMethod, plan: String? = nil,
                tier: String? = nil, email: String? = nil, endpoint: String? = nil,
                limits: [AgentLimit]? = nil, limitsError: String? = nil,
                limitsCheckedAt: Int64? = nil) {
        self.provider = provider
        self.method = method
        self.plan = plan
        self.tier = tier
        self.email = email
        self.endpoint = endpoint
        self.limits = limits
        self.limitsError = limitsError
        self.limitsCheckedAt = limitsCheckedAt
    }

    /// The same credential as the device list stores it: what an agent holds
    /// changes rarely, and the windows it is spending are a question asked on
    /// demand, so they never reach the stored device.
    public var withoutLimits: AgentAccount {
        AgentAccount(provider: provider, method: method, plan: plan, tier: tier,
                     email: email, endpoint: endpoint)
    }

    enum CodingKeys: String, CodingKey {
        case provider, method, plan, tier, email, endpoint, limits
        case limitsError = "limits_error"
        case limitsCheckedAt = "limits_checked_at"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        provider = try values.decodeIfPresent(String.self, forKey: .provider) ?? ""
        method = try values.decodeIfPresent(AccountMethod.self, forKey: .method) ?? .account
        plan = try values.decodeIfPresent(String.self, forKey: .plan)
        tier = try values.decodeIfPresent(String.self, forKey: .tier)
        email = try values.decodeIfPresent(String.self, forKey: .email)
        endpoint = try values.decodeIfPresent(String.self, forKey: .endpoint)
        limits = try values.decodeIfPresent([AgentLimit].self, forKey: .limits)
        limitsError = try values.decodeIfPresent(String.self, forKey: .limitsError)
        limitsCheckedAt = try values.decodeIfPresent(Int64.self, forKey: .limitsCheckedAt)
    }
}
