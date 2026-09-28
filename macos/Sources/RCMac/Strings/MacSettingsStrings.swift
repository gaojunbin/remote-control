import Foundation
import RCCore

/// The settings feature's own Mac words: the ones the web never needs because
/// a browser the gateway refuses is signed out before it can draw them.
public struct MacSettingsStrings: Sendable {
    /// `docs/DESIGN.md` § "The Settings screen": the header dot's word while
    /// the gateway refuses this app's connection — replaced by another app, or
    /// speaking a protocol this build does not. Read aloud and shown on hover.
    public let refused: String
}

extension MacSettingsStrings {
    static let en = MacSettingsStrings(refused: "Refused")

    static let zhHans = MacSettingsStrings(refused: "已拒绝")

    public static func of(_ language: InterfaceLanguage) -> MacSettingsStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}

extension S {
    /// The settings feature's Mac-only words, read like every other group.
    public static var macSettings: MacSettingsStrings { .of(InterfaceLanguageSource.shared.current) }
}
