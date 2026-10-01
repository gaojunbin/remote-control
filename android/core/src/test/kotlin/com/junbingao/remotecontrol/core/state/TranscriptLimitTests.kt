package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.jsonOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What an open transcript costs, and what "Open full output" may do to it. */
class TranscriptLimitTests {
    private fun event(seq: Int, kind: String, fields: Map<String, Any?> = emptyMap()): SessionEvent {
        val members = LinkedHashMap(fields)
        members["seq"] = seq
        members["ts"] = seq
        members["kind"] = kind
        if (kind == SessionEvent.noticeKind) {
            members["level"] = "info"
            members["text"] = "line $seq"
        }
        return jsonOf(members).decode()
    }

    private fun toolCall(seq: Int, firstSeq: Int? = null, output: String, truncated: Boolean = false): SessionEvent {
        val fields = linkedMapOf<String, Any?>(
            "block_id" to "tool", "tool" to "Bash", "tool_kind" to "shell",
            "title" to "pytest", "status" to "succeeded", "output" to output,
        )
        if (truncated) fields["output_truncated"] = true
        if (firstSeq != null) fields["first_seq"] = firstSeq
        return event(seq, kind = "tool_call", fields = fields)
    }

    // The transcript has a ceiling

    /** A transcript left open stops growing, and says history can be paged again. */
    @Test
    fun liveEntriesAreCapped() {
        val timeline = Timeline()
        timeline.markHistoryExhausted()
        assertFalse(timeline.hasMoreHistory)

        for (seq in 1..(Timeline.entryLimit + 10)) {
            timeline.apply(event(seq, kind = "notice"))
        }

        assertEquals(Timeline.entryLimit, timeline.entries.size)
        assertNull(timeline.entry(id = "seq:1"), "the oldest rows are the ones dropped")
        assertNotNull(timeline.entry(id = "seq:${Timeline.entryLimit + 10}"), "the newest are kept")
        assertTrue(timeline.hasMoreHistory, "so scrolling back pages them from the gateway again")
        assertEquals(11, timeline.oldestSeq, "and the history cursor follows the rows that are left")
    }

    /** Paging history back does not trim away what the reader scrolled up to see. */
    @Test
    fun historyIsNotTrimmed() {
        val timeline = Timeline()
        val events = (1..(Timeline.entryLimit + 10)).map { event(it, kind = "notice") }
        timeline.prependHistory(events, hasMore = false)
        assertEquals(Timeline.entryLimit + 10, timeline.entries.size)
        assertNotNull(timeline.entry(id = "seq:1"))
    }

    // The rows a render reads

    /** A redraw does not filter the transcript again. */
    @Test
    fun rowsAreKeptBetweenRenders() = runTest {
        val chat = ChatStore(session = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T",
                                               cwd = "/tmp", state = SessionState.idle, control = SessionControl.remote),
                             channel = InertChannel(), tasks = backgroundScope)
        chat.receive(AppFrame.SessionEvent(sessionID = "s", deviceID = "d", event = event(1, kind = "notice")))
        assertEquals(1, chat.rows.size)
        val built = chat.rowsBuilt
        chat.rows
        chat.rows
        assertEquals(built, chat.rowsBuilt, "three renders, one filter")

        chat.receive(AppFrame.SessionEvent(sessionID = "s", deviceID = "d", event = event(2, kind = "notice")))
        assertEquals(2, chat.rows.size)
        assertEquals(built + 1, chat.rowsBuilt, "a new event is what makes it filter again")
    }

    // Open full output

    /** A block that streamed on is not reverted by the copy that was asked for earlier. */
    @Test
    fun staleFullOutputIsRefused() {
        val timeline = Timeline()
        timeline.apply(toolCall(10, output = "first", truncated = true))
        timeline.apply(toolCall(20, firstSeq = 10, output = "second", truncated = true))
        assertEquals("second", timeline.entry(id = "tool")?.toolCall?.output)

        // The reply to the request that left while the block was at seq 10.
        timeline.replaceBlock(with = toolCall(10, firstSeq = 10, output = "first in full"))
        assertEquals("second", timeline.entry(id = "tool")?.toolCall?.output, "an older copy never writes over a newer one")

        // The reply for the version on screen is the point of the request.
        timeline.replaceBlock(with = toolCall(20, firstSeq = 10, output = "second in full"))
        assertEquals("second in full", timeline.entry(id = "tool")?.toolCall?.output)
        assertNotEquals(true, timeline.entry(id = "tool")?.toolCall?.outputTruncated)
    }

    /** A block the reply moves back into place is re-sorted with the rows around it. */
    @Test
    fun fullOutputResortsWhenItMoves() {
        val timeline = Timeline()
        timeline.apply(event(10, kind = "notice"))
        // A replacement that arrived without `first_seq`, so the row sits at 30.
        timeline.apply(toolCall(30, output = "late", truncated = true))
        assertEquals(listOf("seq:10", "tool"), timeline.roots.map { it.id })

        // The reply carries `first_seq`, which is where the block belongs.
        timeline.replaceBlock(with = toolCall(30, firstSeq = 5, output = "late in full"))
        assertEquals(5, timeline.entry(id = "tool")?.seq)
        assertEquals(listOf("tool", "seq:10"), timeline.roots.map { it.id },
                     "the row is put back in order rather than left where it was")
        assertNotNull(timeline.entry(id = "seq:10"), "and the index still resolves every row")
    }
}
