package com.junbingao.remotecontrol.win.chat

import com.junbingao.remotecontrol.core.protocol.ApprovalOption
import com.junbingao.remotecontrol.core.protocol.ApprovalPayload
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.MetaPayload
import com.junbingao.remotecontrol.core.protocol.NoticeLevel
import com.junbingao.remotecontrol.core.protocol.NoticePayload
import com.junbingao.remotecontrol.core.protocol.OptionStyle
import com.junbingao.remotecontrol.core.protocol.QueuePayload
import com.junbingao.remotecontrol.core.protocol.ResumePayload
import com.junbingao.remotecontrol.core.protocol.ResumeStatus
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.StatusPayload
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.TodosPayload
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.protocol.TurnCompletedPayload
import com.junbingao.remotecontrol.core.protocol.TurnStartedPayload
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.state.OptimisticMessage
import com.junbingao.remotecontrol.core.state.Timeline
import com.junbingao.remotecontrol.core.state.TimelineDetail
import com.junbingao.remotecontrol.win.chat.timeline.TranscriptItem
import com.junbingao.remotecontrol.win.chat.timeline.TranscriptRow
import com.junbingao.remotecontrol.win.chat.timeline.TranscriptSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Events as a device sends them, for the transcript suites. */
internal object ChatEvents {
    fun user(seq: Int, id: String, text: String, source: EventSource = EventSource.remote): SessionEvent =
        SessionEvent(seq = seq, ts = seq.toLong(), kind = SessionEvent.userMessageKind, blockID = id,
            body = SessionEventBody.UserMessage(UserMessagePayload(text = text, source = source)))

    fun text(seq: Int, id: String, text: String, parent: String? = null): SessionEvent =
        SessionEvent(seq = seq, ts = seq.toLong(), kind = SessionEvent.assistantTextKind, blockID = id, parentBlockID = parent,
            body = SessionEventBody.AssistantText(StreamTextPayload(text = text, done = true)))

    fun thinking(seq: Int, id: String): SessionEvent =
        SessionEvent(seq = seq, ts = seq.toLong(), kind = SessionEvent.thinkingKind, blockID = id,
            body = SessionEventBody.Thinking(StreamTextPayload(text = "weighing it up", done = true)))

    fun tool(seq: Int, id: String, tool: String = "Task", kind: ToolKind = ToolKind.subagent, status: ToolStatus = ToolStatus.running): SessionEvent =
        SessionEvent(seq = seq, ts = seq.toLong(), kind = SessionEvent.toolCallKind, blockID = id,
            body = SessionEventBody.ToolCall(
                ToolCallPayload(tool = tool, kind = kind, title = if (tool == "Task") "Audit clocks" else tool, status = status, startedAt = seq.toLong()),
            ))

    fun approval(seq: Int, id: String, parent: String? = null): SessionEvent =
        SessionEvent(seq = seq, ts = seq.toLong(), kind = SessionEvent.approvalKind, blockID = id, parentBlockID = parent,
            body = SessionEventBody.Approval(
                ApprovalPayload(
                    requestID = "req-a", tool = "Bash", kind = ToolKind.shell, title = "git commit",
                    options = listOf(ApprovalOption(id = "allow", label = "Allow once", style = OptionStyle.primary)),
                ),
            ))

    fun notice(seq: Int): SessionEvent =
        SessionEvent(seq = seq, ts = seq.toLong(), kind = SessionEvent.noticeKind,
            body = SessionEventBody.Notice(NoticePayload(level = NoticeLevel.info, text = "the device reconnected")))

    fun turnEnded(seq: Int, reason: StopReason): SessionEvent =
        SessionEvent(seq = seq, ts = seq.toLong(), kind = SessionEvent.turnCompletedKind,
            body = SessionEventBody.TurnCompleted(TurnCompletedPayload(turnID = "t$seq", stopReason = reason, durationMS = 1)))

    fun state(seq: Int, body: SessionEventBody, kind: String): SessionEvent =
        SessionEvent(seq = seq, ts = seq.toLong(), kind = kind, body = body)

    fun timeline(events: List<SessionEvent>): Timeline {
        val timeline = Timeline()
        for (event in events) timeline.apply(event)
        return timeline
    }
}

/** `web/tests/timeline.test.ts` § "detail levels" and the reducer's rules the selection carries over the core's transcript. */
class ChatTranscriptSelectionTests {
    /** The web's `conversation()` fixture. */
    private fun conversation(): Timeline = ChatEvents.timeline(
        listOf(
            ChatEvents.user(1, "u1", "fix it"),
            ChatEvents.thinking(2, "th1"),
            ChatEvents.tool(3, "task1"),
            ChatEvents.text(4, "sub1", "the sub-agent reported back", parent = "task1"),
            ChatEvents.approval(5, "ap1", parent = "task1"),
            ChatEvents.text(6, "a1", "done"),
            ChatEvents.notice(7),
        ),
    )

    private fun roots(selection: TranscriptSelection): List<String> = selection.roots.map { it.id }

    @Test
    fun detailedDrawsTheWholeTranscript() {
        val selection = TranscriptSelection(conversation(), TimelineDetail.detailed)
        assertEquals(listOf("u1", "th1", "task1", "a1", "seq:7"), roots(selection))
        assertEquals(listOf("sub1", "ap1"), selection.children["task1"]?.map { it.id })
    }

    @Test
    fun simpleDropsTheWorkingsInPlaceNotCollapsed() {
        val selection = TranscriptSelection(conversation(), TimelineDetail.simple)
        assertEquals(listOf("u1", "ap1", "a1", "seq:7"), roots(selection))
        assertTrue(selection.children.isEmpty())
    }

    @Test
    fun aCardWaitingOnAnAnswerComesUpOutOfAHiddenToolCall() {
        val ids = roots(TranscriptSelection(conversation(), TimelineDetail.simple))
        assertTrue("ap1" in ids)
        assertFalse("sub1" in ids)
    }

    @Test
    fun aSlashCommandsOwnOutputIsDrawnAtBothLevels() {
        val timeline = conversation()
        timeline.apply(ChatEvents.tool(8, "cmd1", tool = "/usage", kind = ToolKind.other, status = ToolStatus.succeeded))
        for (detail in listOf(TimelineDetail.simple, TimelineDetail.detailed)) {
            assertTrue("cmd1" in roots(TranscriptSelection(timeline, detail)))
        }
    }

    @Test
    fun anUnconfirmedSendIsDrawnLastAtBothLevels() {
        val timeline = conversation()
        timeline.addOptimistic(OptimisticMessage(id = "req-1", text = "and again"))
        for (detail in listOf(TimelineDetail.simple, TimelineDetail.detailed)) {
            assertEquals("req-1", TranscriptSelection(timeline, detail).roots.lastOrNull()?.id)
        }
    }

    @Test
    fun anInterruptedTurnIsARowAndACleanOneIsNot() {
        val timeline = conversation()
        timeline.apply(ChatEvents.turnEnded(8, StopReason.completed))
        timeline.apply(ChatEvents.turnEnded(9, StopReason.interrupted))
        for (detail in listOf(TimelineDetail.simple, TimelineDetail.detailed)) {
            val turns = TranscriptSelection(timeline, detail).roots.filter { (it as? TranscriptItem.Entry)?.entry?.turnCompleted != null }
            assertEquals(listOf("seq:9"), turns.map { it.id })
        }
    }

    @Test
    fun stateOnlyKindsAreNoRows() {
        val timeline = ChatEvents.timeline(
            listOf(
                ChatEvents.state(1, SessionEventBody.Status(StatusPayload(state = SessionState.running)), kind = SessionEvent.statusKind),
                ChatEvents.state(2, SessionEventBody.Meta(MetaPayload(title = "Renamed")), kind = SessionEvent.metaKind),
                ChatEvents.state(3, SessionEventBody.Queue(QueuePayload(pending = emptyList())), kind = SessionEvent.queueKind),
                ChatEvents.state(4, SessionEventBody.Todos(TodosPayload(items = emptyList())), kind = SessionEvent.todosKind),
                ChatEvents.state(
                    5,
                    SessionEventBody.TurnStarted(TurnStartedPayload(turnID = "x", trigger = EventSource.remote)),
                    kind = SessionEvent.turnStartedKind,
                ),
                ChatEvents.turnEnded(6, StopReason.completed),
            ),
        )
        val selection = TranscriptSelection(timeline, TimelineDetail.detailed)
        assertTrue(selection.roots.isEmpty())
        assertEquals(0, selection.rowCount)
        assertEquals(6, selection.newestSeq)
    }

    @Test
    fun anotherAgentsWordsAreItsWorkings() {
        val timeline = ChatEvents.timeline(
            listOf(
                ChatEvents.user(1, "u1", "fix it"),
                ChatEvents.user(2, "u2", "recon-ios: Recon complete.", source = EventSource.agent),
            ),
        )
        assertEquals(listOf("u1", "u2"), roots(TranscriptSelection(timeline, TimelineDetail.detailed)))
        assertEquals(listOf("u1"), roots(TranscriptSelection(timeline, TimelineDetail.simple)))
    }

    @Test
    fun theMomentOfResumingIsNoRow() {
        val timeline = ChatEvents.timeline(
            listOf(
                ChatEvents.state(1, SessionEventBody.Resume(ResumePayload(status = ResumeStatus.scheduled, at = 10)), kind = SessionEvent.resumeKind),
                ChatEvents.state(2, SessionEventBody.Resume(ResumePayload(status = ResumeStatus.fired)), kind = SessionEvent.resumeKind),
            ),
        )
        assertEquals(listOf("seq:1"), roots(TranscriptSelection(timeline, TimelineDetail.simple)))
    }

    @Test
    fun theKeysAreTheFirstAndTheLastDrawnRow() {
        val selection = TranscriptSelection(conversation(), TimelineDetail.simple)
        assertEquals("u1", selection.firstKey)
        assertEquals("seq:7", selection.lastKey)
    }

    /** A row the web renders `null` for takes no gap in the column. */
    @Test
    fun anAnswerWithNoTextYetDrawsNothing() {
        val empty = SessionEvent(
            seq = 1,
            ts = 1,
            kind = SessionEvent.assistantTextKind,
            blockID = "a0",
            body = SessionEventBody.AssistantText(StreamTextPayload(delta = "", done = false)),
        )
        val selection = TranscriptSelection(ChatEvents.timeline(listOf(empty)), TimelineDetail.detailed)
        assertTrue(selection.roots.filter(TranscriptRow::draws).isEmpty())
    }
}
