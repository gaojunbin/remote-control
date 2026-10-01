package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Accounts (A24): the demo gateway models the three answers the sign-in form has to tell apart.
 * The wire cases of RCCore's suite of this name are `protocol.AccountsTests`; the form's words and
 * the settings it keeps per account drive `AccountError` and `SettingsStore`, and are
 * `core-state`'s.
 */
class AccountsTests {
    /** A disabled account is refused with 403, an unknown one with 401. */
    @Test
    fun demoSignIn() = runTest {
        val gateway = demoGateway()
        assertEquals(TransportError.Http(status = 403, code = "forbidden"), assertFailsWith<TransportError> {
            gateway.login(username = DemoFixtures.disabledUsername, password = "correct horse")
        })
        assertEquals(TransportError.Unauthorized, assertFailsWith<TransportError> {
            gateway.login(username = "nobody", password = "correct horse")
        })
        val member = gateway.login(username = DemoFixtures.memberUsername, password = "correct horse")
        assertEquals(UserRole.member, member.user.role)
    }

    /** A member is refused the account routes. */
    @Test
    fun demoMemberIsRefused() = runTest {
        val gateway = demoGateway()
        gateway.login(username = DemoFixtures.memberUsername, password = "correct horse")
        assertEquals(TransportError.Http(status = 403, code = "forbidden"), assertFailsWith<TransportError> {
            gateway.users()
        })
    }

    /** Registration is closed on a fresh gateway and opens from the admin's screen. */
    @Test
    fun demoRegistration() = runTest {
        val gateway = demoGateway()
        assertFalse(gateway.health().registrationOpen)
        assertEquals(TransportError.Http(status = 403, code = "forbidden"), assertFailsWith<TransportError> {
            gateway.register(username = "carol", password = "correct horse battery staple")
        })
        assertTrue(gateway.setRegistration(open = true))
        val created = gateway.register(username = "Carol", password = "correct horse battery staple")
        assertEquals("carol", created.user.username)
        assertEquals(UserRole.member, created.user.role)
        assertEquals(TransportError.Http(status = 409, code = "conflict"), assertFailsWith<TransportError> {
            gateway.register(username = "carol", password = "correct horse battery staple")
        })
        assertEquals(TransportError.Http(status = 400, code = "bad_request"), assertFailsWith<TransportError> {
            gateway.register(username = "no", password = "correct horse battery staple")
        })
    }
}
