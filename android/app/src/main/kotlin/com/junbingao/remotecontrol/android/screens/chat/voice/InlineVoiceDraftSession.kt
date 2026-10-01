package com.junbingao.remotecontrol.android.screens.chat.voice

import com.junbingao.remotecontrol.android.voice.SpeechInputEvent
import com.junbingao.remotecontrol.android.voice.SpeechInputPlatform
import com.junbingao.remotecontrol.core.state.DictationSpan
import com.junbingao.remotecontrol.core.state.VoiceDraftTarget
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One composer's dictation: the controller that listens, and the draft the words land in — the
 * draft it started from, so every partial is joined to that and never to the last partial, and
 * the composer it belongs to, so a transcript never lands in another session's field.
 */
class InlineVoiceDraftSession(platform: SpeechInputPlatform, val isPreview: Boolean = false, scope: CoroutineScope) {
    val voice = VoiceInputController(platform, scope)

    /**
     * Amendment A29: the span a finished dictation left in the field — the draft it started from
     * and the words it added — handed over exactly once. The composer sets this, because it is what
     * knows whether polishing is on and which model does it.
     */
    var onDictationFinished: ((DictationSpan) -> Unit)? = null
    private var target: VoiceDraftTarget? = null
    private var originalDraft = ""
    private var appliedDraft = ""
    private var hasReported = false

    fun start(draft: String, target: VoiceDraftTarget) {
        if (voice.phase.isBusy) return
        this.target = target
        originalDraft = draft
        appliedDraft = draft
        hasReported = false
        voice.start()
    }

    /**
     * Amendment A29: called once the draft is up to date. A dictation that has stopped — the final
     * transcript, a failure, the scene leaving — hands its span over, and only the first of those
     * moments does.
     */
    fun reportFinishedDictation() {
        if (hasReported || target == null || voice.phase.isBusy) return
        val dictated = voice.transcript.trim()
        if (dictated.isEmpty()) return
        hasReported = true
        onDictationFinished?.invoke(DictationSpan(base = originalDraft, dictated = dictated))
    }

    fun updateDraft(currentDraft: String, currentTarget: VoiceDraftTarget): String? {
        val target = target ?: return null
        if (!target.matches(currentTarget) || currentDraft != appliedDraft) {
            reset()
            return null
        }
        val next = target.inserting(voice.transcript, into = originalDraft, currentTarget = currentTarget) ?: originalDraft
        if (next == appliedDraft) return null
        appliedDraft = next
        return next
    }

    fun finish() = voice.finish()

    fun reset() {
        target = null
        originalDraft = ""
        appliedDraft = ""
        hasReported = false
        voice.cancel()
    }
}

/**
 * A scripted platform for previews and the UI tests. It never touches the microphone and is only
 * reachable behind an explicit launch argument, in a debug build.
 *
 * `partials` is how many times the words arrive before they are final — one, as a short sentence
 * does, or several, the way a long one really lands — each a growing prefix cut at a word
 * boundary, the last of which is the whole of it.
 */
class ScriptedSpeechInput(
    private val scope: CoroutineScope,
    private val transcript: String = shortTranscript,
    private val level: Double = defaultLevel,
    partials: Int = 1,
) : SpeechInputPlatform {
    private val partials = partials.coerceAtLeast(1)
    private var onEvent: ((SpeechInputEvent) -> Unit)? = null
    private var delivery: Job? = null

    override suspend fun requestPermission() {}

    override fun start(onEvent: (SpeechInputEvent) -> Unit) {
        this.onEvent = onEvent
        val steps = steps(transcript, partials)
        onEvent(SpeechInputEvent.Transcript(steps[0], isFinal = false))
        onEvent(SpeechInputEvent.Level(level))
        if (steps.size <= 1) return
        delivery = scope.launch {
            for (step in steps.drop(1)) {
                delay(partialInterval)
                val send = this@ScriptedSpeechInput.onEvent ?: return@launch
                send(SpeechInputEvent.Transcript(step, isFinal = false))
            }
        }
    }

    override fun finish() {
        delivery?.cancel()
        delivery = null
        onEvent?.invoke(SpeechInputEvent.Transcript(transcript, isFinal = true))
    }

    override fun cancel() {
        delivery?.cancel()
        delivery = null
        onEvent = null
    }

    companion object {
        /**
         * The level a scripted dictation holds. Ordinary speech sits near the default;
         * `--voice-level=` pins it so the listening glow can be looked at at rest and at full voice
         * without speaking into a phone.
         */
        const val defaultLevel = 0.65

        /**
         * Real speech, fillers and all: it reads as something said rather than typed, which is what
         * dictation polish (A29) is there to clean up.
         */
        const val shortTranscript = "um re-run the the auth suite on the CI runner too."

        /**
         * Dictation long enough to outrun the field: about a minute of speech, which wraps to well
         * past the eight lines the composer grows to, so a test can watch the field follow the words
         * (`docs/DESIGN.md` § "The composer" → **While dictation runs, the field follows the words**).
         */
        const val longTranscript =
            "okay so here is the whole thing i want you to pick up after lunch, first re-run the auth " +
                "suite on the CI runner and keep the flaky login test quarantined for now, then work out " +
                "why the token refresh path retries twice on a cold start, i think the client is racing " +
                "the keychain read there, after that go through the gateway logs from this morning around " +
                "nine fifteen and pull out every request that took longer than two seconds, group them by " +
                "route, and if the slow ones are all on the upload path then check whether the disk on the " +
                "box is full again, and if any of it looks like the flake we chased last week then say so " +
                "in the notes rather than fixing it, i would rather the two of us looked at it together " +
                "first, and write the whole thing up in the round notes so i can read it on the train " +
                "tomorrow morning"

        /** How long a long dictation takes between one partial and the next. */
        val partialInterval = 300.milliseconds

        /** The transcript as it arrives: growing prefixes cut at word boundaries, the last of which is the whole of it. */
        fun steps(transcript: String, count: Int): List<String> {
            val words = transcript.split(" ").filter { it.isNotEmpty() }
            if (count <= 1 || words.size < count) return listOf(transcript)
            return (1..count).map { step -> words.take(words.size * step / count).joinToString(" ") }
        }
    }
}
