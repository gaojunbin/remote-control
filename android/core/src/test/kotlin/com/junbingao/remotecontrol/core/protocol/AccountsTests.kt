package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.transport.HealthResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Amendment A24: every person on a gateway has their own devices, sessions and settings. These are
 * the parts of that the app decodes. RCCore's suite of this name also holds what each refusal says
 * (`AccountError`), whose preferences are read (`SettingsStore`) and the demo gateway's three
 * answers; those cases are in `state/AccountsTests.kt` and `demo/AccountsTests.kt`.
 */
class AccountsTests {
    /** A user is a username and a role. */
    @Test
    fun userIdentity() {
        val user = jsonObjectOf("username" to "alice", "role" to "member").decode<UserIdentity>()
        assertEquals("alice", user.username)
        assertEquals(UserRole.member, user.role)
        assertFalse(user.role.isAdmin)
    }

    /** A role this build has never heard of is decoded, and is not the admin. */
    @Test
    fun unknownRole() {
        val user = jsonObjectOf("username" to "alice", "role" to "owner").decode<UserIdentity>()
        assertEquals("owner", user.role.rawValue)
        assertFalse(user.role.isAdmin)
        assertEquals("owner", user.role.title)
    }

    /** A hello with no user at all still decodes. */
    @Test
    fun helloWithoutUser() {
        val frame = AppFrame(json = jsonObjectOf("type" to "hello", "protocol" to 1, "gateway_version" to "0.1.0",
                                                 "server_time" to 0))
        val hello = assertIs<AppFrame.Hello>(frame, "expected a hello").hello
        assertTrue(hello.user.username.isEmpty())
        assertFalse(hello.user.role.isAdmin)
    }

    /** A user record carries what the row draws. */
    @Test
    fun userRecord() {
        val record = jsonObjectOf("username" to "alice", "role" to "member", "state" to "disabled",
                                  "created_at" to 1_788_512_400_000, "last_login_at" to null,
                                  "devices" to 0).decode<UserRecord>()
        assertEquals("Member · Disabled", record.roleAndState)
        assertTrue(record.deviceSummary.isEmpty())
        assertEquals("never", record.lastLoginSummary())
        assertFalse(record.isOperator)
        assertFalse(record.isActive)
    }

    /** The operator's row is the one with no actions. */
    @Test
    fun operatorRecord() {
        val record = jsonObjectOf("username" to "admin", "role" to "admin", "state" to "active",
                                  "created_at" to 1, "last_login_at" to 2, "devices" to 2).decode<UserRecord>()
        assertTrue(record.isOperator)
        assertEquals("2 devices", record.deviceSummary)
        assertTrue(record.identity.role.isAdmin)
    }

    /** The health response says whether registration is open. */
    @Test
    fun health() {
        val json = jsonObjectOf("ok" to true, "version" to "0.1.0", "protocol" to 1,
                                "auth" to mapOf("mode" to "password", "registration_open" to true))
        assertTrue(json.decode<HealthResponse>().registrationOpen)
        // A gateway that says nothing about it is not taking accounts.
        val quiet = jsonObjectOf("ok" to true, "version" to "0.1.0", "protocol" to 1)
        assertFalse(quiet.decode<HealthResponse>().registrationOpen)
    }

    /** A password is eight characters or more. */
    @Test
    fun passwordLength() {
        assertFalse(AccountRules.isPasswordLongEnough(""))
        assertFalse(AccountRules.isPasswordLongEnough("1234567"))
        assertTrue(AccountRules.isPasswordLongEnough("12345678"))
        assertEquals(8..128, AccountRules.passwordLength)
    }
}
