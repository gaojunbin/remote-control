// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// Word lists the helpers below read. Unknown ids fall back to the id itself.
public struct LabelsStrings: Sendable {
    public let state: [String: String]
    /// The tooltip on a session dot. It names the tone `dotTone` picked, while
    /// the dot's accessibility label stays the raw state. The table is in
    /// `docs/DESIGN.md`.
    public let dotTone: [String: String]
    /// The two timeline detail levels, in the order Settings offers them.
    public let timelineDetail: TimelineDetailLabels
    /// A24: what an account is and whether it may sign in.
    public let role: [String: String]
    public let userState: [String: String]
    /// Where a session came from, which is what its row says beside the dot.
    /// One word for both apps: which of them pressed New session is nobody's
    /// business afterwards.
    public let origin: [String: String]
}

extension LabelsStrings {
    static let en = LabelsStrings(
        state: [
            "starting": "starting",
            "idle": "idle",
            "running": "running",
            "needs_approval": "needs approval",
            "needs_input": "needs input",
            "error": "error",
            "stopped": "stopped",
            "readonly": "terminal"
        ],
        dotTone: [
            "working": "Working",
            "waiting": "Waiting for you",
            "live": "Done",
            "off": "Not running",
            "failed": "Error"
        ],
        timelineDetail: TimelineDetailLabels(simple: "Simple", detailed: "Detailed"),
        role: [
            "admin": "Admin",
            "member": "Member"
        ],
        userState: [
            "active": "Active",
            "disabled": "Disabled"
        ],
        origin: [
            "terminal": "Terminal",
            "remote": "Remote Control"
        ]
    )

    static let zhHans = LabelsStrings(
        state: [
            "starting": "启动中",
            "idle": "空闲",
            "running": "运行中",
            "needs_approval": "待批准",
            "needs_input": "待回答",
            "error": "错误",
            "stopped": "已停止",
            "readonly": "终端"
        ],
        dotTone: [
            "working": "工作中",
            "waiting": "等待你",
            "live": "已完成",
            "off": "未运行",
            "failed": "出错"
        ],
        timelineDetail: TimelineDetailLabels(simple: "简约", detailed: "详细"),
        role: [
            "admin": "管理员",
            "member": "成员"
        ],
        userState: [
            "active": "正常",
            "disabled": "已禁用"
        ],
        origin: [
            "terminal": "终端",
            "remote": "远程启动"
        ]
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> LabelsStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
