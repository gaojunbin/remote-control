// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// The `a11y` group of the web's string table.
public struct A11yStrings: Sendable {
    public let statusDot: @Sendable (String) -> String
    public let openMenu: String
    public let closeDialog: String
    public let toolRow: String
    public let expandRow: String
    public let collapseRow: String
}

extension A11yStrings {
    static let en = A11yStrings(
        statusDot: { state in "Status: \(state)" },
        openMenu: "Open menu",
        closeDialog: "Close dialog",
        toolRow: "Tool call",
        expandRow: "Expand row",
        collapseRow: "Collapse row"
    )

    static let zhHans = A11yStrings(
        statusDot: { state in "状态：\(state)" },
        openMenu: "打开菜单",
        closeDialog: "关闭对话框",
        toolRow: "工具调用",
        expandRow: "展开行",
        collapseRow: "收起行"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> A11yStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
