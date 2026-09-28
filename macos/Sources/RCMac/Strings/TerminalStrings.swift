// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// A38: the shell on a device. Nothing the shell prints is ever translated —
/// it is bytes the emulator draws, not words this app owns.
public struct TerminalStrings: Sendable {
    public let title: String
    public let back: String
    public let connecting: String
    public let connected: String
    public let disconnected: String
    public let reconnect: String
    public let exited: String
    public let exitedCode: @Sendable (Int) -> String
    public let newShell: String
    /// §7.3: `seq` rises by one, so a jump is lost output and not a pause.
    public let gap: String
    public let gone: String
}

extension TerminalStrings {
    static let en = TerminalStrings(
        title: "Terminal",
        back: "Devices",
        connecting: "Connecting",
        connected: "Connected",
        disconnected: "Disconnected",
        reconnect: "Reconnect",
        exited: "Shell exited",
        exitedCode: { code in "Shell exited (\(code))" },
        newShell: "New shell",
        gap: "Some output was lost.",
        gone: "This device is no longer here."
    )

    static let zhHans = TerminalStrings(
        title: "终端",
        back: "设备",
        connecting: "连接中",
        connected: "已连接",
        disconnected: "已断开",
        reconnect: "重新连接",
        exited: "Shell 已退出",
        exitedCode: { code in "Shell 已退出（\(code)）" },
        newShell: "新建 Shell",
        gap: "部分输出已丢失。",
        gone: "该设备已不在这里。"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> TerminalStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
