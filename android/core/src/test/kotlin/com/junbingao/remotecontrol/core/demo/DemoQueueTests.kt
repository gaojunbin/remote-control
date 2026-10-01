package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.AttachmentInfo
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.SendMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Amendment A43 on the demo device: every queue in `ts` order with no two entries under one `ts`,
 * `queue_ts` honoured, `queue_remove` answering `not_found` for what it no longer holds, and an
 * edit going back to its place.
 *
 * RCCore's `editRoundTrip` drives the edit through `ChatStore`, a store, and is `core-state`'s to
 * add; the same round trip at the gateway is `DemoGatewayTests.queuedEditGoesBackToItsPlace`.
 */
class DemoQueueTests {
    private fun item(id: String, ts: Long) = DemoQueue.Item(id = id, text = id, ts = ts)

    private fun order(queue: DemoQueue): List<String> = queue.pending.map { it.id }

    // The rule

    /** A message sent back with queue_ts goes in front of the first entry with a later ts. */
    @Test
    fun insertsInTsOrder() {
        val queue = DemoQueue(listOf(item("a", ts = 10), item("b", ts = 20), item("c", ts = 30)))
        queue.insert(item("front", ts = 5))
        assertEquals(listOf("front", "a", "b", "c"), order(queue))
        queue.insert(item("middle", ts = 25))
        assertEquals(listOf("front", "a", "b", "middle", "c"), order(queue))
        queue.insert(item("end", ts = 40))
        assertEquals(listOf("front", "a", "b", "middle", "c", "end"), order(queue))
        queue.insert(item("tie", ts = 20))
        assertEquals(listOf("front", "a", "b", "tie", "middle", "c", "end"), order(queue),
                     "after an entry with the same ts, not before it")

        val empty = DemoQueue()
        empty.insert(item("only", ts = 7))
        assertEquals(listOf("only"), order(empty))
    }

    /** Anything else joins the end under a ts no entry has. */
    @Test
    fun appendsUnderAFreshTs() {
        val queue = DemoQueue(listOf(item("a", ts = 100)))
        queue.append(item("behind the clock", ts = 50))
        assertEquals(listOf(100L, 101L), queue.pending.map { it.ts }, "one past the last entry when that is later")
        queue.append(item("now", ts = 500))
        queue.append(item("the same instant", ts = 500))
        assertEquals(listOf(100L, 101L, 500L, 501L), queue.pending.map { it.ts })

        val empty = DemoQueue()
        empty.append(item("first", ts = 42))
        assertEquals(listOf(42L), empty.pending.map { it.ts }, "the current time when nothing is ahead of it")
    }

    /** Removing takes out the one message named, and only once. */
    @Test
    fun removesByID() {
        val queue = DemoQueue(listOf(item("a", ts = 1), item("b", ts = 2)))
        assertEquals("a", queue.remove(id = "a")?.message?.id)
        assertNull(queue.remove(id = "a"), "the second time there is nothing to take")
        assertEquals(listOf("b"), order(queue))
        assertEquals("b", queue.next()?.message?.id)
        assertTrue(queue.isEmpty)
    }

    /** The snapshot counts a message's files and carries none of them. */
    @Test
    fun countsFiles() {
        val file = AttachmentInfo(name = "shot.png", mime = "image/png", size = 4)
        assertEquals(2, DemoQueue.Item(id = "f", text = "t", ts = 1, files = listOf(file, file)).message.attachments)
        assertNull(DemoQueue.Item(id = "g", text = "t", ts = 1).message.attachments)
    }

    /** The seeded line has three messages under three different ts, the last with files. */
    @Test
    fun seededLine() {
        val seeded = DemoFixtures.heldMessages(base = 1_000_000)
        assertEquals(3, seeded.map { it.message.ts }.toSet().size)
        assertEquals(seeded.map { it.message.ts }.sorted(), seeded.map { it.message.ts })
        assertEquals(listOf(false, false, true), seeded.map { it.message.carriesFiles })
    }

    // Through the demo gateway

    /** queue_remove answers not_found for a message it does not hold. */
    @Test
    fun removeUnknownIsNotFound() = runTest {
        val gateway = demoGateway(holdsQueue = true)
        val unknown = GatewayRequest.queueRemove(sessionID = DemoFixtures.liveSessionID, queuedID = "gone")
        assertEquals(GatewayErrorCode.notFound, assertFailsWith<GatewayErrorBody> { gateway.request(unknown) }.code)
        gateway.request(GatewayRequest.queueRemove(sessionID = DemoFixtures.liveSessionID, queuedID = "demo-queued-suite"))
        val twice = assertFailsWith<GatewayErrorBody>("a message was removed twice") {
            gateway.request(GatewayRequest.queueRemove(sessionID = DemoFixtures.liveSessionID, queuedID = "demo-queued-suite"))
        }
        assertEquals(GatewayErrorCode.notFound, twice.code, "and so does a second removal of the same one")
    }

    /** A queue_ts that is not a non-negative integer is refused. */
    @Test
    fun badQueueTsIsRefused() = runTest {
        val gateway = demoGateway(holdsQueue = true)
        for (bad in listOf<JsonElement>(JsonPrimitive(-1), JsonPrimitive("12"), JsonPrimitive(1.5), JsonPrimitive(true))) {
            val body = GatewayRequest.send(sessionID = DemoFixtures.liveSessionID, text = "again", mode = SendMode.queue).body
            val request = GatewayRequest(type = "session.send", body = JsonObject(body + ("queue_ts" to bad)))
            val refusal = assertFailsWith<GatewayErrorBody>("queue_ts $bad was taken") { gateway.request(request) }
            assertEquals(GatewayErrorCode.badRequest, refusal.code)
        }
    }
}
