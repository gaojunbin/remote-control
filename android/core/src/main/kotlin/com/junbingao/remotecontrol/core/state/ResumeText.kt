package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.LimitStop
import com.junbingao.remotecontrol.core.protocol.ResumePayload
import com.junbingao.remotecontrol.core.protocol.ResumeStatus
import com.junbingao.remotecontrol.core.protocol.SessionResume
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Amendment A35 — the words a paused session is described with, in one place so the notice above
 * the transcript, the timeline rows and the checks all read the same sentence (`docs/DESIGN.md`
 * § "Paused by the usage limit").
 *
 * Every time is the viewer's own: the device sends a timestamp and nothing else, so the zone and
 * the clock format come from here. RCCore reads the zone from a `Calendar`; the calendar itself is
 * always the Gregorian one.
 */
object ResumeText {
    /** "3:50 PM", with the date in front of it when the time is not today. */
    fun clock(milliseconds: Long, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(),
              locale: Locale = Locale.getDefault()): String {
        val date = Instant.ofEpochMilli(milliseconds).atZone(zone)
        val today = date.toLocalDate() == now.atZone(zone).toLocalDate()
        return DateTimeFormatter.ofPattern(ClockPattern.of(locale, dated = !today), locale).format(date)
    }

    /**
     * The notice above the transcript: "Paused by the usage limit · resumes 3:50 PM", "about" where
     * the device estimated the time, and the attempt where a resumed turn has already run into the
     * limit again.
     */
    fun notice(resume: SessionResume, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(),
               locale: Locale = Locale.getDefault()): String {
        val time = clock(resume.at, now = now, zone = zone, locale = locale)
        val head = if (resume.estimated) L10n.string("Paused by the usage limit · resumes about %@", time)
                   else L10n.string("Paused by the usage limit · resumes %@", time)
        val attempt = attempt(resume.attempts) ?: return head
        return "$head · $attempt"
    }

    /**
     * The end of a turn the vendor's window stopped. Without a time when the vendor named none,
     * because "resets" with nothing after it says less than the first half alone.
     */
    fun turnEnd(limit: LimitStop, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(),
                locale: Locale = Locale.getDefault()): String {
        val resetsAt = limit.resetsAt ?: return L10n.string("Ended at the usage limit")
        return L10n.string("Ended at the usage limit · resets %@", clock(resetsAt, now = now, zone = zone, locale = locale))
    }

    /**
     * One `resume` event as a timeline row, or null for the one status that draws nothing: the
     * moment of resuming is the prompt in the person's bubble and the turn it starts, not a row of
     * its own.
     */
    fun row(payload: ResumePayload, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(),
            locale: Locale = Locale.getDefault()): String? {
        if (!payload.status.isDrawn) return null
        return when (payload.status) {
            ResumeStatus.scheduled, ResumeStatus.rescheduled -> {
                val time = payload.at?.let { clock(it, now = now, zone = zone, locale = locale) }
                scheduleRow(payload.status, time = time, estimated = payload.estimated)
            }
            ResumeStatus.cancelled -> joined(L10n.string("Resume cancelled"), payload.reason)
            ResumeStatus.dropped -> joined(L10n.string("Not resumed"), payload.reason)
            else -> null
        }
    }

    /** The caption under the one message the device writes for the person. */
    val sentForYou: String get() = L10n.string("Sent for you after the limit reset")

    /**
     * The sentence in the resume row of Settings: what the switch does, or why it cannot be turned
     * on (`docs/DESIGN.md` § "The Settings screen" — a state speaks in the row it belongs to).
     */
    fun settingsSentence(offered: Boolean): String {
        if (!offered) return L10n.string("Your gateway does not offer this yet.")
        return L10n.string(
            "When Claude Code or Codex stops at a usage limit, the device continues the session a minute after the limit resets.")
    }

    /** "second try", "third try", or nothing at all for the first one. */
    private fun attempt(attempts: Int): String? = when (attempts) {
        1 -> L10n.string("second try")
        2 -> L10n.string("third try")
        // The device drops the resume after the third try, so there is no fourth to name and a
        // number nobody expects says nothing.
        else -> null
    }

    private fun scheduleRow(status: ResumeStatus, time: String?, estimated: Boolean): String {
        if (time == null) {
            return if (status == ResumeStatus.rescheduled) L10n.string("Resume moved") else L10n.string("Resume scheduled")
        }
        if (status == ResumeStatus.rescheduled) {
            return if (estimated) L10n.string("Resume moved to about %@", time) else L10n.string("Resume moved to %@", time)
        }
        return if (estimated) L10n.string("Resume scheduled for about %@", time) else L10n.string("Resume scheduled for %@", time)
    }

    /** The device's own one-line reason after the app's word for what happened. */
    private fun joined(head: String, reason: String?): String {
        if (reason.isNullOrEmpty()) return head
        return "$head · $reason"
    }
}

/**
 * The locale's own clock, as RCCore's `Date.FormatStyle` gives it: the short time, and for another
 * day the abbreviated month and the day in front of it. Java has no skeletons on every platform
 * this core runs on, so the dated pattern is the locale's medium date with the year taken out,
 * joined to its short time the way the locale joins them.
 */
private object ClockPattern {
    fun of(locale: Locale, dated: Boolean): String {
        val date = if (dated) FormatStyle.MEDIUM else null
        val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(date, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)
        return if (dated) withoutYear(pattern) else pattern
    }

    /**
     * The pattern with its year field gone, and the literal that tied the year to its neighbour:
     * `MMM d, y, h:mm a` → `MMM d, h:mm a`, `y年M月d日 HH:mm` → `M月d日 HH:mm`, `d MMM y, HH:mm` →
     * `d MMM, HH:mm`.
     */
    fun withoutYear(pattern: String): String {
        val tokens = tokens(pattern)
        val year = tokens.indexOfFirst { it.isField && it.text.first() in "yuY" }
        if (year < 0) return pattern
        val next = tokens.getOrNull(year + 1)
        val previous = tokens.getOrNull(year - 1)
        val removed = mutableSetOf(year)
        when {
            // A suffix that names the year itself, as 年 and 년 do, goes with it.
            next != null && !next.isField && next.text.none { it.isWhitespace() || it in ",./-" } -> removed.add(year + 1)
            previous != null && !previous.isField -> removed.add(year - 1)
            next != null && !next.isField -> removed.add(year + 1)
        }
        return tokens.filterIndexed { index, _ -> index !in removed }.joinToString("") { it.text }.trim()
    }

    private class Token(val text: String, val isField: Boolean)

    /** Runs of one pattern letter are fields; everything else, quoted text included, is literal. */
    private fun tokens(pattern: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < pattern.length) {
            val character = pattern[index]
            val end = when {
                character == '\'' -> pattern.indexOf('\'', index + 1).let { if (it < 0) pattern.length else it + 1 }
                character.isLetter() -> {
                    var cursor = index
                    while (cursor < pattern.length && pattern[cursor] == character) cursor += 1
                    cursor
                }
                else -> {
                    var cursor = index
                    while (cursor < pattern.length && !pattern[cursor].isLetter() && pattern[cursor] != '\'') cursor += 1
                    cursor
                }
            }
            val text = pattern.substring(index, end)
            tokens.add(Token(text = text, isField = character.isLetter() && character.code < 0x80))
            index = end
        }
        return tokens
    }
}
