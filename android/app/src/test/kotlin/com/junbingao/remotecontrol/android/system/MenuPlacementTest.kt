package com.junbingao.remotecontrol.android.system

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a menu stands, against the iPhone 17's own pictures of the composer's menus (points at
 * one pixel a point): the safe area less 8 points is 8…394 across and 70…832 down.
 */
class MenuPlacementTest {
    private val bounds = Rect(8f, 70f, 394f, 832f)

    /** A composer control: a 44-point square on the control row, its leading edge at [x]. */
    private fun control(x: Float) = Rect(x, 784f, x + 44f, 828f)

    private fun menu(rows: Int) = IntSize(250, rows * 42 + 20)

    @Test
    fun aMenuFromTheFootOfTheScreenGrowsUpOverItsButton() {
        assertEquals("attach: its foot on the button's foot", IntOffset(20, 724), place(control(20f), menu(2)))
        assertEquals("language: its leading edge on the button's", IntOffset(114, 556), place(control(114f), menu(6)))
    }

    @Test
    fun aMenuThatWouldRunOffTheRightTurnsToEndAtItsButtonAndKeepsItsMargin() {
        assertEquals("permissions", IntOffset(8, 682), place(control(202f), menu(3)))
        assertEquals(IntOffset(144, 682), place(Rect(300f, 784f, 394f, 828f), menu(3)))
    }

    @Test
    fun aMenuFromTheTopGrowsDownOverItsButton() {
        assertEquals("a bar button on the right, its top on the button's", IntOffset(136, 62), place(Rect(338f, 62f, 386f, 106f), menu(3)))
        assertEquals(IntOffset(16, 70), place(Rect(16f, 70f, 60f, 114f), menu(3)))
    }

    @Test
    fun aContextMenuStandsClearOfItsElement() {
        val row = Rect(16f, 300f, 386f, 380f)
        assertEquals("below, with a gap", IntOffset(16, 388), MenuPlacement.place(row, menu(3), bounds, covers = false, gap = 8f))
        val low = Rect(16f, 700f, 386f, 780f)
        assertEquals("above when there is no room below", IntOffset(16, 546), MenuPlacement.place(low, menu(3), bounds, covers = false, gap = 8f))
    }

    @Test
    fun aMenuTallerThanTheRoomKeepsItsTopInView() {
        val tall = IntSize(250, 900)
        assertEquals(70, place(control(20f), tall).y)
    }

    private fun place(source: Rect, card: IntSize) = MenuPlacement.place(source, card, bounds, covers = true, gap = 8f)
}
