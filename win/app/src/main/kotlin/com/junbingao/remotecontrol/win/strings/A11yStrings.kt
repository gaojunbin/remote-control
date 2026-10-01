// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.win.standin.InterfaceLanguage

/** The `a11y` group of the web's string table. */
class A11yStrings(
    val statusDot: (String) -> String,
    val openMenu: String,
    val closeDialog: String,
    val toolRow: String,
    val expandRow: String,
    val collapseRow: String,
) {
    companion object {
        val en = A11yStrings(
            statusDot = { state -> "Status: $state" },
            openMenu = "Open menu",
            closeDialog = "Close dialog",
            toolRow = "Tool call",
            expandRow = "Expand row",
            collapseRow = "Collapse row",
        )

        val zhHans = A11yStrings(
            statusDot = { state -> "状态：$state" },
            openMenu = "打开菜单",
            closeDialog = "关闭对话框",
            toolRow = "工具调用",
            expandRow = "展开行",
            collapseRow = "收起行",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): A11yStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
