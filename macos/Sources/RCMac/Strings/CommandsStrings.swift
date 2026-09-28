// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// A27: the terminal's `/` menu, above the composer.
public struct CommandsStrings: Sendable {
    public let menu: String
    /// What a screen reader reads on one row.
    public let rowLabel: @Sendable (String, String) -> String
    /// The one footer line while a turn is running, and the refusal inline.
    public let whileRunning: String
    public let failed: String
}

extension CommandsStrings {
    static let en = CommandsStrings(
        menu: "Commands",
        rowLabel: { name, description in "Command, /\(name), \(description)" },
        whileRunning: "Available when the turn finishes",
        failed: "Could not run the command."
    )

    static let zhHans = CommandsStrings(
        menu: "命令",
        rowLabel: { name, description in "命令，/\(name)，\(description)" },
        whileRunning: "当前回合结束后可用",
        failed: "无法执行该命令。"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> CommandsStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
