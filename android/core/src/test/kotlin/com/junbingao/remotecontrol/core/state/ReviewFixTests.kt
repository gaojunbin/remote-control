package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.jsonObjectOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The wire cases of RCCore's suite of this name are in `protocol/ReviewFixTests.kt`.
// `sendBlockReasons`, which drives a store through the demo gateway, arrives with it.

/** Review fixes: the transcript's gap repair, its cursor and its child index. */
class ReviewFixTests {
    private fun event(seq: Int, kind: String, vararg fields: Pair<String, Any?>): SessionEvent {
        val members = mutableListOf<Pair<String, Any?>>("seq" to seq, "ts" to seq, "kind" to kind)
        members.addAll(fields)
        if (kind == SessionEvent.noticeKind) {
            members.add("level" to "info")
            members.add("text" to "x")
        }
        return jsonObjectOf(*members.toTypedArray()).decode()
    }

    /** A skipped seq is detected, and a refill clears it. */
    @Test
    fun gapDetection() {
        val timeline = Timeline()
        timeline.apply(event(41, "notice"))
        assertFalse(timeline.hasGap)
        timeline.apply(event(42, "notice"))
        assertFalse(timeline.hasGap)
        timeline.apply(event(44, "notice"))
        assertTrue(timeline.hasGap)
        timeline.clearGap()
        assertFalse(timeline.hasGap)
    }

    /** The first event of a session is not mistaken for a gap. */
    @Test
    fun noGapOnFirstEvent() {
        val timeline = Timeline()
        timeline.apply(event(500, "notice"))
        assertFalse(timeline.hasGap)
    }

    /** A cached transcript can hand its cursor to a warm open. */
    @Test
    fun cursorAdoption() {
        val timeline = Timeline()
        timeline.prependHistory(listOf(event(10, "notice"), event(12, "notice")), hasMore = true)
        assertEquals(0, timeline.lastSeq)
        timeline.adoptCursor(12)
        assertEquals(12, timeline.lastSeq)
        timeline.adoptCursor(5)
        assertEquals(12, timeline.lastSeq)
    }

    /** Sub-agent rows resolve through the child index, in order. */
    @Test
    fun childIndex() {
        val timeline = Timeline()
        timeline.apply(event(1, "tool_call", "block_id" to "task", "tool" to "Task", "tool_kind" to "subagent",
                             "title" to "Audit", "status" to "running"))
        for ((index, seq) in listOf(2, 3, 4).withIndex()) {
            timeline.apply(event(seq, "assistant_text", "block_id" to "sub$index", "parent_block_id" to "task",
                                 "text" to "line $index", "done" to true))
        }
        assertEquals(3, timeline.children(of = "task").size)
        assertEquals(listOf("line 0", "line 1", "line 2"), timeline.children(of = "task").map { it.text })
        assertEquals(1, timeline.roots.size)

        // A history page rebuilds the index without losing a child.
        timeline.prependHistory(listOf(event(0, "user_message", "block_id" to "u", "text" to "go")), hasMore = false)
        assertEquals(3, timeline.children(of = "task").size)
    }
}
