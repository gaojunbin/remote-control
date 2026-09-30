package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.persistence.MemorySecretStore
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.get
import com.junbingao.remotecontrol.core.protocol.jsonObjectOf
import com.junbingao.remotecontrol.core.protocol.stringValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The OkHttp transport, the client and both sockets against a real HTTP server and a real
 * WebSocket upgrade (`mockwebserver3`), where the checks above drive fakes.
 */
class OkHttpTransportTests {
    private val server = MockWebServer()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val secrets = MemorySecretStore()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() {
        background.cancel()
        server.close()
    }

    private fun endpoint(): GatewayEndpoint = GatewayEndpoint(server.url("/").toString())

    private fun client(): GatewayHTTPClient = GatewayHTTPClient(endpoint = endpoint(), secrets = secrets)

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun take(): RecordedRequest = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "no request arrived" }

    private fun RecordedRequest.json() = body?.let { JSONValue.parse(it.toByteArray()) }

    // HTTP

    /** A sign-in keeps the token, files it under the origin and the account, and sends it on every later call. */
    @Test
    fun signInCarriesTheBearer() = runBlocking {
        val login = FixtureSource.json("http/login.response.json")
        server.enqueue(json(login.toString()))
        server.enqueue(json(FixtureSource.json("http/config.response.json").toString()))
        val client = client()

        val response = client.login(username = "admin", password = "correct horse")
        val signIn = take()
        assertEquals("POST", signIn.method)
        assertEquals("/api/login", signIn.url.encodedPath)
        assertNull(signIn.headers["Authorization"], "a sign-in carries no credential")
        assertEquals("application/json", signIn.headers["Content-Type"])
        assertEquals("application/json", signIn.headers["Accept"])
        assertEquals(jsonObjectOf("username" to "admin", "password" to "correct horse"), signIn.json())
        val token = login["token"]?.stringValue
        assertEquals(token, response.token)
        assertTrue(secrets.read("token:${endpoint().origin}:admin").contentEquals(token?.encodeToByteArray()))

        client.config()
        assertEquals("Bearer $token", take().headers["Authorization"])
    }

    /** A 401 is the session ending; anything else outside 2xx says its status and the gateway's code. */
    @Test
    fun refusalsAreTyped() = runBlocking {
        val client = client().also { it.adoptToken("t") }
        server.enqueue(json("""{"ok":false,"error":{"code":"unauthorized","message":"no"}}""", code = 401))
        assertFailsWith<TransportError.Unauthorized> { client.config() }
        server.enqueue(json("""{"ok":false,"error":{"code":"not_found","message":"gone"}}""", code = 404))
        assertEquals(TransportError.Http(status = 404, code = "not_found"), runCatching { client.devices() }.exceptionOrNull())
        server.enqueue(MockResponse.Builder().code(503).body("<html>down</html>").build())
        assertEquals(TransportError.Http(status = 503, code = null), runCatching { client.polishModels() }.exceptionOrNull())
        // An authenticated route with no token never leaves the app.
        assertFailsWith<TransportError.Unauthorized> { client().config() }
        assertEquals(3, server.requestCount)
    }

    /** A redirect is answered, never followed: the bearer must not reach a host the person did not type. */
    @Test
    fun redirectsAreRefused() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "https://elsewhere.example/api/config").build())
        val client = client().also { it.adoptToken("t") }
        assertEquals(TransportError.Http(status = 302, code = null), runCatching { client.config() }.exceptionOrNull())
        assertEquals(1, server.requestCount)
    }

    /** More than 8 MiB is refused rather than loaded. */
    @Test
    fun responseTooLarge() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(8 * 1024 * 1024 + 1)).build())
        val client = client().also { it.adoptToken("t") }
        assertFailsWith<TransportError.ResponseTooLarge> { client.config() }
    }

    /** An id is one path segment whatever it holds, and the list's filters ride in the query. */
    @Test
    fun pathsAndQueries() = runBlocking {
        val client = client().also { it.adoptToken("t") }
        server.enqueue(json("""{"sessions":[]}"""))
        client.sessions(deviceID = "d 1", archived = true)
        val list = take()
        assertEquals("/api/sessions", list.url.encodedPath)
        assertEquals("d 1", list.url.queryParameter("device_id"))
        assertEquals("true", list.url.queryParameter("archived"))

        server.enqueue(json(FixtureSource.json("http/devices.patch.response.json").toString()))
        client.renameDevice("a/b", name = "studio")
        val rename = take()
        assertEquals("PATCH", rename.method)
        assertEquals("/api/devices/a%2Fb", rename.url.encodedPath)
        assertEquals(jsonObjectOf("name" to "studio"), rename.json())

        server.enqueue(json("{}"))
        client.unregisterPush(token = "abc")
        val unregister = take()
        assertEquals("DELETE", unregister.method)
        assertEquals(jsonObjectOf("token" to "abc"), unregister.json())

        server.enqueue(json("{}"))
        client.logout()
        val logout = take()
        assertEquals("POST", logout.method)
        assertEquals(0L, logout.bodySize, "a POST with nothing to say sends an empty body")
        assertFalse(client.hasToken)
    }

    /** A token kept from an earlier launch is read back under the same origin and account. */
    @Test
    fun restoreToken() = runBlocking {
        secrets.write("kept".encodeToByteArray(), key = "token:${endpoint().origin}:alice")
        val client = client()
        assertFalse(client.restoreToken(username = "bob"))
        assertTrue(client.restoreToken(username = "alice"))
        assertEquals("kept", client.bearerToken())
        client.forgetToken(username = "alice")
        assertNull(client.bearerToken())
        assertNull(secrets.read("token:${endpoint().origin}:alice"))
    }

    // WebSocket

    /** The gateway's end of one socket: what it says on opening, and how it answers a text frame. */
    private class Gateway(private val greeting: String? = null,
                          private val onOpened: (WebSocket) -> Unit = {},
                          private val onText: (WebSocket, String) -> Unit = { _, _ -> }) : WebSocketListener() {
        val received = CopyOnWriteArrayList<String>()
        val binary = CopyOnWriteArrayList<ByteString>()

        @Volatile
        var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            greeting?.let(webSocket::send)
            onOpened(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
            onText(webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            binary.add(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }

    private fun upgrade(listener: WebSocketListener): MockResponse = MockResponse.Builder().webSocketUpgrade(listener).build()

    private val hello = """{"type":"hello","protocol":1,"gateway_version":"t","user":{"username":"admin","role":"admin"},"server_time":0}"""

    private fun collect(socket: GatewaySocket): CopyOnWriteArrayList<GatewayEvent> {
        val events = CopyOnWriteArrayList<GatewayEvent>()
        background.launch { socket.events.collect { events.add(it) } }
        return events
    }

    /** The upgrade carries the bearer, the hello opens the socket, a request is answered and a ping is ponged. */
    @Test
    fun socketRoundTrip() = runBlocking {
        val gateway = Gateway(greeting = hello) { webSocket, text ->
            val frame = JSONValue.parse(text.encodeToByteArray())
            frame["id"]?.stringValue?.let { webSocket.send("""{"type":"reply","id":"$it","ok":true,"result":{"accepted":"sent"}}""") }
        }
        server.enqueue(upgrade(gateway))
        val client = client().also { it.adoptToken("socket-token") }
        val socket = GatewaySocket(client = client)
        val events = collect(socket)
        socket.connect()
        settle { socket.isConnected && events.any { it is GatewayEvent.Frame } }
        val upgradeRequest = take()
        assertEquals("/ws/app", upgradeRequest.url.encodedPath)
        assertEquals("Bearer socket-token", upgradeRequest.headers["Authorization"])
        val frame = assertIs<GatewayEvent.Frame>(events.first { it is GatewayEvent.Frame }).frame
        assertEquals("admin", assertIs<AppFrame.Hello>(frame).hello.user.username)
        assertTrue(events.contains(GatewayEvent.State(ConnectionState.connected)))

        val reply = socket.request(GatewayRequest.stop(sessionID = "s"))
        assertEquals("sent", reply["accepted"]?.stringValue)
        assertTrue(gateway.received.any { it.contains("\"session.stop\"") })

        gateway.socket?.send("""{"type":"ping"}""")
        settle { gateway.received.any { it == """{"type":"pong"}""" } }
        assertTrue(gateway.received.contains("""{"type":"pong"}"""), "a ping is answered with a pong")
        socket.disconnect()
        settle { GatewayEvent.State(ConnectionState.disconnected) in events }
        assertTrue(events.contains(GatewayEvent.State(ConnectionState.disconnected)))
    }

    /** Amendment A4 over a real socket: a 4401 close ends the session and is not retried. */
    @Test
    fun unauthorizedCloseIsFinal() = runBlocking {
        server.enqueue(upgrade(Gateway(onOpened = { it.close(4401, "unauthorized") })))
        val socket = GatewaySocket(client = client().also { it.adoptToken("expired") })
        val events = collect(socket)
        socket.connect()
        settle(3.seconds) { events.any { it is GatewayEvent.Closed } }
        assertEquals(GatewayEvent.Closed(SocketCloseReason.unauthorized), events.lastOrNull { it is GatewayEvent.Closed })
        assertTrue(events.contains(GatewayEvent.State(ConnectionState.unauthorized)))
        delay(1500)
        assertEquals(1, server.requestCount, "a refused connection is not tried again")
        socket.disconnect()
    }

    /** Any other close is transient: the socket comes back on its own after the first backoff. */
    @Test
    fun transientCloseReconnects() = runBlocking {
        server.enqueue(upgrade(Gateway(greeting = hello, onOpened = { it.close(1011, "restarting") })))
        server.enqueue(upgrade(Gateway(greeting = hello)))
        val socket = GatewaySocket(client = client().also { it.adoptToken("t") })
        val events = collect(socket)
        socket.connect()
        settle(4.seconds) { server.requestCount >= 2 && socket.isConnected }
        assertEquals(2, server.requestCount)
        assertTrue(socket.isConnected)
        assertTrue(events.contains(GatewayEvent.State(ConnectionState.reconnecting)))
        socket.disconnect()
    }

    /** Dictation over a real socket: audio goes as binary frames, `stt.stop` as text, and the final ends the utterance. */
    @Test
    fun dictationRoundTrip() = runBlocking {
        val gateway = Gateway { webSocket, text ->
            if (text == """{"type":"stt.stop"}""") webSocket.send("""{"type":"stt.final","text":"run the tests","language":"en"}""")
        }
        server.enqueue(upgrade(gateway))
        val socket = STTSocket(client = client().also { it.adoptToken("t") })
        val events = CopyOnWriteArrayList<STTEvent>()
        background.launch { socket.events.collect { events.add(it) } }
        socket.start()
        socket.append(ByteArray(640) { 1 })
        settle { gateway.binary.isNotEmpty() }
        socket.stop()
        settle { STTEvent.Closed in events }
        assertEquals("/ws/stt", take().url.encodedPath)
        assertEquals(640, gateway.binary.firstOrNull()?.size)
        assertNotNull(events.firstOrNull { it == STTEvent.Final(text = "run the tests", language = "en") })
        assertEquals(STTEvent.Closed, events.last())
    }

    private suspend fun settle(timeout: Duration = 2.seconds, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (!condition() && deadline.hasNotPassedNow()) delay(10)
    }
}
