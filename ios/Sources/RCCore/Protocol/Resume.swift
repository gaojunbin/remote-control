import Foundation

/// Amendment A35: why a turn ended when the vendor's usage window was used up.
///
/// The device reads this from the agent's own signal — Claude Code's 429
/// result, Codex's `usageLimitExceeded` — never from the sentence the vendor
/// wrote, so an app can say "the limit" without parsing anybody's prose.
public struct LimitStop: Codable, Sendable, Hashable {
    /// The window that was hit, as `AgentLimit.window_minutes`: 300 for five
    /// hours, 10080 for a week. Absent when the device could not tell.
    public let windowMinutes: Int?
    /// When the window resets, or nil when the vendor named no time.
    public let resetsAt: Int64?

    public init(windowMinutes: Int? = nil, resetsAt: Int64? = nil) {
        self.windowMinutes = windowMinutes
        self.resetsAt = resetsAt
    }

    enum CodingKeys: String, CodingKey {
        case windowMinutes = "window_minutes"
        case resetsAt = "resets_at"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        windowMinutes = try values.decodeIfPresent(Int.self, forKey: .windowMinutes)
        resetsAt = try values.decodeIfPresent(Int64.self, forKey: .resetsAt)
    }
}

/// Amendment A35: the one resume a session can have pending after a usage
/// limit stopped it. It travels on the session summary, so every screen that
/// holds a `Session` knows the session is paused and when it comes back.
public struct SessionResume: Codable, Sendable, Hashable {
    /// When the device will send the resume prompt.
    public let at: Int64
    /// True when the vendor named no reset time and `at` was computed from the
    /// window's length, which is what makes the app say "about".
    public let estimated: Bool
    /// How many resumes have already run into the limit again. The device drops
    /// the resume after the third.
    public let attempts: Int
    /// The window that was hit, when the device knows it.
    public let windowMinutes: Int?

    public init(at: Int64, estimated: Bool = false, attempts: Int = 0, windowMinutes: Int? = nil) {
        self.at = at
        self.estimated = estimated
        self.attempts = attempts
        self.windowMinutes = windowMinutes
    }

    enum CodingKeys: String, CodingKey {
        case at, estimated, attempts
        case windowMinutes = "window_minutes"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        at = try values.decodeIfPresent(Int64.self, forKey: .at) ?? 0
        estimated = try values.decodeIfPresent(Bool.self, forKey: .estimated) ?? false
        attempts = try values.decodeIfPresent(Int.self, forKey: .attempts) ?? 0
        windowMinutes = try values.decodeIfPresent(Int.self, forKey: .windowMinutes)
    }

    public var date: Date { Date(timeIntervalSince1970: Double(at) / 1000) }
}

/// Amendment A35: what the device did about a session the limit stopped.
public struct ResumeStatus: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let scheduled = ResumeStatus(rawValue: "scheduled")
    public static let rescheduled = ResumeStatus(rawValue: "rescheduled")
    public static let fired = ResumeStatus(rawValue: "fired")
    public static let cancelled = ResumeStatus(rawValue: "cancelled")
    public static let dropped = ResumeStatus(rawValue: "dropped")

    /// The moment of resuming is not a row of its own: the prompt appears in
    /// the person's bubble and the turn it starts speaks for itself
    /// (`docs/DESIGN.md` § "Paused by the usage limit").
    public var isDrawn: Bool { self != .fired }
}

/// The body of a `resume` event (protocol 5.15). Like `notice`, it changes no
/// state: the pending resume itself travels as `Session.resume`.
public struct ResumePayload: Codable, Sendable, Hashable {
    public let status: ResumeStatus
    /// For `scheduled` and `rescheduled`: when the prompt will be sent.
    public let at: Int64?
    public let estimated: Bool
    /// For `rescheduled`: how many resumes have run into the limit again.
    public let attempts: Int?
    /// For `cancelled` and `dropped`: why, in the device's words, one line.
    public let reason: String?

    public init(status: ResumeStatus, at: Int64? = nil, estimated: Bool = false,
                attempts: Int? = nil, reason: String? = nil) {
        self.status = status
        self.at = at
        self.estimated = estimated
        self.attempts = attempts
        self.reason = reason
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        status = try values.decodeIfPresent(ResumeStatus.self, forKey: .status)
            ?? ResumeStatus(rawValue: "")
        at = try values.decodeIfPresent(Int64.self, forKey: .at)
        estimated = try values.decodeIfPresent(Bool.self, forKey: .estimated) ?? false
        attempts = try values.decodeIfPresent(Int.self, forKey: .attempts)
        reason = try values.decodeIfPresent(String.self, forKey: .reason)
    }
}

/// What `session.resume_set` will accept: at least a minute ahead and no more
/// than eight days out (protocol 6.3). The app checks them first so a time the
/// device would refuse never leaves the picker.
public enum ResumeBounds {
    public static let leadTime: TimeInterval = 60
    public static let horizon: TimeInterval = 8 * 24 * 60 * 60

    public static func earliest(from now: Date = Date()) -> Date {
        now.addingTimeInterval(leadTime)
    }

    public static func latest(from now: Date = Date()) -> Date {
        now.addingTimeInterval(horizon)
    }

    public static func allows(_ date: Date, now: Date = Date()) -> Bool {
        date >= earliest(from: now) && date <= latest(from: now)
    }
}
