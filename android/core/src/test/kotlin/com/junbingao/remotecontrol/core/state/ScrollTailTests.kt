package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.state.ScrollTail.JumpStep
import com.junbingao.remotecontrol.core.state.ScrollTail.ReaderMotion
import com.junbingao.remotecontrol.core.state.ScrollTail.TailAction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reading-position rule: the timeline follows the newest content only while the reader is at
 * the foot of it, and counts what arrives while they are not.
 */
class ScrollTailTests {
    /** At the bottom is the last screenful, not the last point. */
    @Test
    fun atBottom() {
        // Scrolled all the way down.
        assertTrue(ScrollTail.isAtBottom(contentHeight = 2_000.0, containerHeight = 600.0, offset = 1_400.0))
        // A row that settled a few points short is still at the bottom.
        assertTrue(ScrollTail.isAtBottom(contentHeight = 2_000.0, containerHeight = 600.0, offset = 1_365.0))
        // Past the threshold the reader has gone looking at history.
        assertFalse(ScrollTail.isAtBottom(contentHeight = 2_000.0, containerHeight = 600.0, offset = 1_359.0))
        assertFalse(ScrollTail.isAtBottom(contentHeight = 2_000.0, containerHeight = 600.0, offset = 0.0))
    }

    /** A transcript shorter than its container has no bottom to leave. */
    @Test
    fun shortTranscript() {
        assertTrue(ScrollTail.isAtBottom(contentHeight = 200.0, containerHeight = 600.0, offset = 0.0))
        // Rubber-banding past the end counts as the bottom, not as leaving it.
        assertTrue(ScrollTail.isAtBottom(contentHeight = 2_000.0, containerHeight = 600.0, offset = 1_480.0))
    }

    /** The threshold is a parameter, so a caller can tighten it. */
    @Test
    fun customThreshold() {
        assertTrue(ScrollTail.isAtBottom(contentHeight = 2_000.0, containerHeight = 600.0, offset = 1_390.0, threshold = 20.0))
        assertFalse(ScrollTail.isAtBottom(contentHeight = 2_000.0, containerHeight = 600.0, offset = 1_370.0, threshold = 20.0))
    }

    /** The badge counts updates and stops at 99. */
    @Test
    fun badge() {
        assertNull(ScrollTail.badge(updates = 0))
        assertEquals("1", ScrollTail.badge(updates = 1))
        assertEquals("3", ScrollTail.badge(updates = 3))
        assertEquals("99", ScrollTail.badge(updates = 99))
        assertEquals("99+", ScrollTail.badge(updates = 100))
        assertNull(ScrollTail.spokenBadge(updates = 0))
        assertEquals("1 new update", ScrollTail.spokenBadge(updates = 1))
        assertEquals("4 new updates", ScrollTail.spokenBadge(updates = 4))
    }

    // What the store counts

    /** Blocks that arrive while the reader is away are counted, and cleared on return. */
    @Test
    fun updatesWhileAway() = runTest {
        val chat = ChatStore(session = session(), channel = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay),
                             tasks = backgroundScope)
        chat.receive(frame(text(seq = 1, blockID = "a-1", "first")))
        assertEquals(0, chat.updatesWhileAway)

        chat.isFollowingTail = false
        chat.receive(frame(text(seq = 2, blockID = "a-2", "second")))
        chat.receive(frame(text(seq = 3, blockID = "a-3", "third")))
        assertEquals(2, chat.updatesWhileAway)

        // Streaming into a block that is already there is the same update.
        chat.receive(frame(delta(seq = 4, blockID = "a-3", " and more")))
        assertEquals(2, chat.updatesWhileAway)

        chat.isFollowingTail = true
        assertEquals(0, chat.updatesWhileAway)
    }

    /** A burst of tool calls is nothing at all at Simple, and one per block at Detailed. */
    @Test
    fun updatesFollowTheDetailLevel() = runTest {
        val settings = SettingsStore(defaults = MemoryUserDefaults())
        val chat = ChatStore(session = session(), channel = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay),
                             tasks = backgroundScope)
        chat.detailSource = { settings.timelineDetail }
        chat.isFollowingTail = false

        for (seq in 1..4) chat.receive(frame(tool(seq = seq, blockID = "tool-$seq")))
        assertEquals(0, chat.updatesWhileAway, "the reader chose not to see these rows")
        chat.receive(frame(thinking(seq = 5, blockID = "th-1")))
        assertEquals(0, chat.updatesWhileAway, "nor this one")
        chat.receive(frame(text(seq = 6, blockID = "a-1", "and here is the answer")))
        assertEquals(1, chat.updatesWhileAway, "what is written to them is counted")

        settings.timelineDetail = TimelineDetail.detailed
        for (seq in 7..10) chat.receive(frame(tool(seq = seq, blockID = "tool-$seq")))
        assertEquals(5, chat.updatesWhileAway, "at Detailed the same burst is one per block")
    }

    /**
     * A message from another agent is counted at Detailed and not at Simple. Amendment A34: the
     * jump-to-latest badge counts what the reader would have seen, and at Simple another agent's
     * message is not one of them.
     */
    @Test
    fun agentMessagesAreCountedWithTheAgentsWork() = runTest {
        val settings = SettingsStore(defaults = MemoryUserDefaults())
        val chat = ChatStore(session = session(), channel = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay),
                             tasks = backgroundScope)
        chat.detailSource = { settings.timelineDetail }
        chat.isFollowingTail = false

        chat.receive(frame(fromAgent(seq = 1, blockID = "u-agent")))
        assertEquals(0, chat.updatesWhileAway, "at Simple it is the agent's working, and not drawn")

        settings.timelineDetail = TimelineDetail.detailed
        chat.receive(frame(fromAgent(seq = 2, blockID = "u-agent-2")))
        assertEquals(1, chat.updatesWhileAway, "at Detailed it is a row like any other")
    }

    /** Sending returns the transcript to the tail. */
    @Test
    fun sendingFollowsTheTail() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val live = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == DemoFixtures.liveSessionID },
                                 "the demo live session exists")
        val chat = ChatStore(session = live, channel = gateway, tasks = backgroundScope)
        chat.isFollowingTail = false
        chat.draft = "back to the bottom"
        chat.send()
        assertTrue(chat.isFollowingTail)
        assertEquals(0, chat.updatesWhileAway)
    }

    // Fixtures

    private fun session(): Session =
        Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp", state = SessionState.running)

    private fun frame(event: SessionEvent): AppFrame = AppFrame.SessionEvent(sessionID = "s", deviceID = "d", event = event)

    private fun text(seq: Int, blockID: String, body: String): SessionEvent =
        SessionEvent(seq = seq, ts = 1_788_944_400_000L + seq, kind = SessionEvent.assistantTextKind, blockID = blockID,
                     body = SessionEventBody.AssistantText(StreamTextPayload(text = body, done = true)))

    private fun tool(seq: Int, blockID: String): SessionEvent =
        SessionEvent(seq = seq, ts = 1_788_944_400_000L + seq, kind = SessionEvent.toolCallKind, blockID = blockID,
                     body = SessionEventBody.ToolCall(ToolCallPayload(tool = "Read", kind = ToolKind.read, title = "one file",
                                                                      status = ToolStatus.succeeded)))

    /** Amendment A30: a message the CLI filed as a user turn that nobody typed. */
    private fun fromAgent(seq: Int, blockID: String): SessionEvent =
        SessionEvent(seq = seq, ts = 1_788_944_400_000L + seq, kind = SessionEvent.userMessageKind, blockID = blockID,
                     body = SessionEventBody.UserMessage(UserMessagePayload(text = "recon-ios: three findings need a decision",
                                                                            source = EventSource.agent)))

    private fun thinking(seq: Int, blockID: String): SessionEvent =
        SessionEvent(seq = seq, ts = 1_788_944_400_000L + seq, kind = SessionEvent.thinkingKind, blockID = blockID,
                     body = SessionEventBody.Thinking(StreamTextPayload(text = "a shared clock", done = true)))

    private fun delta(seq: Int, blockID: String, body: String): SessionEvent =
        SessionEvent(seq = seq, ts = 1_788_944_400_000L + seq, kind = SessionEvent.assistantTextKind, blockID = blockID,
                     body = SessionEventBody.AssistantText(StreamTextPayload(delta = body, done = false)))

    // Who is moving

    /** A reader scrolling up is never pulled back, even while the content is still settling. */
    @Test
    fun readerWinsWhileTheRangeMoves() {
        // The bug: rows settling after a turn, or history laid out lazily, move the scrollable range
        // on every frame of an upward drag.
        assertEquals(TailAction.Follow(false),
                     ScrollTail.decide(rangeChanged = true, atBottom = false, following = true, motion = ReaderMotion.reading))
        assertEquals(TailAction.Follow(true),
                     ScrollTail.decide(rangeChanged = true, atBottom = true, following = true, motion = ReaderMotion.reading))
        assertEquals(TailAction.Follow(false),
                     ScrollTail.decide(rangeChanged = false, atBottom = false, following = true, motion = ReaderMotion.reading))
    }

    /** Content that grows while nobody is scrolling pins a reader at the foot and leaves one away alone. */
    @Test
    fun contentGrowsWhileStill() {
        assertEquals(TailAction.ScrollToTail,
                     ScrollTail.decide(rangeChanged = true, atBottom = false, following = true, motion = ReaderMotion.still))
        assertEquals(TailAction.None,
                     ScrollTail.decide(rangeChanged = true, atBottom = false, following = false, motion = ReaderMotion.still))
        // The range shrank until nothing scrolls: back at the bottom.
        assertEquals(TailAction.Follow(true),
                     ScrollTail.decide(rangeChanged = true, atBottom = true, following = false, motion = ReaderMotion.still))
        // No range change and nobody moving: the position speaks for itself.
        assertEquals(TailAction.Follow(false),
                     ScrollTail.decide(rangeChanged = false, atBottom = false, following = true, motion = ReaderMotion.still))
    }

    /** A scroll the view started can only confirm that it arrived. */
    @Test
    fun animatingOnlyConfirmsArrival() {
        assertEquals(TailAction.None,
                     ScrollTail.decide(rangeChanged = true, atBottom = false, following = true, motion = ReaderMotion.animating))
        assertEquals(TailAction.Follow(true),
                     ScrollTail.decide(rangeChanged = false, atBottom = true, following = false, motion = ReaderMotion.animating))
    }

    // Getting all the way back down

    /** A jump that landed short of the tail scrolls again, and one that arrived stops. */
    @Test
    fun jumpKeepsGoingUntilItArrives() {
        assertEquals(JumpStep.again, ScrollTail.jump(attempt = 1, atBottom = false))
        assertEquals(JumpStep.arrived, ScrollTail.jump(attempt = 1, atBottom = true))
        // However far away it started, arriving is what ends it.
        assertEquals(JumpStep.arrived, ScrollTail.jump(attempt = ScrollTail.jumpLimit, atBottom = true))
    }

    /** A transcript growing faster than it is scrolled cannot hold the view for ever. */
    @Test
    fun jumpGivesUp() {
        assertEquals(JumpStep.again, ScrollTail.jump(attempt = ScrollTail.jumpLimit - 1, atBottom = false))
        assertEquals(JumpStep.giveUp, ScrollTail.jump(attempt = ScrollTail.jumpLimit, atBottom = false))
        assertEquals(JumpStep.giveUp, ScrollTail.jump(attempt = 2, atBottom = false, limit = 2))
    }
}
