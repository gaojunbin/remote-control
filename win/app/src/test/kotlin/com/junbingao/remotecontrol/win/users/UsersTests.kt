package com.junbingao.remotecontrol.win.users

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.protocol.UserState
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.app.LaunchOptions
import com.junbingao.remotecontrol.win.app.ModelHarness
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.app.signIn
import com.junbingao.remotecontrol.win.app.signOut
import com.junbingao.remotecontrol.win.notifications.SettingsFeature
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.S
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `web/tests/UsersPage.test.tsx` and `stores/users.ts`, on the demo gateway's accounts: the list,
 * the switch, the row actions and the dialogs, and a member who is given none of it.
 */
class UsersTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    private fun admin(block: suspend WinAppModel.() -> Unit) = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            harness.waitFor { connection.hasSnapshot }
            block()
        }
    }

    @Test
    fun itListsEveryAccountOnceItHasAsked() = admin {
        val users = UsersModel()
        assertTrue(!users.loaded && users.users.isEmpty())
        users.load(on = connection)
        assertTrue(users.loaded && users.error == null)
        assertEquals(listOf("admin", "alice", "bob"), users.users.map { it.username })
        assertEquals(true, users.users.firstOrNull()?.isOperator)
        signOut()
    }

    @Test
    fun theRegistrationSwitchAnswersTheClickAndKeepsTheGatewaysWord() = admin {
        val users = UsersModel()
        users.load(on = connection)
        assertFalse(users.registrationOpen)
        users.openRegistration(true, on = connection)
        assertTrue(users.registrationOpen && users.error == null)
        users.openRegistration(false, on = connection)
        assertFalse(users.registrationOpen)
        signOut()
    }

    @Test
    fun disablingAnAccountReReadsTheList() = admin {
        val users = UsersModel()
        users.load(on = connection)
        val alice = assertNotNull(users.users.firstOrNull { it.username == "alice" })
        users.toggleState(of = alice, on = connection)
        assertEquals(UserState.disabled, users.users.firstOrNull { it.username == "alice" }?.state)
        val bob = assertNotNull(users.users.firstOrNull { it.username == "bob" })
        users.toggleState(of = bob, on = connection)
        assertEquals(UserState.active, users.users.firstOrNull { it.username == "bob" }?.state)
        // The operator refuses, and the page says so.
        val operatorRow = assertNotNull(users.users.firstOrNull { it.isOperator })
        users.toggleState(of = operatorRow, on = connection)
        assertEquals(S.account.notAllowed, users.error)
        signOut()
    }

    @Test
    fun addingAnAccountTakesAUsernameAPasswordAndARole() = admin {
        val users = UsersModel()
        users.load(on = connection)
        val form = AddUserForm()
        assertTrue(form.role == UserRole.member && !form.ready)
        form.username = "  carol "
        form.password = "short"
        assertFalse(form.ready)
        form.password = "long enough"
        assertTrue(form.ready)
        assertTrue(form.submit(to = users, on = connection))
        assertEquals("carol", users.users.lastOrNull()?.username)
        val again = AddUserForm()
        again.username = "carol"
        again.password = "long enough"
        assertFalse(again.submit(to = users, on = connection))
        assertEquals(S.account.taken, again.error)
        signOut()
    }

    @Test
    fun aResetAndADeleteGoThroughTheirOwnDialogs() = admin {
        val users = UsersModel()
        users.load(on = connection)
        val reset = ResetPasswordForm(username = "alice")
        assertFalse(reset.ready)
        reset.password = "a new password"
        assertTrue(reset.submit(to = users, on = connection))
        val alice = assertNotNull(users.users.firstOrNull { it.username == "alice" })
        val delete = DeleteUserForm(user = alice)
        assertEquals(S.users.deleteBodyDevices("alice", alice.devices), delete.body)
        assertTrue(delete.submit(to = users, on = connection))
        assertTrue(users.users.none { it.username == "alice" })
        val bob = assertNotNull(users.users.firstOrNull { it.username == "bob" })
        assertEquals(S.users.deleteBody("bob"), DeleteUserForm(user = bob).body)
        signOut()
    }

    @Test
    fun aMemberIsGivenNoAccountsAndASignOutForgetsThem() {
        admin {
            val users = SettingsFeature.state(of = this).users
            users.load(on = connection)
            assertTrue(users.loaded && users.users.isNotEmpty())
            signOut()
            assertTrue(!users.loaded && users.users.isEmpty())
        }
        ModelHarness(LaunchOptions(demoAccount = true, ephemeral = true)).use { harness ->
            harness.run {
                restoreOrPrompt()
                signIn(origin = "https://demo.remote-control.invalid", username = DemoFixtures.memberUsername, password = "devdevdev")
                val memberUsers = UsersModel()
                assertNull(memberUsers.store(on = connection))
                memberUsers.load(on = connection)
                assertFalse(memberUsers.loaded)
                router.go(Route.Users)
                assertEquals(Route.Sessions, router.route)
                signOut()
            }
        }
    }
}
