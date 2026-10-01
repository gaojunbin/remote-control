package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.ApprovalOption
import com.junbingao.remotecontrol.core.protocol.ApprovalPayload
import com.junbingao.remotecontrol.core.protocol.ErrorPayload
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.NoticeLevel
import com.junbingao.remotecontrol.core.protocol.NoticePayload
import com.junbingao.remotecontrol.core.protocol.OptionStyle
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.TodoItem
import com.junbingao.remotecontrol.core.protocol.TodoStatus
import com.junbingao.remotecontrol.core.protocol.TodosPayload
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.protocol.TurnCompletedPayload
import com.junbingao.remotecontrol.core.protocol.TurnStartedPayload
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test

/**
 * `ios/Verification/StoreChecks.swift`: store behaviour that needs no screen. Its checks that run on
 * the demo gateway or read its fixtures — the connection, the chat, the shared sessions, the queued
 * edit, the sessions list, the revived and closed sessions, the dot tones, the settings, pairing,
 * close handling, the reconnect, gap repair, the warm open, sendability, the optimistic send, the
 * terminal settings, the control row, the speed tier and the slash commands — arrive with the demo
 * gateway.
 */
class StoreChecks {
    /** The two detail levels draw from one transcript, and the jump-to-latest count reads the level. */
    @Test
    fun timelineDetail() = runTest {
        val checks = CheckRunner("stores")
        val base = 1_788_944_400_000L
        var seq = 0
        fun event(kind: String, blockID: String?, parent: String? = null, body: SessionEventBody): SessionEvent {
            seq += 1
            return SessionEvent(seq = seq, ts = base + seq, kind = kind, blockID = blockID, parentBlockID = parent, body = body)
        }
        val options = listOf(ApprovalOption(id = "allow", label = "Allow", style = OptionStyle.primary),
                             ApprovalOption(id = "deny", label = "Deny", style = OptionStyle.danger))

        val timeline = Timeline()
        timeline.apply(event(SessionEvent.turnStartedKind, null,
                             body = SessionEventBody.TurnStarted(TurnStartedPayload(turnID = "t1", trigger = EventSource.remote))))
        timeline.apply(event(SessionEvent.userMessageKind, "u-1",
                             body = SessionEventBody.UserMessage(UserMessagePayload(text = "fix the flake"))))
        timeline.apply(event(SessionEvent.thinkingKind, "think-1",
                             body = SessionEventBody.Thinking(StreamTextPayload(text = "a shared clock", done = true))))
        timeline.apply(event(SessionEvent.assistantTextKind, "a-1",
                             body = SessionEventBody.AssistantText(StreamTextPayload(text = "Reproducing first.", done = true))))
        timeline.apply(event(SessionEvent.toolCallKind, "task-1",
                             body = SessionEventBody.ToolCall(ToolCallPayload(tool = "Task", kind = ToolKind.subagent,
                                                                              title = "Audit", status = ToolStatus.running))))
        timeline.apply(event(SessionEvent.assistantTextKind, "sub-1", parent = "task-1",
                             body = SessionEventBody.AssistantText(StreamTextPayload(text = "found it", done = true))))
        timeline.apply(event(SessionEvent.approvalKind, "ap-1", parent = "task-1",
                             body = SessionEventBody.Approval(ApprovalPayload(requestID = "r-1", tool = "Bash", kind = ToolKind.shell,
                                                                              title = "rm -rf build", options = options))))
        timeline.apply(event(SessionEvent.todosKind, null,
                             body = SessionEventBody.Todos(TodosPayload(items = listOf(
                                 TodoItem(id = "1", text = "reproduce", status = TodoStatus.completed))))))
        timeline.apply(event(SessionEvent.noticeKind, null,
                             body = SessionEventBody.Notice(NoticePayload(level = NoticeLevel.warn, text = "the model was switched"))))
        timeline.apply(event(SessionEvent.errorKind, null,
                             body = SessionEventBody.Error(ErrorPayload(message = "the device went away"))))
        timeline.apply(event(SessionEvent.turnCompletedKind, null,
                             body = SessionEventBody.TurnCompleted(TurnCompletedPayload(turnID = "t1", stopReason = StopReason.completed,
                                                                                        durationMS = 4_000))))
        timeline.apply(event(SessionEvent.turnCompletedKind, null,
                             body = SessionEventBody.TurnCompleted(TurnCompletedPayload(turnID = "t2", stopReason = StopReason.interrupted,
                                                                                        durationMS = 900))))

        val detailed = timeline.roots(at = TimelineDetail.detailed).map { it.id }
        checks.equal(detailed, listOf("seq:1", "u-1", "think-1", "a-1", "task-1", "seq:9", "seq:10", "seq:11", "seq:12"),
                     "Detailed is the timeline as it was, with the sub-agent's rows under their tool call")
        checks.equal(timeline.children(of = "task-1", at = TimelineDetail.detailed).map { it.id }, listOf("sub-1", "ap-1"),
                     "and the tool call keeps its children")

        val simple = timeline.roots(at = TimelineDetail.simple).map { it.id }
        checks.equal(simple, listOf("u-1", "a-1", "ap-1", "seq:9", "seq:10", "seq:12"),
                     "Simple keeps the message, the prose, the approval, the notice, the error and the interrupted turn")
        checks.expect("think-1" !in simple, "thinking is not drawn at Simple")
        checks.expect("task-1" !in simple, "nor is a tool call")
        checks.expect("sub-1" !in simple, "nor what a sub-agent wrote under it")
        checks.expect("ap-1" in simple, "but an approval comes up to the top level rather than going with the tool row")
        checks.expect("seq:11" !in simple, "a turn that simply finished says nothing more at Simple")
        checks.equal(timeline.children(of = "task-1", at = TimelineDetail.simple).size, 0,
                     "nothing hangs under a tool call that is not drawn")
        checks.equal(timeline.entries.size, 11,
                     "and the store still holds every block, so switching back shows what was there")
        checks.equal(timeline.todos.size, 1, "including the todo snapshot the header chip is hidden from")

        // The jump-to-latest count is the rows the level draws. A burst of tool calls is nothing at
        // all to a reader who has chosen not to see them.
        val session = Session(sessionID = "detail", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp",
                              state = SessionState.running)
        val preference = SettingsStore(defaults = MemoryUserDefaults())
        val chat = ChatStore(session = session, channel = ScriptedChannel(), tasks = backgroundScope)
        chat.detailSource = { preference.timelineDetail }
        fun burst(first: Int) {
            for (offset in 0 until 3) {
                val number = first + offset
                chat.receive(AppFrame.SessionEvent(sessionID = "detail", deviceID = "d", event = SessionEvent(
                    seq = number, ts = base + number, kind = SessionEvent.toolCallKind, blockID = "tool-$number",
                    body = SessionEventBody.ToolCall(ToolCallPayload(tool = "Read", kind = ToolKind.read, title = "one file",
                                                                     status = ToolStatus.succeeded)))))
            }
        }
        chat.isFollowingTail = false
        burst(first = 1)
        checks.equal(chat.updatesWhileAway, 0, "a burst of tool calls counts as nothing at Simple")
        preference.timelineDetail = TimelineDetail.detailed
        burst(first = 10)
        checks.equal(chat.updatesWhileAway, 3, "and as one per block at Detailed")
        checks.equal(chat.detail, TimelineDetail.detailed, "the transcript reads the level rather than holding a copy")
        checks.expect(!chat.showsTodos, "the todo chip needs a count as well as the level")
        checks.assertAll()
    }

    /** Where the reader is, what they missed while away, and what brings them back. */
    @Test
    fun readingPosition() = runTest {
        val checks = CheckRunner("stores")
        checks.expect(ScrollTail.isAtBottom(contentHeight = 2_000.0, containerHeight = 600.0, offset = 1_400.0),
                      "the foot of the content is the bottom")
        checks.expect(ScrollTail.isAtBottom(contentHeight = 2_000.0, containerHeight = 600.0, offset = 1_365.0),
                      "a row that settles a few points short is still the bottom")
        checks.expect(!ScrollTail.isAtBottom(contentHeight = 2_000.0, containerHeight = 600.0, offset = 1_200.0),
                      "a screenful up is not the bottom")
        checks.expect(ScrollTail.isAtBottom(contentHeight = 200.0, containerHeight = 600.0, offset = 0.0),
                      "a transcript shorter than its container has no bottom to leave")
        checks.equal(ScrollTail.badge(updates = 0), null, "nothing missed carries no count")
        checks.equal(ScrollTail.badge(updates = 3), "3", "three blocks read as three")
        checks.equal(ScrollTail.badge(updates = 140), "99+", "the count stops at 99")

        val session = Session(sessionID = "reading", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp",
                              state = SessionState.running)
        val chat = ChatStore(session = session, channel = ScriptedChannel(), tasks = backgroundScope)
        fun arriving(seq: Int, blockID: String, delta: Boolean = false): AppFrame {
            val payload = if (delta) StreamTextPayload(delta = "more", done = false) else StreamTextPayload(text = "a line", done = true)
            return AppFrame.SessionEvent(sessionID = "reading", deviceID = "d", event = SessionEvent(
                seq = seq, ts = 1_788_944_400_000L + seq, kind = SessionEvent.assistantTextKind, blockID = blockID,
                body = SessionEventBody.AssistantText(payload)))
        }
        chat.receive(arriving(1, blockID = "a-1"))
        checks.equal(chat.updatesWhileAway, 0, "nothing is counted while the reader is at the bottom")
        chat.isFollowingTail = false
        chat.receive(arriving(2, blockID = "a-2"))
        chat.receive(arriving(3, blockID = "a-3"))
        chat.receive(arriving(4, blockID = "a-3", delta = true))
        checks.equal(chat.updatesWhileAway, 2, "blocks are counted while the reader is away, streaming deltas are not")
        chat.isFollowingTail = true
        checks.equal(chat.updatesWhileAway, 0, "returning to the bottom clears the count")

        chat.isFollowingTail = false
        chat.draft = "back to the bottom"
        chat.send()
        checks.expect(chat.isFollowingTail, "sending returns the transcript to the tail")
        checks.assertAll()
    }

    /**
     * A channel that takes every request and answers it with an empty object, as RCCore's scripted
     * channel answers what a check did not script. Its scripted replies and its `hello` of the demo's
     * devices come with the checks that use them, on the demo gateway.
     */
    private class ScriptedChannel : InertChannel() {
        override suspend fun request(request: GatewayRequest): JsonElement = JSONValue.emptyObject
    }
}
