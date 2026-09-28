import Foundation
import RCCore

/// The composer's Mac-only words. Where the web names the browser, the Mac
/// names the Mac (`docs/DESIGN.md` § "The Mac app" → **The Mac's own words**).
public struct MacComposerStrings: Sendable {
    /// `voice.unsupported` on the Mac: no input device to capture from.
    public let voiceUnsupported: String
}

extension MacComposerStrings {
    static let en = MacComposerStrings(
        voiceUnsupported: "This Mac cannot capture audio."
    )

    static let zhHans = MacComposerStrings(
        voiceUnsupported: "这台 Mac 无法采集音频。"
    )

    public static func of(_ language: InterfaceLanguage) -> MacComposerStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}

extension S {
    /// The composer's Mac-only words, read like every other group.
    public static var macComposer: MacComposerStrings { .of(InterfaceLanguageSource.shared.current) }
}
