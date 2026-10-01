package com.junbingao.remotecontrol.android.shell

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
import com.junbingao.remotecontrol.android.strings.L10n
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The root's own flow, as the activity runs it on the demo gateway behind the sign-in form
 * (`--demo-account`): the form, a refusal said in its words, a sign-in that lands in the shell,
 * and "Try the demo". The iPhone's UI tests of these screens are `android-settings`' to port; this
 * is the foundation's own check that the pieces they drive answer.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class SignInFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun launch(vararg arguments: String): ActivityScenario<MainActivity> =
        ActivityScenario.launch(Intent(context, MainActivity::class.java).putExtra(LaunchOptions.EXTRA, arrayOf(*arguments)))

    private fun shown(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun signIn(username: String, password: String) {
        compose.onNodeWithTag("login.gateway").performTextInput("https://rc.test.example")
        compose.onNodeWithTag("login.username").performTextInput(username)
        compose.onNodeWithTag("login.password").performTextInput(password)
        compose.onNodeWithTag("login.connect").performClick()
    }

    @Test
    fun aDisabledAccountIsToldSoAndTheOperatorSignsIn() {
        launch("--ui-testing", "--demo-account", "--reset-state").use {
            compose.awaitOnRealTime("the sign-in form") { shown("login.connect") }
            compose.capture("sign-in-flow", "form", Variant(L10n.english, dark = false))
            signIn("bob", "correct horse")
            compose.awaitOnRealTime("the refusal") { shown("login.error") }
            compose.onNodeWithText("This account is disabled.").assertExists()
            compose.capture("sign-in-flow", "disabled", Variant(L10n.english, dark = false))
            compose.onNodeWithTag("login.username").performTextClearance()
            compose.onNodeWithTag("login.username").performTextInput("admin")
            compose.onNodeWithTag("login.connect").performClick()
            compose.awaitOnRealTime("the shell") { shown("tab.settings") }
        }
    }

    @Test
    fun theDemoIsOneTapFromTheForm() {
        launch("--ui-testing", "--reset-state").use {
            compose.awaitOnRealTime("the sign-in form") { shown("login.demo") }
            compose.onNodeWithTag("login.demo").performClick()
            compose.awaitOnRealTime("the shell") { shown("tab.sessions") }
            val model = (context as RemoteControlApplication).model(LaunchOptions(emptyList(), debug = true))
            assertEquals(true, model.isDemo)
        }
    }
}
