package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.protocol.AppSupport
import com.junbingao.remotecontrol.core.protocol.AppsInfo
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.protocol.STTConfig
import com.junbingao.remotecontrol.core.protocol.UserIdentity
import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.transport.GatewayConfig
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import com.junbingao.remotecontrol.core.transport.HealthResponse
import com.junbingao.remotecontrol.core.transport.LoginResponse
import com.junbingao.remotecontrol.core.transport.PushConfig
import com.junbingao.remotecontrol.core.transport.SessionInfoResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A reply belongs to the connection that asked for it.
 *
 * `restore` and `start` both confirm a connection behind the screens they have already drawn —
 * `/api/session` for the account, `/api/config` for what the gateway offers. Signing out and in
 * again while one of those is in flight used to land the old gateway's answer on the new
 * connection.
 */
class ConnectionScopeTests {
    private fun store(gateways: List<ScopedGateway>, directory: File, tasks: CoroutineScope): ConnectionStore =
        ConnectionStore(tasks = tasks, cache = LocalCache(directory),
                        makeAPI = { endpoint -> gateways.firstOrNull { it.endpoint.origin == endpoint.origin } ?: gateways[0] },
                        makeChannel = { InertChannel() })

    /** A late /api/session answer cannot rewrite the account that signed in after it. */
    @Test
    fun lateAccountConfirmation() = runTest {
        val directory = scratchDirectory("scope")
        try {
            val first = ScopedGateway(origin = "https://a.example.invalid",
                                      identity = UserIdentity(username = "alice", role = UserRole.admin),
                                      sessionDelay = 400.milliseconds)
            val second = ScopedGateway(origin = "https://b.example.invalid",
                                       identity = UserIdentity(username = "bob", role = UserRole.member))
            val store = store(listOf(first, second), directory, backgroundScope)

            assertTrue(store.restore(origin = "https://a.example.invalid", username = "alice"))
            assertEquals("alice", store.username)
            store.signOut()
            store.signIn(origin = "https://b.example.invalid", username = "bob", password = "secret")
            assertEquals("bob", store.username)

            // Long enough for the first gateway's `/api/session` to answer.
            delay(700)
            assertEquals("bob", store.username, "the account the person signed in as stands")
            assertFalse(store.isAdmin, "and so does its role, which gates the admin screens")
            assertEquals("https://b.example.invalid|bob", store.account, "the cache and the drafts are keyed by this")
            assertNotEquals<ConnectionPhase>(ConnectionPhase.SignedOut, store.phase, "a stale 401 cannot end a session that is fine")
        } finally {
            directory.deleteRecursively()
        }
    }

    /** A late /api/config answer from a gateway you left cannot block the app. */
    @Test
    fun lateConfiguration() = runTest {
        val directory = scratchDirectory("scope")
        try {
            // Amendment A31: this gateway will not talk to any build this app can be.
            val old = ScopedGateway(origin = "https://old.example.invalid", identity = UserIdentity(username = "me"),
                                    configDelay = 400.milliseconds, minimumAppVersion = "99.0.0")
            val good = ScopedGateway(origin = "https://good.example.invalid", identity = UserIdentity(username = "me"))
            val store = store(listOf(old, good), directory, backgroundScope)

            store.signIn(origin = "https://old.example.invalid", username = "me", password = "secret")
            assertNull(store.updateRequired)
            store.signOut()
            store.signIn(origin = "https://good.example.invalid", username = "me", password = "secret")

            delay(700)
            assertNull(store.updateRequired, "a gateway that states no minimum does not show the Update required screen")
            assertEquals("https://good.example.invalid", store.config.publicOrigin,
                         "and the config on screen is the one this connection answered with")
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Signing out clears the blocking screen the gateway put up. */
    @Test
    fun signOutClearsTheUpdateScreen() = runTest {
        val directory = scratchDirectory("scope")
        try {
            val demanding = ScopedGateway(origin = "https://old.example.invalid", identity = UserIdentity(username = "me"),
                                          minimumAppVersion = "99.0.0")
            val store = store(listOf(demanding), directory, backgroundScope)

            store.signIn(origin = "https://old.example.invalid", username = "me", password = "secret")
            delay(200)
            assertNotNull(store.updateRequired, "the gateway's minimum still reaches the app")
            store.signOut()
            assertNull(store.updateRequired)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * A gateway that answers the two routes the connection scope depends on, each after a delay of
     * the test's choosing, and refuses everything else.
     */
    private class ScopedGateway(
        origin: String,
        private val identity: UserIdentity,
        private val sessionDelay: Duration = Duration.ZERO,
        private val configDelay: Duration = Duration.ZERO,
        private val minimumAppVersion: String? = null,
    ) : StubGateway(GatewayEndpoint(origin)) {
        private val apps: AppsInfo? get() = minimumAppVersion?.let { AppsInfo(ios = AppSupport(minimumVersion = it)) }

        override suspend fun health(): HealthResponse =
            HealthResponse(version = "test", protocolVersion = RemoteProtocol.version, registrationOpen = false, apps = apps)

        override suspend fun login(username: String, password: String): LoginResponse =
            LoginResponse(token = "t", exp = 0, user = identity)

        override suspend fun register(username: String, password: String): LoginResponse = login(username, password)

        override suspend fun session(): SessionInfoResponse {
            delay(sessionDelay)
            return SessionInfoResponse(user = identity, exp = 0)
        }

        override suspend fun config(): GatewayConfig {
            delay(configDelay)
            return GatewayConfig(publicOrigin = endpoint.origin, stt = STTConfig.disabled, push = PushConfig.disabled,
                                 version = "test", apps = apps)
        }

        override suspend fun restoreToken(username: String): Boolean = true

        override suspend fun bearerToken(): String = "t"

        override suspend fun changePassword(current: String, new: String) {}
    }
}
