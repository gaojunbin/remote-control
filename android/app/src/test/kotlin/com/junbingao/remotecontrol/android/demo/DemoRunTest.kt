package com.junbingao.remotecontrol.android.demo

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.MainActivity
import com.junbingao.remotecontrol.android.RemoteControlApplication
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.harness.Variant
import com.junbingao.remotecontrol.android.harness.awaitOnRealTime
import com.junbingao.remotecontrol.android.harness.capture
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.android.shell.AppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app as `adb shell am start … --esa args --demo` launches it: the activity, the process's one
 * model, the demo gateway on real time. It lands where the iPhone's first screen is, with the
 * demo's devices and sessions, opens the live conversation, and Back returns to the list.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class DemoRunTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val english = Variant(L10n.english, dark = false)
    private val live = "${DemoFixtures.macDeviceID}/${DemoFixtures.liveSessionID}"

    private fun model(): AppModel =
        (context as RemoteControlApplication).model(LaunchOptions(listOf("--demo"), debug = true))

    private fun shown(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theDemoLandsOnTheSessionsOpensTheLiveConversationAndComesBack() {
        val intent = Intent(context, MainActivity::class.java).putExtra(LaunchOptions.EXTRA, arrayOf("--demo"))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.awaitOnRealTime("the demo's sessions") { shown("session.${DemoFixtures.liveSessionID}") }
            val model = model()
            assertEquals("an account with machines lands on the conversations", AppModel.Tab.sessions, model.tab)
            assertEquals("the demo's machines are all here", DemoFixtures.devices.size, model.connection.devices.size)
            compose.capture("demo-run", "sessions", english)

            compose.onNodeWithTag("session.${DemoFixtures.liveSessionID}").performClick()
            compose.awaitOnRealTime("the live conversation") { shown("chat.$live") && model.chat?.timeline?.entries?.isNotEmpty() == true }
            assertEquals(listOf(live), model.path)
            assertEquals("the conversation hides the tab bar", false, shown("tab.sessions"))
            compose.capture("demo-run", "chat", english)

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.awaitOnRealTime("the list again") { shown("tab.sessions") && model.chat == null }
            assertEquals("Back leaves the conversation for the list", emptyList<String>(), model.path)
            assertNull("and closes its store", model.chat)
            compose.capture("demo-run", "back", english)
        }
    }
}
