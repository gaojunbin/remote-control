// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/** The `nav` group of the web's string table. */
class NavStrings(
    val devices: String,
    val sessions: String,
    val settings: String,
    val backToSessions: String,
    val primary: String,
) {
    companion object {
        val en = NavStrings(
            devices = "Devices",
            sessions = "Sessions",
            settings = "Settings",
            backToSessions = "Back to sessions",
            primary = "Primary",
        )

        val zhHans = NavStrings(
            devices = "设备",
            sessions = "会话",
            settings = "设置",
            backToSessions = "返回会话",
            primary = "主导航",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): NavStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
