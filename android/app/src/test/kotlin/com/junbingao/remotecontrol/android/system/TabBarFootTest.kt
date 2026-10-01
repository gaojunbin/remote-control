package com.junbingao.remotecontrol.android.system

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the floating tab bar's foot stands: on the iPhone's measurement over the home indicator and
 * over gesture navigation's handle, and wholly above Android's three navigation buttons, which take
 * touches of their own.
 */
class TabBarFootTest {
    @Test
    fun theIPhone17sBarSitsIntoTheHomeIndicatorsInset() {
        assertEquals(20.67f, TabBarMetrics.footMargin(SafeArea.iPhone17).value, 0.01f)
        assertEquals(83.33f, TabBarMetrics.reserved(SafeArea.iPhone17).value, 0.01f)
    }

    @Test
    fun gestureNavigationKeepsTheIPhonesRule() {
        val gestures = SafeArea(top = 24.dp, bottom = 34.dp, tappableBottom = 0.dp)
        assertEquals(20.67f, TabBarMetrics.footMargin(gestures).value, 0.01f)
    }

    @Test
    fun threeButtonNavigationLeavesTheWholeBarAboveTheButtons() {
        val buttons = SafeArea(top = 24.dp, bottom = 48.dp, tappableBottom = 48.dp)
        assertEquals(56f, TabBarMetrics.footMargin(buttons).value, 0.01f)
    }

    @Test
    fun noInsetStillLeavesTheBarItsMargin() {
        assertEquals(8f, TabBarMetrics.footMargin(SafeArea(top = 0.dp, bottom = 0.dp)).value, 0.01f)
    }
}
