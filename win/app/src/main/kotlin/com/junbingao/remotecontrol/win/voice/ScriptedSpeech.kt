package com.junbingao.remotecontrol.win.voice

import com.junbingao.remotecontrol.win.platform.RecorderHandlers
import com.junbingao.remotecontrol.win.platform.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Dictation with neither a microphone nor a gateway: the offline demo's, and a preview's, which must
 * never open the microphone. The words are the web mock's own script (`web/mock/server.ts`,
 * `WS /ws/stt`) — three partials that grow a sentence, and a final transcript with the fillers and
 * stammers spoken words carry, so polish has something to do.
 */
object ScriptedSpeech {
    val partials = listOf(
        "also add a retry",
        "also add a retry to the token refresh path",
        "also add a retry to the token refresh path and re-run the suite",
    )
    const val final = "um so also add a retry to the the token refresh path and re-run the suite on on the CI runner too"

    /**
     * The mock sends a partial every two seconds; a preview asks for them sooner, so a picture taken
     * a second in has words in it, and can ask for a final transcript that never comes, to show the
     * wait for it. The script runs in `tasks`, the composer's thread.
     */
    fun services(tasks: CoroutineScope, partialEvery: Duration = 2.seconds, finishes: Boolean = true): SpeechServices =
        SpeechServices(
            recorder = { handlers -> ScriptedRecorder(handlers, tasks) },
            socket = { onEvent -> ScriptedSocket(partialEvery, finishes, onEvent, tasks) },
        )
}

/** A microphone that hears someone talking: a level that rises and falls. */
class ScriptedRecorder(private val handlers: RecorderHandlers, private val tasks: CoroutineScope) : VoiceRecorder {
    private var talking: Job? = null

    override suspend fun start(): Boolean {
        val onLevel = handlers.onLevel
        talking = tasks.launch {
            var step = 0.0
            while (isActive) {
                onLevel(0.45 + 0.35 * sin(step) * cos(step * 0.37))
                step += 0.9
                delay(90.milliseconds)
            }
        }
        return true
    }

    override suspend fun stop() {
        talking?.cancel()
        talking = null
    }
}

/** A socket that transcribes the script, whatever it is sent. */
class ScriptedSocket(
    private val interval: Duration,
    private val finishes: Boolean,
    private val onEvent: (SpeechEvent) -> Unit,
    private val tasks: CoroutineScope,
) : SpeechStream {
    private var speaking: Job? = null
    private var finishing: Job? = null

    override suspend fun start() {
        speaking = tasks.launch {
            for (text in ScriptedSpeech.partials) {
                delay(interval)
                onEvent(SpeechEvent.Partial(text))
            }
        }
    }

    override fun append(frame: ByteArray) {}

    override fun stop() {
        speaking?.cancel()
        if (!finishes) return
        finishing = tasks.launch {
            delay(300.milliseconds)
            onEvent(SpeechEvent.Final(ScriptedSpeech.final))
            onEvent(SpeechEvent.Closed)
        }
    }

    override fun cancel() {
        speaking?.cancel()
        finishing?.cancel()
    }
}
