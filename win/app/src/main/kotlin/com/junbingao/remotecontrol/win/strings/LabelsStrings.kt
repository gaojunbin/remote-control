// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/** Word lists the helpers below read. Unknown ids fall back to the id itself. */
class LabelsStrings(
    val state: Map<String, String>,
    /**
     * The tooltip on a session dot. It names the tone `dotTone` picked, while
     * the dot's accessibility label stays the raw state. The table is in
     * `docs/DESIGN.md`.
     */
    val dotTone: Map<String, String>,
    /** The two timeline detail levels, in the order Settings offers them. */
    val timelineDetail: TimelineDetailLabels,
    /** A24: what an account is and whether it may sign in. */
    val role: Map<String, String>,
    val userState: Map<String, String>,
    /**
     * Where a session came from, which is what its row says beside the dot.
     * One word for both apps: which of them pressed New session is nobody's
     * business afterwards.
     */
    val origin: Map<String, String>,
) {
    companion object {
        val en = LabelsStrings(
            state = mapOf(
                "starting" to "starting",
                "idle" to "idle",
                "running" to "running",
                "needs_approval" to "needs approval",
                "needs_input" to "needs input",
                "error" to "error",
                "stopped" to "stopped",
                "readonly" to "terminal"
            ),
            dotTone = mapOf(
                "working" to "Working",
                "waiting" to "Waiting for you",
                "live" to "Done",
                "off" to "Not running",
                "failed" to "Error"
            ),
            timelineDetail = TimelineDetailLabels(simple = "Simple", detailed = "Detailed"),
            role = mapOf(
                "admin" to "Admin",
                "member" to "Member"
            ),
            userState = mapOf(
                "active" to "Active",
                "disabled" to "Disabled"
            ),
            origin = mapOf(
                "terminal" to "Terminal",
                "remote" to "Remote Control"
            ),
        )

        val zhHans = LabelsStrings(
            state = mapOf(
                "starting" to "启动中",
                "idle" to "空闲",
                "running" to "运行中",
                "needs_approval" to "待批准",
                "needs_input" to "待回答",
                "error" to "错误",
                "stopped" to "已停止",
                "readonly" to "终端"
            ),
            dotTone = mapOf(
                "working" to "工作中",
                "waiting" to "等待你",
                "live" to "已完成",
                "off" to "未运行",
                "failed" to "出错"
            ),
            timelineDetail = TimelineDetailLabels(simple = "简约", detailed = "详细"),
            role = mapOf(
                "admin" to "管理员",
                "member" to "成员"
            ),
            userState = mapOf(
                "active" to "正常",
                "disabled" to "已禁用"
            ),
            origin = mapOf(
                "terminal" to "终端",
                "remote" to "远程启动"
            ),
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): LabelsStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
