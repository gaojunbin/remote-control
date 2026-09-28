// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// The `connection` group of the web's string table.
public struct ConnectionStrings: Sendable {
    public let reconnecting: String
    public let offline: String
    public let restored: String
}

extension ConnectionStrings {
    static let en = ConnectionStrings(
        reconnecting: "Reconnecting…",
        offline: "Connection lost. Reconnecting…",
        restored: "Reconnected"
    )

    static let zhHans = ConnectionStrings(
        reconnecting: "重新连接中…",
        offline: "连接已断开。正在重新连接…",
        restored: "已重新连接"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> ConnectionStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
