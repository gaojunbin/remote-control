package com.junbingao.remotecontrol.android.system

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** A swipe's buttons follow the row and their names, as iOS 26 lays them out on the reference screenshots. */
class SwipeMetricsTest {
    @Test
    fun aCapsuleGivesUpHeightInAShortRow() {
        assertEquals("the Users screen's 66-point rows", 38f, SwipeMetrics.capsuleHeight(66.dp).value, 0.01f)
        assertEquals("a device row is tall enough for the tallest", 50f, SwipeMetrics.capsuleHeight(92.dp).value, 0.01f)
        assertEquals("and never taller", 50f, SwipeMetrics.capsuleHeight(140.dp).value, 0.01f)
    }

    @Test
    fun everyCapsuleIsAsWideAsTheLongestName() {
        assertEquals("Reset password", 96f, SwipeMetrics.buttonWidth(96.dp).value, 0.01f)
        assertEquals("a lone short name still has a capsule of its own", 50f, SwipeMetrics.buttonWidth(31.dp).value, 0.01f)
    }

    @Test
    fun theRowSlidesAsFarAsItsButtonsNeed() {
        assertEquals("the Users screen's three", 328f, SwipeMetrics.reveal(3, 96.dp).value, 0.01f)
        assertEquals("a failed update's four, which leave the row's trailing words under them", 362f, SwipeMetrics.reveal(4, 78.dp).value, 0.01f)
    }
}
