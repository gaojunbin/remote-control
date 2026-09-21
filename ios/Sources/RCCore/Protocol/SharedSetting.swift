import Foundation

/// Amendment A40: one of the four session settings an attachment may or may
/// not carry. `AgentInfo.shared_settings_keys` names the ones `session.set`
/// really changes on a `shared` session; the rest stay what the terminal set
/// and an app draws them as values (A17).
///
/// The raw value is the word the wire uses, so the list is compared against
/// what a device sends without a translation table in between.
public enum SharedSetting: String, Sendable, Hashable, CaseIterable {
    case model
    case permissionMode = "permission_mode"
    case effort
    case speed
}
