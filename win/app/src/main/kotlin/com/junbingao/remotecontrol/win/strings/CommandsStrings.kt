// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.win.standin.InterfaceLanguage

/** A27: the terminal's `/` menu, above the composer. */
class CommandsStrings(
    val menu: String,
    /** What a screen reader reads on one row. */
    val rowLabel: (String, String) -> String,
    /** The one footer line while a turn is running, and the refusal inline. */
    val whileRunning: String,
    val failed: String,
) {
    companion object {
        val en = CommandsStrings(
            menu = "Commands",
            rowLabel = { name, description -> "Command, /$name, $description" },
            whileRunning = "Available when the turn finishes",
            failed = "Could not run the command.",
        )

        val zhHans = CommandsStrings(
            menu = "命令",
            rowLabel = { name, description -> "命令，/$name，$description" },
            whileRunning = "当前回合结束后可用",
            failed = "无法执行该命令。",
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): CommandsStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
