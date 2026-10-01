package com.junbingao.remotecontrol.android.haptics

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** One selection tick per change of the trigger, and none when it first appears. */
@RunWith(AndroidJUnit4::class)
class HapticsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aChangeTicksAndTheFirstValueDoesNot() {
        var stop by mutableIntStateOf(0)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            Box(Modifier.selectionFeedback(stop))
        }
        compose.waitForIdle()
        assertEquals(-1, shadowOf(view).lastHapticFeedbackPerformed())
        stop = 1
        compose.waitForIdle()
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, shadowOf(view).lastHapticFeedbackPerformed())
    }
}
