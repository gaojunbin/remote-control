import Foundation

/// Amendment A35: the switches that must read the same on the phone, in the
/// browser and on every device of the account, so the gateway keeps them and
/// no app keeps a copy of its own.
///
/// There is one today. A gateway older than the amendment sends none, which is
/// `nil` in the app and a switch shown disabled with a note.
public struct Preferences: Codable, Sendable, Hashable {
    /// Whether a session the vendor's usage limit stopped is resumed by its
    /// device once the limit resets (protocol 7.2). Off until the person turns
    /// it on.
    public let resumeAfterLimit: Bool

    public init(resumeAfterLimit: Bool = false) {
        self.resumeAfterLimit = resumeAfterLimit
    }

    enum CodingKeys: String, CodingKey {
        case resumeAfterLimit = "resume_after_limit"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        resumeAfterLimit = try values.decodeIfPresent(Bool.self, forKey: .resumeAfterLimit) ?? false
    }
}

/// The body of `GET` and `PATCH /api/preferences` (protocol 3.2).
public struct PreferencesResponse: Codable, Sendable, Hashable {
    public let preferences: Preferences

    public init(preferences: Preferences) { self.preferences = preferences }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        preferences = try values.decodeIfPresent(Preferences.self, forKey: .preferences)
            ?? Preferences()
    }
}
