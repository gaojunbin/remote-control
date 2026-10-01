package com.junbingao.remotecontrol.win.chat

import com.junbingao.remotecontrol.core.state.TimelineDetail
import com.junbingao.remotecontrol.win.chat.timeline.ScrollFollow
import com.junbingao.remotecontrol.win.chat.timeline.TranscriptGeometry
import com.junbingao.remotecontrol.win.chat.timeline.TranscriptScroll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the lazy list adds to the follow rule: a trip to the tail that holds until the tail is on
 * screen, and a verdict reached on the list's guess at rows it has not laid out taken again once it
 * has — a 400-point window, as in the rule's own suite.
 */
class ChatTranscriptScrollTests {
    private fun geometry(offset: Float, content: Float, measured: Boolean = true) =
        TranscriptGeometry(offset = offset, contentHeight = content, viewportHeight = 400f, measuredToTheEnd = measured)

    /** A transcript opened at its tail: the first rows pin it, and the list reports it there. */
    private fun atTheTail(content: Float): TranscriptScroll {
        val scroll = TranscriptScroll()
        assertEquals(
            listOf(TranscriptScroll.Action.ScrollToBottom(animated = false)),
            scroll.rowsChanged(ScrollFollow.Rows("1", TimelineDetail.detailed, "a", "c")),
        )
        scroll.geometryChanged(geometry(content - 400f, content))
        return scroll
    }

    @Test
    fun aTripToTheTailIsRepeatedUntilTheTailIsOnScreen() {
        val scroll = TranscriptScroll()
        scroll.rowsChanged(ScrollFollow.Rows("1", TimelineDetail.detailed, "a", "c"))
        // The list laid out more rows on the way and its end moved: it goes again.
        assertEquals(listOf(TranscriptScroll.Action.ScrollToBottom(animated = false)), scroll.geometryChanged(geometry(500f, 1200f)))
        assertTrue(scroll.geometryChanged(geometry(800f, 1200f)).isEmpty())
        assertTrue(scroll.following)
    }

    @Test
    fun aReaderJudgedFarFromTheTailOnAGuessIsJudgedAgainOnceTheEndIsKnown() {
        val scroll = atTheTail(content = 404f)
        // At the top, with the last row guessed as tall as the average row: 70 from the tail.
        scroll.geometryChanged(geometry(0f, 470f, measured = false))
        assertFalse(scroll.following)
        // Laid out, the last row is the status line, and the tail is 4 away.
        scroll.geometryChanged(geometry(0f, 404f))
        assertTrue(scroll.following)
    }

    @Test
    fun aMeasuredVerdictWaitsForTheReadersNextScroll() {
        val scroll = atTheTail(content = 1000f)
        scroll.geometryChanged(geometry(100f, 1000f))
        assertFalse(scroll.following)
        // The content below shrank without a scroll, as a browser's would: the reader is judged
        // when they next move.
        scroll.geometryChanged(geometry(100f, 520f))
        assertFalse(scroll.following)
        scroll.geometryChanged(geometry(110f, 520f))
        assertTrue(scroll.following)
    }

    @Test
    fun reachingTheTopAsksForOlderHistoryOnce() {
        val scroll = atTheTail(content = 1000f)
        assertEquals(listOf(TranscriptScroll.Action.LoadOlder), scroll.geometryChanged(geometry(40f, 1000f, measured = false)))
        // The guess corrected in place is no new trip to the top.
        assertTrue(scroll.geometryChanged(geometry(40f, 980f)).isEmpty())
    }
}
