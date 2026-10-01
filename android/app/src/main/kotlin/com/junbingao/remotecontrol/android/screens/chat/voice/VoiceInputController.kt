package com.junbingao.remotecontrol.android.screens.chat.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.android.voice.SpeechInputEvent
import com.junbingao.remotecontrol.android.voice.SpeechInputFailure
import com.junbingao.remotecontrol.android.voice.SpeechInputPlatform
import com.junbingao.remotecontrol.core.state.VoiceInputPhase
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One dictation at a time, from asking for the microphone to the last word: what the composer's
 * voice row and the glow read, on the backend [platform] the conversation chose.
 *
 * Its work runs in [scope], the composer's own, and every event a backend delivers is taken on a
 * later turn of that scope rather than inside the call that delivered it, as the iPhone hops to
 * the main actor: a backend may speak the moment it is started, before listening has begun.
 */
class VoiceInputController(private val platform: SpeechInputPlatform, private val scope: CoroutineScope) {
    var phase: VoiceInputPhase by mutableStateOf(VoiceInputPhase.idle)
        private set
    var transcript: String by mutableStateOf("")
    var inputLevel: Double by mutableDoubleStateOf(0.0)
        private set
    var failure: SpeechInputFailure? by mutableStateOf(null)
        private set
    private var runID = UUID.randomUUID()
    private var sceneIsActive = true
    private var authorizedRun: UUID? = null

    /** The only timer this controller owns: how long a backend may take to answer `finish()`. Nothing arms it while listening. */
    private var finalTranscriptTimeout: Job? = null
    private var permissionTask: Job? = null

    /** True only between Done and the backend's last word. */
    val isAwaitingFinalTranscript: Boolean get() = finalTranscriptTimeout != null

    fun start() {
        if (phase.isBusy || !sceneIsActive) return
        platform.cancel()
        clearTimeout()
        permissionTask?.cancel()
        runID = UUID.randomUUID()
        val run = runID
        authorizedRun = null
        transcript = ""
        failure = null
        inputLevel = 0.0
        phase = VoiceInputPhase.requestingPermission
        permissionTask = scope.launch {
            try {
                platform.requestPermission()
                if (run != runID) return@launch
                authorizedRun = run
                if (sceneIsActive) beginCapture(run)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (run != runID) return@launch
                fail(error as? SpeechInputFailure ?: SpeechInputFailure.Recording)
            }
        }
    }

    /** Stop listening and keep the transcript. Sending stays a separate tap. */
    fun finish() {
        if (phase != VoiceInputPhase.listening) return
        phase = VoiceInputPhase.finishing
        inputLevel = 0.0
        clearTimeout()
        platform.finish()
        val run = runID
        val grace = platform.finishGracePeriod.seconds
        finalTranscriptTimeout = scope.launch {
            delay(grace)
            if (runID != run) return@launch
            // The backend never answered. Keep what was recognised rather than discarding the
            // utterance.
            complete()
        }
    }

    fun cancel() {
        runID = UUID.randomUUID()
        permissionTask?.cancel()
        permissionTask = null
        authorizedRun = null
        clearTimeout()
        platform.cancel()
        phase = VoiceInputPhase.idle
        transcript = ""
        failure = null
        inputLevel = 0.0
    }

    /** Drop a failure message once it has been read. The transcript gathered before the failure is already in the draft and is left alone. */
    fun dismissFailure() {
        if (phase != VoiceInputPhase.failed) return
        failure = null
        phase = if (transcript.isEmpty()) VoiceInputPhase.idle else VoiceInputPhase.review
    }

    fun setSceneActive(active: Boolean, cancelAuthorization: Boolean = false) {
        sceneIsActive = active
        if (!active && (cancelAuthorization || phase != VoiceInputPhase.requestingPermission)) suspend()
        val run = authorizedRun
        if (active && run != null) beginCapture(run)
    }

    fun suspend() {
        if (!phase.isBusy) return
        runID = UUID.randomUUID()
        permissionTask?.cancel()
        clearTimeout()
        platform.cancel()
        authorizedRun = null
        inputLevel = 0.0
        phase = if (transcript.isEmpty()) VoiceInputPhase.idle else VoiceInputPhase.review
    }

    private fun beginCapture(run: UUID) {
        if (run != runID || authorizedRun != run || !sceneIsActive || phase != VoiceInputPhase.requestingPermission) return
        authorizedRun = null
        try {
            platform.start { event -> scope.launch { receive(event, run) } }
            phase = VoiceInputPhase.listening
        } catch (error: Exception) {
            fail(error as? SpeechInputFailure ?: SpeechInputFailure.Recording)
        }
    }

    private fun receive(event: SpeechInputEvent, run: UUID) {
        if (runID != run || (phase != VoiceInputPhase.listening && phase != VoiceInputPhase.finishing)) return
        when (event) {
            is SpeechInputEvent.Transcript -> {
                transcript = event.text
                if (event.isFinal) complete()
            }
            is SpeechInputEvent.Level ->
                if (phase == VoiceInputPhase.listening) inputLevel = if (event.value.isFinite()) event.value.coerceIn(0.0, 1.0) else 0.0
            is SpeechInputEvent.Failure -> fail(event.failure)
        }
    }

    private fun complete() {
        authorizedRun = null
        runID = UUID.randomUUID()
        clearTimeout()
        platform.cancel()
        inputLevel = 0.0
        phase = VoiceInputPhase.review
    }

    /** A failed run keeps whatever was recognised before it broke: the words are already in the draft, and losing them helps no one. */
    private fun fail(error: SpeechInputFailure) {
        authorizedRun = null
        runID = UUID.randomUUID()
        clearTimeout()
        platform.cancel()
        inputLevel = 0.0
        failure = error
        phase = VoiceInputPhase.failed
    }

    private fun clearTimeout() {
        finalTranscriptTimeout?.cancel()
        finalTranscriptTimeout = null
    }

    companion object {
        /**
         * Listening has no deadline. It ends when Done is tapped, when the app leaves the
         * foreground, or when the backend fails; a backend whose own request expires rolls over to
         * a new one underneath, so a long dictation is never cut off from here.
         */
        val listeningDeadline: Duration? = null
    }
}
