package com.junbingao.remotecontrol.android.voice

import android.content.Context
import com.junbingao.remotecontrol.android.permissions.PermissionRequest
import com.junbingao.remotecontrol.core.state.TranscriptSegments

/**
 * On-device dictation: the iPhone's `SystemSpeechRecognizer`. Audio never leaves the phone and is
 * never written to disk; the transcript becomes an editable draft and never submits itself.
 *
 * Android's recogniser ends a request at every pause, so the runs underneath are chained for as
 * long as the person talks ([RecognizerRuns]), and each run keeps its own slot in the core's
 * [TranscriptSegments] — the iPhone's rolled-over requests, one slot each, joined in the order
 * their audio was spoken.
 *
 * [microphone] is the screen's own request for the microphone, which Android answers through the
 * activity; Android asks for no separate speech permission, so it is the only one there is.
 */
class SystemSpeechRecognizer(
    context: Context,
    /** The language to listen for, as `DictationLanguage` hands it over: `zh-CN`, `en-US`, … */
    localeIdentifier: String,
    private val microphone: PermissionRequest,
    private val runs: RecognitionRuns = RecognizerRuns(context, localeIdentifier),
) : SpeechInputPlatform {
    private var segments = TranscriptSegments()
    private var emit: ((SpeechInputEvent) -> Unit)? = null
    private var isFinishing = false

    override suspend fun requestPermission() {
        if (!microphone.request()) throw SpeechInputFailure.MicrophonePermission
    }

    override fun start(onEvent: (SpeechInputEvent) -> Unit) {
        cancel()
        segments = TranscriptSegments()
        isFinishing = false
        emit = onEvent
        try {
            runs.start(::receive)
        } catch (failure: SpeechInputFailure) {
            emit = null
            throw failure
        }
    }

    override fun finish() {
        if (isFinishing || emit == null) return
        isFinishing = true
        // The run under way flushes what it holds; its final settles the segment and publishes.
        runs.finish()
    }

    override fun cancel() {
        isFinishing = false
        emit = null
        runs.cancel()
        segments = TranscriptSegments()
    }

    private fun receive(event: RecognitionEvent) {
        val report = emit ?: return
        when (event) {
            is RecognitionEvent.Began -> segments.begin()
            is RecognitionEvent.Partial -> if (segments.update(event.segment, event.text)) publish()
            is RecognitionEvent.Final -> if (segments.update(event.segment, event.text)) publish()
            is RecognitionEvent.Ended -> {
                segments.end(event.segment)
                // While finishing, the last run's end is followed by Finished, which says the
                // final word once.
                if (!isFinishing) publish()
            }
            is RecognitionEvent.Level -> report(SpeechInputEvent.Level(event.value))
            is RecognitionEvent.Failed -> report(SpeechInputEvent.Failure(event.failure))
            RecognitionEvent.Finished -> {
                publish()
                emit = null
            }
        }
    }

    private fun publish() {
        emit?.invoke(SpeechInputEvent.Transcript(segments.joined, isFinal = isFinishing && segments.isSettled))
    }
}
