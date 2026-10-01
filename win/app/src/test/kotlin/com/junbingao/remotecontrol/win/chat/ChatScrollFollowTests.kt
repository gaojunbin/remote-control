package com.junbingao.remotecontrol.win.chat

import com.junbingao.remotecontrol.core.state.TimelineDetail
import com.junbingao.remotecontrol.win.chat.timeline.ScrollFollow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val viewport = 400f

/**
 * `web/tests/useScrollFollow.test.tsx`, over the rule the transcript applies: a 400-point window
 * onto content that grows the way events make it grow.
 */
class ChatScrollFollowTests {
    private class Harness(revision: Int, first: String?, last: String?, redraw: TimelineDetail = TimelineDetail.detailed) {
        val follow = ScrollFollow(ScrollFollow.Rows("$revision", redraw, first, last))
        var offset = 0f
        var height = 1000f

        init {
            follow.measured(height)
            follow.reachedBottom()
            offset = height - viewport
        }

        /** The reader scrolls to `to`. Answers whether older history was asked for. */
        fun scroll(to: Float): Boolean {
            offset = to
            return follow.scrolled(offset = to, contentHeight = height, viewportHeight = viewport)
        }

        /** A render with new rows and the content at `height`, then the view does what the rule answers. */
        fun update(revision: Int, first: String?, last: String?, redraw: TimelineDetail = TimelineDetail.detailed, height: Float? = null) {
            val scrolls = follow.rowsChanged(ScrollFollow.Rows("$revision", redraw, first, last))
            if (height != null) this.height = height
            follow.measured(this.height)?.let { offset += it }
            if (scrolls) {
                offset = this.height - viewport
                follow.reachedBottom()
            }
        }
    }

    @Test
    fun itStartsPinnedToTheBottom() {
        val h = Harness(revision = 1, first = "a", last = "a")
        assertTrue(h.follow.following)
        assertEquals(0, h.follow.missed)
        assertEquals(600f, h.offset)
    }

    @Test
    fun itLetsGoWhenTheReaderScrollsUpAndTakesHoldAgainAtTheBottom() {
        val h = Harness(revision = 1, first = "a", last = "a")
        h.scroll(to = 200f)
        assertFalse(h.follow.following)
        h.scroll(to = 600f)
        assertTrue(h.follow.following)
    }

    @Test
    fun aBlockAppendedBelowDoesNotDragTheReaderDown() {
        val h = Harness(revision = 1, first = "a", last = "c")
        h.scroll(to = 200f)
        h.update(revision = 2, first = "a", last = "d", height = 1400f)
        assertEquals(200f, h.offset)
    }

    @Test
    fun aPrependedHistoryPageKeepsTheAnchor() {
        val h = Harness(revision = 5, first = "c", last = "e")
        h.scroll(to = 100f)
        h.update(revision = 5, first = "a", last = "e", height = 1600f)
        assertEquals(700f, h.offset)
    }

    @Test
    fun itCountsBlocksNotStreamingDeltas() {
        val h = Harness(revision = 1, first = "a", last = "a")
        h.scroll(to = 200f)
        for (revision in 2..11) h.update(revision = revision, first = "a", last = "a")
        assertEquals(0, h.follow.missed)
        h.update(revision = 12, first = "a", last = "b")
        assertEquals(1, h.follow.missed)
        h.update(revision = 13, first = "a", last = "c")
        assertEquals(2, h.follow.missed)
    }

    @Test
    fun aPrependedPageIsNoMissedUpdate() {
        val h = Harness(revision = 5, first = "c", last = "e")
        h.scroll(to = 100f)
        h.update(revision = 5, first = "a", last = "e", height = 1600f)
        assertEquals(0, h.follow.missed)
    }

    @Test
    fun itFollowsTheTailWhileAttachedAndResetsTheCountOnReturn() {
        val h = Harness(revision = 1, first = "a", last = "a")
        h.update(revision = 2, first = "a", last = "b", height = 1200f)
        assertEquals(800f, h.offset)
        assertEquals(0, h.follow.missed)
        h.scroll(to = 100f)
        h.update(revision = 3, first = "a", last = "c", height = 1400f)
        assertEquals(1, h.follow.missed)
        assertEquals(100f, h.offset)
        h.scroll(to = 1000f)
        assertEquals(0, h.follow.missed)
        assertTrue(h.follow.following)
    }

    @Test
    fun aRedrawOfTheSameBlocksCountsNothing() {
        val h = Harness(revision = 1, first = "a", last = "c", redraw = TimelineDetail.simple)
        h.scroll(to = 200f)
        h.update(revision = 2, first = "a", last = "d", redraw = TimelineDetail.simple)
        assertEquals(1, h.follow.missed)
        h.update(revision = 2, first = "thinking-1", last = "tool-9", redraw = TimelineDetail.detailed, height = 2200f)
        assertEquals(0, h.follow.missed)
        h.update(revision = 3, first = "thinking-1", last = "e", redraw = TimelineDetail.detailed, height = 2400f)
        assertEquals(1, h.follow.missed)
    }

    @Test
    fun aRedrawThatChangesTheFirstRowMovesNoAnchor() {
        val h = Harness(revision = 5, first = "c", last = "e", redraw = TimelineDetail.simple)
        h.scroll(to = 100f)
        h.update(revision = 5, first = "a", last = "e", redraw = TimelineDetail.detailed, height = 1600f)
        assertEquals(100f, h.offset)
    }

    @Test
    fun reachingTheTopAsksForOlderHistory() {
        val h = Harness(revision = 1, first = "a", last = "a")
        val nearTheTop = h.scroll(to = 40f)
        val further = h.scroll(to = 300f)
        assertTrue(nearTheTop)
        assertFalse(further)
    }

    @Test
    fun theButtonTakesTheCountAwayAtOnce() {
        val h = Harness(revision = 1, first = "a", last = "a")
        h.scroll(to = 100f)
        h.update(revision = 2, first = "a", last = "b")
        assertEquals(1, h.follow.missed)
        h.follow.jumpStarted()
        assertEquals(0, h.follow.missed)
        assertFalse(h.follow.following)
    }
}
