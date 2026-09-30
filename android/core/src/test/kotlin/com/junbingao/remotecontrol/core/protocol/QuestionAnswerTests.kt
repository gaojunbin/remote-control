package com.junbingao.remotecontrol.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Amendment A20, answering a question: decoding. RCCore's suite of this name also drives the card
 * and the composer (`QuestionDraft`, `ChatStore`) and the demo's attached session; those cases are
 * `core-state`'s and `core-demo`'s to add here.
 */
class QuestionAnswerTests {
    /** A resolved question says who answered it, and an older one says nothing. */
    @Test
    fun attributionDecodes() {
        val event = jsonObjectOf("seq" to 1, "ts" to 1, "kind" to "question", "block_id" to "q",
                                 "request_id" to "r", "status" to "resolved",
                                 "answers" to mapOf("q1" to listOf("clamp")), "by" to "terminal").decode<SessionEvent>()
        assertEquals(EventSource.terminal, event.question?.by)
        assertEquals("terminal", JSONValue.encode(event)["by"]?.stringValue)

        val quiet = jsonObjectOf("seq" to 1, "ts" to 1, "kind" to "question", "block_id" to "q",
                                 "request_id" to "r", "status" to "resolved")
        assertNull(quiet.decode<SessionEvent>().question?.by)
    }
}
