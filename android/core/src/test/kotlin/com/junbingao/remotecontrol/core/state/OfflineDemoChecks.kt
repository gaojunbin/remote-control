package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.protocol.UserState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test

/**
 * The store's half of `ios/VerificationUI/main.swift` § "Accounts (A24)", `docs/DESIGN.md`
 * § "Accounts": `ConnectionStore.offlineDemo` reaches the offline gateway through the sign-in form
 * rather than around it, so every answer the form has to tell apart — an unknown account, a
 * disabled one, a member, an admin — is driven without a gateway to reach. What the form remembers
 * between launches is the app's, and is checked there.
 */
class OfflineDemoChecks {
    private fun check(body: suspend TestScope.(CheckRunner, File) -> Unit) = runTest {
        val checks = CheckRunner("accounts")
        val directory = scratchDirectory("offline-demo")
        try {
            body(checks, directory)
        } finally {
            directory.deleteRecursively()
        }
        checks.assertAll()
    }

    /** A gateway that takes no accounts offers none, and tells each refusal apart. */
    @Test
    fun signInThroughTheForm() = check { checks, directory ->
        val closed = ConnectionStore.offlineDemo(tasks = backgroundScope, cache = LocalCache(directory))
        checks.expect(!closed.registrationOpen(origin = "https://rc.example.com"),
                      "a gateway that is not taking accounts offers no way to create one")

        closed.signIn(origin = "https://rc.example.com", username = DemoFixtures.disabledUsername, password = "correct horse")
        checks.expect(!closed.isSignedIn, "a disabled account cannot sign in")
        checks.equal(closed.errorMessage, "This account is disabled.", "and is told why")

        closed.signIn(origin = "https://rc.example.com", username = "nobody", password = "correct horse")
        checks.equal(closed.errorMessage, "Wrong username or password.", "while an unknown account is not told that it is unknown")

        closed.signIn(origin = "https://rc.example.com", username = DemoFixtures.memberUsername, password = "correct horse")
        checks.expect(closed.isSignedIn, "a member signs in")
        checks.equal(closed.username, DemoFixtures.memberUsername, "as itself")
        checks.expect(!closed.isAdmin, "a member is not an admin")
        checks.expect(closed.usersStore() == null, "so the accounts screen is not theirs to open")
        closed.signOut()
    }

    /** A gateway taking accounts says so, and creating one signs it in. */
    @Test
    fun registration() = check { checks, directory ->
        val open = ConnectionStore.offlineDemo(tasks = backgroundScope, cache = LocalCache(directory), registrationOpen = true)
        checks.expect(open.registrationOpen(origin = "https://rc.example.com"),
                      "a gateway taking accounts says so, which is what draws Create an account")
        open.register(origin = "https://rc.example.com", username = "Carol", password = "correct horse battery staple")
        checks.expect(open.isSignedIn, "creating an account signs it in")
        checks.equal(open.username, "carol", "under the lower-cased name the gateway keeps")
        checks.equal(open.user.role, UserRole.member, "as a member")
        open.register(origin = "https://rc.example.com", username = "carol", password = "correct horse battery staple")
        checks.equal(open.errorMessage, "That username is taken.", "and a second time is refused by name")
        open.register(origin = "https://rc.example.com", username = "no", password = "correct horse battery staple")
        checks.equal(open.errorMessage, AccountError.rules, "while a name outside the rules is the one time the rule is stated")
        open.signOut()
    }

    /** The admin's screen, driven through the store the screen reads. */
    @Test
    fun usersScreen() = check { checks, directory ->
        val operating = ConnectionStore.offlineDemo(tasks = backgroundScope, cache = LocalCache(directory))
        operating.signIn(origin = "https://rc.example.com", username = DemoFixtures.adminUsername, password = "correct horse")
        checks.expect(operating.isAdmin, "the operator signs in as an admin")
        val users = operating.usersStore()
        if (users == null) {
            checks.expect(false, "an admin has an accounts screen")
            return@check
        }
        users.load()
        checks.equal(users.users.size, 3, "the accounts screen lists every account")
        checks.expect(!users.registrationOpen, "with the registration switch where the gateway has it")
        checks.expect(users.users.firstOrNull()?.isOperator == true, "the operator is the first row")
        attempt { users.setRegistration(open = true) }
        checks.expect(users.registrationOpen, "and the switch is what the gateway answered")

        attempt { users.create(username = "dave", password = "correct horse battery staple", role = UserRole.member) }
        checks.equal(users.users.size, 4, "adding an account adds a row")
        checks.equal(users.users.lastOrNull()?.role, UserRole.member, "as a member unless an admin was chosen")

        attempt { users.setState(UserState.disabled, of = DemoFixtures.memberUsername) }
        checks.equal(users.users.firstOrNull { it.username == DemoFixtures.memberUsername }?.state, UserState.disabled,
                     "disabling an account changes its row and nothing else")
        attempt { users.setState(UserState.active, of = DemoFixtures.memberUsername) }
        checks.equal(users.users.firstOrNull { it.username == DemoFixtures.memberUsername }?.state, UserState.active,
                     "and enabling it puts it back")

        attempt { users.delete("dave") }
        checks.equal(users.users.size, 3, "deleting an account takes its row with it")

        // The operator's row has no actions on the screen; the gateway refuses them as well, so a
        // race cannot do what the screen would not offer.
        var refused: String? = null
        try {
            users.setState(UserState.disabled, of = DemoFixtures.adminUsername)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            refused = AccountError.manage(error)
        }
        checks.equal(refused, "This account cannot be changed.", "the operator cannot be disabled even by asking directly")
        operating.signOut()
    }

    /** RCCore's `try?`: what the step answered is not the check, what the screen shows afterwards is. */
    private suspend fun attempt(step: suspend () -> Unit) {
        try {
            step()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The line after it reads the store, which is what the check is about.
        }
    }
}
