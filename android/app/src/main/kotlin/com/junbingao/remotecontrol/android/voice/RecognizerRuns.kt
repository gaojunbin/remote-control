package com.junbingao.remotecontrol.android.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.junbingao.remotecontrol.android.permissions.isPermissionGranted

/**
 * Android's own recogniser, listening for as long as it is asked to: the part of the iPhone's
 * `SystemSpeechRecognizer` that `SFSpeechRecognizer` does there. Audio stays on the phone — the
 * on-device recogniser where the phone has one (Android 12 and later), otherwise the system
 * recogniser told to work offline only.
 *
 * Android ends a request whenever the speaker pauses, so listening with no maximum duration is a
 * chain of requests: each one is a run, reported as its own segment ([RecognitionEvent]), and the
 * next starts the moment one ends. The seam between two runs is a gap of a few hundred
 * milliseconds that one microphone cannot close; a word spoken exactly across it can be lost,
 * where the iPhone's overlapping requests only split it. [SystemSpeechRecognizer] joins the runs.
 *
 * Everything here runs on the main thread, as `SpeechRecognizer` requires.
 */
class RecognizerRuns(
    private val context: Context,
    /** The language to listen for, as `DictationLanguage` hands it over: `zh-CN`, `en-US`, … */
    private val localeIdentifier: String,
) : RecognitionRuns {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var emit: ((RecognitionEvent) -> Unit)? = null
    private var segment = -1
    private var runStarted = 0L
    private var runOpen = false
    private var finishing = false
    private val failures = PrematureFailures()

    /**
     * Start listening. Throws [SpeechInputFailure.MicrophonePermission] when the microphone is
     * not granted — ask with `rememberPermissionRequest(RECORD_AUDIO)` first, at the moment the
     * iPhone asks — and [SpeechInputFailure.Unavailable] when the phone has no recogniser.
     */
    override fun start(onEvent: (RecognitionEvent) -> Unit) {
        cancel()
        if (!isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)) throw SpeechInputFailure.MicrophonePermission
        val made = make(context) ?: throw SpeechInputFailure.Unavailable
        made.setRecognitionListener(Listener())
        recognizer = made
        emit = onEvent
        segment = -1
        finishing = false
        failures.reset()
        openRun()
    }

    /**
     * Stop listening and keep what was heard: the run under way delivers its final, then
     * [RecognitionEvent.Finished] says nothing more will come.
     */
    override fun finish() {
        if (emit == null || finishing) return
        finishing = true
        main.removeCallbacksAndMessages(null)
        if (runOpen) recognizer?.stopListening() else close()
    }

    /** Stop and drop everything; no event follows. */
    override fun cancel() {
        main.removeCallbacksAndMessages(null)
        emit = null
        runOpen = false
        finishing = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun openRun() {
        val listening = recognizer ?: return
        segment += 1
        runOpen = true
        runStarted = SystemClock.elapsedRealtime()
        emit?.invoke(RecognitionEvent.Began(segment))
        listening.startListening(intent())
    }

    private fun endRun(outcome: RecognitionOutcome) {
        if (!runOpen) return
        runOpen = false
        emit?.invoke(RecognitionEvent.Ended(segment))
        if (finishing) return close()
        when (outcome) {
            RecognitionOutcome.Restart -> openRun()
            RecognitionOutcome.RestartIfRecoverable ->
                if (failures.recoverable(SystemClock.elapsedRealtime() - runStarted)) {
                    // A beat before asking again, so a recogniser that is still tearing the last
                    // request down answers the next one rather than calling itself busy.
                    main.postDelayed({ if (emit != null && !finishing) openRun() }, RESTART_DELAY_MILLIS)
                } else {
                    fail(SpeechInputFailure.Recognition)
                }
            is RecognitionOutcome.Fail -> fail(outcome.failure)
        }
    }

    private fun fail(failure: SpeechInputFailure) {
        val report = emit
        cancel()
        report?.invoke(RecognitionEvent.Failed(failure))
    }

    private fun close() {
        val report = emit
        cancel()
        report?.invoke(RecognitionEvent.Finished)
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeIdentifier)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Offline only: the promise this backend makes is that audio never leaves the phone.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Punctuation and casing, as `addsPunctuation` asks of the iPhone's.
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            if (runOpen && !finishing) emit?.invoke(RecognitionEvent.Level(RecognizerLevel.from(rmsdB)))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = best(partialResults) ?: return
            if (runOpen) emit?.invoke(RecognitionEvent.Partial(segment, text))
        }

        override fun onResults(results: Bundle?) {
            if (!runOpen) return
            best(results)?.let {
                failures.reset()
                emit?.invoke(RecognitionEvent.Final(segment, it))
            }
            endRun(RecognitionOutcome.Restart)
        }

        override fun onError(error: Int) {
            endRun(RecognitionErrors.outcome(error))
        }

        private fun best(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val RESTART_DELAY_MILLIS = 250L

        /** Whether the phone can recognise speech on the device itself (Android 12 and later). */
        fun isOnDeviceAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /** Whether there is any recogniser to ask at all. */
        fun isAvailable(context: Context): Boolean =
            isOnDeviceAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context)

        internal fun make(context: Context): SpeechRecognizer? = when {
            isOnDeviceAvailable(context) -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
            else -> null
        }
    }
}
