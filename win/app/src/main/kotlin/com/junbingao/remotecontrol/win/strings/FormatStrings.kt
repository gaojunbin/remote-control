// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/** Relative times and durations. Numbers stay; only the words move. */
class FormatStrings(
    /** Passed to `toLocaleDateString` for dates older than a week. */
    val dateLocale: String,
    val now: String,
    val minutes: (Int) -> String,
    val hours: (Int) -> String,
    val days: (Int) -> String,
    val justNow: String,
    val minutesAgo: (Int) -> String,
    val hoursAgo: (Int) -> String,
    val yesterday: String,
    val daysAgo: (Int) -> String,
    val millis: (Int) -> String,
    val seconds: (String) -> String,
    val minutesSeconds: (Int, Int) -> String,
    val hoursMinutes: (Int, Int) -> String,
) {
    companion object {
        val en = FormatStrings(
            dateLocale = "en",
            now = "now",
            minutes = { n -> "${n}m" },
            hours = { n -> "${n}h" },
            days = { n -> "${n}d" },
            justNow = "just now",
            minutesAgo = { n -> "${n}m ago" },
            hoursAgo = { n -> "${n}h ago" },
            yesterday = "yesterday",
            daysAgo = { n -> "${n}d ago" },
            millis = { n -> "${n}ms" },
            seconds = { s -> "${s}s" },
            minutesSeconds = { m, s -> "${m}m ${s}s" },
            hoursMinutes = { h, m -> "${h}h ${m}m" },
        )

        val zhHans = FormatStrings(
            dateLocale = "zh-Hans",
            now = "刚刚",
            minutes = { n -> "$n 分钟" },
            hours = { n -> "$n 小时" },
            days = { n -> "$n 天" },
            justNow = "刚刚",
            minutesAgo = { n -> "$n 分钟前" },
            hoursAgo = { n -> "$n 小时前" },
            yesterday = "昨天",
            daysAgo = { n -> "$n 天前" },
            millis = { n -> "$n 毫秒" },
            seconds = { s -> "$s 秒" },
            minutesSeconds = { m, s -> "$m 分 $s 秒" },
            hoursMinutes = { h, m -> "$h 小时 $m 分" },
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): FormatStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
