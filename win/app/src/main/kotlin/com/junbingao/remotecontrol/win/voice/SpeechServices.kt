package com.junbingao.remotecontrol.win.voice

import com.junbingao.remotecontrol.win.platform.RecorderHandlers
import com.junbingao.remotecontrol.win.platform.VoiceRecorder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What one utterance's socket reports (`SttEvent` in `sttSocket.ts`). `Failed` carries the
 * gateway's own sentence when it sent one.
 */
sealed interface SpeechEvent {
    data class Partial(val text: String) : SpeechEvent

    data class Final(val text: String) : SpeechEvent

    data class Failed(val message: String?) : SpeechEvent

    data object Closed : SpeechEvent
}

/**
 * One utterance over `WS /ws/stt`: 16 kHz PCM16LE mono frames out, partial transcripts back, one
 * final transcript after `stop()`. Its events are reported on the thread the composer runs on.
 */
interface SpeechStream {
    /** Connect; throws when the gateway takes no audio. */
    suspend fun start()

    fun append(frame: ByteArray)

    /** Transcribe everything sent so far and wait for the final transcript. */
    fun stop()

    /** Drop the utterance: no final transcript is wanted, and nothing more is reported. */
    fun cancel()
}

/**
 * Where a dictation's microphone and sockets come from: the computer's own microphone and the
 * gateway, or — for the offline demo and for a preview — a script that speaks without either. The
 * microphone itself is the platform's (`VoiceRecorder`, `RecorderHandlers`, `RecorderError`).
 */
class SpeechServices(
    val recorder: (RecorderHandlers) -> VoiceRecorder,
    val socket: (onEvent: (SpeechEvent) -> Unit) -> SpeechStream,
)

/** `useVoice`'s timings. A test runs the same machine on a shorter clock. */
data class VoiceTiming(
    /** Well inside the gateway's 120 s and 4 MiB budget for one utterance. */
    val segment: Duration = 30.seconds,
    /** How long a cut may wait for a pause in the speech before it is taken anyway. */
    val segmentLimit: Duration = 45.seconds,
    /** How long the gateway gets to answer Done before the draft is kept as it is. */
    val finalTimeout: Duration = 30.seconds,
    /** How often the elapsed clock ticks and a waiting cut looks again. */
    val poll: Duration = 200.milliseconds,
    /** Input level under which the speaker counts as between words. */
    val silenceLevel: Double = 0.12,
) {
    companion object {
        val standard = VoiceTiming()
    }
}
