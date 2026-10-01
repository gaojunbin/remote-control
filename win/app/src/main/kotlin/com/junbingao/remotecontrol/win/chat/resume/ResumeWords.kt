package com.junbingao.remotecontrol.win.chat.resume

import com.junbingao.remotecontrol.core.protocol.LimitStop
import com.junbingao.remotecontrol.core.protocol.ResumePayload
import com.junbingao.remotecontrol.core.protocol.ResumeStatus
import com.junbingao.remotecontrol.core.protocol.SessionResume
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `web/src/features/chat/resume.ts` — A35: the words a pending resume is drawn with, the words the
 * device's own steps read as, and the bounds the time picker refuses outside of (`docs/DESIGN.md`
 * § "Paused by the usage limit").
 *
 * Everything here is pure: a timestamp and the clock go in, the reader's own words come out. The
 * time is always the viewer's, because the gateway has no idea what zone the app is in. Times are
 * epoch milliseconds, as on the wire.
 */
object ResumeWords {
    /** §6.3: the soonest and the latest a resume may be set for. */
    const val minAheadMS: Long = 60_000
    const val maxAheadMS: Long = 8L * 24 * 60 * 60_000

    /**
     * A time in the viewer's own zone: "3:50 PM" when it falls today, "Sep 18, 3:50 PM" on any
     * other day. The clock face is the reader's language's, so 中文 reads 15:50 where English reads
     * 3:50 PM — `toLocaleTimeString` and `toLocaleDateString` in the interface language.
     */
    fun timeText(at: Long, now: Long = Format.nowMillis, zone: ZoneId = ZoneId.systemDefault()): String {
        val moment = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone)
        val chinese = S.format.dateLocale.startsWith("zh")
        val locale = if (chinese) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
        val time = DateTimeFormatter.ofPattern(if (chinese) "HH:mm" else "h:mm a", locale).format(moment)
        if (moment.toLocalDate() == today.toLocalDate()) return time
        val date = DateTimeFormatter.ofPattern(if (chinese) "M月d日" else "MMM d", locale).format(moment)
        return "$date, $time"
    }

    /** The try a resume is on, once one has already run into the limit again. */
    internal fun attemptWord(attempts: Int): String? {
        if (attempts <= 0) return null
        return if (attempts == 1) S.chat.resumeSecondTry else S.chat.resumeThirdTry
    }

    /** "Paused by the usage limit · resumes 3:50 PM · second try". */
    fun noticeText(resume: SessionResume, now: Long = Format.nowMillis, zone: ZoneId = ZoneId.systemDefault()): String {
        val moment = timeText(resume.at, now, zone)
        val parts = mutableListOf(S.chat.pausedByLimit, if (resume.estimated) S.chat.resumesAbout(moment) else S.chat.resumesAt(moment))
        attemptWord(resume.attempts)?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /** How a turn the usage limit ended closes, with the reset time when the vendor named one and without it when `resets_at` is null. */
    fun limitEndText(limit: LimitStop, now: Long = Format.nowMillis, zone: ZoneId = ZoneId.systemDefault()): String {
        val resetsAt = limit.resetsAt ?: return S.chat.turnLimit
        return S.chat.turnLimitResets(timeText(resetsAt, now, zone))
    }

    /**
     * One of the device's steps as a timeline row, or null for `fired`: the moment of resuming is
     * the prompt in the person's bubble, not a row of its own.
     */
    fun rowText(event: ResumePayload, now: Long = Format.nowMillis, zone: ZoneId = ZoneId.systemDefault()): String? = when (event.status) {
        ResumeStatus.scheduled -> event.at?.let { S.chat.resumeScheduledAt(timeText(it, now, zone)) } ?: S.chat.resumeScheduled
        ResumeStatus.rescheduled -> event.at?.let { S.chat.resumeMovedTo(timeText(it, now, zone)) } ?: S.chat.resumeMoved
        ResumeStatus.cancelled -> withReason(S.chat.resumeCancelled, event.reason)
        ResumeStatus.dropped -> withReason(S.chat.resumeDropped, event.reason)
        else -> null
    }

    /** The device's one line of why, after the app's own word for what happened. */
    private fun withReason(label: String, reason: String?): String =
        if (reason.isNullOrEmpty()) label else "$label · $reason"

    /** Why a time the reader picked cannot be sent, or null when it can. */
    fun boundError(at: Long, now: Long = Format.nowMillis): String? {
        if (at < now + minAheadMS) return S.chat.resumeTooSoon
        if (at > now + maxAheadMS) return S.chat.resumeTooFar
        return null
    }
}
