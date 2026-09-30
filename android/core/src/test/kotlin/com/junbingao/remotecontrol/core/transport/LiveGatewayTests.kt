package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.persistence.MemorySecretStore
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.HistoryResult
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.protocol.SubscribeResult
import com.junbingao.remotecontrol.core.state.request
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The client and the socket against a real gateway implementation — the web app's mock gateway,
 * `web/mock/server.ts`, or any other — at the address `RC_MOCK_GATEWAY` names. Without it the
 * check is skipped, which is how CI runs it.
 */
@EnabledIfEnvironmentVariable(named = "RC_MOCK_GATEWAY", matches = ".+")
class LiveGatewayTests {
    /** Sign in as the operator, read health and config, open the socket and read its hello, then close. */
    @Test
    fun signsInAndReadsTheHello() = runBlocking {
        val endpoint = GatewayEndpoint(checkNotNull(System.getenv("RC_MOCK_GATEWAY")))
        val client = GatewayHTTPClient(endpoint = endpoint, secrets = MemorySecretStore())

        val health = client.health()
        assertEquals(RemoteProtocol.version, health.protocolVersion, "the gateway speaks this build's protocol")
        assertTrue(health.version.isNotEmpty(), "and says which gateway it is")

        val login = client.login(username = "admin", password = System.getenv("RC_MOCK_PASSWORD") ?: "dev")
        assertEquals("admin", login.user.username)
        assertTrue(login.user.role.isAdmin, "the operator signs in as the admin")
        assertTrue(client.hasToken)

        val config = client.config()
        assertEquals(health.version, config.version, "the config names the same gateway")

        val socket = GatewaySocket(client = client)
        val readers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hello = CompletableDeferred<AppFrame.Hello>()
        readers.launch {
            socket.events.collect { event ->
                ((event as? GatewayEvent.Frame)?.frame as? AppFrame.Hello)?.let(hello::complete)
            }
        }
        try {
            socket.connect()
            val frame = withTimeout(10.seconds) { hello.await() }.hello
            assertEquals(RemoteProtocol.version, frame.protocolVersion)
            assertEquals("admin", frame.user.username, "the socket signed in as the account the client did")
            assertTrue(socket.isConnected)
            println("hello: gateway ${frame.gatewayVersion}, ${frame.devices.size} devices, ${frame.sessions.size} sessions")
        } finally {
            socket.disconnect()
            readers.cancel()
        }
    }

    /** Beyond the hello: every session of the snapshot subscribes, and its transcript decodes event by event. */
    @Test
    fun subscribesToEverySession() = runBlocking {
        val client = GatewayHTTPClient(endpoint = GatewayEndpoint(checkNotNull(System.getenv("RC_MOCK_GATEWAY"))),
                                       secrets = MemorySecretStore())
        client.login(username = "admin", password = System.getenv("RC_MOCK_PASSWORD") ?: "dev")
        val socket = GatewaySocket(client = client)
        val readers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hello = CompletableDeferred<AppFrame.Hello>()
        readers.launch {
            socket.events.collect { event -> ((event as? GatewayEvent.Frame)?.frame as? AppFrame.Hello)?.let(hello::complete) }
        }
        try {
            socket.connect()
            val sessions = withTimeout(10.seconds) { hello.await() }.hello.sessions
            assertTrue(sessions.isNotEmpty(), "the snapshot lists sessions")
            var events = 0
            for (session in sessions) {
                val subscribed = socket.request(GatewayRequest.subscribe(sessionID = session.sessionID), SubscribeResult.serializer())
                assertEquals(session.sessionID, subscribed.session.sessionID)
                val page = socket.request(GatewayRequest.history(sessionID = session.sessionID), HistoryResult.serializer())
                events += subscribed.events.size + page.events.size
                socket.request(GatewayRequest.unsubscribe(sessionID = session.sessionID))
            }
            println("subscribed to ${sessions.size} sessions, decoded $events events")
        } finally {
            socket.disconnect()
            readers.cancel()
        }
    }
}
