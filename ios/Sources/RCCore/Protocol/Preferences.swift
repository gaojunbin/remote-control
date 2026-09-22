import Foundation

/// Amendments A35 and A41: the settings that must read the same on the phone,
/// in the browser and on every device of the account, so the gateway keeps
/// them and no app keeps a copy of its own.
///
/// `resume_after_limit` came first (A35) and is always there. A41 added the
/// Settings screen's own — the interface language, the dictation language,
/// polish with its model and strength, and the timeline detail — and every one
/// of them is optional: absent means nobody has set it yet, and an app then
/// writes its own value up once (`PreferenceSync`). A gateway older than A35
/// sends no object at all, which is `nil` in the app and a resume switch shown
/// disabled with a note.
///
/// An unknown word in one of the enum fields reads as absent rather than
/// failing the frame: the gateway validates every write, so a value this build
/// does not know is one a later build added, and the rest of the object is
/// still the account's.
public struct Preferences: Codable, Sendable, Hashable {
    /// Whether a session the vendor's usage limit stopped is resumed by its
    /// device once the limit resets (protocol 7.2). Off until the person turns
    /// it on.
    public let resumeAfterLimit: Bool
    /// The app's interface language (A41).
    public let language: InterfaceLanguage?
    /// The dictation language: `auto`, or a code from `stt.languages` (A41).
    public let sttLanguage: String?
    /// Whether a finished dictation goes through the gateway's polish model
    /// (A29, A41).
    public let polishEnabled: Bool?
    /// The polish model chosen from `GET /api/polish/models`; empty when none.
    public let polishModel: String?
    /// How far the polish may go (A29, A41).
    public let polishStrength: PolishStrength?
    /// How much of a transcript is drawn (A41).
    public let timelineDetail: TimelineDetail?

    public init(resumeAfterLimit: Bool = false,
                language: InterfaceLanguage? = nil,
                sttLanguage: String? = nil,
                polishEnabled: Bool? = nil,
                polishModel: String? = nil,
                polishStrength: PolishStrength? = nil,
                timelineDetail: TimelineDetail? = nil) {
        self.resumeAfterLimit = resumeAfterLimit
        self.language = language
        self.sttLanguage = sttLanguage
        self.polishEnabled = polishEnabled
        self.polishModel = polishModel
        self.polishStrength = polishStrength
        self.timelineDetail = timelineDetail
    }

    enum CodingKeys: String, CodingKey {
        case resumeAfterLimit = "resume_after_limit"
        case language
        case sttLanguage = "stt_language"
        case polishEnabled = "polish_enabled"
        case polishModel = "polish_model"
        case polishStrength = "polish_strength"
        case timelineDetail = "timeline_detail"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        resumeAfterLimit = try values.decodeIfPresent(Bool.self, forKey: .resumeAfterLimit) ?? false
        language = InterfaceLanguage(
            rawValue: try values.decodeIfPresent(String.self, forKey: .language) ?? "")
        sttLanguage = try values.decodeIfPresent(String.self, forKey: .sttLanguage)
        polishEnabled = try values.decodeIfPresent(Bool.self, forKey: .polishEnabled)
        polishModel = try values.decodeIfPresent(String.self, forKey: .polishModel)
        polishStrength = PolishStrength(
            rawValue: try values.decodeIfPresent(String.self, forKey: .polishStrength) ?? "")
        timelineDetail = TimelineDetail(
            rawValue: try values.decodeIfPresent(String.self, forKey: .timelineDetail) ?? "")
    }

    /// The object a `PATCH` leaves behind: the fields the write names, and the
    /// rest as they were (protocol 3.2). The gateway is the one writer; this is
    /// how the app draws the write before the round trip and how the demo
    /// gateway keeps the account's copy.
    public func applying(_ changes: PreferencePatch) -> Preferences {
        Preferences(resumeAfterLimit: changes.resumeAfterLimit ?? resumeAfterLimit,
                    language: changes.language ?? language,
                    sttLanguage: changes.sttLanguage ?? sttLanguage,
                    polishEnabled: changes.polishEnabled ?? polishEnabled,
                    polishModel: changes.polishModel ?? polishModel,
                    polishStrength: changes.polishStrength ?? polishStrength,
                    timelineDetail: changes.timelineDetail ?? timelineDetail)
    }
}

/// The body of `PATCH /api/preferences` (protocol 3.2): the fields to set and
/// no others. A field left out is left alone, and the gateway answers with the
/// whole object.
public struct PreferencePatch: Encodable, Sendable, Hashable {
    public var resumeAfterLimit: Bool?
    public var language: InterfaceLanguage?
    public var sttLanguage: String?
    public var polishEnabled: Bool?
    public var polishModel: String?
    public var polishStrength: PolishStrength?
    public var timelineDetail: TimelineDetail?

    public init(resumeAfterLimit: Bool? = nil,
                language: InterfaceLanguage? = nil,
                sttLanguage: String? = nil,
                polishEnabled: Bool? = nil,
                polishModel: String? = nil,
                polishStrength: PolishStrength? = nil,
                timelineDetail: TimelineDetail? = nil) {
        self.resumeAfterLimit = resumeAfterLimit
        self.language = language
        self.sttLanguage = sttLanguage
        self.polishEnabled = polishEnabled
        self.polishModel = polishModel
        self.polishStrength = polishStrength
        self.timelineDetail = timelineDetail
    }

    typealias CodingKeys = Preferences.CodingKeys

    /// Nothing to write, which is a request nobody should send.
    public var isEmpty: Bool { self == PreferencePatch() }
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
