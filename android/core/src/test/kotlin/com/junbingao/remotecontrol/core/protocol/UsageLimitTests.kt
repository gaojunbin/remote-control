package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.transport.PushRoute
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amendment A35, paused by the usage limit: the fixtures the amendment added, decoded as the app
 * decodes them, the requests, and the bounds the picker is held to. RCCore's suite of this name
 * also holds the words the phone writes (`ResumeText`, `TurnAlerts`) and what Simple keeps
 * (`Timeline`); those cases are `core-state`'s to add here.
 */
class UsageLimitTests {
    /** Every fixture A35 added decodes. */
    @Test
    fun fixtureParses() {
        for (name in listOf(
            "objects/session.resume-pending.json",
            "events/turn_completed.limit.json",
            "events/resume.json",
            "events/resume.dropped.json",
            "events/user_message.resume.json",
            "events/turn_started.resume.json",
            "app/preferences.updated.json",
            "app/session.resume_set.json",
            "app/session.resume_cancel.json",
            "app/reply.session.resume_set.json",
            "device/preferences.json",
            "device/forwarded/session.resume_set.json",
            "device/forwarded/session.resume_cancel.json",
            "http/preferences.response.json",
            "http/preferences.patch.request.json",
            "http/push.payload.limit.json",
        )) {
            assertNotEquals<Any>(JsonNull, FixtureSource.json(name), name)
        }
    }

    /** A session carries the resume the device has pending. */
    @Test
    fun sessionResume() {
        val session = FixtureSource.json("objects/session.resume-pending.json").decode<Session>()
        assertEquals(1_788_966_060_000, session.resume?.at)
        assertEquals(false, session.resume?.estimated)
        assertEquals(0, session.resume?.attempts)
        assertEquals(300, session.resume?.windowMinutes)
        // The pause is the notice's to carry: the session itself is idle.
        assertEquals(SessionState.idle, session.state)
        assertEquals(SessionControl.shared, session.control)
    }

    /** A session with no resume field has no resume pending. */
    @Test
    fun sessionWithoutResume() {
        assertNull(FixtureSource.json("objects/session.shared-idle.json").decode<Session>().resume)
    }

    /** A turn the limit ended is an error stop that says when it resets. */
    @Test
    fun turnCompletedLimit() {
        val event = FixtureSource.json("events/turn_completed.limit.json").decode<SessionEvent>()
        val payload = assertIs<SessionEventBody.TurnCompleted>(event.body, "expected a turn_completed").payload
        assertEquals(StopReason.error, payload.stopReason)
        assertEquals(300, payload.limit?.windowMinutes)
        assertEquals(1_788_966_000_000, payload.limit?.resetsAt)
        // The field survives a round trip, so a cached transcript keeps it.
        val again = JSONValue.encode(event).decode<SessionEvent>()
        assertEquals(event.body, again.body)
    }

    /** A limit the vendor named no time for decodes with a null reset. */
    @Test
    fun limitWithoutResetTime() {
        val event = jsonObjectOf(
            "seq" to 9, "ts" to 1, "kind" to "turn_completed", "turn_id" to "t",
            "stop_reason" to "error", "duration_ms" to 900,
            "limit" to mapOf("window_minutes" to 10_080, "resets_at" to null),
        )
        val payload = assertIs<SessionEventBody.TurnCompleted>(event.decode<SessionEvent>().body).payload
        assertNotNull(payload.limit)
        assertNull(payload.limit.resetsAt)
        assertEquals(10_080, payload.limit.windowMinutes)
    }

    /** A resume event says what the device did, and one status draws nothing. */
    @Test
    fun resumeEvents() {
        val scheduled = FixtureSource.json("events/resume.json").decode<SessionEvent>()
        assertEquals(SessionEvent.resumeKind, scheduled.kind)
        assertEquals(ResumeStatus.scheduled, scheduled.resume?.status)
        assertEquals(1_788_966_060_000, scheduled.resume?.at)
        assertEquals(false, scheduled.resume?.estimated)

        val dropped = FixtureSource.json("events/resume.dropped.json").decode<SessionEvent>()
        assertEquals(ResumeStatus.dropped, dropped.resume?.status)
        assertEquals(false, dropped.resume?.reason?.isEmpty())

        assertFalse(ResumeStatus.fired.isDrawn)
        for (status in listOf(ResumeStatus.scheduled, ResumeStatus.rescheduled, ResumeStatus.cancelled,
                              ResumeStatus.dropped)) {
            assertTrue(status.isDrawn, status.rawValue)
        }
    }

    /** A resume status this build has not heard of decodes rather than throwing. */
    @Test
    fun unknownResumeStatus() {
        val payload = jsonObjectOf("seq" to 1, "ts" to 1, "kind" to "resume", "status" to "deferred")
            .decode<SessionEvent>().resume
        assertEquals("deferred", payload?.status?.rawValue)
        assertEquals(true, payload?.status?.isDrawn)
    }

    /** The prompt is the person's own message and the turn it starts is theirs. */
    @Test
    fun resumeSourceAndTrigger() {
        val message = FixtureSource.json("events/user_message.resume.json").decode<SessionEvent>()
        assertEquals(EventSource.resume, message.userMessage?.source)
        // Amendment A30's "somebody else started this" rule must not claim it.
        assertEquals(false, message.userMessage?.source?.isElsewhere)

        val started = FixtureSource.json("events/turn_started.resume.json").decode<SessionEvent>()
        val payload = assertIs<SessionEventBody.TurnStarted>(started.body, "expected a turn_started").payload
        assertEquals(EventSource.resume, payload.trigger)
        assertFalse(payload.trigger.isElsewhere)
    }

    /** Preferences ride on hello, on their own frame and on the REST route. */
    @Test
    fun preferences() {
        val hello = assertIs<AppFrame.Hello>(AppFrame(json = FixtureSource.json("app/hello.json")), "expected a hello").hello
        assertNotNull(hello.preferences)
        assertEquals(false, hello.preferences.resumeAfterLimit)

        val updated = assertIs<AppFrame.PreferencesUpdated>(AppFrame(json = FixtureSource.json("app/preferences.updated.json")),
                                                            "expected a preferences.updated").preferences
        assertTrue(updated.resumeAfterLimit)

        val response = FixtureSource.json("http/preferences.response.json").decode<PreferencesResponse>()
        assertTrue(response.preferences.resumeAfterLimit)
    }

    /** A gateway older than the amendment offers no preferences at all. */
    @Test
    fun preferencesAbsent() {
        val hello = JsonObject(FixtureSource.json("app/hello.json").objectValue.orEmpty() - "preferences")
        val payload = assertIs<AppFrame.Hello>(AppFrame(json = hello), "expected a hello").hello
        assertNull(payload.preferences)
    }

    /** The push kinds a resume produces decode (the route's half; `TurnAlerts` is a store's). */
    @Test
    fun pushKinds() {
        val route = FixtureSource.json("http/push.payload.limit.json")["rc"]?.decode<PushRoute>()
        assertEquals(PushKind.limitReached, route?.kind)
        assertEquals("mac-studio-office: paused by the usage limit", route?.title)
    }

    /** The two requests are built exactly as the fixtures are. */
    @Test
    fun requests() {
        val set = FixtureSource.json("app/session.resume_set.json")
        val at = assertNotNull(set["at"]?.longValue)
        val built = GatewayRequest.resumeSet(sessionID = set["session_id"]?.stringValue ?: "", at = Instant.ofEpochMilli(at))
        assertEquals("session.resume_set", built.type)
        assertEquals(JsonPrimitive(at), built.body["at"])
        assertEquals(set["session_id"], built.body["session_id"])

        val cancel = FixtureSource.json("app/session.resume_cancel.json")
        val cancelled = GatewayRequest.resumeCancel(sessionID = cancel["session_id"]?.stringValue ?: "")
        assertEquals("session.resume_cancel", cancelled.type)
        assertEquals(cancel["session_id"], cancelled.body["session_id"])

        val reply = FixtureSource.json("app/reply.session.resume_set.json")
        val session = assertNotNull(reply["result"]).decode<SessionResult>().session
        assertEquals(1_788_967_860_000, session.resume?.at)
    }

    // The bounds the picker is held to

    /** A resume is between a minute from now and eight days away. */
    @Test
    fun bounds() {
        val now = Instant.ofEpochSecond(1_788_966_000)
        for ((offset, allowed) in listOf(30.0 to false, 59.0 to false, 61.0 to true, 3600.0 to true,
                                         8 * 86_400.0 to true, 8 * 86_400.0 + 60 to false)) {
            val date = now.plusMillis((offset * 1000).toLong())
            assertEquals(allowed, ResumeBounds.allows(date, now = now), "$offset s ahead")
        }
    }
}
