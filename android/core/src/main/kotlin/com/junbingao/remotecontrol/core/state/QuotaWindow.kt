package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AgentLimit
import com.junbingao.remotecontrol.core.swiftRounded
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Amendment A33: one rate-limit window, worded for a meter row.
 *
 * `docs/DESIGN.md` § "Quota is a meter, drawn for accounts only": the window's name with its scope
 * after it, the share used, and when it resets in the reader's own words.
 *
 * RCCore reads the reader's clock from a `Calendar`; here it is the zone, because the calendar is
 * always the Gregorian one.
 */
object QuotaWindow {
    /** How full a meter is allowed to look before it stops being the ink colour. The bands are the page's only colour rule. */
    enum class Band { normal, warning, danger }

    fun band(usedPercent: Double): Band {
        if (usedPercent >= 100) return Band.danger
        if (usedPercent > 80) return Band.warning
        return Band.normal
    }

    /** The share of the bar that is filled, clamped to what a bar can draw. */
    fun fill(usedPercent: Double): Double {
        if (!usedPercent.isFinite()) return 0.0
        return min(1.0, max(0.0, usedPercent / 100))
    }

    fun percentage(usedPercent: Double): String {
        if (!usedPercent.isFinite()) return L10n.string("%lld%%", 0)
        return L10n.string("%lld%%", min(100.0, max(0.0, usedPercent)).swiftRounded())
    }

    /** The window's name: a length in the unit it divides into, with the scope after it where the vendor confined it to one. */
    fun name(limit: AgentLimit): String {
        val length = length(minutes = limit.windowMinutes)
        val scope = limit.scope
        if (scope.isNullOrEmpty()) return length
        return length + AccountLine.separator + scope
    }

    /** 300 → "5-hour", 1440 → "24-hour", 10080 → "7-day". A window that is not whole hours is named in minutes rather than rounded into a lie. */
    fun length(minutes: Int): String {
        if (minutes <= 0) return L10n.string("%lld-minute", 0)
        // A day is the longest unit an hour still reads well in: a weekly window is seven days, and
        // the daily one is the vendor's own 24 hours.
        if (minutes % 1440 == 0 && minutes > 1440) return L10n.string("%lld-day", minutes / 1440)
        if (minutes % 60 == 0) return L10n.string("%lld-hour", minutes / 60)
        return L10n.string("%lld-minute", minutes)
    }

    /** "resets 15:40" for a window that resets today, "resets Tue 22:00" for one that does not. The clock is the reader's, not the device's. */
    fun resets(at: Long, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(),
               locale: Locale = Locale.getDefault()): String =
        L10n.string("resets %@", clock(at = at, now = now, zone = zone, locale = locale))

    internal fun clock(at: Long, now: Instant, zone: ZoneId, locale: Locale): String {
        val date = Instant.ofEpochMilli(at).atZone(zone)
        val today = date.toLocalDate() == now.atZone(zone).toLocalDate()
        return DateTimeFormatter.ofPattern(if (today) "HH:mm" else "EEE HH:mm", locale).format(date)
    }
}
