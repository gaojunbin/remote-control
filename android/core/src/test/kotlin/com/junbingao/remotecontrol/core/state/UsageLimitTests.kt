package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.LimitStop
import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.ResumePayload
import com.junbingao.remotecontrol.core.protocol.ResumeStatus
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionResume
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.TurnCompletedPayload
import com.junbingao.remotecontrol.core.protocol.TurnStartedPayload
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amendment A35 — a session the usage limit stopped resumes itself: the words the phone writes
 * around the fixtures the amendment added, and what Simple keeps of them. The fixtures, the requests
 * and the bounds — the wire's half of RCCore's suite — are the first agent's `protocol/UsageLimitTests.kt`.
 */
class UsageLimitTests {
    /** The push kinds a resume produces decode (the app's half: the alerts it raises for itself). */
    @Test
    fun pushKinds() {
        assertEquals(PushKind.limitReached, TurnAlerts.kind(resume = ResumeStatus.scheduled))
        assertEquals(PushKind.resumed, TurnAlerts.kind(resume = ResumeStatus.fired))
        assertEquals(PushKind.resumeDropped, TurnAlerts.kind(resume = ResumeStatus.dropped))
        // Neither is news: one is a detail of a pause already told, the other is usually the
        // person's own doing.
        assertNull(TurnAlerts.kind(resume = ResumeStatus.rescheduled))
        assertNull(TurnAlerts.kind(resume = ResumeStatus.cancelled))
    }

    // The words the phone writes

    private companion object {
        /** One fixed zone and one fixed language, so the words are the same wherever the check runs. */
        val zone: ZoneId = ZoneId.of("Asia/Singapore")
        val locale: Locale = Locale.forLanguageTag("en-US")

        /** 2026-09-09 20:00 in Singapore, which is the day the fixtures are on. */
        val now: Instant = Instant.ofEpochSecond(1_788_955_200)

        /** 22:20 the same evening, and 09:00 the next morning. */
        const val tonight: Long = 1_788_963_600_000
        const val tomorrow: Long = 1_789_002_000_000
    }

    private fun clock(milliseconds: Long): String = ResumeText.clock(milliseconds, now = now, zone = zone, locale = locale)

    /**
     * The clock itself is the system's, in the viewer's zone and language, so what is pinned here is
     * the rule the app owns: a time today is a time, and a time on another day says which day.
     * Pinning the pattern would pin this suite to one release's locale data and nothing else.
     */
    @Test
    fun clockWording() {
        val tonight = clock(UsageLimitTests.tonight)
        assertTrue(tonight.contains("10:20"), tonight)
        assertTrue(tonight.contains("PM"), tonight)
        assertFalse(tonight.contains("Sep"), tonight)

        val tomorrow = clock(UsageLimitTests.tomorrow)
        assertTrue(tomorrow.contains("9:00"), tomorrow)
        assertTrue(tomorrow.contains("Sep"), tomorrow)
        assertTrue(tomorrow.contains("10"), tomorrow)
    }

    /** The clock is read in the viewer's own zone, not the device's. */
    @Test
    fun clockZone() {
        val text = ResumeText.clock(tonight, now = now, zone = ZoneId.of("Europe/London"), locale = locale)
        // 22:20 in Singapore is 15:20 in London, on the same day either way.
        assertTrue(text.contains("3:20"), text)
        assertTrue(text.contains("PM"), text)
    }

    /** The notice names the time, the guess and the attempt. */
    @Test
    fun noticeWording() {
        fun notice(resume: SessionResume): String = ResumeText.notice(resume, now = now, zone = zone, locale = locale)
        val at = tonight
        val time = clock(at)
        assertEquals("Paused by the usage limit · resumes $time", notice(SessionResume(at = at)))
        assertEquals("Paused by the usage limit · resumes about $time", notice(SessionResume(at = at, estimated = true)))
        assertEquals("Paused by the usage limit · resumes $time · second try", notice(SessionResume(at = at, attempts = 1)))
        assertEquals("Paused by the usage limit · resumes $time · third try", notice(SessionResume(at = at, attempts = 2)))
        // The device drops the resume after the third try, so there is no fourth to name.
        assertEquals("Paused by the usage limit · resumes $time", notice(SessionResume(at = at, attempts = 3)))
    }

    /** A turn the limit ended says so, with the reset time when there is one. */
    @Test
    fun turnEndWording() {
        fun end(limit: LimitStop): String = ResumeText.turnEnd(limit, now = now, zone = zone, locale = locale)
        assertEquals("Ended at the usage limit · resets ${clock(tonight)}",
                     end(LimitStop(windowMinutes = 300, resetsAt = tonight)))
        assertEquals("Ended at the usage limit", end(LimitStop(windowMinutes = 10_080)))
    }

    /** Each resume row is in the notice voice, and the fired one is no row. */
    @Test
    fun rowWording() {
        fun row(payload: ResumePayload): String? = ResumeText.row(payload, now = now, zone = zone, locale = locale)
        val at = tonight
        val time = clock(at)
        assertEquals("Resume scheduled for $time", row(ResumePayload(status = ResumeStatus.scheduled, at = at)))
        assertEquals("Resume scheduled for about $time",
                     row(ResumePayload(status = ResumeStatus.scheduled, at = at, estimated = true)))
        assertEquals("Resume moved to $time", row(ResumePayload(status = ResumeStatus.rescheduled, at = at)))
        // A device that names no time still gets a row rather than a sentence that stops halfway.
        assertEquals("Resume scheduled", row(ResumePayload(status = ResumeStatus.scheduled)))
        assertEquals("Resume moved", row(ResumePayload(status = ResumeStatus.rescheduled)))
        assertNull(row(ResumePayload(status = ResumeStatus.fired)))
        assertEquals("Resume cancelled · you sent a message",
                     row(ResumePayload(status = ResumeStatus.cancelled, reason = "you sent a message")))
        assertEquals("Not resumed · The terminal that owned this session was closed.",
                     row(ResumePayload(status = ResumeStatus.dropped,
                                       reason = "The terminal that owned this session was closed.")))
        // A device that says nothing about why still gets a row.
        assertEquals("Resume cancelled", row(ResumePayload(status = ResumeStatus.cancelled)))
    }

    /** Simple keeps the limit end, the device's rows and the captioned prompt. */
    @Test
    fun simpleKeepsThem() {
        val timeline = Timeline()
        timeline.apply(SessionEvent(
            seq = 1, ts = 1, kind = SessionEvent.turnCompletedKind,
            body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
                turnID = "t", stopReason = StopReason.error, durationMS = 1_200,
                limit = LimitStop(windowMinutes = 300, resetsAt = tonight)))))
        timeline.apply(SessionEvent(seq = 2, ts = 2, kind = SessionEvent.resumeKind,
                                    body = SessionEventBody.Resume(ResumePayload(status = ResumeStatus.scheduled, at = 3))))
        timeline.apply(SessionEvent(seq = 3, ts = 3, kind = SessionEvent.resumeKind,
                                    body = SessionEventBody.Resume(ResumePayload(status = ResumeStatus.fired))))
        timeline.apply(SessionEvent(seq = 4, ts = 4, kind = SessionEvent.userMessageKind, blockID = "u",
                                    body = SessionEventBody.UserMessage(UserMessagePayload(text = "go on",
                                                                                           source = EventSource.resume))))
        timeline.apply(SessionEvent(seq = 5, ts = 5, kind = SessionEvent.turnStartedKind,
                                    body = SessionEventBody.TurnStarted(TurnStartedPayload(turnID = "t2",
                                                                                           trigger = EventSource.resume))))
        val simple = timeline.roots(at = TimelineDetail.simple)
        assertTrue(simple.any { it.turnCompleted?.limit != null })
        assertTrue(simple.any { it.resume?.status == ResumeStatus.scheduled })
        assertTrue(simple.any { it.userMessage?.source == EventSource.resume })
        // The moment of resuming is not a row at any level.
        assertFalse(simple.any { it.resume?.status == ResumeStatus.fired })
        assertFalse(timeline.roots(at = TimelineDetail.detailed).any { it.resume?.status == ResumeStatus.fired })
    }
}
