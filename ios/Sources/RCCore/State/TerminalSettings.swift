import Foundation

/// Amendment A17: a setting a terminal chose for a session this app may not
/// retune, ready to be drawn where its control would stand.
///
/// The device reads the model, the permission mode, the effort and the speed
/// out of the agent's own transcript and publishes them as `meta`, so the
/// values are known even where no request could change them. Showing them
/// beats an empty gap: a person on the phone can otherwise not tell which
/// model the terminal is running.
public struct TerminalSetting: Sendable, Hashable, Identifiable {
    /// Which control this stands in for. The raw value is the identifier suffix
    /// the checks and the accessibility tree use.
    public enum Field: String, Sendable, Hashable, CaseIterable {
        /// Amendment A21: model, effort and speed are one control, so they are
        /// one chip here too.
        case modelCard
        case permissionMode

        /// What assistive technology calls it. The same word the live control
        /// uses, because the chip stands exactly where that control would.
        public var label: String {
            switch self {
            case .modelCard: L10n.string("Model")
            case .permissionMode: L10n.string("Permissions")
            }
        }
    }

    public let field: Field
    /// The agent's own labels for the ids, or the raw ids when its lists do not
    /// know them — an `auto` permission mode read from a transcript is shown as
    /// `auto` rather than dropped.
    public let text: String
    /// Amendment A21: the tier's label while one is on, drawn as the lightning
    /// glyph before the text. Nil on the standard speed and on every other chip.
    public let speed: String?

    public var id: String { field.rawValue }

    /// What assistive technology reads as the chip's value. The glyph says
    /// "faster tier" to the eye and nothing at all to a screen reader, so the
    /// tier is spelled out here.
    public var spokenValue: String { speed.map { "\(text), \($0)" } ?? text }

    public init(field: Field, text: String, speed: String? = nil) {
        self.field = field
        self.text = text
        self.speed = speed
    }

    /// Amendment A21: the words on the model card — the model label with the
    /// effort word after it, in whichever of the two the device has reported.
    /// The live control and this chip read the same session the same way.
    public static func modelCardText(for session: Session, agent: AgentInfo?) -> String {
        [agent?.modelLabel(session.model) ?? session.model,
         agent?.effortLabel(session.effort) ?? session.effort]
            .compactMap { $0 }
            .joined(separator: " ")
    }

    /// The chips for a session, in the order the live controls stand in. A
    /// value the device has not seen is left out entirely rather than drawn as
    /// a placeholder, so an agent that reports no effort shows the model alone.
    public static func all(for session: Session, agent: AgentInfo?) -> [TerminalSetting] {
        var settings: [TerminalSetting] = []
        let card = modelCardText(for: session, agent: agent)
        if !card.isEmpty {
            settings.append(TerminalSetting(field: .modelCard, text: card,
                                            speed: agent?.speedLabel(session.speed) ?? session.speed))
        }
        if let mode = agent?.permissionModeLabel(session.permissionMode) ?? session.permissionMode {
            settings.append(TerminalSetting(field: .permissionMode, text: mode))
        }
        return settings
    }
}
