package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign
import com.junbingao.remotecontrol.win.design.overlay.PopoverPlacement
import com.junbingao.remotecontrol.win.design.overlay.PopoverSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `web/tests/popoverPlacement.test.ts`, case for case. */
class PopoverPlacementTests {
    private val viewport = Size(1280f, 800f)
    private val panel = Size(200f, 120f)

    private fun trigger(left: Float, top: Float) = Rect(left, top, left + 28, top + 28)

    @Test
    fun hangsThePanelUnderTheTriggerWithAGap() {
        val at = PopoverPlacement.place(trigger(400f, 100f), panel, viewport, PopoverAlign.start, PopoverSide.bottom)
        assertEquals(PopoverPlacement.Placement(left = 400f, top = 134f, bottom = null), at)
    }

    @Test
    fun linesThePanelUpWithTheRightEdgeOfTheTrigger() {
        val at = PopoverPlacement.place(trigger(400f, 100f), panel, viewport, PopoverAlign.end, PopoverSide.bottom)
        assertEquals(228f, at.left)
    }

    @Test
    fun flipsAboveWhenThePanelDoesNotFitBelow() {
        val at = PopoverPlacement.place(trigger(400f, 720f), panel, viewport, PopoverAlign.start, PopoverSide.bottom)
        assertNull(at.top)
        assertEquals(86f, at.bottom)
    }

    @Test
    fun flipsBelowWhenTheSideAskedForHasNoRoom() {
        val at = PopoverPlacement.place(trigger(400f, 20f), panel, viewport, PopoverAlign.start, PopoverSide.top)
        assertNull(at.bottom)
        assertEquals(54f, at.top)
    }

    @Test
    fun staysOnTheSideWithMoreRoomWhenNeitherFits() {
        val at = PopoverPlacement.place(trigger(400f, 300f), panel, Size(1280f, 420f), PopoverAlign.start, PopoverSide.bottom)
        assertNull(at.top)
        assertEquals(126f, at.bottom)
    }

    @Test
    fun keepsThePanelInsideTheWindowOnBothEdges() {
        val right = PopoverPlacement.place(trigger(1240f, 100f), panel, viewport, PopoverAlign.start, PopoverSide.bottom)
        assertEquals(1072f, right.left)
        val left = PopoverPlacement.place(trigger(4f, 100f), panel, viewport, PopoverAlign.end, PopoverSide.bottom)
        assertEquals(8f, left.left)
    }

    @Test
    fun aPanelAboveIsPlacedByItsTopEdgeToo() {
        val at = PopoverPlacement.place(trigger(400f, 720f), panel, viewport, PopoverAlign.start, PopoverSide.bottom)
        assertEquals(594f, PopoverPlacement.top(at, panelHeight = 120f, viewportHeight = 800f))
    }
}
