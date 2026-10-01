// Ported from web/src/strings.ts and web/src/strings.zh-Hans.ts. Keep the two
// tables of this file in step with the web's: the English is the source, and
// the Chinese is the web's own translation, copied rather than re-translated.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.win.standin.InterfaceLanguage

/**
 * A33: the page one device has. Vendor names, plan words, tiers, emails and
 * hosts are what the device reported and are never translated.
 */
class DevicePageStrings(
    val back: String,
    val gone: String,
    val goneHint: String,
    val refresh: String,
    val accountOf: (String) -> String,
    val apiKeyOf: (String) -> String,
    val notSignedIn: String,
    val checking: String,
    val offlineQuota: String,
    val windowHours: (Int) -> String,
    val windowDays: (Int) -> String,
    val windowMinutes: (Int) -> String,
    val percent: (Int) -> String,
    val resets: (String) -> String,
    val usage: (String) -> String,
) {
    companion object {
        val en = DevicePageStrings(
            back = "Devices",
            gone = "This device is no longer here.",
            goneHint = "It was revoked, or it never reached this gateway.",
            refresh = "Refresh",
            accountOf = { vendor -> "$vendor account" },
            apiKeyOf = { vendor -> "$vendor API key" },
            notSignedIn = "Not signed in",
            checking = "Checking…",
            offlineQuota = "Offline · quota unavailable",
            windowHours = { n -> "$n-hour" },
            windowDays = { n -> "$n-day" },
            windowMinutes = { n -> "$n-minute" },
            percent = { n -> "$n%" },
            resets = { `when` -> "resets ${`when`}" },
            usage = { window -> "$window usage" },
        )

        val zhHans = DevicePageStrings(
            back = "设备",
            gone = "该设备已不在这里。",
            goneHint = "它已被吊销，或从未连接到本网关。",
            refresh = "刷新",
            accountOf = { vendor -> "$vendor 账户" },
            apiKeyOf = { vendor -> "$vendor API key" },
            notSignedIn = "未登录",
            checking = "检查中…",
            offlineQuota = "离线 · 无法获取配额",
            windowHours = { n -> "$n 小时" },
            windowDays = { n -> "$n 天" },
            windowMinutes = { n -> "$n 分钟" },
            percent = { n -> "$n%" },
            resets = { `when` -> "${`when`} 重置" },
            usage = { window -> "${window}用量" },
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): DevicePageStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
