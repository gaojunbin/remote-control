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
    /// Amendment A21: the tier's label while one is on, drawn as the bolt at
    /// the gauge's corner (A44). Nil on the standard speed and on every other
    /// control.
    public let speed: String?

    /// What assistive technology reads as the control's value. Amendment A44:
    /// on the phone the control is an icon, so the value says in words what
    /// the icon draws — for the model card, `modelCardSpoken`.
    public let spokenValue: String

    public var id: String { field.rawValue }

    public init(field: Field, text: String, speed: String? = nil, spokenValue: String? = nil) {
        self.field = field
        self.text = text
        self.speed = speed
        self.spokenValue = spokenValue ?? text
    }

    /// Amendment A21: the words for the model card — the model label with the
    /// effort word after it, in whichever of the two the device has reported.
    /// A terminal-held session's menu shows them as the value it set (A44).
    public static func modelCardText(for session: Session, agent: AgentInfo?) -> String {
        [agent?.modelLabel(session.model) ?? session.model, effortText(for: session, agent: agent)]
            .compactMap { $0 }
            .joined(separator: " ")
    }

    /// Amendment A44: the model card's value in words — "Opus 4.6, effort
    /// High" — with the tier's name after it while one is on. The gauge draws
    /// the effort and its bolt says "faster tier" to the eye, and neither says
    /// anything at all to a screen reader. The live control and the value a
    /// terminal set read a session the same way.
    public static func modelCardSpoken(for session: Session, agent: AgentInfo?) -> String {
        let model = agent?.modelLabel(session.model) ?? session.model ?? AgentLabel.name(session.agent)
        let spoken = effortText(for: session, agent: agent)
            .map { L10n.string("%@, effort %@", model, $0) } ?? model
        guard let tier = agent?.speedLabel(session.speed) ?? session.speed else { return spoken }
        return "\(spoken), \(tier)"
    }

    /// The chips for a session, in the order the live controls stand in. A
    /// value the device has not seen is left out entirely rather than drawn as
    /// a placeholder, so an agent that reports no effort shows the model alone.
    public static func all(for session: Session, agent: AgentInfo?) -> [TerminalSetting] {
        var settings: [TerminalSetting] = []
        let card = modelCardText(for: session, agent: agent)
        if !card.isEmpty {
            settings.append(TerminalSetting(field: .modelCard, text: card,
                                            speed: agent?.speedLabel(session.speed) ?? session.speed,
                                            spokenValue: modelCardSpoken(for: session, agent: agent)))
        }
        if let mode = permissionText(for: session, agent: agent) {
            settings.append(TerminalSetting(field: .permissionMode, text: mode))
        }
        return settings
    }

    /// Amendment A25: an agent that lists no effort levels has no such setting,
    /// so the card reads the model alone even where a session carries a value.
    /// An agent this app has never met keeps the raw id, as A17 asks.
    public static func effortText(for session: Session, agent: AgentInfo?) -> String? {
        guard let agent else { return session.effort }
        guard !agent.efforts.isEmpty else { return nil }
        return agent.effortLabel(session.effort) ?? session.effort
    }

    /// Amendment A25: an agent that lists no permission modes has no permission
    /// system (pi), so no chip stands in for the picker it never had.
    public static func permissionText(for session: Session, agent: AgentInfo?) -> String? {
        guard let agent else { return session.permissionMode }
        guard !agent.permissionModes.isEmpty else { return nil }
        return agent.permissionModeLabel(session.permissionMode) ?? session.permissionMode
    }
}
