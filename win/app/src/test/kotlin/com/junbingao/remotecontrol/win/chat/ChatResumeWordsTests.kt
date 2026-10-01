package com.junbingao.remotecontrol.win.chat

import com.junbingao.remotecontrol.core.protocol.LimitStop
import com.junbingao.remotecontrol.core.protocol.ResumePayload
import com.junbingao.remotecontrol.core.protocol.ResumeStatus
import com.junbingao.remotecontrol.core.protocol.SessionResume
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.chat.resume.ResumeFieldFormat
import com.junbingao.remotecontrol.win.chat.resume.ResumeWords
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `web/tests/resume.test.ts` — A35: the words a pending resume and the device's own steps are
 * drawn with. The time is the viewer's, the date joins it when it is not today, "about" says the
 * device estimated it, and the try is appended once a resume has run into the limit again.
 */
class ChatResumeWordsTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    /** A local instant, so the assertions read in whatever zone the suite runs in. */
    private fun at(hour: Int, minute: Int, day: Int = 0): Long =
        LocalDateTime.of(2026, 9, 17 + day, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val now: Long get() = at(10, 0)

    private fun pending(estimated: Boolean = false, attempts: Int = 0) =
        SessionResume(at = at(15, 50), estimated = estimated, attempts = attempts, windowMinutes = 300)

    @Test
    fun aTimeTodayIsAClockAlone() {
        assertEquals("3:50 PM", ResumeWords.timeText(at(15, 50), now = now))
    }

    @Test
    fun anotherDayCarriesTheDate() {
        assertEquals("Sep 18, 3:50 PM", ResumeWords.timeText(at(15, 50, day = 1), now = now))
    }

    @Test
    fun theClockFollowsTheInterfaceLanguage() {
        InterfaceLanguageSource.current = InterfaceLanguage.zhHans
        assertEquals("15:50", ResumeWords.timeText(at(15, 50), now = now))
        assertEquals("9月18日, 15:50", ResumeWords.timeText(at(15, 50, day = 1), now = now))
    }

    @Test
    fun theNoticeSaysWhatHappenedAndWhenItResumes() {
        assertEquals("Paused by the usage limit · resumes 3:50 PM", ResumeWords.noticeText(pending(), now = now))
        assertEquals("Paused by the usage limit · resumes about 3:50 PM", ResumeWords.noticeText(pending(estimated = true), now = now))
    }

    @Test
    fun theTryIsAppendedOnceAResumeHitTheLimitAgain() {
        assertTrue(ResumeWords.noticeText(pending(attempts = 1), now = now).endsWith(" · second try"))
        assertTrue(ResumeWords.noticeText(pending(attempts = 2), now = now).endsWith(" · third try"))
        assertFalse(ResumeWords.noticeText(pending(), now = now).contains("try"))
    }

    @Test
    fun theNoticeIsSaidInTheInterfaceLanguage() {
        InterfaceLanguageSource.current = InterfaceLanguage.zhHans
        assertEquals("已因用量限额暂停 · 将于 15:50 继续", ResumeWords.noticeText(pending(), now = now))
    }

    @Test
    fun theDeviceStepsNameTheirTime() {
        assertEquals("Resume scheduled for 3:50 PM", ResumeWords.rowText(ResumePayload(status = ResumeStatus.scheduled, at = at(15, 50)), now = now))
        assertEquals("Resume moved to 4:20 PM", ResumeWords.rowText(ResumePayload(status = ResumeStatus.rescheduled, at = at(16, 20)), now = now))
    }

    @Test
    fun theMomentOfResumingDrawsNothing() {
        assertNull(ResumeWords.rowText(ResumePayload(status = ResumeStatus.fired), now = now))
    }

    @Test
    fun theDevicesOwnReasonFollowsWhatHappened() {
        assertEquals(
            "Resume cancelled · you sent a message",
            ResumeWords.rowText(ResumePayload(status = ResumeStatus.cancelled, reason = "you sent a message"), now = now),
        )
        assertEquals(
            "Not resumed · the terminal was closed",
            ResumeWords.rowText(ResumePayload(status = ResumeStatus.dropped, reason = "the terminal was closed"), now = now),
        )
        assertEquals("Resume cancelled", ResumeWords.rowText(ResumePayload(status = ResumeStatus.cancelled), now = now))
        assertEquals("Resume scheduled", ResumeWords.rowText(ResumePayload(status = ResumeStatus.scheduled), now = now))
    }

    @Test
    fun theTurnTheLimitEndedSaysWhenItResets() {
        assertEquals(
            "Ended at the usage limit · resets 3:50 PM",
            ResumeWords.limitEndText(LimitStop(windowMinutes = 300, resetsAt = at(15, 50)), now = now),
        )
        assertEquals("Ended at the usage limit", ResumeWords.limitEndText(LimitStop(resetsAt = null), now = now))
    }

    @Test
    fun thePickerTakesAMinuteToEightDaysAndNothingElse() {
        assertNull(ResumeWords.boundError(now + ResumeWords.minAheadMS, now = now))
        assertNull(ResumeWords.boundError(now + ResumeWords.maxAheadMS, now = now))
        assertEquals("Pick a time at least a minute from now.", ResumeWords.boundError(now + 30_000, now = now))
        assertEquals("Pick a time within the next eight days.", ResumeWords.boundError(now + ResumeWords.maxAheadMS + 60_000, now = now))
    }
}

/** The change form's field draws the time as Chrome's `datetime-local` does. */
class ChatResumeFieldTests {
    @Test
    fun everyNumberTakesTheFieldsWidth() {
        assertEquals("yyyy/MM/dd HH:mm", ResumeFieldFormat.fieldPattern("y/M/d HH:mm"))
        assertEquals("MM/dd/yyyy, hh:mm a", ResumeFieldFormat.fieldPattern("M/d/yy, h:mm a"))
        assertEquals("yyyy/MM/dd HH:mm", ResumeFieldFormat.fieldPattern("y/MM/dd H:mm"))
    }

    @Test
    fun quotedTextIsKept() {
        assertEquals("yyyy'年'MM'月'dd'日' HH:mm", ResumeFieldFormat.fieldPattern("y'年'M'月'd'日' H:mm"))
        assertEquals("dd 'dd' MM", ResumeFieldFormat.fieldPattern("d 'dd' M"))
    }

    @Test
    fun theFieldReadsBackWhatItShows() {
        val at = Instant.ofEpochSecond(1_790_000_000)
        val shown = ResumeFieldFormat.text(at)
        val minute = Instant.ofEpochSecond(1_790_000_000L / 60 * 60)
        assertEquals(minute, ResumeFieldFormat.date(shown))
        assertNull(ResumeFieldFormat.date("  "))
        assertNull(ResumeFieldFormat.date("not a time"))
    }
}
