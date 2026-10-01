package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.protocol.TurnCompletedPayload
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Amendment A14: a message sent into a running turn is read by the agent at its next step, so the
 * device emits the `user_message` when the agent takes it and not when it was sent. Until that block
 * arrives the app's own row is the message, and it belongs at the foot of the transcript: that is
 * where the device's block will land.
 */
class SteeredSendTests {
    private val requestID = "req-steer"

    private fun userMessage(text: String, id: String, seq: Int, firstSeq: Int,
                            source: EventSource = EventSource.remote): SessionEvent =
        SessionEvent(seq = seq, ts = 0, kind = SessionEvent.userMessageKind, blockID = id, firstSeq = firstSeq,
                     body = SessionEventBody.UserMessage(UserMessagePayload(text = text, source = source)))

    private fun assistantText(delta: String, id: String, seq: Int, firstSeq: Int, done: Boolean = false): SessionEvent =
        SessionEvent(seq = seq, ts = 0, kind = SessionEvent.assistantTextKind, blockID = id, firstSeq = firstSeq,
                     body = SessionEventBody.AssistantText(StreamTextPayload(delta = delta, done = done)))

    private fun toolCall(title: String, id: String, seq: Int, firstSeq: Int, status: ToolStatus): SessionEvent =
        SessionEvent(seq = seq, ts = 0, kind = SessionEvent.toolCallKind, blockID = id, firstSeq = firstSeq,
                     body = SessionEventBody.ToolCall(ToolCallPayload(tool = "Bash", kind = ToolKind.shell, title = title,
                                                                      status = status)))

    private fun text(entry: TimelineEntry): String {
        entry.userMessage?.let { return it.text }
        entry.toolCall?.let { return it.title }
        return entry.text
    }

    /**
     * The session that produced amendment A14, replayed: the app steers a running Codex turn, the
     * turn keeps talking, and the device's own block for the steered message arrives after the
     * output that preceded it. The transcript must end in the order the terminal on the same session
     * drew.
     */
    @Test
    fun steeredRowHoldsTheFootUntilTheDeviceBlockArrives() {
        val timeline = Timeline()
        timeline.apply(userMessage("当前工作目录是哪里", id = "u-42", seq = 42, firstSeq = 42))

        // The send: `accepted: "steered"` leaves this row standing (A12 + A14).
        timeline.addOptimistic(OptimisticMessage(id = requestID, text = "有什么项目"))
        timeline.markSteered(requestID)
        assertEquals(requestID, timeline.roots.last().pending?.id)

        // The turn was mid-step when the message was sent and carries on: text it had already
        // started, the tool it had already called, then the answer to the *previous* message. All of
        // it sorts above the row.
        timeline.apply(assistantText("我确认一下", id = "a-46", seq = 46, firstSeq = 46))
        timeline.apply(assistantText("当前工作目录。", id = "a-46", seq = 49, firstSeq = 46, done = true))
        assertEquals(requestID, timeline.roots.last().pending?.id, "streaming text does not displace it")

        timeline.apply(toolCall("pwd", id = "t-50", seq = 50, firstSeq = 50, status = ToolStatus.running))
        timeline.apply(toolCall("pwd", id = "t-50", seq = 51, firstSeq = 50, status = ToolStatus.succeeded))
        assertEquals(requestID, timeline.roots.last().pending?.id, "nor does a tool call or its result")

        timeline.apply(assistantText("当前工作目录是 /Users/junbingao。", id = "a-53", seq = 58, firstSeq = 53, done = true))
        assertEquals(requestID, timeline.roots.last().pending?.id,
                     "the agent has not taken the message yet, so the row is still the message")
        assertEquals(1, timeline.optimistic.size)

        // Codex reads the steered message at its next step and the device emits the block then, under
        // the request id and after the output above it.
        timeline.apply(userMessage("有什么项目", id = requestID, seq = 59, firstSeq = 59))
        assertTrue(timeline.optimistic.isEmpty(), "the device's block is the message now")

        timeline.apply(assistantText("我查看一下当前目录…", id = "a-60", seq = 65, firstSeq = 60, done = true))

        assertEquals(listOf("u-42", "a-46", "t-50", "a-53", requestID, "a-60"), timeline.roots.map { it.id })
        assertEquals(
            listOf(
                "当前工作目录是哪里",
                "我确认一下当前工作目录。",
                "pwd",
                "当前工作目录是 /Users/junbingao。",
                "有什么项目",
                "我查看一下当前目录…",
            ),
            timeline.roots.map(::text),
            "the same order the terminal on this session drew",
        )
        assertEquals(1, timeline.roots.count { it.userMessage?.text == "有什么项目" },
                     "and exactly one copy of the steered message")
    }

    /** The end of the turn does not retire a steered row. */
    @Test
    fun turnCompletedLeavesTheRowStanding() {
        val timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id = requestID, text = "有什么项目"))
        timeline.markSteered(requestID)

        timeline.apply(SessionEvent(seq = 70, ts = 0, kind = SessionEvent.turnCompletedKind,
                                    body = SessionEventBody.TurnCompleted(TurnCompletedPayload(
                                        turnID = "t", stopReason = StopReason.completed, durationMS = 900))))
        assertEquals(1, timeline.optimistic.size,
                     "the device emits the block at the turn's end; only that block retires the row")

        timeline.apply(userMessage("有什么项目", id = requestID, seq = 71, firstSeq = 71))
        assertTrue(timeline.optimistic.isEmpty())
    }

    /** A steered row waits without ever claiming the send is lost. */
    @Test
    fun steeringIsNotAnUnconfirmedDelivery() {
        val sent = Instant.now().minusSeconds(600)
        val waiting = OptimisticMessage(id = "req", text = "有什么项目", sentAt = sent, isSteering = true)
        assertFalse(waiting.isUnconfirmed(), "the device took it; the agent has not read it yet")

        val timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id = "req", text = "有什么项目", sentAt = sent))
        assertEquals(1, timeline.unconfirmedOptimistic().size, "before the reply it is simply in flight")
        timeline.markSteered("req")
        assertTrue(timeline.unconfirmedOptimistic().isEmpty())
        assertEquals(1, timeline.optimistic.size, "and it is still on screen either way")
    }

    /**
     * A page of older history can hold a message whose words this send repeats ("continue", "go on").
     * Under A14 the row waits for a whole turn, so that page must not be allowed to retire it: history
     * is older than the send.
     */
    @Test
    fun historyDoesNotReconcileByText() {
        val timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id = requestID, text = "继续"))
        timeline.markSteered(requestID)

        timeline.prependHistory(listOf(userMessage("继续", id = "older-block", seq = 8, firstSeq = 8)), hasMore = false)
        assertEquals(1, timeline.optimistic.size, "an older message is not this one")

        timeline.prependHistory(listOf(userMessage("继续", id = requestID, seq = 30, firstSeq = 30)), hasMore = false)
        assertTrue(timeline.optimistic.isEmpty(), "the block under the request id still is")
    }

    /** A device that mints its own ids is still reconciled by text, live. */
    @Test
    fun liveTextFallbackStillApplies() {
        val timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id = requestID, text = "有什么项目"))
        timeline.markSteered(requestID)

        timeline.apply(userMessage("有什么项目", id = "device-block", seq = 59, firstSeq = 59))
        assertTrue(timeline.optimistic.isEmpty())
        assertEquals(listOf("device-block"), timeline.roots.map { it.id })
    }
}
