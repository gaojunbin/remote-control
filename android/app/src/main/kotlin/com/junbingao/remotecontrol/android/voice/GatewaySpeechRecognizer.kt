package com.junbingao.remotecontrol.android.voice

import android.os.Handler
import android.os.Looper
import com.junbingao.remotecontrol.android.permissions.PermissionRequest
import com.junbingao.remotecontrol.core.state.TranscriptSegments
import com.junbingao.remotecontrol.core.transport.GatewayHTTPClient
import com.junbingao.remotecontrol.core.transport.STTEvent
import com.junbingao.remotecontrol.core.transport.STTSocket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dictation through the gateway's streaming endpoint: the iPhone's `GatewaySpeechRecognizer`.
 * The microphone gives the PCM16LE, 16 kHz mono frames the protocol specifies ([AudioCapture]);
 * audio leaves the phone here, which is exactly the difference from on-device recognition and is
 * stated in the voice settings.
 *
 * One socket carries one utterance and the gateway caps that at 120 s, so a long dictation is cut
 * into segments and their transcripts joined in order, each an ordinary `WS /ws/stt` session of
 * the core's [STTSocket]. The replacement socket is connected and taking audio before the outgoing
 * one is told to stop, so the seam drops nothing, and the cut waits for the first quiet moment
 * after the segment length rather than landing mid-word.
 *
 * Amendment A44: nothing here names a language. The gateway's provider detects it, which is why the
 * composer offers none while this backend is the one listening. Everything but the capture runs on
 * the main thread, where the events are delivered.
 */
class GatewaySpeechRecognizer(
    private val client: GatewayHTTPClient,
    private val microphone: PermissionRequest,
    private val capture: AudioCapture,
    private val makeSocket: (GatewayHTTPClient) -> STTSocket = { STTSocket(it) },
) : SpeechInputPlatform {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val route = STTAudioRoute(scope)

    /**
     * The open segments, by the index the transcript keeps them under. A socket and its reader are
     * dropped the moment the socket's event stream ends, so a ten-minute dictation holds one of each
     * and not one per thirty seconds of speech.
     */
    private val sockets = HashMap<Int, STTSocket>()
    private val readers = HashMap<Int, Job>()
    private var segments = TranscriptSegments()
    private var rollover: Job? = null
    private var emit: ((SpeechInputEvent) -> Unit)? = null
    private var isCapturing = false
    private var isFinishing = false

    /** Each socket arms its own deadline at `STTSocket.finalTimeout`; this one only has to outlast it, so a stalled gateway still ends the session. */
    override val finishGracePeriod: Double get() = (STTSocket.finalTimeout + 3.seconds).inWholeMilliseconds / 1000.0

    override suspend fun requestPermission() {
        if (!microphone.request()) throw SpeechInputFailure.MicrophonePermission
    }

    override fun start(onEvent: (SpeechInputEvent) -> Unit) {
        cancel()
        emit = onEvent
        segments = TranscriptSegments()
        isFinishing = false
        try {
            capture.start(
                onChunk = { pcm, level ->
                    route.send(pcm, level)
                    main.post { emit?.invoke(SpeechInputEvent.Level(level)) }
                },
                onFailure = { failure -> main.post { emit?.invoke(SpeechInputEvent.Failure(failure)) } },
            )
            isCapturing = true
        } catch (failure: SpeechInputFailure) {
            cancel()
            throw failure
        }
        rollover = scope.launch { segmentLoop(opening = true) }
    }

    override fun finish() {
        if (isFinishing) return
        isFinishing = true
        rollover?.cancel()
        rollover = null
        stopCapture()
        val last = route.exchange(null)
        scope.launch { last?.stop() }
        publish()
    }

    override fun cancel() {
        isFinishing = false
        rollover?.cancel()
        rollover = null
        stopCapture()
        route.exchange(null)
        readers.values.forEach { it.cancel() }
        readers.clear()
        val closing = sockets.values.toList()
        sockets.clear()
        scope.launch { closing.forEach { it.cancel() } }
        segments = TranscriptSegments()
        emit = null
    }

    // Segments

    /** Open the first segment, then roll over for as long as the person talks. */
    private suspend fun segmentLoop(opening: Boolean) {
        if (opening && !openSegment()) return
        while (currentCoroutineContext().isActive && !isFinishing) {
            delay(segmentDuration)
            if (!currentCoroutineContext().isActive || isFinishing) return
            waitForSilence()
            if (!currentCoroutineContext().isActive || isFinishing) return
            if (!openSegment()) return
        }
    }

    /** A segment whose socket has closed: both handles are let go, which is what keeps a long dictation from holding one of each per thirty seconds. */
    private fun retire(segment: Int) {
        sockets.remove(segment)
        readers.remove(segment)
    }

    /** Hold the cut until the speaker pauses, and take it anyway if they do not. */
    private suspend fun waitForSilence() {
        val forced = TimeSource.Monotonic.markNow() + (segmentLimit - segmentDuration)
        while (currentCoroutineContext().isActive && route.lastLevel > silenceLevel && forced.hasNotPassedNow()) {
            delay(200.milliseconds)
        }
    }

    /** Connect a fresh socket, hand the audio over to it, and let the one it replaces transcribe what it already holds. */
    private suspend fun openSegment(): Boolean {
        val index = segments.begin()
        val socket = makeSocket(client)
        sockets[index] = socket
        readers[index] = scope.launch {
            socket.events.collect { event -> receive(event, index) }
            // The stream ends when the socket closes, which is where a settled segment stops costing anything.
            retire(index)
        }
        try {
            socket.start()
        } catch (_: Exception) {
            segments.end(index)
            retire(index)
            emit?.invoke(SpeechInputEvent.Failure(SpeechInputFailure.Unavailable))
            return false
        }
        // Connecting is the one suspension here, so the session can have ended while it ran. A
        // socket nobody is going to speak into is closed, not armed.
        if (!currentCoroutineContext().isActive || isFinishing) {
            segments.end(index)
            socket.cancel()
            return false
        }
        val previous = route.exchange(socket)
        scope.launch { previous?.stop() }
        return true
    }

    private fun receive(event: STTEvent, segment: Int) {
        if (emit == null || segments.text(segment) == null) return
        when (event) {
            is STTEvent.Partial -> if (segments.update(segment, event.text)) publish()
            is STTEvent.Final -> {
                segments.update(segment, event.text)
                segments.end(segment)
                publish()
            }
            is STTEvent.Failed -> {
                segments.end(segment)
                // A segment that already handed the microphone on keeps whatever it transcribed;
                // only the live one can end the dictation.
                if (segment == segments.active) emit?.invoke(SpeechInputEvent.Failure(SpeechInputFailure.Recognition)) else publish()
            }
            STTEvent.Closed -> {
                // A socket closes after its final as a matter of course; only an unannounced close
                // still has a segment to settle.
                if (!segments.isOpen(segment)) return
                segments.end(segment)
                publish()
            }
        }
    }

    private fun publish() {
        emit?.invoke(SpeechInputEvent.Transcript(segments.joined, isFinal = isFinishing && segments.isSettled))
    }

    private fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false
        capture.stop()
    }

    /** Let go of everything this recogniser started, for the screen that owned it going away. */
    fun release() {
        cancel()
        scope.cancel()
    }

    companion object {
        /** Well inside the gateway's 120 s and 4 MiB budget for one utterance. */
        val segmentDuration = 30.seconds

        /** How long a cut may wait for a silence before it is taken anyway. */
        val segmentLimit = 45.seconds

        /** Normalised input level under which the speaker counts as between words. */
        const val silenceLevel = 0.12
    }
}
