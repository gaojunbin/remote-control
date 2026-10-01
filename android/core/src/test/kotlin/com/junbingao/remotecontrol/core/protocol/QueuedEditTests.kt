package com.junbingao.remotecontrol.core.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amendment A43, editing a queued message: the wire. RCCore's suite of this name also drives the
 * store's half through `ChatStore`; those cases are in `state/QueuedEditTests.kt`.
 */
class QueuedEditTests {
    /** An entry says how many files it holds, and says nothing when it holds none. */
    @Test
    fun attachmentsDecode() {
        val entry = jsonObjectOf("id" to "q", "text" to "t", "ts" to 1, "attachments" to 2).decode<QueuedMessage>()
        assertEquals(2, entry.attachments)
        assertTrue(entry.carriesFiles)

        val bare = jsonObjectOf("id" to "q", "text" to "t", "ts" to 1).decode<QueuedMessage>()
        assertNull(bare.attachments)
        assertFalse(bare.carriesFiles)
        assertNull(JSONValue.encode(bare)["attachments"], "and an absent count stays absent")
    }

    /** session.send carries queue_ts only for a message going back to its place. */
    @Test
    fun queueTsIsOptional() {
        val back = GatewayRequest.send(sessionID = "s", text = "t", mode = SendMode.queue, queueTs = 2_000)
        assertEquals(JsonPrimitive(2_000), back.json["queue_ts"])
        assertNull(GatewayRequest.send(sessionID = "s", text = "t").json["queue_ts"])
    }
}
