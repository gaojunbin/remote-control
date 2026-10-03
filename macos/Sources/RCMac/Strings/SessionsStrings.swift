// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

import Foundation
import RCCore

/// The `sessions` group of the web's string table.
public struct SessionsStrings: Sendable {
    public let title: String
    public let new: String
    public let searchPlaceholder: String
    public let empty: String
    public let emptyHint: String
    public let noMatches: String
    public let allDevices: String
    public let allAgents: String
    public let agentFilter: String
    public let archiveGroup: @Sendable (Int) -> String
    public let archived: String
    /// A39: the row action on a session the device drives. It ends the session
    /// on the machine and the row lands in the Archive afterwards, so the word
    /// is Close, not Archive. The question is asked only while the agent is
    /// working, because an idle session has nothing to lose.
    public let close: String
    public let closeTitle: String
    public let closeBody: String
    public let open: String
    public let untitled: String
    /// A47: what a screen reader hears on a row carrying the red dot of a
    /// session that stopped working and waits for the person.
    public let unseen: String
    /// The dot legend, drawn once above the list (`docs/DESIGN.md` § "A legend,
    /// once, and quiet"). Four entries, not five: the pulsing amber and the
    /// solid amber are one colour to the eye, and "For you" covers both a
    /// question waiting and a finished turn to look at.
    public let legend: String
    public let legendWorking: String
    public let legendAttention: String
    public let legendOff: String
    public let legendFailed: String
}

extension SessionsStrings {
    static let en = SessionsStrings(
        title: "Sessions",
        new: "New session",
        searchPlaceholder: "Search sessions",
        empty: "No sessions yet.",
        emptyHint: "Start one from a paired device, or open a terminal session on the machine.",
        noMatches: "Nothing matches that search.",
        allDevices: "All devices",
        allAgents: "All agents",
        agentFilter: "Filter by agent",
        archiveGroup: { n in "Archive · \(n)" },
        archived: "Archived",
        close: "Close",
        closeTitle: "Close this session?",
        closeBody: "The agent is still working; what it has not finished is lost.",
        open: "Open session",
        untitled: "Untitled session",
        unseen: "not yet opened",
        legend: "What the dots mean",
        legendWorking: "Working",
        legendAttention: "For you",
        legendOff: "Not running",
        legendFailed: "Error"
    )

    static let zhHans = SessionsStrings(
        title: "会话",
        new: "新建会话",
        searchPlaceholder: "搜索会话",
        empty: "还没有会话。",
        emptyHint: "从已配对的设备上新建一个，或在机器上打开一个终端会话。",
        noMatches: "没有匹配的结果。",
        allDevices: "全部设备",
        allAgents: "全部 agent",
        agentFilter: "按 agent 筛选",
        archiveGroup: { n in "归档 · \(n)" },
        archived: "已归档",
        close: "关闭",
        closeTitle: "关闭此会话？",
        closeBody: "代理仍在工作，未完成的部分会丢失。",
        open: "打开会话",
        untitled: "未命名会话",
        unseen: "未查看",
        legend: "圆点含义",
        legendWorking: "运行中",
        legendAttention: "等你处理",
        legendOff: "未运行",
        legendFailed: "出错"
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> SessionsStrings {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}
