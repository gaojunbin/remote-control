package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.persistence.MemorySecretStore
import com.junbingao.remotecontrol.core.protocol.AccountRules
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.ProtocolFailure
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.protocol.UserIdentity
import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.protocol.UserState
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.get
import com.junbingao.remotecontrol.core.protocol.stringValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test

/**
 * `ios/Verification/AccountChecks.swift`: accounts (amendment A24), end to end through the real
 * HTTP client. Every body the app sends is compared with the fixture in `protocol/fixtures` rather
 * than with a copy written here, and every answer it reads is decoded from one. What each refusal
 * says is `AccountError`'s, a store's, and is `core-state`'s to add here.
 */
class AccountChecks {
    // What the gateway answers

    @Test
    fun responses() {
        val checks = CheckRunner("accounts")
        checks.noThrow("a health response decodes") {
            val health = FixtureSource.json("http/health.response.json").decode<HealthResponse>()
            if (health.protocolVersion != RemoteProtocol.version || health.registrationOpen) throw ProtocolFailure.Malformed("health")
        }
        val login = FixtureSource.json("http/login.response.json").decode<LoginResponse>()
        checks.equal(login.user.username, "admin", "a login response names the account")
        checks.expect(login.user.role.isAdmin, "and carries its role")
        checks.expect(FixtureSource.json("http/auth.session.response.json").decode<SessionInfoResponse>().user.role.isAdmin,
                      "the stored account's role comes back from /api/session")
        val hello = (AppFrame(json = FixtureSource.json("app/hello.json")) as AppFrame.Hello).hello
        checks.equal(hello.user.username, "admin", "the app hello names the account the socket signed in as")

        val list = FixtureSource.json("http/users.list.response.json").decode<UserListResponse>()
        checks.equal(list.users.size, 2, "the user list decodes both accounts")
        checks.expect(!list.registrationOpen, "and says registration is closed")
        val admin = list.users.firstOrNull()
        checks.expect(admin?.isOperator == true, "the first row is the operator's")
        checks.equal(admin?.devices, 2, "with the devices it has enrolled")
        val alice = list.users.lastOrNull()
        checks.equal(alice?.state, UserState.disabled, "the second account is disabled")
        checks.equal(alice?.lastLoginAt, null, "and has never signed in")
        checks.equal(alice?.lastLoginSummary(), "never", "which the row says in words")
        checks.equal(alice?.deviceSummary, "", "an account with no devices says nothing about them")
        checks.expect(alice?.isOperator == false, "and it is not the operator, so it has actions")

        checks.noThrow("a single user response decodes") {
            val record = FixtureSource.json("http/users.response.json").decode<UserResponse>().user
            if (record.role != UserRole.member || record.state != UserState.active) throw ProtocolFailure.Malformed("users.response")
        }
        checks.noThrow("a registration response decodes") {
            if (!FixtureSource.json("http/registration.response.json").decode<RegistrationResponse>().open) {
                throw ProtocolFailure.Malformed("registration.response")
            }
        }
        // A role this build has never heard of decodes rather than failing, and is not the admin: an
        // older app must never open 3.9 by accident.
        val user = FixtureSource.invalidJSON("objects.user__role_unknown.json").decode<UserIdentity>()
        checks.equal(user.role.rawValue, "owner", "an unknown role decodes as itself")
        checks.expect(!user.role.isAdmin, "and is not treated as the admin")
        checks.assertAll()
    }

    // What the app sends

    @Test
    fun requests() = runBlocking {
        val checks = CheckRunner("accounts")
        val transport = RecordingTransport()
        transport.answer("POST /api/login", body = FixtureSource.json("http/login.response.json"))
        transport.answer("POST /api/register", body = FixtureSource.json("http/login.response.json"))
        transport.answer("GET /api/users", body = FixtureSource.json("http/users.list.response.json"))
        transport.answer("POST /api/users", body = FixtureSource.json("http/users.response.json"))
        transport.answer("PATCH /api/users/alice", body = FixtureSource.json("http/users.response.json"))
        transport.answer("PATCH /api/registration", body = FixtureSource.json("http/registration.response.json"))
        transport.answer("GET /api/health", body = FixtureSource.json("http/health.response.json"))
        val client = GatewayHTTPClient(endpoint = GatewayEndpoint.placeholder, transport = transport, secrets = MemorySecretStore())

        runCatching { client.login(username = "admin", password = "correct horse battery staple") }
        compare(transport, "POST /api/login", "http/login.request.json", checks)
        runCatching { client.register(username = "alice", password = "correct horse battery staple") }
        compare(transport, "POST /api/register", "http/register.request.json", checks)
        runCatching { client.changePassword(current = "correct horse battery staple", new = "staple battery horse correct") }
        compare(transport, "POST /api/password", "http/password.request.json", checks)

        checks.equal(runCatching { client.users() }.getOrNull()?.users?.size, 2, "the admin's list comes back as records")
        runCatching { client.createUser(username = "alice", password = "correct horse battery staple", role = UserRole.member) }
        compare(transport, "POST /api/users", "http/users.create.request.json", checks)
        runCatching { client.patchUser("alice", state = UserState.disabled) }
        compare(transport, "PATCH /api/users/alice", "http/users.patch.request.json", checks)
        runCatching { client.setRegistration(open = true) }
        compare(transport, "PATCH /api/registration", "http/registration.patch.request.json", checks)

        // The username is required: the negative fixture is the body 3.1 refuses, and nothing the
        // app builds may look like it.
        val login = transport.body("POST /api/login")
        checks.expect(login?.get("username")?.stringValue == "admin", "every sign-in the app sends names an account")
        val refused = FixtureSource.invalidJSON("http.login__no_username.json")
        checks.expect(refused["username"] == null && login != refused, "the body 3.1 refuses is not the one the app builds")

        // The account routes are the signed-in caller's, so they carry the bearer the login stored
        // and never go out unauthenticated.
        checks.expect(transport.authorization("GET /api/users")?.startsWith("Bearer ") == true,
                      "the account routes carry the bearer token")
        checks.equal(transport.authorization("GET /api/health"), null,
                     "and the health probe carries nothing, because nobody has signed in yet")

        runCatching { client.deleteUser("alice") }
        checks.equal(transport.method("DELETE /api/users/alice"), "DELETE", "deleting an account is a DELETE on its own path")
        checks.equal(runCatching { client.health() }.getOrNull()?.registrationOpen, false,
                     "the health probe reads the registration switch")
        checks.assertAll()
    }

    private fun compare(transport: RecordingTransport, call: String, fixture: String, checks: CheckRunner) {
        val sent = transport.body(call)
        if (sent == null) {
            checks.expect(false, "$call was sent")
            return
        }
        checks.equal(sent, FixtureSource.json(fixture), "$call sends exactly $fixture")
    }

    // The rules, written once

    @Test
    fun rules() {
        val checks = CheckRunner("accounts")
        checks.expect(!AccountRules.isPasswordLongEnough("1234567"), "seven characters is not a password")
        checks.expect(AccountRules.isPasswordLongEnough("12345678"), "eight is")
        checks.equal(AccountRules.operatorUsername, "admin", "the operator is the account named admin")
        checks.expect(UserRole.admin.isAdmin && !UserRole.member.isAdmin, "only the admin role opens the accounts screen")
        checks.assertAll()
    }
}

/** An `HTTPTransport` that answers from a table and keeps what it was asked, so the bodies the client builds can be compared with the frozen fixtures. */
class RecordingTransport : HTTPTransport {
    private class Call(val method: String, val body: JsonElement?, val authorization: String?)

    private val calls = ConcurrentHashMap<String, Call>()
    private val answers = ConcurrentHashMap<String, Pair<Int, JsonElement>>()

    fun answer(call: String, status: Int = 200, body: JsonElement) {
        answers[call] = status to body
    }

    fun body(call: String): JsonElement? = calls[call]?.body
    fun authorization(call: String): String? = calls[call]?.authorization
    fun method(call: String): String? = calls[call]?.method

    override suspend fun perform(request: Request): Pair<ByteArray, Response> {
        val call = "${request.method} ${request.url.encodedPath}"
        val sent = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readByteArray().takeIf { it.isNotEmpty() }?.let { runCatching { JSONValue.parse(it) }.getOrNull() }
        }
        calls[call] = Call(request.method, sent, request.header("Authorization"))
        val (status, body) = answers[call] ?: (200 to JSONValue.emptyObject)
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("").build()
        return body.toString().encodeToByteArray() to response
    }
}
