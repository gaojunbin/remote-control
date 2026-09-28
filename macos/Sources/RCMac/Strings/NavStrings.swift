// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// The `nav` group of the web's string table.
public struct NavStrings: Sendable {
    public let devices: String
    public let sessions: String
    public let settings: String
    public let backToSessions: String
    public let primary: String
}

extension NavStrings {
    static let en = NavStrings(
        devices: "Devices",
        sessions: "Sessions",
        settings: "Settings",
        backToSessions: "Back to sessions",
        primary: "Primary"
    )

    static let zhHans = NavStrings(
        devices: "设备",
        sessions: "会话",
        settings: "设置",
        backToSessions: "返回会话",
        primary: "主导航"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> NavStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
