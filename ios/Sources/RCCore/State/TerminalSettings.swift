import Foundation

/// Amendment A17: one of the three settings a terminal chose for a session
/// this app may not retune, ready to be drawn where its picker would stand.
///
/// The device reads the model, the permission mode and the effort out of the
/// agent's own transcript and publishes them as `meta`, so the values are
/// known even where no request could change them. Showing them beats an empty
/// gap: a person on the phone can otherwise not tell which model the terminal
/// is running.
public struct TerminalSetting: Sendable, Hashable, Identifiable {
    /// Which of the three this is. The raw value is the identifier suffix the
    /// checks and the accessibility tree use.
    public enum Field: String, Sendable, Hashable, CaseIterable {
        case model
        case permissionMode
        case effort

        /// What assistive technology calls it. The same words the pickers use,
        /// because the chip stands exactly where the picker would.
        public var label: String {
            switch self {
            case .model: "Model"
            case .permissionMode: "Permissions"
            case .effort: "Effort"
            }
        }
    }

    public let field: Field
    /// The agent's own label for the id, or the raw id when its lists do not
    /// know it — an `auto` permission mode read from a transcript is shown as
    /// `auto` rather than dropped.
    public let text: String

    public var id: String { field.rawValue }

    public init(field: Field, text: String) {
        self.field = field
        self.text = text
    }

    /// The chips for a session, in the order the pickers stand in. A value the
    /// device has not seen is left out entirely rather than drawn as a
    /// placeholder, so an agent that reports no effort shows two chips.
    public static func all(for session: Session, agent: AgentInfo?) -> [TerminalSetting] {
        let values: [(Field, String?)] = [
            (.model, agent?.modelLabel(session.model) ?? session.model),
            (.permissionMode, agent?.permissionModeLabel(session.permissionMode) ?? session.permissionMode),
            (.effort, agent?.effortLabel(session.effort) ?? session.effort)
        ]
        return values.compactMap { field, text in
            text.map { TerminalSetting(field: field, text: $0) }
        }
    }
}
