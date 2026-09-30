package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.persistence.MemorySecretStore
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.protocol.get
import com.junbingao.remotecontrol.core.protocol.stringValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * `ios/Verification/SocketChecks.swift`, amendment A4: what the app does with each WebSocket close
 * code. The socket is driven through fake connections, so the reconnect decision is observed
 * rather than assumed. These run in real time: the socket's own timers are real.
 */
class SocketChecks {
    /** Where the readers and the overlapping requests run, apart from each test's own coroutine so a failed check cannot leave one behind. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stopBackground() = background.cancel()

    @Test
    fun closeReasons() {
        val checks = CheckRunner("socket")
        checks.equal(SocketCloseReason(code = 4401), SocketCloseReason.unauthorized, "4401 maps to unauthorized")
        checks.equal(SocketCloseReason(code = 4403), SocketCloseReason.forbidden, "4403 maps to forbidden")
        checks.equal(SocketCloseReason(code = 4001), SocketCloseReason.replaced, "4001 maps to replaced")
        checks.equal(SocketCloseReason(code = 1006), SocketCloseReason.transient, "an ordinary close is transient")
        checks.equal(SocketCloseReason(code = null), SocketCloseReason.transient, "a close with no code is transient")
        checks.expect(!SocketCloseReason.unauthorized.shouldReconnect, "unauthorized stops reconnecting")
        checks.expect(!SocketCloseReason.forbidden.shouldReconnect, "forbidden stops reconnecting")
        checks.expect(!SocketCloseReason.replaced.shouldReconnect, "replaced stops reconnecting")
        checks.expect(SocketCloseReason.transient.shouldReconnect, "a transient close reconnects")
        checks.assertAll()
    }

    /** A terminal code must produce exactly one connection attempt. */
    @Test
    fun terminal() = runBlocking {
        val checks = CheckRunner("socket")
        for ((code, expected) in listOf(4401 to SocketCloseReason.unauthorized, 4403 to SocketCloseReason.forbidden,
                                        4001 to SocketCloseReason.replaced)) {
            val factory = ClosingWebSocketFactory(closeCode = code)
            val socket = GatewaySocket(client = makeClient(), factory = factory)
            var sawUnauthorizedState = false
            val closed = CompletableDeferred<SocketCloseReason>()
            val reader = background.launch {
                socket.events.collect { event ->
                    when (event) {
                        is GatewayEvent.Closed -> closed.complete(event.reason)
                        is GatewayEvent.State -> if (event.state == ConnectionState.unauthorized) sawUnauthorizedState = true
                        else -> Unit
                    }
                }
            }
            socket.connect()
            val reason = withTimeoutOrNull(2.seconds) { closed.await() }
            checks.equal(reason, expected, "close $code reports $expected")
            checks.equal(factory.attempts.get(), 1, "close $code is not retried")
            if (expected == SocketCloseReason.unauthorized) {
                checks.expect(sawUnauthorizedState, "4401 also reports an unauthorized connection state")
            }
            reader.cancel()
            socket.disconnect()
        }
        checks.assertAll()
    }

    /** An ordinary close keeps trying, with the first backoff at one second. */
    @Test
    fun transient() = runBlocking {
        val checks = CheckRunner("socket")
        val factory = ClosingWebSocketFactory(closeCode = 1006)
        val socket = GatewaySocket(client = makeClient(), factory = factory)
        socket.connect()
        settle(4.seconds) { factory.attempts.get() >= 2 }
        checks.expect(factory.attempts.get() >= 2, "a transient close is retried")
        socket.disconnect()
        val settled = factory.attempts.get()
        delay(300)
        checks.equal(factory.attempts.get(), settled, "disconnecting stops the retry loop")
        checks.assertAll()
    }

    /** Review finding 12: two frames must reach the gateway in the order they were issued. A slow first write proves the chain rather than luck. */
    @Test
    fun writeOrdering() = runBlocking {
        val checks = CheckRunner("socket")
        val connection = OrderedWebSocket(firstWriteDelay = 120.milliseconds)
        val socket = GatewaySocket(client = makeClient(), factory = SingleConnectionFactory(connection))
        socket.connect()
        settle(2.seconds) { socket.isConnected }
        checks.expect(socket.isConnected, "the ordered-write socket connects")

        val first = background.launch { runCatching { socket.request(GatewayRequest.stop(sessionID = "s")) } }
        delay(20)
        runCatching { socket.request(GatewayRequest.unsubscribe(sessionID = "s")) }
        first.cancel()

        val order = connection.sentTypes.toList()
        checks.equal(order.firstOrNull(), "session.stop", "the first request is written first")
        checks.equal(order.getOrNull(1) ?: "", "session.unsubscribe", "a later frame cannot overtake a slow earlier write")
        socket.disconnect()
        checks.assertAll()
    }

    /**
     * A request issued while the socket is still coming up waits for the hello instead of failing.
     *
     * The gateway closes a silent socket after 25 s, so an app returning to the foreground has one
     * to rebuild more often than not. Failing the send for the length of a TLS handshake reads as a
     * dead Send button.
     */
    @Test
    fun sendWhileReconnecting() = runBlocking {
        val checks = CheckRunner("socket")
        val connection = SlowHelloWebSocket(helloDelay = 400.milliseconds)
        val socket = GatewaySocket(client = makeClient(), factory = SingleConnectionFactory(connection))
        socket.connect()

        // Issued at once: the hello is still 400 ms away. The transport may already be open — here
        // it opens without a pause — so what says the socket is not up yet is the missing hello.
        checks.expect(!connection.greeted, "the request is issued before the socket is up")
        val started = TimeSource.Monotonic.markNow()
        val delivered = runCatching { socket.request(GatewayRequest.stop(sessionID = "s")) }
        checks.expect(delivered.isSuccess, "a request issued while reconnecting is flushed once the hello lands: ${delivered.exceptionOrNull()}")
        checks.expect(started.elapsedNow() >= 300.milliseconds, "and it really did wait for the connection rather than racing it")
        checks.equal(connection.sentTypes.toList(), listOf("session.stop"), "the gateway sees it once, after the hello")
        socket.disconnect()
        checks.assertAll()
    }

    /** The wait is for a socket on its way back, not for one that was never asked for: a request on a disconnected transport still fails at once. */
    @Test
    fun sendWithNoSocketAtAll() = runBlocking {
        val checks = CheckRunner("socket")
        val socket = GatewaySocket(client = makeClient(), factory = SingleConnectionFactory(SlowHelloWebSocket(Duration.ZERO)))
        val refused = runCatching { socket.request(GatewayRequest.stop(sessionID = "s")) }.exceptionOrNull()
        checks.expect(refused == TransportError.NotConnected, "a request with no connection attempt under way fails at once")
        checks.assertAll()
    }

    /**
     * One frame the app cannot read is one frame, not a broken connection.
     *
     * Nothing validates frames against the schema at runtime, so a device adapter that omits a
     * required field reaches every app unfiltered. If that closed the socket, the app would fail
     * every request in flight as unconfirmed and reconnect, for as long as that session ran.
     */
    @Test
    fun undecodableFrame() = runBlocking {
        val checks = CheckRunner("socket")
        val connection = FlawedWebSocket()
        val factory = CountingFactory(connection)
        val socket = GatewaySocket(client = makeClient(), factory = factory)
        socket.connect()
        settle(2.seconds) { socket.isConnected }

        val replied = runCatching { socket.request(GatewayRequest.stop(sessionID = "s")) }
        checks.expect(replied.isSuccess, "a request in flight still gets its reply past a frame that was dropped: ${replied.exceptionOrNull()}")
        checks.equal(factory.attempts.get(), 1, "and the connection is not torn down and rebuilt")
        checks.expect(socket.isConnected, "the socket is still the one that was connected")
        socket.disconnect()
        checks.assertAll()
    }

    /**
     * Amendment A12 makes a retry reuse its request id, so two of them can be outstanding at once.
     * The first caller has to be answered rather than left suspended for the life of the process.
     */
    @Test
    fun duplicateRequestID() = runBlocking {
        val checks = CheckRunner("socket")
        val connection = HeldReplyWebSocket()
        val socket = GatewaySocket(client = makeClient(), factory = SingleConnectionFactory(connection))
        socket.connect()
        settle(2.seconds) { socket.isConnected }

        val request = GatewayRequest.send(id = "duplicate", sessionID = "s", text = "one", attachments = emptyList(),
                                          mode = SendMode.auto)
        val outcomes = ConcurrentHashMap<String, String>()
        // Neither is awaited: before the fix the first one never returns, and a check that waited
        // for it would hang rather than fail.
        val first = background.launch { outcomes["first"] = outcome { socket.request(request) } }
        settle(2.seconds) { connection.writes.get() == 1 }
        val second = background.launch { outcomes["second"] = outcome { socket.request(request) } }
        settle(2.seconds) { connection.writes.get() == 2 }

        connection.answerOutstanding()
        settle(3.seconds) { outcomes["second"] != null }

        checks.equal(outcomes["first"], "deliveryUncertain", "the caller whose slot was taken is answered, not stranded")
        checks.equal(outcomes["second"], "replied", "and the second request gets the gateway's reply")
        first.cancel()
        second.cancel()
        socket.disconnect()
        checks.assertAll()
    }

    private suspend fun outcome(block: suspend () -> Unit): String = try {
        block()
        "replied"
    } catch (_: TransportError.DeliveryUncertain) {
        "deliveryUncertain"
    } catch (_: TransportError.RequestTimedOut) {
        "requestTimedOut"
    } catch (error: Exception) {
        "$error"
    }

    private fun makeClient(): GatewayHTTPClient =
        GatewayHTTPClient(endpoint = GatewayEndpoint("https://rc.example.invalid"), transport = UnusedHTTPTransport,
                          secrets = MemorySecretStore()).also { it.adoptToken("verification-token") }

    private suspend fun settle(timeout: Duration, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (!condition() && deadline.hasNotPassedNow()) delay(10)
    }
}

private const val HELLO = """{"type":"hello","protocol":1,"gateway_version":"t","user":{"username":"a"},"server_time":0}"""

/** The socket checks never issue an HTTP request; reaching this is a bug. */
private object UnusedHTTPTransport : HTTPTransport {
    override suspend fun perform(request: Request): Pair<ByteArray, okhttp3.Response> = throw TransportError.NotConnected
}

private fun typeAndID(text: String): Pair<String?, String?> {
    val json = runCatching { JSONValue.parse(text.encodeToByteArray()) }.getOrNull()
    return json?.get("type")?.stringValue to json?.get("id")?.stringValue
}

private class SingleConnectionFactory(private val connection: WebSocketConnection) : WebSocketFactory {
    override suspend fun makeConnection(request: Request): WebSocketConnection = connection
}

private class CountingFactory(private val connection: WebSocketConnection) : WebSocketFactory {
    val attempts = AtomicInteger(0)
    override suspend fun makeConnection(request: Request): WebSocketConnection {
        attempts.incrementAndGet()
        return connection
    }
}

/** A connection that takes its time over the hello, then answers every request it is written with an empty successful reply. */
private class SlowHelloWebSocket(private val helloDelay: Duration) : WebSocketConnection {
    private val lock = Mutex()

    @Volatile
    var greeted = false
        private set
    private val replies = ArrayDeque<String>()
    val sentTypes = CopyOnWriteArrayList<String>()

    override suspend fun resume() {}
    override suspend fun send(text: String) {
        val (type, id) = typeAndID(text)
        if (type == null || id == null) return
        sentTypes.add(type)
        lock.withLock { replies.addLast("""{"type":"reply","id":"$id","ok":true,"result":{}}""") }
    }

    override suspend fun send(binary: ByteArray) {}
    override suspend fun receive(): ByteArray {
        if (!greeted) {
            delay(helloDelay)
            greeted = true
            return HELLO.encodeToByteArray()
        }
        while (true) {
            lock.withLock { replies.removeFirstOrNull() }?.let { return it.encodeToByteArray() }
            delay(5)
        }
    }

    override suspend fun closeCode(): Int? = null
    override suspend fun cancel() {}
}

/** Stays open, records the frame types it was sent, and makes the first write slow so an ordering bug would be observable. */
private class OrderedWebSocket(private val firstWriteDelay: Duration) : WebSocketConnection {
    val sentTypes = CopyOnWriteArrayList<String>()

    @Volatile
    private var wroteOnce = false

    override suspend fun resume() {}
    override suspend fun send(text: String) {
        if (!wroteOnce) {
            wroteOnce = true
            delay(firstWriteDelay)
        }
        typeAndID(text).first?.let(sentTypes::add)
    }

    override suspend fun send(binary: ByteArray) {}

    /** Hellos until the first write, then silence: the socket stays connected for the test. */
    override suspend fun receive(): ByteArray {
        if (wroteOnce || sentTypes.isNotEmpty()) {
            delay(30.seconds)
            throw TransportError.NotConnected
        }
        return HELLO.encodeToByteArray()
    }

    override suspend fun closeCode(): Int? = null
    override suspend fun cancel() {}
}

/** A factory whose connections open, immediately fail their first read, and report the close code they were built with. */
private class ClosingWebSocketFactory(private val closeCode: Int) : WebSocketFactory {
    val attempts = AtomicInteger(0)
    override suspend fun makeConnection(request: Request): WebSocketConnection {
        attempts.incrementAndGet()
        return ClosingWebSocket(closeCode)
    }
}

private class ClosingWebSocket(private val code: Int) : WebSocketConnection {
    override suspend fun resume() {}
    override suspend fun send(text: String) {}
    override suspend fun send(binary: ByteArray) {}
    override suspend fun receive(): ByteArray = throw TransportError.NotConnected
    override suspend fun closeCode(): Int? = code
    override suspend fun cancel() {}
}

/**
 * Answers every request, but puts one frame the app cannot read in front of the reply: a `todos`
 * item with no `text`, which violates the schema's `required` and is exactly what a device
 * adapter with a missing field sends.
 */
private class FlawedWebSocket : WebSocketConnection {
    private val lock = Mutex()
    private var greeted = false
    private val outbox = ArrayDeque<String>()

    override suspend fun resume() {}
    override suspend fun send(text: String) {
        val id = typeAndID(text).second ?: return
        lock.withLock {
            outbox.addLast("""{"type":"session.event","session_id":"s","event":""" +
                               """{"seq":1,"ts":0,"kind":"todos","items":[{"status":"pending"}]}}""")
            outbox.addLast("""{"type":"reply","id":"$id","ok":true,"result":{}}""")
        }
    }

    override suspend fun send(binary: ByteArray) {}
    override suspend fun receive(): ByteArray {
        if (!greeted) {
            greeted = true
            return HELLO.encodeToByteArray()
        }
        while (true) {
            lock.withLock { outbox.removeFirstOrNull() }?.let { return it.encodeToByteArray() }
            delay(5)
        }
    }

    override suspend fun closeCode(): Int? = null
    override suspend fun cancel() {}
}

/**
 * Takes writes and holds their replies, so two requests can be outstanding at once.
 * [answerOutstanding] then replies to the last id it was written, which is what a gateway
 * deduplicating a retry does (amendment A12).
 */
private class HeldReplyWebSocket : WebSocketConnection {
    private val lock = Mutex()
    private var greeted = false
    private val outbox = ArrayDeque<String>()

    @Volatile
    private var lastID: String? = null
    val writes = AtomicInteger(0)

    override suspend fun resume() {}
    override suspend fun send(text: String) {
        val id = typeAndID(text).second ?: return
        lastID = id
        writes.incrementAndGet()
    }

    override suspend fun send(binary: ByteArray) {}

    suspend fun answerOutstanding() {
        val id = lastID ?: return
        lock.withLock { outbox.addLast("""{"type":"reply","id":"$id","ok":true,"result":{"accepted":"sent"}}""") }
    }

    override suspend fun receive(): ByteArray {
        if (!greeted) {
            greeted = true
            return HELLO.encodeToByteArray()
        }
        while (true) {
            lock.withLock { outbox.removeFirstOrNull() }?.let { return it.encodeToByteArray() }
            delay(5)
        }
    }

    override suspend fun closeCode(): Int? = null
    override suspend fun cancel() {}
}
