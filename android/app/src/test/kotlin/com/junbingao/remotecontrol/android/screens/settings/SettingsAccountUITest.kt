package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.screens.shell.Driving
import com.junbingao.remotecontrol.android.screens.shell.Phone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `testOnlyAnAdminIsOfferedTheUsersScreen`: the accounts screen is the admin's and nobody else's —
 * a member never sees the row, and gets the row an admin does not. Signed in for real against the
 * offline gateway behind the form (`--demo-account`).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class SettingsAccountUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun phoneWithAScreenLock() = Phone.hasAScreenLock()

    @Test
    fun onlyAnAdminIsOfferedTheUsersScreen() = DemoApp(compose, "testOnlyAnAdminIsOfferedTheUsersScreen", DemoApp.signedOut).use { app ->
        val drive = Driving(compose, app)
        drive.signIn(username = "admin", password = "correct horse")
        drive.openSettingsTab()

        assertTrue("an admin is offered the accounts screen", drive.waitFor(hasTestTag("settings.users"), 20_000))
        assertFalse("and not a password it cannot change, because it is the gateway's own", drive.exists("settings.changePassword"))
        assertTrue("the header says who is signed in", drive.exists("settings.identity"))
        val header = drive.label("settings.identity")
        assertTrue("with the account and the role it has, and not a row for either", header.contains("admin") && header.contains("Admin"))
        app.attach("75-settings-admin")

        app.tap("settings.signOut")
        // The confirmation is its own sheet; the row behind it carries the same words, so the
        // button is taken from the sheet rather than by its words.
        assertTrue("signing out asks first", drive.waitFor(hasTestTag("settings.signOut.confirm"), 10_000))
        app.tap("settings.signOut.confirm")

        drive.signIn(username = "alice", password = "correct horse")
        drive.openSettingsTab()
        assertTrue("a member can change its own password", drive.waitFor(hasTestTag("settings.changePassword"), 20_000))
        assertFalse("and is never offered the accounts screen", drive.exists("settings.users"))
        app.attach("76-settings-member")
    }
}
