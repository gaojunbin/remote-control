package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.transport.SocketCloseReason
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Protocol amendments. RCCore's suite of this name also holds `snapshotOrdering`, which drives
 * `Timeline`, a store's; that case is in `state/AmendmentTests.kt`.
 */
class AmendmentTests {
    // A4, WebSocket close codes

    /** A close code decides whether reconnecting makes sense. */
    @Test
    fun closeCodes() {
        val table = listOf(
            Triple(4401, SocketCloseReason.unauthorized, false),
            Triple(4403, SocketCloseReason.forbidden, false),
            Triple(4001, SocketCloseReason.replaced, false),
            Triple(1000, SocketCloseReason.transient, true),
            Triple(1006, SocketCloseReason.transient, true),
        )
        for ((code, reason, reconnects) in table) {
            assertEquals(reason, SocketCloseReason(code = code), "close $code")
            assertEquals(reconnects, SocketCloseReason(code = code).shouldReconnect, "close $code")
        }
    }

    /** A close with no code is treated as transient. */
    @Test
    fun missingCloseCode() {
        assertEquals(SocketCloseReason.transient, SocketCloseReason(code = null))
        assertTrue(SocketCloseReason(code = null).shouldReconnect)
    }

    // A5, device_id on session frames

    /** session.event carries an optional device id. */
    @Test
    fun sessionEventDeviceID() {
        val withDevice = jsonObjectOf(
            "type" to "session.event", "session_id" to "s", "device_id" to "d",
            "event" to mapOf("seq" to 1, "ts" to 1, "kind" to "notice", "level" to "info", "text" to "x"),
        )
        val frame = assertIs<AppFrame.SessionEvent>(AppFrame(json = withDevice), "expected a session event")
        assertEquals("s", frame.sessionID)
        assertEquals("d", frame.deviceID)
        assertEquals(1, frame.event.seq)

        val withoutDevice = jsonObjectOf(
            "type" to "session.event", "session_id" to "s",
            "event" to mapOf("seq" to 1, "ts" to 1, "kind" to "notice", "level" to "info", "text" to "x"),
        )
        val missing = assertIs<AppFrame.SessionEvent>(AppFrame(json = withoutDevice), "expected a session event")
        assertNull(missing.deviceID)
    }

    /** session.removed keys on the session id, with or without a device id. */
    @Test
    fun sessionRemovedDeviceID() {
        val removal = assertIs<AppFrame.SessionRemoved>(
            AppFrame(json = jsonObjectOf("type" to "session.removed", "session_id" to "s")), "expected a removal")
        assertEquals("s", removal.sessionID)
        assertNull(removal.deviceID)

        val named = assertIs<AppFrame.SessionRemoved>(
            AppFrame(json = jsonObjectOf("type" to "session.removed", "session_id" to "s", "device_id" to "d")),
            "expected a removal")
        assertEquals("d", named.deviceID)
    }

    // A6, snapshots outside the live stream

    /** The subscribe reply's queue is optional and decodes when present. */
    @Test
    fun subscribeQueue() {
        val session = jsonObjectOf("session_id" to "s", "device_id" to "d")
        val withQueue = jsonObjectOf(
            "session" to session, "events" to emptyList<Any>(), "resync" to false,
            "queue" to mapOf("pending" to listOf(mapOf("id" to "q1", "text" to "later", "ts" to 1))),
        )
        assertEquals(1, withQueue.decode<SubscribeResult>().queue?.pending?.size)
        assertNull(jsonObjectOf("session" to session).decode<SubscribeResult>().queue)
    }
}

/**
 * Amendments A7 and A8. RCCore's suite also drives `ChatStore` and `Timeline` (A7's composer, A8's
 * ordering); those cases are in `state/AmendmentTests.kt`.
 */
class TerminalAndOrderingTests {
    private fun streaming(seq: Int, block: String, firstSeq: Int?, text: String): SessionEvent {
        val fields = linkedMapOf<String, JsonElement>(
            "seq" to jsonOf(seq), "ts" to jsonOf(seq), "kind" to jsonOf(SessionEvent.assistantTextKind),
            "block_id" to jsonOf(block), "delta" to jsonOf(text), "done" to jsonOf(false),
        )
        if (firstSeq != null) fields["first_seq"] = jsonOf(firstSeq)
        return jsonOf(fields).decode()
    }

    /** first_seq decodes, defaults to seq, and survives a round trip. */
    @Test
    fun firstSeqDecoding() {
        val withFirst = streaming(12, block = "a", firstSeq = 10, text = "x")
        assertEquals(10, withFirst.firstSeq)
        assertEquals(10, withFirst.orderSeq)
        assertEquals(10, JSONValue.encode(withFirst)["first_seq"]?.intValue)

        val without = streaming(12, block = "a", firstSeq = null, text = "x")
        assertNull(without.firstSeq)
        assertEquals(12, without.orderSeq)
        assertNull(JSONValue.encode(without)["first_seq"])
    }
}
