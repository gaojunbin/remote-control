package com.junbingao.remotecontrol.core.state

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
import kotlin.test.assertTrue

// The store cases of RCCore's `AmendmentTests.swift`; its wire cases are the first agent's
// `protocol/AmendmentTests.kt`. `terminalControlLocksTheComposer` needs the demo's Claude agent and
// arrives with the demo.

/** Protocol amendments: the one case of the suite that drives a store. */
class AmendmentTests {
    // A6, snapshots outside the live stream

    /** An older snapshot from a history page never overwrites a newer one. */
    @Test
    fun snapshotOrdering() {
        fun event(seq: Int, kind: String, fields: Map<String, Any?>): SessionEvent {
            val members = LinkedHashMap(fields)
            members["seq"] = seq
            members["ts"] = seq
            members["kind"] = kind
            return jsonOf(members).decode()
        }

        val timeline = Timeline()
        timeline.apply(event(10, "todos", mapOf("items" to listOf(mapOf("id" to "1", "text" to "new", "status" to "pending")))))
        timeline.prependHistory(listOf(
            event(2, "todos", mapOf("items" to listOf(mapOf("id" to "a", "text" to "old", "status" to "pending"),
                                                      mapOf("id" to "b", "text" to "old", "status" to "pending")))),
        ), hasMore = false)
        assertEquals(1, timeline.todos.size)
        assertEquals("new", timeline.todos.firstOrNull()?.text)

        // A replayed snapshot newer than the cursor still applies.
        timeline.apply(event(11, "queue", mapOf("pending" to listOf(mapOf("id" to "q", "text" to "later", "ts" to 1)))))
        assertEquals(1, timeline.queue.size)

        // The subscribe reply describes the session at the current cursor.
        timeline.applySubscribedQueue(emptyList())
        assertTrue(timeline.queue.isEmpty())
    }
}

/**
 * Amendments A7 and A8, the store's half: A7's composer and A8's ordering. The channel these
 * conversations are built on is never asked anything, so an inert one stands in for RCCore's demo
 * gateway.
 */
class TerminalAndOrderingTests {
    // A7, control decides who may type

    /** A terminal-driven turn still reads as running. */
    @Test
    fun terminalRunningState() = runTest {
        val running = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp",
                              state = SessionState.running, control = SessionControl.terminal)
        val idle = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp",
                           state = SessionState.readonly, control = SessionControl.terminal)
        assertTrue(ChatStore(session = running, channel = InertChannel(), tasks = backgroundScope).isRunning)
        assertFalse(ChatStore(session = idle, channel = InertChannel(), tasks = backgroundScope).isRunning)
        assertTrue(running.state.isWorking)
        assertFalse(idle.state.isWorking)
    }

    /** A session the app controls stays writable and stoppable. */
    @Test
    fun remoteControlStaysWritable() = runTest {
        val session = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp",
                              state = SessionState.running, control = SessionControl.remote)
        val chat = ChatStore(session = session, channel = InertChannel(), tasks = backgroundScope)
        chat.draft = "hello"
        assertFalse(chat.isReadOnly)
        assertTrue(chat.canSend)
        assertTrue(chat.canStop)
    }

    // A8, a block holds its first position

    private fun streaming(seq: Int, block: String, firstSeq: Int?, text: String): SessionEvent {
        val fields = linkedMapOf<String, Any?>(
            "seq" to seq, "ts" to seq, "kind" to SessionEvent.assistantTextKind,
            "block_id" to block, "delta" to text, "done" to false,
        )
        if (firstSeq != null) fields["first_seq"] = firstSeq
        return jsonOf(fields).decode()
    }

    private fun notice(seq: Int): SessionEvent =
        jsonOf(mapOf("seq" to seq, "ts" to seq, "kind" to SessionEvent.noticeKind, "level" to "info", "text" to "x")).decode()

    /** A streaming block keeps its place while its seq rises. */
    @Test
    fun streamingKeepsPosition() {
        val timeline = Timeline()
        timeline.apply(streaming(10, block = "a", firstSeq = 10, text = "one"))
        timeline.apply(notice(11))
        timeline.apply(streaming(12, block = "a", firstSeq = 10, text = " two"))
        assertEquals(listOf("a", "seq:11"), timeline.entries.map { it.id })
        assertEquals(10, timeline.entry(id = "a")?.seq)
        assertEquals(12, timeline.entry(id = "a")?.latestSeq)
        assertEquals("one two", timeline.entry(id = "a")?.text)
    }

    /** A block that began before the cursor sorts by where it began. */
    @Test
    fun lateArrivalSortsByOrigin() {
        val timeline = Timeline()
        timeline.apply(notice(30))
        timeline.apply(streaming(31, block = "b", firstSeq = 20, text = "earlier"))
        assertEquals(listOf("b", "seq:30"), timeline.entries.map { it.id })
        // The history cursor is a real event seq, never a block position.
        assertEquals(30, timeline.oldestSeq)
    }

    /** History pages honour the same ordering. */
    @Test
    fun historyOrdering() {
        val timeline = Timeline()
        timeline.apply(streaming(50, block = "c", firstSeq = 40, text = "late"))
        timeline.prependHistory(listOf(notice(45)), hasMore = false)
        assertEquals(listOf("c", "seq:45"), timeline.entries.map { it.id })
        assertEquals(45, timeline.oldestSeq)
    }
}
