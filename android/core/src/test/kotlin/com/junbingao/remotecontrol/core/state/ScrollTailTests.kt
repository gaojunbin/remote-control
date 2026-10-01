package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.state.ScrollTail.JumpStep
import com.junbingao.remotecontrol.core.state.ScrollTail.ReaderMotion
import com.junbingao.remotecontrol.core.state.ScrollTail.TailAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The cases that drive a `ChatStore` on the demo gateway — `updatesWhileAway`,
// `updatesFollowTheDetailLevel`, `agentMessagesAreCountedWithTheAgentsWork` and
// `sendingFollowsTheTail` — arrive with the demo.

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
