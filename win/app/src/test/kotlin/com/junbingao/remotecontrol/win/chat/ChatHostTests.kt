package com.junbingao.remotecontrol.win.chat

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.StatusPayload
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.state.TimelineDetail
import com.junbingao.remotecontrol.win.app.LaunchOptions
import com.junbingao.remotecontrol.win.app.ModelHarness
import com.junbingao.remotecontrol.win.app.signOut
import com.junbingao.remotecontrol.win.chat.page.ChatHost
import com.junbingao.remotecontrol.win.chat.page.ChatMemory
import com.junbingao.remotecontrol.win.chat.page.sourceEvent
import com.junbingao.remotecontrol.win.chat.timeline.TranscriptSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The page's hold on a conversation, on the core's offline demo: what it opens with and what it leaves behind when it goes. */
class ChatHostTests {
    private val device = DemoFixtures.macDeviceID
    private val session = DemoFixtures.approvalSessionID

    @Test
    fun aConversationOpensOnceAndItsDraftOutlivesIt() = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            assertTrue(harness.waitFor { connection.hasSnapshot })
            val host = ChatHost()
            host.open(device, session, this)
            val chat = assertNotNull(host.chat)
            assertSame(host, ChatMemory.of(this).open)
            assertTrue(harness.waitFor { chat.timeline.historyLoaded })
            // The same session asked for again is the conversation already open.
            host.open(device, session, this)
            assertSame(chat, host.chat)

            chat.draft = "half a thought"
            host.close(this)
            assertNull(host.chat)
            assertNull(host.actions)
            assertNull(ChatMemory.of(this).open)
            assertEquals("half a thought", drafts.draft(account = account, key = chat.key))

            host.open(device, session, this)
            assertEquals("half a thought", host.chat?.draft)
            host.close(this)
            signOut()
        }
    }

    @Test
    fun signingOutClosesTheOpenConversation() = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            assertTrue(harness.waitFor { connection.hasSnapshot })
            val host = ChatHost()
            host.open(device, session, this)
            assertNotNull(host.chat)
            signOut()
            assertNull(host.chat)
            assertNull(ChatMemory.of(this).open)
        }
    }

    @Test
    fun aSessionTheGatewayDoesNotListOpensNothing() = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            assertTrue(harness.waitFor { connection.hasSnapshot })
            val host = ChatHost()
            host.open(device, "no-such-session", this)
            assertNull(host.chat)
            signOut()
        }
    }

    /** What the transcript cache holds of a row: the finished event, never the last delta, and none of the state-only kinds. */
    @Test
    fun aStoredRowIsTheFinishedEventItCameFrom() {
        fun delta(seq: Int, text: String) = SessionEvent(
            seq = seq,
            ts = seq.toLong(),
            kind = SessionEvent.assistantTextKind,
            blockID = "a1",
            body = SessionEventBody.AssistantText(StreamTextPayload(delta = text, done = false)),
        )
        val timeline = ChatEvents.timeline(
            listOf(
                ChatEvents.user(1, "u1", "fix it"),
                delta(2, "Hel"),
                delta(3, "lo"),
                ChatEvents.notice(4),
                ChatEvents.state(5, SessionEventBody.Status(StatusPayload(state = SessionState.running)), kind = SessionEvent.statusKind),
            ),
        )
        val stored = timeline.entries.mapNotNull { it.sourceEvent }
        val answer = stored.first { it.blockID == "a1" }
        assertEquals(StreamTextPayload(text = "Hello", done = true), (answer.body as? SessionEventBody.AssistantText)?.payload)
        val notice = stored.first { it.kind == SessionEvent.noticeKind }
        assertNull(notice.blockID)
        assertTrue(stored.none { it.kind == SessionEvent.statusKind })

        val replayed = ChatEvents.timeline(stored)
        val rows = { selection: TranscriptSelection -> selection.roots.map { it.id } }
        assertEquals(rows(TranscriptSelection(timeline, TimelineDetail.detailed)), rows(TranscriptSelection(replayed, TimelineDetail.detailed)))
    }
}
