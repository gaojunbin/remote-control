package com.junbingao.remotecontrol.android.shell

import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.StatusPayload
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.state.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the offline cache keeps of a rendered transcript: whole answers, and no session chrome. */
class TimelineEntrySourceTest {
    private fun stream(seq: Int, delta: String, done: Boolean) = SessionEvent(
        seq = seq, ts = 1_000L + seq, kind = SessionEvent.assistantTextKind, blockID = "reply",
        body = SessionEventBody.AssistantText(StreamTextPayload(delta = delta, done = done)),
    )

    @Test
    fun aStreamedAnswerIsStoredWhole() {
        val timeline = Timeline()
        timeline.apply(stream(1, "Reproducing ", done = false))
        timeline.apply(stream(2, "first.", done = true))
        val stored = timeline.entries.single().sourceEvent!!
        assertEquals(SessionEvent.assistantTextKind, stored.kind)
        assertEquals("reply", stored.blockID)
        val body = stored.body as SessionEventBody.AssistantText
        assertEquals("Reproducing first.", body.payload.text)
        assertEquals(true, body.payload.done)
    }

    @Test
    fun aMessageIsStoredAsItCame() {
        val timeline = Timeline()
        val message = SessionEvent(seq = 3, ts = 9, kind = SessionEvent.userMessageKind,
                                   body = SessionEventBody.UserMessage(UserMessagePayload(text = "Find the race")))
        timeline.apply(message)
        val stored = timeline.entries.single().sourceEvent!!
        assertEquals(SessionEvent.userMessageKind, stored.kind)
        assertNull("a row keyed by its seq has no block of its own", stored.blockID)
        assertEquals(message.body, stored.body)
    }

    @Test
    fun theSessionsOwnRowsAreNotStored() {
        val timeline = Timeline()
        timeline.apply(SessionEvent(seq = 4, ts = 10, kind = SessionEvent.statusKind, body = SessionEventBody.Status(StatusPayload(state = SessionState.running))))
        timeline.entries.forEach { assertNull(it.sourceEvent) }
    }
}
