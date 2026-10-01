package com.junbingao.remotecontrol.win.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.win.platform.RecorderError
import com.junbingao.remotecontrol.win.platform.RecorderHandlers
import com.junbingao.remotecontrol.win.platform.VoiceRecorder
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * `web/src/features/voice/useVoice.ts`: dictation into the composer's own field.
 *
 * The mic button starts listening; listening ends only when the person clicks Done, reaches for the
 * field, or the microphone fails. There is no time limit: one gateway socket carries at most 120 s
 * of audio, so a long dictation is cut into segments whose transcripts are joined in order, and the
 * replacement socket is taking audio before the outgoing one is told to stop, so the seam drops
 * nothing. Nothing is ever sent by the act of stopping.
 *
 * Its work runs in `tasks`, the composer's thread, where its state is read and written.
 */
class VoiceController(
    private val services: SpeechServices,
    private val timing: VoiceTiming = VoiceTiming.standard,
    private val tasks: CoroutineScope,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    var state: VoiceState by mutableStateOf(VoiceState.idle)
        private set
    var level: Double by mutableStateOf(0.0)
        private set
    var elapsedMs: Double by mutableStateOf(0.0)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /**
     * Whether the composer takes a dictation at all. A composer taken away mid-dictation — the
     * device goes offline, the terminal takes the session back — ends the run here rather than
     * streaming audio to the gateway for the life of the window; the words already recognised are
     * in the field.
     */
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value && state != VoiceState.idle && state != VoiceState.error) cancel()
        }

    /**
     * The transcript so far. `isFinal` marks the last call of a dictation, after which the text
     * belongs to the field and this controller is idle.
     */
    var onTranscript: (text: String, isFinal: Boolean) -> Unit = { _, _ -> }

    private var recorder: VoiceRecorder? = null
    private var sockets = mutableListOf<SpeechStream>()
    private var segments = DictationSegments()

    /** The socket taking audio right now. The others are transcribing. */
    private var route: SpeechStream? = null
    private var levelNow = 0.0
    private var startedAt: TimeMark = clock.markNow()
    private var finishing = false
    private var finalTimer: Job? = null
    private var ticker: Job? = null
    private var loop: Job? = null

    /** Bumped by every start and every end, so a stale loop or reply is ignored. */
    private var runID = 0

    fun start() {
        if (!enabled || !(state == VoiceState.idle || state == VoiceState.error)) return
        teardown()
        segments = DictationSegments()
        error = null
        level = 0.0
        elapsedMs = 0.0
        startedAt = clock.markNow()
        move(VoiceState.starting)
        val run = runID
        val recorder = services.recorder(RecorderHandlers(
            onFrame = { frame -> route?.append(frame) },
            onLevel = { value ->
                levelNow = value
                level = value
            },
            onError = { error -> fail(text(error)) },
        ))
        this.recorder = recorder
        tasks.launch {
            val started = recorder.start()
            if (run != runID || !started) return@launch
            if (!openSegment(run) || run != runID) return@launch
            startedAt = clock.markNow()
            move(VoiceState.listening)
            loop = segmentLoop(run)
        }
    }

    /** Stop listening and keep the transcript. Sending stays a separate click. */
    fun done() {
        if (state != VoiceState.listening) return
        finishing = true
        move(VoiceState.finishing)
        level = 0.0
        val run = runID
        val recorder = recorder
        this.recorder = null
        // The recorder hands on its tail as it stops, so the live socket is told to transcribe only
        // once that last audio has reached it.
        tasks.launch {
            recorder?.stop()
            if (run != runID) return@launch
            val live = route
            route = null
            if (live != null) live.stop() else publish(true)
        }
        // A gateway that never answers must not leave the composer waiting: keep what was
        // recognised instead.
        val timeout = timing.finalTimeout
        finalTimer = tasks.launch {
            delay(timeout)
            if (run != runID) return@launch
            publish(true)
        }
    }

    /** Drop the run without publishing: a keystroke takes the field back. */
    fun cancel() {
        reset()
        error = null
        move(VoiceState.idle)
    }

    fun dismissError() {
        error = null
        move(VoiceState.idle)
    }

    /** The composer went away: the microphone and every socket go with it. */
    fun shutDown() {
        teardown()
        ticker?.cancel()
        ticker = null
    }

    // The run

    private fun move(next: VoiceState) {
        state = next
        if (next == VoiceState.listening) {
            startTicker()
        } else {
            ticker?.cancel()
            ticker = null
        }
    }

    /** Drop the microphone and every socket. Nothing is published from here. */
    private fun teardown() {
        runID += 1
        finishing = false
        finalTimer?.cancel()
        finalTimer = null
        loop?.cancel()
        loop = null
        recorder?.let { stopping ->
            recorder = null
            tasks.launch { stopping.stop() }
        }
        route = null
        val closing = sockets
        sockets = mutableListOf()
        for (socket in closing) socket.cancel()
        levelNow = 0.0
    }

    private fun reset() {
        teardown()
        segments = DictationSegments()
        level = 0.0
        elapsedMs = 0.0
    }

    /** A failed dictation keeps whatever was recognised: those words are already in the field, and losing them helps no one. */
    private fun fail(message: String) {
        onTranscript(segments.joined, true)
        reset()
        error = message
        move(VoiceState.error)
    }

    private fun publish(isFinal: Boolean) {
        onTranscript(segments.joined, isFinal)
        if (isFinal) {
            reset()
            move(VoiceState.idle)
        }
    }

    private fun receive(event: SpeechEvent, index: Int, run: Int) {
        if (run != runID || !segments.has(index)) return
        when (event) {
            is SpeechEvent.Partial -> if (segments.update(index, event.text)) settle()
            is SpeechEvent.Final -> {
                segments.update(index, event.text)
                segments.end(index)
                settle()
            }
            is SpeechEvent.Failed -> {
                segments.end(index)
                // A segment that already handed the microphone on keeps what it transcribed; only
                // the live one can end the dictation.
                if (index == segments.active) fail(event.message?.ifEmpty { null } ?: S.voice.failed) else settle()
            }
            SpeechEvent.Closed -> {
                // A socket closes after its final as a matter of course; only an unannounced close
                // still has a segment to settle.
                if (!segments.isOpen(index)) return
                segments.end(index)
                settle()
            }
        }
    }

    private fun settle() = publish(finishing && segments.isSettled)

    /**
     * Connect a fresh socket, hand the audio over to it, and let the one it replaces transcribe what
     * it already holds.
     */
    private suspend fun openSegment(run: Int): Boolean {
        val index = segments.begin()
        val socket = services.socket { event -> receive(event, index, run) }
        sockets.add(socket)
        try {
            socket.start()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            segments.end(index)
            // A dictation that ended while this socket was still connecting is not a failure: the
            // teardown is what refused the connect.
            if (run != runID) return false
            fail(S.voice.failed)
            return false
        }
        // Connecting is the one wait here, so the dictation can have ended while it ran. A socket
        // nobody is going to speak into is closed.
        if (run != runID || finishing) {
            segments.end(index)
            socket.cancel()
            return false
        }
        val previous = route
        route = socket
        previous?.stop()
        return true
    }

    /** Roll over to a new socket for as long as the person keeps talking. */
    private fun segmentLoop(run: Int): Job = tasks.launch {
        while (true) {
            delay(timing.segment)
            // Hold a cut until the speaker pauses, and take it anyway if they do not.
            val forced = clock.markNow() + (timing.segmentLimit - timing.segment)
            while (isCurrent(run) && levelNow > timing.silenceLevel && forced.hasNotPassedNow()) {
                delay(timing.poll)
            }
            if (!isCurrent(run)) return@launch
            if (!openSegment(run)) return@launch
        }
    }

    /** Whether this run is the one listening, and is not being finished. */
    private fun isCurrent(run: Int): Boolean = run == runID && !finishing

    /** The elapsed clock runs only while listening, and stops at Done. */
    private fun startTicker() {
        ticker?.cancel()
        val poll = timing.poll
        ticker = tasks.launch {
            while (isActive) {
                delay(poll)
                // In milliseconds, as the web's clocks count them.
                elapsedMs = startedAt.elapsedNow().inWholeMicroseconds / 1000.0
            }
        }
    }

    private fun text(error: RecorderError): String = when (error) {
        RecorderError.denied -> S.voice.denied
        RecorderError.unsupported -> S.winComposer.voiceUnsupported
        RecorderError.failed -> S.voice.failed
    }
}
