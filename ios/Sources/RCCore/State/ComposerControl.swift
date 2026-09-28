import Foundation

/// Amendments A43 and A44: the controls of the composer's row, in the one
/// order `docs/DESIGN.md` gives them — what waits, how you speak, what runs,
/// what it may do.
///
/// Up next stands only while something is queued, the dictation language only
/// where the phone recognises speech itself, and the model card and the
/// permission mode wherever the session has them to show, as a control or as
/// the value a terminal set (A17). The order lives here, away from the view,
/// so it is one rule that can be checked without a screen.
public enum ComposerControl: String, Sendable, Hashable, CaseIterable, Identifiable {
    case upNext
    case dictationLanguage
    case modelCard
    case permissions

    public var id: String { rawValue }

    /// The Up next icon's accessible value: the count, in words, because the
    /// badge that carries it on screen says nothing to a screen reader.
    public static func upNextValue(_ queued: Int) -> String {
        L10n.string(queued == 1 ? "%lld message" : "%lld messages", queued)
    }
}

extension ChatStore {
    /// The row for this session, leading edge first, given the backend that
    /// is transcribing (`VoiceBackend.inEffect`).
    public func controlRow(backend: VoiceBackend) -> [ComposerControl] {
        var row: [ComposerControl] = []
        if session.queued > 0 { row.append(.upNext) }
        if backend == .onDevice { row.append(.dictationLanguage) }
        if allowsModelCardChanges || terminalSetting(.modelCard) != nil { row.append(.modelCard) }
        if offersPermissionPicker || terminalSetting(.permissionMode) != nil { row.append(.permissions) }
        return row
    }

    /// Amendment A25: the permission picker is offered where this app may
    /// change the mode and the agent has modes to choose from. An agent with
    /// no permission system (pi) lists none, and nothing stands in its place.
    public var offersPermissionPicker: Bool {
        allowsSettingsChanges(for: .permissionMode) && agent?.permissionModes.isEmpty == false
    }
}
