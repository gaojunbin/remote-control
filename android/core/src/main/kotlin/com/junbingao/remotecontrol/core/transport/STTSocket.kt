package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.attempt
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.objectValue
import com.junbingao.remotecontrol.core.protocol.string
import com.junbingao.remotecontrol.core.state.L10n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.Request
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface STTEvent {
    data class Partial(val text: String) : STTEvent
    data class Final(val text: String, val language: String) : STTEvent
    data class Failed(val message: String) : STTEvent
    data object Closed : STTEvent
}

/**
 * Streaming speech-to-text against the gateway.
 *
 * The caller pushes PCM16LE, 16 kHz, mono frames; the gateway answers with a partial transcript
 * roughly every two seconds and one final transcript after [stop]. Audio leaves the phone here,
 * which is the difference from on-device recognition and is stated in the voice settings.
 *
 * Amendment A44: the socket names no language. The gateway's provider detects it, and
 * `stt.final` says which one it heard, or `auto`.
 *
 * RCCore's actor, kept as one: its state is read and written on [isolation] alone.
 */
class STTSocket(
    private val client: GatewayHTTPClient,
    private val factory: WebSocketFactory = OkHttpWebSocketFactory(),
) {
    private val isolation: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + isolation)
    private val buffer = EventBuffer.makeStream<STTEvent>(EventBuffer.sttCapacity)
    val events: Flow<STTEvent> = buffer.stream

    private val continuation = buffer.continuation
    private var connection: WebSocketConnection? = null
    private var reader: Job? = null
    private var sentBytes = 0
    private var finished = false
    private var receivedFinal = false
    private var deadline: Job? = null

    companion object {
        /** Gateway limits: 120 s of audio, 4 MiB total per utterance. */
        const val maxAudioBytes = 4 * 1024 * 1024
        val maxAudioSeconds: Duration = 120.seconds
        const val sampleRate: Double = 16_000.0

        /**
         * How long the gateway gets to answer `stt.stop`. It has to transcribe up to 120 s of
         * audio, so this is generous; it exists only so a gateway that never answers cannot leave
         * the panel waiting forever.
         */
        val finalTimeout: Duration = 30.seconds

        private const val STOP = """{"type":"stt.stop"}"""
        private const val CANCEL = """{"type":"stt.cancel"}"""
    }

    suspend fun start(): Unit = withContext(isolation) {
        if (connection != null || finished) return@withContext
        val token = client.bearerToken() ?: throw TransportError.Unauthorized
        val request = Request.Builder()
            .url(client.endpoint.socketURL(path = "/ws/stt").toString())
            .header("Authorization", "Bearer $token")
            .build()
        val socket = factory.makeConnection(request)
        connection = socket
        socket.resume()
        reader = scope.launch { readLoop(socket) }
    }

    /** Append one capture buffer. Silently drops audio past the gateway budget rather than letting the socket be closed under the user mid-sentence. */
    suspend fun append(pcm: ByteArray): Unit = withContext(isolation) {
        val connection = connection ?: return@withContext
        if (finished || pcm.isEmpty()) return@withContext
        if (sentBytes + pcm.size > maxAudioBytes) {
            stopUtterance()
            return@withContext
        }
        sentBytes += pcm.size
        try {
            connection.send(binary = pcm)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            fail("audio upload failed")
        }
    }

    /** Finish and transcribe everything sent so far, then wait for `stt.final`. */
    suspend fun stop(): Unit = withContext(isolation) { stopUtterance() }

    private suspend fun stopUtterance() {
        val connection = connection ?: return
        if (finished) return
        finished = true
        attempt { connection.send(text = STOP) }
        deadline?.cancel()
        deadline = scope.launch {
            delay(finalTimeout)
            giveUpWaiting()
        }
    }

    private suspend fun giveUpWaiting() {
        if (receivedFinal) return
        fail(L10n.string("The gateway did not return a transcript."))
    }

    /** Discard the utterance. The gateway drops the audio and closes. */
    suspend fun cancel(): Unit = withContext(NonCancellable + isolation) {
        val socket = connection
        finished = true
        attempt { socket?.send(text = CANCEL) }
        closeSocket()
    }

    suspend fun close(): Unit = withContext(NonCancellable + isolation) { closeSocket() }

    /**
     * The end of the utterance, whoever called it — the reading coroutine among them, which is why
     * the rest of it runs even once that coroutine has been told to stop.
     */
    private suspend fun closeSocket() {
        deadline?.cancel()
        deadline = null
        reader?.cancel()
        reader = null
        val socket = connection
        connection = null
        withContext(NonCancellable) { socket?.cancel() }
        continuation.trySend(STTEvent.Closed)
        continuation.close()
    }

    private suspend fun readLoop(socket: WebSocketConnection) {
        while (currentCoroutineContext().isActive) {
            try {
                // Between two frames the socket's other work gets its turn, as it would between
                // two awaits of an actor, even when the next frame is already waiting.
                yield()
                val data = socket.receive()
                val frame = attempt { JSONValue.parse(data) }?.objectValue ?: continue
                when (frame.string("type") ?: continue) {
                    "stt.partial" -> continuation.trySend(STTEvent.Partial(frame.string("text") ?: ""))
                    "stt.final" -> {
                        receivedFinal = true
                        continuation.trySend(STTEvent.Final(text = frame.string("text") ?: "",
                                                            language = frame.string("language") ?: "auto"))
                        closeSocket()
                        return
                    }
                    "stt.error" -> {
                        fail(frame.string("message") ?: "Transcription failed.")
                        return
                    }
                    else -> continue
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!currentCoroutineContext().isActive) return
                // A close without a final is the end of the utterance: whatever the last partial
                // produced is what the user gets.
                fail(if (finished) L10n.string("The transcript did not finish. What was recognised is in your draft.")
                     else L10n.string("The transcription connection dropped."))
                return
            }
        }
    }

    private suspend fun fail(message: String) {
        continuation.trySend(STTEvent.Failed(message))
        closeSocket()
    }
}
