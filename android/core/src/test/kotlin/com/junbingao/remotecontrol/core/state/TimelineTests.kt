package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.jsonOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The timeline reducer. */
class TimelineTests {
    private fun event(seq: Int, kind: String, fields: Map<String, Any?>): SessionEvent {
        val members = LinkedHashMap(fields)
        members["seq"] = seq
        members["ts"] = 1_788_944_400_000 + seq
        members["kind"] = kind
        return jsonOf(members).decode()
    }

    /** Streaming deltas accumulate into one block. */
    @Test
    fun streamingAppends() {
        val timeline = Timeline()
        timeline.apply(event(1, "assistant_text", mapOf("block_id" to "a", "delta" to "Hello", "done" to false)))
        timeline.apply(event(2, "assistant_text", mapOf("block_id" to "a", "delta" to " there", "done" to false)))
        assertEquals(1, timeline.entries.size)
        assertEquals("Hello there", timeline.entry(id = "a")?.text)
        assertEquals(true, timeline.entry(id = "a")?.isStreaming)

        timeline.apply(event(3, "assistant_text", mapOf("block_id" to "a", "text" to "Hello there.", "done" to true)))
        assertEquals("Hello there.", timeline.entry(id = "a")?.text)
        assertEquals(false, timeline.entry(id = "a")?.isStreaming)
    }

    /** A later event for a block replaces it and keeps its position. */
    @Test
    fun replacementKeepsPosition() {
        val timeline = Timeline()
        timeline.apply(event(1, "tool_call", mapOf("block_id" to "b", "tool" to "Bash", "tool_kind" to "shell",
                                                   "title" to "pytest", "status" to "running")))
        timeline.apply(event(2, "notice", mapOf("level" to "info", "text" to "still running")))
        timeline.apply(event(3, "tool_call", mapOf("block_id" to "b", "tool" to "Bash", "tool_kind" to "shell",
                                                   "title" to "pytest", "status" to "succeeded")))
        assertEquals(2, timeline.entries.size)
        assertEquals("b", timeline.entries.first().id)
        assertEquals(ToolStatus.succeeded, timeline.entry(id = "b")?.toolCall?.status)
    }

    /** Late and duplicate frames are dropped. */
    @Test
    fun staleFramesDropped() {
        for (seq in listOf(3, 5)) {
            val timeline = Timeline()
            timeline.apply(event(5, "notice", mapOf("level" to "info", "text" to "first")))
            assertFalse(timeline.apply(event(seq, "notice", mapOf("level" to "info", "text" to "late"))), "seq $seq")
            assertEquals(1, timeline.entries.size, "seq $seq")
            assertEquals(5, timeline.lastSeq, "seq $seq")
        }
    }

    /** History prepends without moving the live cursor or clobbering newer blocks. */
    @Test
    fun historyMerge() {
        val timeline = Timeline()
        timeline.apply(event(20, "assistant_text", mapOf("block_id" to "a", "text" to "newest", "done" to true)))
        timeline.prependHistory(listOf(
            event(10, "user_message", mapOf("block_id" to "u", "text" to "older")),
            event(12, "assistant_text", mapOf("block_id" to "a", "text" to "stale", "done" to true)),
        ), hasMore = true)
        assertEquals("u", timeline.entries.first().id)
        assertEquals("newest", timeline.entry(id = "a")?.text)
        assertEquals(20, timeline.lastSeq)
        assertEquals(10, timeline.oldestSeq)
    }

    /** Todos and queue are snapshots, not rows. */
    @Test
    fun snapshotsAreNotRows() {
        val timeline = Timeline()
        timeline.apply(event(1, "todos", mapOf("items" to listOf(mapOf("id" to "1", "text" to "a", "status" to "pending")))))
        timeline.apply(event(2, "queue", mapOf("pending" to listOf(mapOf("id" to "q", "text" to "later", "ts" to 1)))))
        assertTrue(timeline.entries.isEmpty())
        assertEquals(1, timeline.todos.size)
        assertEquals(1, timeline.queue.size)
        assertEquals(2, timeline.lastSeq)
    }

    private val options = listOf(
        mapOf("id" to "allow", "label" to "Allow", "style" to "primary"),
        mapOf("id" to "deny", "label" to "Deny", "style" to "danger"),
    )

    /** A resolved approval stops being actionable. */
    @Test
    fun approvalLifecycle() {
        val timeline = Timeline()
        timeline.apply(event(1, "approval", mapOf("block_id" to "ap", "request_id" to "r", "tool" to "Bash",
                                                  "tool_kind" to "shell", "title" to "rm -rf",
                                                  "options" to options, "status" to "pending")))
        assertEquals("ap", timeline.pendingRequest?.id)
        timeline.apply(event(2, "approval", mapOf("block_id" to "ap", "request_id" to "r", "tool" to "Bash",
                                                  "tool_kind" to "shell", "title" to "rm -rf",
                                                  "options" to options, "status" to "resolved",
                                                  "decision" to mapOf("option_id" to "allow", "by" to "remote"))))
        assertNull(timeline.pendingRequest)
    }

    /** Sub-agent rows hang under their parent tool call. */
    @Test
    fun nestedRows() {
        val timeline = Timeline()
        timeline.apply(event(1, "tool_call", mapOf("block_id" to "task", "tool" to "Task",
                                                   "tool_kind" to "subagent", "title" to "Audit", "status" to "running")))
        timeline.apply(event(2, "assistant_text", mapOf("block_id" to "sub", "parent_block_id" to "task",
                                                        "text" to "found it", "done" to true)))
        assertEquals(1, timeline.roots.size)
        assertEquals(1, timeline.children(of = "task").size)
    }

    // Two levels of detail

    /** A transcript with one of everything the two levels disagree about. */
    private fun mixedTimeline(): Timeline {
        val timeline = Timeline()
        timeline.apply(event(1, "turn_started", mapOf("turn_id" to "t", "trigger" to "remote")))
        timeline.apply(event(2, "user_message", mapOf("block_id" to "u", "text" to "fix the flake")))
        timeline.apply(event(3, "thinking", mapOf("block_id" to "th", "text" to "a shared clock", "done" to true)))
        timeline.apply(event(4, "assistant_text", mapOf("block_id" to "a", "text" to "Reproducing.", "done" to true)))
        timeline.apply(event(5, "tool_call", mapOf("block_id" to "task", "tool" to "Task", "tool_kind" to "subagent",
                                                   "title" to "Audit", "status" to "running")))
        timeline.apply(event(6, "assistant_text", mapOf("block_id" to "sub", "parent_block_id" to "task",
                                                        "text" to "found it", "done" to true)))
        timeline.apply(event(7, "approval", mapOf("block_id" to "ap", "parent_block_id" to "task",
                                                  "request_id" to "r", "tool" to "Bash", "tool_kind" to "shell",
                                                  "title" to "rm -rf build", "options" to options,
                                                  "status" to "pending")))
        timeline.apply(event(8, "todos", mapOf("items" to listOf(mapOf("id" to "1", "text" to "a", "status" to "pending")))))
        timeline.apply(event(9, "notice", mapOf("level" to "warn", "text" to "the model was switched")))
        timeline.apply(event(10, "error", mapOf("message" to "the device went away")))
        timeline.apply(event(11, "turn_completed", mapOf("turn_id" to "t", "stop_reason" to "completed",
                                                         "duration_ms" to 4_000)))
        timeline.apply(event(12, "turn_completed", mapOf("turn_id" to "t2", "stop_reason" to "interrupted",
                                                         "duration_ms" to 900)))
        return timeline
    }

    /** Detailed is the whole transcript. */
    @Test
    fun detailedDrawsEverything() {
        val timeline = mixedTimeline()
        assertEquals(timeline.roots.map { it.id }, timeline.roots(at = TimelineDetail.detailed).map { it.id })
        assertEquals(listOf("seq:1", "u", "th", "a", "task", "seq:9", "seq:10", "seq:11", "seq:12"),
                     timeline.roots(at = TimelineDetail.detailed).map { it.id })
        assertEquals(listOf("sub", "ap"), timeline.children(of = "task", at = TimelineDetail.detailed).map { it.id })
    }

    /** Simple draws only what is written to the reader. */
    @Test
    fun simpleDropsTheAgentsOwnWork() {
        val timeline = mixedTimeline()
        val rows = timeline.roots(at = TimelineDetail.simple).map { it.id }
        // The message, the prose, the approval, the notice, the error and the turn that was stopped.
        // Not thinking, not the tool call, not the sub-agent's prose under it, not a turn that simply
        // finished.
        assertEquals(listOf("u", "a", "ap", "seq:9", "seq:10", "seq:12"), rows)
        assertTrue(timeline.children(of = "task", at = TimelineDetail.simple).isEmpty())
        // Nothing was dropped from the store, so the other level still has it.
        assertEquals(11, timeline.entries.size)
        assertEquals(1, timeline.todos.size)
    }

    /** A message this app has sent is drawn at either level. */
    @Test
    fun pendingRowsSurviveTheFilter() {
        val timeline = mixedTimeline()
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "and the CI runner"))
        assertEquals("req-1", timeline.roots(at = TimelineDetail.simple).last().pending?.id)
        assertEquals("req-1", timeline.roots(at = TimelineDetail.detailed).last().pending?.id)
    }

    /**
     * Amendment A34: a teammate's report is the agent's working, not the reader's own words, so the
     * level that hides the working hides it too.
     */
    @Test
    fun agentMessageFollowsTheDetailLevel() {
        val timeline = Timeline()
        timeline.apply(event(1, "user_message", mapOf("block_id" to "mine", "text" to "fix the flake")))
        timeline.apply(event(2, "user_message", mapOf("block_id" to "theirs", "source" to "agent",
                                                      "text" to "recon-ios: three findings need a decision")))
        timeline.apply(event(3, "assistant_text", mapOf("block_id" to "a", "text" to "Reading it.", "done" to true)))

        assertEquals(listOf("mine", "theirs", "a"), timeline.roots(at = TimelineDetail.detailed).map { it.id })
        assertEquals(listOf("mine", "a"), timeline.roots(at = TimelineDetail.simple).map { it.id })
        assertEquals(true, timeline.entry(id = "theirs")?.isDrawn(at = TimelineDetail.detailed))
        assertEquals(false, timeline.entry(id = "theirs")?.isDrawn(at = TimelineDetail.simple))
        // The person's own words are drawn at both, whichever way they arrived.
        assertEquals(true, timeline.entry(id = "mine")?.isDrawn(at = TimelineDetail.simple))
    }

    /** The level is a preference of this device, and Simple is the default. */
    @Test
    fun detailPreference() {
        val defaults = MemoryUserDefaults()
        val settings = SettingsStore(defaults = defaults)
        assertEquals(TimelineDetail.simple, settings.timelineDetail)
        settings.timelineDetail = TimelineDetail.detailed
        assertEquals(TimelineDetail.detailed, SettingsStore(defaults = defaults).timelineDetail)
        assertEquals(listOf("Simple", "Detailed"), TimelineDetail.allCases.map { it.title })
        assertEquals("Simple shows only what is written to you. " +
                         "Detailed adds thinking, tool calls and the task list.",
                     TimelineDetail.footnote)
    }
}
