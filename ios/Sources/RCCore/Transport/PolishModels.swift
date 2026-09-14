import Foundation

/// Amendment A29: how hard the gateway's model is allowed to work on a
/// dictation. Both strengths keep the language the words were spoken in and
/// return text only; neither adds a request the speaker did not make.
public enum PolishStrength: String, Codable, Sendable, Hashable, CaseIterable {
    /// Fillers, false starts, repetitions, plain mishearings and punctuation.
    /// The speaker's words and their order are kept.
    case moderate
    /// Also restructures for clarity and precision, and resolves a vague
    /// reference from what the conversation already said.
    case strong

    public var title: String {
        switch self {
        case .moderate: L10n.string("Moderate")
        case .strong: L10n.string("Strong")
        }
    }
}

/// One model the gateway's configured provider offers.
public struct PolishModel: Codable, Sendable, Hashable, Identifiable {
    public let id: String
    public let label: String

    public init(id: String, label: String) {
        self.id = id
        self.label = label
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decodeIfPresent(String.self, forKey: .id) ?? ""
        // A provider that names a model and nothing else is still a model.
        label = try values.decodeIfPresent(String.self, forKey: .label) ?? id
    }
}

/// `GET /api/polish/models`.
public struct PolishModelsResponse: Codable, Sendable, Hashable {
    public let models: [PolishModel]

    public init(models: [PolishModel]) {
        self.models = models
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        models = (try values.decodeIfPresent([PolishModel].self, forKey: .models) ?? [])
            .filter { !$0.id.isEmpty }
    }
}

/// Who said one of the messages the model is given as conversation.
public enum PolishRole: String, Codable, Sendable, Hashable {
    case user, assistant
}

/// One message of the recent conversation, as the app already shows it.
public struct PolishContextItem: Codable, Sendable, Hashable {
    public let role: PolishRole
    public let text: String

    public init(role: PolishRole, text: String) {
        self.role = role
        self.text = text
    }
}

/// `POST /api/polish`: the dictated words, what the user chose, and the
/// conversation they were spoken into.
public struct PolishRequest: Codable, Sendable, Hashable {
    public let text: String
    public let model: String
    public let strength: PolishStrength
    /// The dictation language the user chose, or nothing when it is automatic.
    public let language: String?
    public let context: [PolishContextItem]

    public init(text: String, model: String, strength: PolishStrength,
                language: String? = nil, context: [PolishContextItem] = []) {
        self.text = text
        self.model = model
        self.strength = strength
        self.language = language
        self.context = context
    }
}

/// The polished text and nothing else.
public struct PolishResponse: Codable, Sendable, Hashable {
    public let text: String

    public init(text: String) {
        self.text = text
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        text = try values.decodeIfPresent(String.self, forKey: .text) ?? ""
    }
}
