import Foundation
import RCCore

/// `web/src/features/chat/modelLabels.ts`: one model-and-effort pair the model
/// chip or the card's name row can end up holding. Either half is nil when the
/// agent lists none of that kind.
public struct LabelPair: Sendable, Hashable {
    public let model: String?
    public let effort: String?

    public init(model: String?, effort: String?) {
        self.model = model
        self.effort = effort
    }

    /// A key no label can collide with, whatever the agent calls its models.
    public var key: String { "\(model ?? "") \(effort ?? "")" }

    /// Every combination of the agent's models and effort levels. An agent that
    /// lists only one of the two is measured on what it has, and one that lists
    /// neither is measured on whatever it is currently drawing.
    public static func pairs(models: [AgentOption], efforts: [AgentOption]) -> [LabelPair] {
        if models.isEmpty && efforts.isEmpty { return [] }
        if efforts.isEmpty { return models.map { LabelPair(model: $0.label, effort: nil) } }
        if models.isEmpty { return efforts.map { LabelPair(model: nil, effort: $0.label) } }
        return models.flatMap { model in efforts.map { LabelPair(model: model.label, effort: $0.label) } }
    }
}
