package com.junbingao.remotecontrol.android.awake

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The screen is held on exactly while a conversation is on it and the app is in front. */
@RunWith(AndroidJUnit4::class)
class ScreenAwakeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun keptOn(): Boolean =
        compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test
    fun aConversationOnScreenHoldsItOnAndLeavingLetsItGo() {
        var showing by mutableStateOf(true)
        compose.setContent { if (showing) Box(Modifier.keepsScreenAwake()) }
        compose.waitForIdle()
        assertTrue(keptOn())
        showing = false
        compose.waitForIdle()
        assertFalse(keptOn())
    }

    @Test
    fun aSecondConversationLaidOverTheFirstKeepsItOnWhenOneLeaves() {
        var first by mutableStateOf(true)
        compose.setContent {
            if (first) Box(Modifier.keepsScreenAwake())
            Box(Modifier.keepsScreenAwake())
        }
        compose.waitForIdle()
        first = false
        compose.waitForIdle()
        assertTrue(keptOn())
    }

    @Test
    fun theCountNeverGoesBelowNobody() {
        OpenConversations.left()
        OpenConversations.left()
        assertEquals(false, OpenConversations.any)
        OpenConversations.entered()
        assertEquals(true, OpenConversations.any)
        OpenConversations.left()
    }
}
