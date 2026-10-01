package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.MessageDelivery
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendResult
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.jsonObjectOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The decoding cases of RCCore's suite of this name are in `protocol/SharedControlTests.kt`. Its
// composer cases, whose store answers for the demo's Claude through the demo gateway, and
// `attachHints`, `demoFixtures` and `demoInjection` arrive with the demo gateway.

/**
 * Amendment A10: a live CLI owns the session and the device is attached to it. The app types,
 * approves and queues as it would for a session it runs itself, and never offers takeover, Stop,
 * attachments or the terminal's own settings.
 */
class SharedControlTests {
    /** A held message is accepted as queued with its own id, never a flag. */
    @Test
    fun sendResultShape() {
        val queued = jsonObjectOf("accepted" to "queued", "queued_id" to "req-1").decode<SendResult>()
        assertEquals(SendAcceptance.queued, queued.accepted)
        assertEquals("req-1", queued.queuedID)

        val sent = jsonObjectOf("accepted" to "sent").decode<SendResult>()
        assertEquals(SendAcceptance.sent, sent.accepted)
        assertNull(sent.queuedID)
    }

    // The delivery chip

    /**
     * A held message is a queue entry until the CLI takes it. Amendment A19: the device holds a
     * message sent into a running attached turn as a queue entry and nothing else. The optimistic row
     * the app drew when it sent retires into that queue, and the block arrives only when the CLI takes
     * it — after the output of the turn it waited for.
     */
    @Test
    fun heldMessageIsAQueueEntry() {
        fun event(seq: Int, kind: String, vararg fields: Pair<String, Any?>): SessionEvent =
            jsonObjectOf("seq" to seq, "ts" to seq, "kind" to kind, *fields).decode()

        val timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "drop I, L, O and U"))
        assertEquals("req-1", timeline.roots.lastOrNull()?.pending?.id)

        // The device answers `queued` and publishes the queue. No block yet.
        timeline.apply(event(12, SessionEvent.queueKind,
                             "pending" to listOf(mapOf("id" to "req-1", "text" to "drop I, L, O and U", "ts" to 1))))
        assertEquals(listOf("req-1"), timeline.queue.map { it.id })
        assertTrue(timeline.roots.all { it.pending == null },
                   "the optimistic row moved into the queue rather than staying in the transcript")
        assertFalse(timeline.entries.any { it.userMessage != null }, "and no block was drawn for it")

        // The turn the message waited for finishes.
        timeline.apply(event(16, SessionEvent.assistantTextKind, "block_id" to "a-1", "text" to "Done.", "done" to true))
        // The CLI takes the message: the block appears, after that output.
        timeline.apply(event(18, SessionEvent.userMessageKind, "block_id" to "req-1", "text" to "drop I, L, O and U",
                             "source" to "remote", "delivery" to "delivered"))
        timeline.apply(event(19, SessionEvent.queueKind, "pending" to emptyList<Any>()))
        assertEquals(MessageDelivery.delivered, timeline.entry(id = "req-1")?.userMessage?.delivery)
        assertTrue(timeline.queue.isEmpty())
        assertEquals(listOf("a-1", "req-1"), timeline.roots.map { it.id }.takeLast(2),
                     "and it is drawn after the output of the turn it waited for")
    }

    /** An absorbed message keeps its block so the re-send replaces it. */
    @Test
    fun absorbedKeepsTheBlock() {
        fun message(seq: Int, delivery: String): SessionEvent = jsonObjectOf(
            "seq" to seq, "ts" to seq, "kind" to SessionEvent.userMessageKind, "block_id" to "u-absorbed",
            "text" to "also update the docstring", "source" to "remote", "delivery" to delivery,
        ).decode()

        val timeline = Timeline()
        timeline.apply(message(24, delivery = "absorbed"))
        assertEquals(MessageDelivery.absorbed, timeline.entry(id = "u-absorbed")?.userMessage?.delivery)
        timeline.apply(message(31, delivery = "delivered"))
        assertEquals(1, timeline.entries.size)
        assertEquals(MessageDelivery.delivered, timeline.entry(id = "u-absorbed")?.userMessage?.delivery)
    }
}
