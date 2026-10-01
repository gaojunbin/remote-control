package com.junbingao.remotecontrol.win.shared

import com.junbingao.remotecontrol.win.strings.S
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * `web/src/lib/format.ts`: display formatters. The ones that carry words read them from the
 * interface language's table, so "3m ago" becomes "3 分钟前"; every number, clock and unit of
 * storage is written the same way in both languages. Times are epoch milliseconds, as the wire
 * carries them.
 */
object Format {
    private const val MINUTE: Long = 60_000
    private const val HOUR: Long = 60 * MINUTE
    private const val DAY: Long = 24 * HOUR

    /** Now, in the milliseconds every timestamp on the wire is written in. */
    val nowMillis: Long get() = System.currentTimeMillis()

    /**
     * "Sep 28" / "9月28日": what a date older than a week reads — the month and the day as the
     * date locale writes them (`toLocaleDateString(dateLocale, { month: 'short', day: 'numeric' })`),
     * in the local time zone as a browser writes it.
     */
    internal fun shortDate(ts: Long): String {
        val chinese = S.format.dateLocale.startsWith("zh")
        val pattern = if (chinese) "M月d日" else "MMM d"
        val locale = if (chinese) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ts))
    }

    /** Compact relative time as used in session rows: "4m", "3h", "2d", "now". */
    fun relativeTime(ts: Long, now: Long = nowMillis): String {
        val delta = max(0, now - ts)
        if (delta < 45_000) return S.format.now
        if (delta < HOUR) return S.format.minutes(rounded(delta, MINUTE))
        if (delta < DAY) return S.format.hours(rounded(delta, HOUR))
        if (delta < 7 * DAY) return S.format.days(rounded(delta, DAY))
        return shortDate(ts)
    }

    /** Longer relative form used for "recent directories": "2h ago", "yesterday". */
    fun relativeAgo(ts: Long, now: Long = nowMillis): String {
        val delta = max(0, now - ts)
        if (delta < MINUTE) return S.format.justNow
        if (delta < HOUR) return S.format.minutesAgo(rounded(delta, MINUTE))
        if (delta < DAY) return S.format.hoursAgo(rounded(delta, HOUR))
        if (delta < 2 * DAY) return S.format.yesterday
        if (delta < 7 * DAY) return S.format.daysAgo(rounded(delta, DAY))
        return shortDate(ts)
    }

    /** "6.4s", "1m 12s", "820ms" — durations inside tool rows and turn timers. */
    fun duration(ms: Double): String {
        if (!ms.isFinite() || ms < 0) return ""
        if (ms < 1000) return S.format.millis(jsRound(ms).toInt())
        val seconds = ms / 1000
        if (seconds < 60) {
            return S.format.seconds(if (seconds < 10) toFixed(seconds, 1) else jsRound(seconds).toInt().toString())
        }
        val m = floor(seconds / 60).toInt()
        val s = jsRound(seconds.rem(60)).toInt()
        if (m < 60) return S.format.minutesSeconds(m, s)
        return S.format.hoursMinutes(m / 60, m % 60)
    }

    /** "0:12" / "9:47" clock used by pairing countdowns and the voice timer. */
    fun clock(ms: Double): String {
        val total = max(0, jsRound(ms / 1000).toInt())
        return "${total / 60}:${String.format(Locale.ROOT, "%02d", total % 60)}"
    }

    /** "48.2k" token counts. */
    fun compactNumber(n: Double): String {
        if (!n.isFinite()) return "0"
        if (n < 1000) return jsRound(n).toInt().toString()
        if (n < 1_000_000) {
            val k = n / 1000
            return "${if (k < 100) toFixed(k, 1) else jsRound(k).toInt().toString()}k"
        }
        return "${toFixed(n / 1_000_000, 1)}M"
    }

    fun bytes(n: Int): String {
        if (n < 1024) return "$n B"
        if (n < 1024 * 1024) return "${toFixed(n.toDouble() / 1024, 0)} KiB"
        return "${toFixed(n.toDouble() / (1024 * 1024), 1)} MiB"
    }

    /** Collapse the home directory to `~` the way the prototype shows paths. */
    fun tildePath(path: String, home: String? = null): String {
        if (!home.isNullOrEmpty() && path.startsWith(home)) return "~" + path.substring(home.length)
        val match = Regex("^/(?:home|Users)/[^/]+").find(path) ?: return path
        return "~" + path.substring(match.range.last + 1)
    }

    /** Last path segment, used as the short cwd label in the sidebar. */
    fun baseName(path: String): String {
        // `(.)\/+$` → `$1`: trailing slashes go, but a lone "/" stays.
        var trimmed = path
        while (trimmed.length > 1 && trimmed.endsWith("/")) trimmed = trimmed.dropLast(1)
        val slash = trimmed.lastIndexOf('/')
        if (slash < 0) return trimmed
        val tail = trimmed.substring(slash + 1)
        return tail.ifEmpty { "/" }
    }

    fun latency(ms: Double?): String {
        if (ms == null || !ms.isFinite()) return "—"
        return "${jsRound(ms).toInt()} ms"
    }

    /** A text cut to a line budget: the head, whether anything was folded away, and how many lines there were. */
    data class Folded(val head: String, val folded: Boolean, val total: Int)

    /** Truncate to a line budget, returning the head and whether it was folded. */
    fun foldLines(text: String, maxLines: Int): Folded {
        val lines = text.split("\n")
        if (lines.size <= maxLines) return Folded(text, false, lines.size)
        return Folded(lines.take(maxLines).joinToString("\n"), true, lines.size)
    }

    // JavaScript's rounding

    /** `Math.round`: halves go up, towards positive infinity. */
    internal fun jsRound(value: Double): Double = floor(value + 0.5)

    private fun rounded(delta: Long, unit: Long): Int = jsRound(delta.toDouble() / unit.toDouble()).toInt()

    /** `Number.prototype.toFixed`: a tie goes to the larger of the two, where `%.1f` would round it to even. */
    internal fun toFixed(value: Double, digits: Int): String {
        val scale = 10.0.pow(digits)
        val scaled = BigDecimal(value * scale).setScale(0, RoundingMode.HALF_UP).toDouble() / scale
        return String.format(Locale.ROOT, "%.${digits}f", scaled)
    }
}
