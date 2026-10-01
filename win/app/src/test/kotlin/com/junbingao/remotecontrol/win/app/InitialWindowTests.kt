package com.junbingao.remotecontrol.win.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** The window opens at the Mac's size where the screen has room, and inside the screen where not. */
class InitialWindowTests {
    @Test
    fun aLargeScreenGetsTheMacsSize() {
        assertEquals(DpSize(1280.dp, 860.dp), InitialWindow.size(DpSize(1920.dp, 1032.dp)))
    }

    @Test
    fun aSmallScreenGetsItsWorkAreaLessTheMargin() {
        // 1024 × 768 with a 48-point taskbar, as a CI runner's desktop is.
        assertEquals(DpSize(976.dp, 672.dp), InitialWindow.size(DpSize(1024.dp, 720.dp)))
    }

    @Test
    fun neverBelowTheMinimum() {
        assertEquals(InitialWindow.minimum, InitialWindow.size(DpSize(400.dp, 500.dp)))
    }
}
