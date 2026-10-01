package com.junbingao.remotecontrol.android.screens.lock

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.harness.IPhoneFrame
import com.junbingao.remotecontrol.android.harness.Variant
import com.junbingao.remotecontrol.android.strings.L10n
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lock and the privacy cover, with a stand-in for the phone's own prompt so that nothing is
 * ever asked: the owner proving themselves opens the app, a refusal says so and can be tried again,
 * nothing under the lock is reachable — not by a touch and not by Back — and the cover lets every
 * touch through.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class AppLockTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val english = Variant(L10n.english, dark = false)

    @Test
    fun theOwnerProvingThemselvesUnlocks() {
        var unlocked = 0
        val asked = mutableListOf<String>()
        compose.setContent {
            IPhoneFrame(english) {
                CompositionLocalProvider(LocalDeviceOwnerAuthentication provides DeviceOwnerAuthentication { reason -> asked += reason; true }) {
                    AppLockWindow(locked = true) { unlocked += 1 }
                }
            }
        }
        compose.onNodeWithText(L10n.string("Remote Control is locked")).assertExists()
        compose.onNodeWithTag("app.lock.unlock").performClick()
        compose.waitForIdle()
        assertEquals("the phone is asked once, saying what for", listOf(L10n.string("Unlock Remote Control")), asked)
        assertEquals("and the app opens", 1, unlocked)
    }

    @Test
    fun aRefusalSaysSoAndCanBeTriedAgain() {
        var unlocked = 0
        var answers = listOf(false, true)
        compose.setContent {
            IPhoneFrame(english) {
                CompositionLocalProvider(
                    LocalDeviceOwnerAuthentication provides DeviceOwnerAuthentication { answers.first().also { answers = answers.drop(1) } },
                ) {
                    AppLockWindow(locked = true) { unlocked += 1 }
                }
            }
        }
        compose.onNodeWithTag("app.lock.unlock").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("app.lock.error").assertExists()
        compose.onNodeWithText(L10n.string("Not unlocked. You can try again.")).assertExists()
        assertEquals("a refusal keeps the lock up", 0, unlocked)
        compose.onNodeWithTag("app.lock.unlock").performClick()
        compose.waitForIdle()
        assertEquals("and the next try opens it", 1, unlocked)
    }

    @Test
    fun theButtonWaitsForTheAnswerItAskedFor() {
        val answer = CompletableDeferred<Boolean>()
        var unlocked = 0
        compose.setContent {
            IPhoneFrame(english) {
                CompositionLocalProvider(LocalDeviceOwnerAuthentication provides DeviceOwnerAuthentication { answer.await() }) {
                    AppLockWindow(locked = true) { unlocked += 1 }
                }
            }
        }
        compose.onNodeWithTag("app.lock.unlock").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("app.lock.unlock").assertIsNotEnabledCompat()
        answer.complete(true)
        compose.waitForIdle()
        assertEquals(1, unlocked)
    }

    @Test
    fun nothingUnderTheLockIsReachable() {
        var tapped = 0
        var wentBack = 0
        var locked by mutableStateOf(true)
        compose.setContent {
            IPhoneFrame(english, over = { AppLockWindow(locked) { locked = false } }) {
                BackHandler { wentBack += 1 }
                Box(Modifier.fillMaxSize()) {
                    Button(onClick = { tapped += 1 }, Modifier.fillMaxSize().testTag("under")) { Text("under") }
                }
            }
        }
        compose.onNodeWithTag("under", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("a touch lands on the lock, not on what it covers", 0, tapped)

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals("and Back leaves the app rather than the screen under the lock", 0, wentBack)
        assertTrue("the app goes to the back, as it does from a tab's root", shadowOf(compose.activity).isTaskMovedToBack)

        locked = false
        compose.waitForIdle()
        assertFalse("unlocked, the lock is gone", compose.onAllNodesWithTagExists("app.lock"))
        compose.onNodeWithTag("under", useUnmergedTree = true).performClick()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals("and the screen answers again", 1, tapped)
        assertEquals(1, wentBack)
    }

    @Test
    fun theCoverLetsEveryTouchThrough() {
        var tapped = 0
        compose.setContent {
            IPhoneFrame(english) {
                Box(Modifier.fillMaxSize()) {
                    Button(onClick = { tapped += 1 }, Modifier.fillMaxSize().testTag("under")) { Text("under") }
                    PrivacyShield(visible = true)
                }
            }
        }
        compose.onNodeWithTag("app.privacy").assertExists()
        compose.onNodeWithTag("under", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("the cover hides the screen and takes nothing from it", 1, tapped)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsNotEnabledCompat() {
    val config = fetchSemanticsNode().config
    assertTrue("the button is disabled while the answer is awaited",
               config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled))
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
