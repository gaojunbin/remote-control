// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/** The `connection` group of the web's string table. */
class ConnectionStrings(
    val reconnecting: String,
    val offline: String,
    val restored: String,
) {
    companion object {
        val en = ConnectionStrings(
            reconnecting = "Reconnecting…",
            offline = "Connection lost. Reconnecting…",
            restored = "Reconnected",
        )

        val zhHans = ConnectionStrings(
            reconnecting = "重新连接中…",
            offline = "连接已断开。正在重新连接…",
            restored = "已重新连接",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): ConnectionStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
