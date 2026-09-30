package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.persistence.MemorySecretStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * `ios/Verification/STTChecks.swift`. Review finding 31: the gateway transcription socket had no
 * behavioural test, which is why finding 2 (a two-second cancel after Stop) went unnoticed.
 */
class STTChecks {
    /** Where the readers run, apart from each test's own coroutine so a failed check cannot leave one behind. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stopReaders() = background.cancel()

    /** `stt.stop` and `stt.cancel` are the only text frames the app sends. */
    @Test
    fun framing() = runBlocking {
        val checks = CheckRunner("stt")
        val connection = FakeSTTConnection()
        val socket = makeSocket(connection)
        socket.start()
        socket.append(ByteArray(320))
        socket.stop()
        settle { connection.sentText.size == 1 }
        checks.equal(connection.sentText.toList(), listOf("""{"type":"stt.stop"}"""), "stop sends stt.stop")
        checks.equal(connection.sentBinary.size, 1, "audio is sent as binary frames")
        checks.equal(connection.sentBinary.firstOrNull()?.size, 320, "the buffer is sent verbatim")
        socket.close()

        val cancelling = FakeSTTConnection()
        val second = makeSocket(cancelling)
        second.start()
        second.cancel()
        settle { cancelling.sentText.size == 1 }
        checks.equal(cancelling.sentText.toList(), listOf("""{"type":"stt.cancel"}"""), "cancel sends stt.cancel")
        checks.assertAll()
    }

    /** Amendment A44: the socket names no language, and a final the provider put no language on reads as `auto`. */
    @Test
    fun noLanguage() = runBlocking {
        val checks = CheckRunner("stt")
        val connection = FakeSTTConnection()
        val socket = makeSocket(connection)
        val recorder = Recorder(background, socket)
        socket.start()
        val url = connection.requestURL
        checks.equal(url?.encodedPath, "/ws/stt", "dictation opens the streaming socket")
        checks.expect(url?.query == null, "with no language on it: the gateway's provider detects it")

        socket.stop()
        connection.deliver("""{"type":"stt.final","text":"re-run the suite"}""")
        val final = STTEvent.Final(text = "re-run the suite", language = "auto")
        settle { final in recorder.events }
        checks.expect(final in recorder.events, "and a final that names no language says auto rather than guessing")
        recorder.stop()
        checks.assertAll()
    }

    /** A partial updates the draft; the final ends the utterance. */
    @Test
    fun finalTranscript() = runBlocking {
        val checks = CheckRunner("stt")
        val connection = FakeSTTConnection()
        val socket = makeSocket(connection)
        val recorder = Recorder(background, socket)
        socket.start()
        connection.deliver("""{"type":"stt.partial","text":"re-run the"}""")
        settle { STTEvent.Partial("re-run the") in recorder.events }
        checks.expect(STTEvent.Partial("re-run the") in recorder.events, "a partial transcript is delivered")

        socket.stop()
        connection.deliver("""{"type":"stt.final","text":"re-run the suite","language":"en"}""")
        val final = STTEvent.Final(text = "re-run the suite", language = "en")
        settle { final in recorder.events }
        checks.expect(final in recorder.events, "the final transcript is delivered with its language")
        settle { STTEvent.Closed in recorder.events }
        checks.expect(STTEvent.Closed in recorder.events, "the socket closes after a final")
        recorder.stop()
        checks.assertAll()
    }

    @Test
    fun cancelled() = runBlocking {
        val checks = CheckRunner("stt")
        val connection = FakeSTTConnection()
        val socket = makeSocket(connection)
        socket.start()
        socket.cancel()
        checks.expect(connection.cancelled, "cancelling tears the connection down")
        checks.assertAll()
    }

    @Test
    fun gatewayError() = runBlocking {
        val checks = CheckRunner("stt")
        val connection = FakeSTTConnection()
        val socket = makeSocket(connection)
        val recorder = Recorder(background, socket)
        socket.start()
        connection.deliver("""{"type":"stt.error","message":"no model"}""")
        settle { recorder.failure() != null }
        checks.expect(recorder.failure() != null, "an stt.error is surfaced")
        recorder.stop()
        checks.assertAll()
    }

    /** Review finding 2: a close without a final is the terminal outcome, and it must be reported rather than leaving the panel waiting. */
    @Test
    fun closeWithoutFinal() = runBlocking {
        val checks = CheckRunner("stt")
        val connection = FakeSTTConnection()
        val socket = makeSocket(connection)
        val recorder = Recorder(background, socket)
        socket.start()
        connection.deliver("""{"type":"stt.partial","text":"half a sentence"}""")
        settle { STTEvent.Partial("half a sentence") in recorder.events }
        socket.stop()
        connection.dropConnection()
        settle { recorder.failure() != null }
        checks.expect(recorder.failure()?.contains("draft") == true, "a close without a final says the recognised text was kept")
        checks.expect(STTEvent.Partial("half a sentence") in recorder.events, "the last partial is still what the caller received")
        recorder.stop()
        checks.assertAll()
    }

    /** The gateway caps an utterance at 4 MiB; the app stops rather than being closed under the user. */
    @Test
    fun audioBudget() = runBlocking {
        val checks = CheckRunner("stt")
        val connection = FakeSTTConnection()
        val socket = makeSocket(connection)
        socket.start()
        val chunk = ByteArray(512 * 1024)
        repeat(10) { socket.append(chunk) }
        val sent = connection.sentBinary.sumOf { it.size }
        checks.expect(sent <= STTSocket.maxAudioBytes, "the PCM budget is not exceeded")
        checks.expect("""{"type":"stt.stop"}""" in connection.sentText,
                      "reaching the budget finishes the utterance instead of dropping audio silently")
        socket.close()
        checks.assertAll()
    }

    private fun makeSocket(connection: FakeSTTConnection): STTSocket {
        val client = GatewayHTTPClient(endpoint = GatewayEndpoint.placeholder, transport = UnusedTransport,
                                       secrets = MemorySecretStore())
        client.adoptToken("verification-token")
        return STTSocket(client = client, factory = FakeSTTFactory(connection))
    }

    private suspend fun settle(timeout: Duration = 2.seconds, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (!condition() && deadline.hasNotPassedNow()) delay(10)
    }
}

/** Collects socket events off the reading coroutine. */
private class Recorder(scope: CoroutineScope, socket: STTSocket) {
    val events = CopyOnWriteArrayList<STTEvent>()
    private val reader: Job = scope.launch { socket.events.collect { events.add(it) } }

    fun failure(): String? = events.firstNotNullOfOrNull { (it as? STTEvent.Failed)?.message }

    fun stop() = reader.cancel()
}

private class FakeSTTFactory(private val connection: FakeSTTConnection) : WebSocketFactory {
    override suspend fun makeConnection(request: Request): WebSocketConnection {
        connection.record(request)
        return connection
    }
}

/** Records what the app sends and lets a check push gateway frames back. */
private class FakeSTTConnection : WebSocketConnection {
    val sentText = CopyOnWriteArrayList<String>()
    val sentBinary = CopyOnWriteArrayList<ByteArray>()

    @Volatile
    var cancelled = false
        private set

    /** The URL the socket was opened on. */
    @Volatile
    var requestURL: HttpUrl? = null
        private set
    private val lock = Mutex()
    private val inbox = ArrayDeque<ByteArray>()
    private var waiting: CompletableDeferred<ByteArray>? = null

    fun record(request: Request) {
        requestURL = request.url
    }

    override suspend fun resume() {}
    override suspend fun send(text: String) {
        sentText.add(text)
    }

    override suspend fun send(binary: ByteArray) {
        sentBinary.add(binary)
    }

    override suspend fun receive(): ByteArray {
        val waiter = lock.withLock {
            inbox.removeFirstOrNull()?.let { return it }
            CompletableDeferred<ByteArray>().also { waiting = it }
        }
        return waiter.await()
    }

    override suspend fun closeCode(): Int? = null

    override suspend fun cancel() {
        cancelled = true
        lock.withLock {
            waiting?.completeExceptionally(IOException("cancelled"))
            waiting = null
        }
    }

    suspend fun deliver(json: String) {
        val data = json.encodeToByteArray()
        lock.withLock {
            val waiter = waiting
            if (waiter != null) {
                waiting = null
                waiter.complete(data)
            } else {
                inbox.addLast(data)
            }
        }
    }

    /** The gateway went away without answering. */
    suspend fun dropConnection() {
        lock.withLock {
            waiting?.completeExceptionally(IOException("dropped"))
            waiting = null
        }
    }
}

private object UnusedTransport : HTTPTransport {
    override suspend fun perform(request: Request): Pair<ByteArray, okhttp3.Response> = throw TransportError.NotConnected
}
