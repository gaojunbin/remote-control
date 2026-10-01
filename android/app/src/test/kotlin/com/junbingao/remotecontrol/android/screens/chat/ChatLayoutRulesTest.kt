package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The two layout rules the conversation borrows from SwiftUI: an `HStack`'s layout priorities, and where a popover goes. */
class ChatLayoutRulesTest {
    @Test
    fun aRowThatFitsGivesEveryChildItsOwnWidth() {
        assertArrayEquals(intArrayOf(50, 100), PriorityRowWidths.distribute(listOf(50, 100), listOf(null, 0.0), listOf(null, null), 400, true, 0))
    }

    @Test
    fun theLowestPriorityIsCutFirst() {
        assertArrayEquals("a fixed child is never cut", intArrayOf(100, 150), PriorityRowWidths.distribute(listOf(100, 300), listOf(null, 0.0), listOf(null, null), 250, true, 0))
        assertArrayEquals("a higher priority keeps its width", intArrayOf(200, 100), PriorityRowWidths.distribute(listOf(200, 200), listOf(1.0, 0.0), listOf(null, null), 300, true, 0))
    }

    @Test
    fun oneLevelIsSharedSmallestFirst() {
        assertArrayEquals(intArrayOf(50, 175, 175), PriorityRowWidths.distribute(listOf(50, 300, 300), listOf(0.0, 0.0, 0.0), listOf(null, null, null), 400, true, 0))
    }

    @Test
    fun spacersShareWhatIsLeftAndGapsAreTakenFirst() {
        assertArrayEquals(intArrayOf(100, 200), PriorityRowWidths.distribute(listOf(100, 0), listOf(null, null), listOf(null, 8), 300, true, 0))
        assertArrayEquals(intArrayOf(100, 180), PriorityRowWidths.distribute(listOf(100, 0), listOf(null, null), listOf(null, 0), 300, true, 20))
    }

    @Test
    fun lessRoomThanAnEllipsisIsNoRoomAtAll() {
        assertArrayEquals(intArrayOf(100, 0), PriorityRowWidths.distribute(listOf(100, 50), listOf(null, 0.0), listOf(null, null), 108, true, 0, minimumVisible = 12))
        assertArrayEquals("an unbounded row takes what it asks for", intArrayOf(100, 50), PriorityRowWidths.distribute(listOf(100, 50), listOf(null, 0.0), listOf(null, null), 0, false, 0))
    }

    @Test
    fun aPopoverIsCentredOnItsSourceAndFlipsWhereItCannotFit() {
        val screen = Size(400f, 800f)
        val card = Size(200f, 300f)
        val above = PopoverPlacement.place(Rect(100f, 500f, 150f, 540f), card, screen, top = 50f, bottom = 780f, margin = 8f, edge = PopoverEdge.above)
        assertEquals(PopoverEdge.above, above.edge)
        assertEquals(Rect(25f, 200f, 225f, 500f), above.rect)
        val flipped = PopoverPlacement.place(Rect(100f, 200f, 150f, 240f), card, screen, top = 50f, bottom = 780f, margin = 8f, edge = PopoverEdge.above)
        assertEquals("no room above, so it opens below", PopoverEdge.below, flipped.edge)
        assertEquals(240f, flipped.rect.top)
    }

    @Test
    fun aPopoverKeepsItsMarginFromTheScreensSides() {
        val screen = Size(400f, 800f)
        val card = Size(200f, 100f)
        assertEquals(8f, PopoverPlacement.place(Rect(10f, 500f, 30f, 540f), card, screen, 50f, 780f, 8f, PopoverEdge.above).rect.left)
        assertEquals(192f, PopoverPlacement.place(Rect(380f, 500f, 400f, 540f), card, screen, 50f, 780f, 8f, PopoverEdge.above).rect.left)
    }
}
