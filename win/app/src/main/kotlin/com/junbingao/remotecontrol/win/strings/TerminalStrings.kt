// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.win.standin.InterfaceLanguage

/**
 * A38: the shell on a device. Nothing the shell prints is ever translated —
 * it is bytes the emulator draws, not words this app owns.
 */
class TerminalStrings(
    val title: String,
    val back: String,
    val connecting: String,
    val connected: String,
    val disconnected: String,
    val reconnect: String,
    val exited: String,
    val exitedCode: (Int) -> String,
    val newShell: String,
    /** §7.3: `seq` rises by one, so a jump is lost output and not a pause. */
    val gap: String,
    val gone: String,
) {
    companion object {
        val en = TerminalStrings(
            title = "Terminal",
            back = "Devices",
            connecting = "Connecting",
            connected = "Connected",
            disconnected = "Disconnected",
            reconnect = "Reconnect",
            exited = "Shell exited",
            exitedCode = { code -> "Shell exited ($code)" },
            newShell = "New shell",
            gap = "Some output was lost.",
            gone = "This device is no longer here.",
        )

        val zhHans = TerminalStrings(
            title = "终端",
            back = "设备",
            connecting = "连接中",
            connected = "已连接",
            disconnected = "已断开",
            reconnect = "重新连接",
            exited = "Shell 已退出",
            exitedCode = { code -> "Shell 已退出（$code）" },
            newShell = "新建 Shell",
            gap = "部分输出已丢失。",
            gone = "该设备已不在这里。",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): TerminalStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
