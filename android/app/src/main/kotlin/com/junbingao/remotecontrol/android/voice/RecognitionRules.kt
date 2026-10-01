package com.junbingao.remotecontrol.android.voice

import android.speech.SpeechRecognizer
import kotlin.math.max
import kotlin.math.min

/**
 * What a recogniser error means for a dictation that has no maximum duration.
 *
 * Android ends a request on a pause — "no match", "speech timeout" — where the iPhone's request
 * simply goes on hearing silence, so those two are a restart and nothing more. The rest are the
 * iPhone's rule (`SystemSpeechRecognizer.swift`, `isRecoverable`): a restart, unless the request
 * failed within five seconds of starting three times running, which is a broken recogniser
 * rather than a stretch of silence. A refused microphone and a language the recogniser does not
 * have end dictation at once, with the words the composer has for each.
 */
internal sealed interface RecognitionOutcome {
    /** Start the next run; a pause is not a failure. */
    data object Restart : RecognitionOutcome

    /** Start the next run if the failures so far allow it. */
    data object RestartIfRecoverable : RecognitionOutcome

    /** Stop listening and say why. */
    data class Fail(val failure: SpeechInputFailure) : RecognitionOutcome
}

internal object RecognitionErrors {
    fun outcome(code: Int): RecognitionOutcome = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionOutcome.Restart
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            RecognitionOutcome.Fail(SpeechInputFailure.MicrophonePermission)
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
            RecognitionOutcome.Fail(SpeechInputFailure.Unsupported)
        SpeechRecognizer.ERROR_AUDIO -> RecognitionOutcome.Fail(SpeechInputFailure.Recording)
        else -> RecognitionOutcome.RestartIfRecoverable
    }

    // The API 31 constants, spelled out so the rule reads the same on API 29 and 30.
    private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    private const val ERROR_LANGUAGE_UNAVAILABLE = 13
}

/** How many runs in a row failed too soon after starting to be anything but broken. */
internal class PrematureFailures(
    private val windowMillis: Long = 5_000,
    private val limit: Int = 3,
) {
    private var count = 0

    /**
     * Whether a run that failed [elapsedMillis] after it started may be followed by another. A
     * failure after a stretch of listening resets the count, as the iPhone's does.
     */
    fun recoverable(elapsedMillis: Long): Boolean {
        if (elapsedMillis >= windowMillis) {
            count = 0
            return true
        }
        count += 1
        return count < limit
    }

    /** A run that delivered words proves the recogniser works. */
    fun reset() {
        count = 0
    }
}

/**
 * Android's recogniser reports its own loudness scale, roughly −2 dB in a quiet room to 10 dB for
 * a raised voice, rather than the raw buffer `InputLevel` reads. This maps that range onto the
 * same 0…1 scale, so the glow swings as far for the phone's recogniser as for the gateway's.
 */
internal object RecognizerLevel {
    const val quietDB = -2.0
    const val loudDB = 10.0

    fun from(rmsDB: Float): Double {
        if (rmsDB.isNaN()) return 0.0
        return min(1.0, max(0.0, (rmsDB - quietDB) / (loudDB - quietDB)))
    }
}
