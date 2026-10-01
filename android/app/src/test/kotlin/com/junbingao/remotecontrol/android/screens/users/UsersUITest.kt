package com.junbingao.remotecontrol.android.screens.users

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
 * `testUsersScreenListsAccountsAndAddsOne`: the switch at the top, one row per account, the actions
 * on a swipe, and Add user in the bottom bar where Add device and New session sit — signed in as
 * the operator against the offline gateway behind the form.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class UsersUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun phoneWithAScreenLock() = Phone.hasAScreenLock()

    @Test
    fun usersScreenListsAccountsAndAddsOne() = DemoApp(compose, "testUsersScreenListsAccountsAndAddsOne", DemoApp.signedOut).use { app ->
        val drive = Driving(compose, app)
        drive.signIn(username = "admin", password = "correct horse")
        drive.openSettingsTab()
        app.tap("settings.users")

        assertTrue("the registration switch is at the top", drive.waitFor(hasTestTag("users.registration"), 20_000))
        assertTrue("the operator has a row", drive.waitFor(hasTestTag("user.admin"), 10_000))
        assertTrue("and so does every other account", drive.exists("user.alice"))
        assertTrue("with the accounts under the switch", drive.frame("user.admin").top > drive.frame("users.registration").top)
        assertTrue("Add user is the screen's primary button", drive.exists("users.add"))
        assertTrue("in the bottom bar", drive.frame("users.add").top > drive.frame("user.admin").top)
        app.attach("77-users-screen")

        // The operator's row offers nothing; another account's offers three.
        app.node("user.admin").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue("the admin row has no actions to swipe to", drive.shown("user.delete").isEmpty())

        app.node("user.alice").performTouchInput { swipeLeft() }
        app.await("a member's row swipes to Delete", 10_000) { drive.shown("user.delete").isNotEmpty() }
        assertTrue("Disable", drive.shown("user.disable").isNotEmpty())
        assertTrue("and Reset password", drive.shown("user.reset").isNotEmpty())
        assertTrue(
            "reading Reset · Disable · Delete from the inside out",
            drive.shown("user.reset").first().boundsInRoot.left < drive.shown("user.delete").first().boundsInRoot.left,
        )
        app.attach("78-users-swipe-actions")

        // Deleting asks first and names what goes with the account. The row is read while the tap
        // is still being handled, because dismissing an alert clears the state a task started from
        // it would have read.
        drive.tapShown("user.delete")
        assertTrue("deleting asks first", drive.waitFor(hasTestTag("users.delete.confirm"), 10_000))
        val named = drive.all(hasText("alice", substring = true)).any { node ->
            node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                .any { it.text.contains("alice") && it.text.contains("1 device") }
        }
        assertTrue("and names the account and the devices that go with it", named)
        app.attach("79-users-delete-confirm")
        app.tap("users.delete.confirm")
        assertTrue("and the row goes", drive.waitForAbsence(hasTestTag("user.alice"), 15_000))

        app.tap("users.add")
        assertTrue("the sheet asks for a username", drive.waitFor(hasTestTag("addUser.username"), 10_000))
        assertTrue("a password", drive.exists("addUser.password"))
        assertTrue("and a role", drive.exists("addUser.role"))
        app.attach("80-add-user-sheet")

        app.node("addUser.username").performTextInput("dave")
        app.node("addUser.password").performTextInput("correct horse battery staple")
        compose.waitForIdle()
        app.tap("addUser.add")

        assertTrue("and the account it made is a row on the screen", drive.waitFor(hasTestTag("user.dave"), 15_000))
        app.attach("81-users-after-add")
    }
}
