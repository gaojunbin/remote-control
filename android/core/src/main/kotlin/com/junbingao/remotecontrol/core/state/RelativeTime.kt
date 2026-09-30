package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.formatted
import com.junbingao.remotecontrol.core.swiftRounded
import java.time.Instant
import kotlin.math.max

/** Compact relative time for list rows: "4m", "3h", "yesterday". From RCCore's `SessionStore.swift`. */
object RelativeTime {
    fun short(since: Long, now: Instant = Instant.now()): String {
        if (since <= 0) return ""
        val seconds = now.secondsSince1970 - since.toDouble() / 1000
        if (seconds < 45) return L10n.string("now")
        if (seconds < 3600) return L10n.string("%lldm", (seconds / 60).toInt())
        if (seconds < 86_400) return L10n.string("%lldh", (seconds / 3600).toInt())
        if (seconds < 172_800) return L10n.string("yesterday")
        return L10n.string("%lldd", (seconds / 86_400).toInt())
    }

    /** "6.4s", "1m 12s" for durations reported in milliseconds. */
    fun duration(milliseconds: Int): String {
        val seconds = milliseconds.toDouble() / 1000
        if (seconds < 10) return L10n.string("%.1fs", seconds)
        if (seconds < 60) return L10n.string("%llds", seconds.swiftRounded())
        return L10n.string("%lldm %llds", seconds.toInt() / 60, seconds.toInt() % 60)
    }

    /** "48.2k" for a token count. */
    fun compactCount(value: Int): String {
        if (value < 1000) return "$value"
        if (value < 1_000_000) return formatted("%.1fk", value.toDouble() / 1000)
        return formatted("%.1fM", value.toDouble() / 1_000_000)
    }

    /** "9:47" countdown for a pairing code. */
    fun countdown(to: Long, now: Instant = Instant.now()): String {
        val remaining = max(0.0, to.toDouble() / 1000 - now.secondsSince1970)
        return formatted("%d:%02d", remaining.toInt() / 60, remaining.toInt() % 60)
    }

    /** `Date.timeIntervalSince1970`. */
    private val Instant.secondsSince1970: Double get() = epochSecond + nano / 1_000_000_000.0
}
