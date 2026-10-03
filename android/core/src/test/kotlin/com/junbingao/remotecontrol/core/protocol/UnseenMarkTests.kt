package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.transport.PushRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amendment A47 — a session that stopped working and waits for you is marked until someone looks:
 * the fixtures the amendment added, decoded as the app decodes them. RCCore's suite of this name also
 * holds the rule, the count, the cache and `session.seen` (`state/UnseenMarkTests.kt`), and what the
 * demo does with the mark (`demo/UnseenMarkTests.kt`).
 */
class UnseenMarkTests {
    /** A session carries the gateway's mark, and one without the field carries none. */
    @Test
    fun sessionDecodes() {
        val sessions = assertNotNull(FixtureSource.json("app/hello.json")["sessions"]).decode<List<Session>>()
        assertEquals(2, sessions.size)
        assertTrue(sessions[0].unseen)
        assertFalse(sessions[1].unseen, "absent is false: a gateway before A47 sends no field")
    }

    /** `session.seen` is the fixture's shape, under a request id of its own. */
    @Test
    fun seenRequest() {
        val expected = assertNotNull(FixtureSource.json("app/session.seen.json").objectValue)
        val sessionID = assertNotNull(expected["session_id"]?.stringValue)
        val built = GatewayRequest.seen(sessionID = sessionID).json
        assertEquals(expected["type"], built["type"])
        assertEquals(expected["session_id"], built["session_id"])
        assertEquals(false, built["id"]?.stringValue?.isEmpty())
        assertEquals(expected.keys, built.keys, "and nothing the fixture does not carry")
    }

    /** Every push carries the count, and the badge-only push says what it is. */
    @Test
    fun pushCount() {
        val badge = assertNotNull(FixtureSource.json("http/push.payload.badge.json")["rc"]).decode<PushRoute>()
        assertEquals(PushKind.badge, badge.kind)
        assertEquals(0, badge.badge)
        assertTrue(badge.title.isEmpty(), "it shows nothing")
        val alert = assertNotNull(FixtureSource.json("http/push.payload.json")["rc"]).decode<PushRoute>()
        assertEquals(PushKind.needsApproval, alert.kind)
        assertEquals(1, alert.badge)
        val older = assertNotNull(FixtureSource.json("http/push.payload.limit.json")["rc"]).decode<PushRoute>()
        assertNull(older.badge, "a payload without the count says nothing about it")
    }
}
