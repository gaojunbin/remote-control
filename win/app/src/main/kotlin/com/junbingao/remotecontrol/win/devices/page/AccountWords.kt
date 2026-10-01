package com.junbingao.remotecontrol.win.devices.page

import com.junbingao.remotecontrol.core.protocol.AccountMethod
import com.junbingao.remotecontrol.core.protocol.AgentAccount
import com.junbingao.remotecontrol.core.protocol.AgentLimit
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `web/src/features/devices/accounts.ts`: the words a device page puts on an account and its quota
 * windows (A33; `docs/DESIGN.md` § "A device has a page" and § "Quota is a meter").
 *
 * Pure functions of what the device reported. The vendor's name comes from `S.vendorLabels`; the
 * plan word, the tier, the email and the third-party host are printed exactly as they arrived, in
 * both interface languages, because they are data rather than the app's own words.
 */
object AccountWords {
    /**
     * The band a meter's fill is drawn in: the ink colour up to 80 % of the window, the warning
     * colour past it, the danger colour once it is spent.
     */
    enum class MeterTone { ink, warn, danger }

    /**
     * The one line under an agent's name. Both methods lead with the vendor: "Anthropic account ·
     * Max · Max 5x · me@example.com", "Anthropic API key", or "OpenAI API key · api.relay.example".
     * Nothing is drawn for what the device did not report.
     */
    fun signInLine(account: AgentAccount): String {
        val vendor = S.vendorLabel(account.provider)
        if (account.method == AccountMethod.apiKey) {
            val key = S.devicePage.apiKeyOf(vendor)
            val endpoint = account.endpoint
            return if (endpoint.isNullOrEmpty()) key else "$key · $endpoint"
        }
        val parts = mutableListOf(S.devicePage.accountOf(vendor))
        account.plan?.takeIf { it.isNotEmpty() }?.let { parts += planWord(it) }
        account.tier?.takeIf { it.isNotEmpty() }?.let { parts += it }
        account.email?.takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /** The window's name, with what it is confined to after it: "7-day · Fable". */
    fun windowName(limit: AgentLimit): String {
        val length = windowLength(limit.windowMinutes)
        val scope = limit.scope
        return if (scope.isNullOrEmpty()) length else "$length · $scope"
    }

    fun meterTone(usedPercent: Double): MeterTone {
        if (usedPercent >= 100) return MeterTone.danger
        if (usedPercent > 80) return MeterTone.warn
        return MeterTone.ink
    }

    /** The share of the window that is drawn and printed, clamped to the meter. */
    fun usedPercent(limit: AgentLimit): Int = Format.jsRound(limit.usedPercent).coerceIn(0.0, 100.0).toInt()

    /**
     * When the window resets, in the reader's own words: "resets 15:40" for a reset later today,
     * "resets Tue 22:00" for one on another day.
     */
    fun resetsText(resetsAt: Long, now: Long = Format.nowMillis): String {
        val zone = ZoneId.systemDefault()
        val locale = Locale.forLanguageTag(S.format.dateLocale)
        val time = Instant.ofEpochMilli(resetsAt).atZone(zone)
        val clock = DateTimeFormatter.ofPattern("HH:mm", locale).format(time)
        if (time.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) return S.devicePage.resets(clock)
        val weekday = DateTimeFormatter.ofPattern("EEE", locale).format(time)
        return S.devicePage.resets("$weekday $clock")
    }

    /** The plan reads as a word rather than as an id: `max` is drawn "Max". */
    private fun planWord(plan: String): String {
        val first = plan.codePointAt(0)
        val length = Character.charCount(first)
        return plan.substring(0, length).uppercase() + plan.substring(length)
    }

    /**
     * A window's length in the reader's units: 300 minutes is "5-hour", 1440 is "24-hour", 10080 is
     * "7-day". A day is only reached above a day's worth of hours, so a window of exactly 24 hours
     * stays an hour window.
     */
    private fun windowLength(minutes: Int): String {
        if (minutes % 60 != 0) return S.devicePage.windowMinutes(minutes)
        val hours = minutes / 60
        if (hours > 24 && minutes % 1440 == 0) return S.devicePage.windowDays(minutes / 1440)
        return S.devicePage.windowHours(hours)
    }
}
