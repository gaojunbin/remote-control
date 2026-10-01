package com.junbingao.remotecontrol.android.screens.settings

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.harness.Variant
import com.junbingao.remotecontrol.android.harness.swipeOpen
import com.junbingao.remotecontrol.android.screens.shell.Driving
import com.junbingao.remotecontrol.android.screens.shell.Phone
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.state.ConnectionPhase
import com.junbingao.remotecontrol.core.state.VoiceBackend
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings and Users in the languages and appearances the iPhone's tests do not run in, at the
 * steps the iPhone's pictures are taken, into the same folders under the same names — so the four
 * variants of a step lie side by side with the iPhone's — and the states no iPhone test pictures:
 * Diagnostics, the sign-out question, a member's Account group and the password sheet, Notify me
 * blocked by the system, a row's context menu and the reset-password alert.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class SettingsPicturesTest(private val variant: Variant) {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun phoneWithAScreenLock() = Phone.hasAScreenLock()

    private fun demo(test: String, vararg extra: String, run: (DemoApp, Driving) -> Unit) =
        DemoApp(compose, test, DemoApp.launchArguments + extra, variant).use { app -> run(app, Driving(compose, app)) }

    private fun DemoApp.settled() = await("connected") { model.connection.phase == ConnectionPhase.Connected }

    @Test
    fun headerAndVersions() = demo("testSettingsHeaderAndVersionsLineNameTheAccountAndTheBuild") { app, drive ->
        drive.openSettingsTab()
        app.waitFor("settings.identity", 15_000)
        app.settled()
        app.attach("ios-round43-settings")
        drive.scrollDown(toTag = "settings.versions")
        app.attach("ios-round43-settings-2")
    }

    @Test
    fun polishTurnedOn() = demo("testVoiceSettingsOfferPolish") { app, drive ->
        drive.openSettingsTab()
        drive.scrollDown(toTag = "settings.polish")
        drive.turnOn("settings.polish")
        drive.waitFor(hasTestTag("settings.polishModel"), 10_000)
        app.attach("ios-polish-settings")
    }

    @Test
    fun transcribeOnTheGateway() = demo("testTranscribeDecidesWhetherALanguageIsOffered") { app, drive ->
        drive.openSettingsTab()
        drive.scrollDown(toTag = "settings.voiceBackend")
        drive.waitFor(hasTestTag("settings.voiceLanguage"), 10_000)
        app.attach("ios-a44-settings-on-phone")
        app.tap("settings.voiceBackend")
        val gateway = L10n.platform(VoiceBackend.gateway.title)
        drive.waitFor(hasText(gateway), 10_000)
        app.attach("ios-a44-transcribe-menu")
        compose.onNode(hasText(gateway), useUnmergedTree = true).performClick()
        drive.waitForAbsence(hasTestTag("settings.voiceLanguage"), 10_000)
        app.attach("ios-a44-settings-gateway")
    }

    @Test
    fun usersScreen() = demo("testUsersScreenListsAccountsAndAddsOne") { app, drive ->
        drive.openSettingsTab()
        app.tap("settings.users")
        drive.waitFor(hasTestTag("user.bob"), 20_000)
        app.attach("77-users-screen")
        app.node("user.alice").performTouchInput { swipeOpen() }
        app.await("the swipe") { drive.shown("user.delete").isNotEmpty() }
        app.attach("78-users-swipe-actions")
        drive.tapShown("user.delete")
        drive.waitFor(hasTestTag("users.delete.confirm"), 10_000)
        app.attach("79-users-delete-confirm")
        app.back()
        app.tap("users.add")
        drive.waitFor(hasTestTag("addUser.username"), 10_000)
        app.attach("80-add-user-sheet")
    }

    @Test
    fun statesWithoutAnIPhonePicture() = demo("settings-states") { app, drive ->
        drive.openSettingsTab()
        app.waitFor("settings.identity", 15_000)
        app.settled()
        app.tap("settings.signOut")
        drive.waitFor(hasTestTag("settings.signOut.confirm"), 10_000)
        app.attach("sign-out-question")
        app.back()

        app.tap("settings.users")
        drive.waitFor(hasTestTag("user.alice"), 20_000)
        app.node("user.alice").performTouchInput { longClick() }
        // The row's swipe holds the same actions, laid out under it and out of sight.
        app.await("the context menu") { drive.shown("user.reset").isNotEmpty() }
        app.attach("users-context-menu")
        drive.tapShown("user.reset")
        drive.waitFor(hasTestTag("users.reset.confirm"), 10_000)
        app.attach("users-reset-password")
        app.back()
        app.back()

        drive.scrollDown(toTag = "settings.diagnostics")
        app.tap("settings.diagnostics")
        drive.waitFor(hasTestTag("diagnostics.report"), 10_000)
        app.attach("diagnostics")
    }

    /** A phone that was asked once and said no: the row goes to Android Settings, and the switch stays inert. */
    @Test
    fun notificationsBlockedBySystem() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("notifications", Context.MODE_PRIVATE)
            .edit(commit = true) { putBoolean("asked", true) }
        demo("settings-states") { app, drive ->
            drive.openSettingsTab()
            drive.scrollDown(toTag = "settings.notifications.blocked")
            app.attach("notifications-blocked")
        }
    }

    /** A member's Account group, and the sheet its Change password row opens. */
    @Test
    fun memberAndPassword() = DemoApp(compose, "settings-states", DemoApp.signedOut, variant).use { app ->
        val drive = Driving(compose, app)
        drive.signIn(username = "alice", password = "correct horse")
        drive.openSettingsTab()
        drive.waitFor(hasTestTag("settings.changePassword"), 20_000)
        app.attach("76-settings-member")
        app.tap("settings.changePassword")
        drive.waitFor(hasTestTag("password.current"), 10_000)
        app.attach("password-sheet")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = IPhone.variants.map { arrayOf(it) }
    }
}
