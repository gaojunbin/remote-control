// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/** The `sessions` group of the web's string table. */
class SessionsStrings(
    val title: String,
    val new: String,
    val searchPlaceholder: String,
    val empty: String,
    val emptyHint: String,
    val noMatches: String,
    val allDevices: String,
    val allAgents: String,
    val agentFilter: String,
    val archiveGroup: (Int) -> String,
    val archived: String,
    /**
     * A39: the row action on a session the device drives. It ends the session
     * on the machine and the row lands in the Archive afterwards, so the word
     * is Close, not Archive. The question is asked only while the agent is
     * working, because an idle session has nothing to lose.
     */
    val close: String,
    val closeTitle: String,
    val closeBody: String,
    val open: String,
    /**
     * A47: what a screen reader hears for the red dot of a session that
     * stopped working and that nobody on the account has opened since.
     */
    val unseen: String,
    val untitled: String,
    /**
     * The dot legend, drawn once above the list (`docs/DESIGN.md` § "A legend,
     * once, and quiet"). Four entries, not five: the pulsing amber and the
     * solid amber are one colour to the eye, and "For you" covers both a
     * question waiting and a finished turn to look at.
     */
    val legend: String,
    val legendWorking: String,
    val legendAttention: String,
    val legendOff: String,
    val legendFailed: String,
) {
    companion object {
        val en = SessionsStrings(
            title = "Sessions",
            new = "New session",
            searchPlaceholder = "Search sessions",
            empty = "No sessions yet.",
            emptyHint = "Start one from a paired device, or open a terminal session on the machine.",
            noMatches = "Nothing matches that search.",
            allDevices = "All devices",
            allAgents = "All agents",
            agentFilter = "Filter by agent",
            archiveGroup = { n -> "Archive · $n" },
            archived = "Archived",
            close = "Close",
            closeTitle = "Close this session?",
            closeBody = "The agent is still working; what it has not finished is lost.",
            open = "Open session",
            unseen = "not yet opened",
            untitled = "Untitled session",
            legend = "What the dots mean",
            legendWorking = "Working",
            legendAttention = "For you",
            legendOff = "Not running",
            legendFailed = "Error",
        )

        val zhHans = SessionsStrings(
            title = "会话",
            new = "新建会话",
            searchPlaceholder = "搜索会话",
            empty = "还没有会话。",
            emptyHint = "从已配对的设备上新建一个，或在机器上打开一个终端会话。",
            noMatches = "没有匹配的结果。",
            allDevices = "全部设备",
            allAgents = "全部 agent",
            agentFilter = "按 agent 筛选",
            archiveGroup = { n -> "归档 · $n" },
            archived = "已归档",
            close = "关闭",
            closeTitle = "关闭此会话？",
            closeBody = "代理仍在工作，未完成的部分会丢失。",
            open = "打开会话",
            unseen = "未查看",
            untitled = "未命名会话",
            legend = "圆点含义",
            legendWorking = "运行中",
            legendAttention = "等你处理",
            legendOff = "未运行",
            legendFailed = "出错",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): SessionsStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
