package com.junbingao.remotecontrol.android.screens.chat.voice

import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.voice.SpeechInputEvent
import com.junbingao.remotecontrol.android.voice.SpeechInputPlatform
import com.junbingao.remotecontrol.core.state.TranscriptSegments
import com.junbingao.remotecontrol.core.state.VoiceDraftTarget
import com.junbingao.remotecontrol.core.state.VoiceInputPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checks `ios/VerificationUI` makes of dictation — its lifecycle, no maximum duration, Done
 * keeping the draft, the finish grace belonging to the backend, the scripted platform — on the
 * test's clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatVoiceTest {
    private val target = VoiceDraftTarget(account = "demo", deviceID = "d", sessionID = "s")

    @Test
    fun dictationLifecycle() = runTest {
        val voice = InlineVoiceDraftSession(ScriptedSpeechInput(backgroundScope, transcript = "run the suite again"), isPreview = true, scope = backgroundScope)
        voice.start(draft = "before", target = target)
        runCurrent()
        assertEquals("dictation reaches the listening phase", VoiceInputPhase.listening, voice.voice.phase)
        assertEquals("a partial transcript is merged into the draft", "before\nrun the suite again", voice.updateDraft("before", target))
        assertNull("an unchanged transcript does not rewrite the draft", voice.updateDraft("before\nrun the suite again", target))
        // A human edit wins: dictation stops rewriting what the user typed.
        assertNull("a typed edit stops dictation from overwriting it", voice.updateDraft("typed by hand", target))

        val other = VoiceDraftTarget(account = "demo", deviceID = "d", sessionID = "another")
        voice.start(draft = "x", target = target)
        runCurrent()
        assertNull("a transcript never lands in a different session's composer", voice.updateDraft("x", other))
        voice.reset()
        assertEquals("resetting dictation returns to idle", VoiceInputPhase.idle, voice.voice.phase)
    }

    @Test
    fun dictationHasNoMaximumDuration() = runTest {
        assertNull("listening has no deadline of its own", VoiceInputController.listeningDeadline)
        val segmented = SegmentedSpeechInput()
        val unlimited = VoiceInputController(segmented, backgroundScope)
        unlimited.start()
        runCurrent()
        assertEquals("dictation starts listening", VoiceInputPhase.listening, unlimited.phase)
        assertFalse("listening arms no timer of its own", unlimited.isAwaitingFinalTranscript)

        segmented.hear("re-run the auth suite")
        runCurrent()
        assertEquals("re-run the auth suite", unlimited.transcript)
        // The recognition request underneath expires. A backend rolls over to a new one rather than
        // ending the session, and the microphone never stops.
        segmented.rollOver()
        segmented.hear("on the CI runner too")
        runCurrent()
        assertEquals("a recognition request ending mid-session is a restart, not a stop", VoiceInputPhase.listening, unlimited.phase)
        assertEquals("the backend opened a second request underneath", 2, segmented.requests)
        assertEquals("each segment is appended in the order it was spoken", "re-run the auth suite on the CI runner too", unlimited.transcript)

        unlimited.finish()
        runCurrent()
        assertEquals("Done ends the session", VoiceInputPhase.review, unlimited.phase)
        assertEquals("and keeps every segment of the transcript", "re-run the auth suite on the CI runner too", unlimited.transcript)
        assertFalse("with no timer left behind", unlimited.isAwaitingFinalTranscript)
        unlimited.cancel()
    }

    @Test
    fun doneIsTheOneWayOutAndKeepsTheDraft() = runTest {
        val keeping = SegmentedSpeechInput()
        val keepSession = InlineVoiceDraftSession(keeping, isPreview = true, scope = backgroundScope)
        keepSession.start(draft = "", target = target)
        runCurrent()
        keeping.hear("first half")
        keeping.rollOver()
        keeping.hear("second half")
        runCurrent()
        keepSession.finish()
        runCurrent()
        assertEquals(VoiceInputPhase.review, keepSession.voice.phase)
        assertEquals("Done leaves the whole transcript in the message field", "first half second half", keepSession.updateDraft("", target))
        keepSession.reset()

        val appending = SegmentedSpeechInput()
        val appendSession = InlineVoiceDraftSession(appending, isPreview = true, scope = backgroundScope)
        appendSession.start(draft = "the draft I already had", target = target)
        runCurrent()
        appending.hear("and some dictation")
        runCurrent()
        assertEquals(
            "dictation is appended after whatever the user already had",
            "the draft I already had\nand some dictation",
            appendSession.updateDraft("the draft I already had", target),
        )
        appendSession.reset()
        assertEquals("and leaving the composer leaves dictation idle", VoiceInputPhase.idle, appendSession.voice.phase)
    }

    /** Review finding 2: a backend with a long grace must not be cancelled at the on-device value. */
    @Test
    fun theFinishGraceBelongsToTheBackend() = runTest {
        val patient = SlowSpeechInput(grace = 4.0)
        val controller = VoiceInputController(patient, backgroundScope)
        controller.start()
        runCurrent()
        assertEquals(VoiceInputPhase.listening, controller.phase)
        controller.finish()
        assertEquals("stopping enters the finishing phase", VoiceInputPhase.finishing, controller.phase)
        advanceTimeBy(2_600)
        runCurrent()
        assertEquals("a gateway-length grace is still waiting after the on-device timeout", VoiceInputPhase.finishing, controller.phase)
        patient.deliverFinal("the whole sentence")
        runCurrent()
        assertEquals(VoiceInputPhase.review, controller.phase)
        assertEquals("the late transcript is kept rather than discarded", "the whole sentence", controller.transcript)
        controller.cancel()
    }

    @Test
    fun aSilentBackendStillEndsAtItsGrace() = runTest {
        val silent = SlowSpeechInput(grace = 2.0)
        val controller = VoiceInputController(silent, backgroundScope)
        controller.start()
        runCurrent()
        controller.finish()
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals("the backend never answered, so what was recognised is kept", VoiceInputPhase.review, controller.phase)
        assertEquals("the whole", controller.transcript)
    }

    @Test
    fun theListeningGlowFollowsTheDisplay() {
        assertEquals("a display with a home indicator has round corners to follow", DisplayCorner.fallbackRadius, DisplayCorner.radius(34.dp))
        assertEquals("an older display does not", DisplayCorner.squareRadius, DisplayCorner.radius(0.dp))
    }

    /** `--voice-transcript=long`: a dictation longer than the eight lines the field grows to, arriving a partial at a time. */
    @Test
    fun aLongDictationArrivesAPartialAtATime() = runTest {
        val long = ScriptedSpeechInput(backgroundScope, transcript = ScriptedSpeechInput.longTranscript, partials = 4)
        val controller = VoiceInputController(long, backgroundScope)
        controller.start()
        runCurrent()
        val first = controller.transcript
        assertTrue("a long dictation starts on its first partial", first.isNotEmpty())
        assertTrue("which is a part of what was said rather than the whole of it", first.length < ScriptedSpeechInput.longTranscript.length)
        advanceTimeBy(ScriptedSpeechInput.partialInterval.inWholeMilliseconds + 1)
        runCurrent()
        assertTrue("and the rest of it arrives a partial at a time", controller.transcript.length > first.length)
        assertTrue("what is finally said is far past the eight lines the field grows to", ScriptedSpeechInput.longTranscript.length > 600)
        controller.cancel()
    }

    @Test
    fun theScriptedWordsArriveAsGrowingPrefixes() {
        val steps = ScriptedSpeechInput.steps("one two three four five six seven eight", 4)
        assertEquals(listOf("one two", "one two three four", "one two three four five six", "one two three four five six seven eight"), steps)
        assertEquals("a short sentence arrives once", listOf("hello there"), ScriptedSpeechInput.steps("hello there", 4))
        assertEquals(listOf("anything"), ScriptedSpeechInput.steps("anything", 1))
    }
}

/**
 * A platform that models what both speech backends do for a dictation with no maximum duration: the
 * microphone stays up while the recognition request underneath is rolled over, each request owns one
 * slot in the transcript, and nothing is called final until the user is done.
 */
internal class SegmentedSpeechInput : SpeechInputPlatform {
    private var onEvent: ((SpeechInputEvent) -> Unit)? = null
    private var segments = TranscriptSegments()
    private var slot = 0
    var requests = 0
        private set

    override suspend fun requestPermission() {}

    override fun start(onEvent: (SpeechInputEvent) -> Unit) {
        this.onEvent = onEvent
        segments = TranscriptSegments()
        slot = segments.begin()
        requests = 1
    }

    /** One more partial result for the request that is running. */
    fun hear(text: String) {
        segments.update(slot, text)
        publish()
    }

    /** The running request expired. A new one takes over without the session ending. */
    fun rollOver() {
        segments.end(slot)
        slot = segments.begin()
        requests += 1
        publish()
    }

    override fun finish() {
        segments.end(slot)
        publish(isFinal = segments.isSettled)
    }

    override fun cancel() {
        onEvent = null
    }

    private fun publish(isFinal: Boolean = false) {
        onEvent?.invoke(SpeechInputEvent.Transcript(segments.joined, isFinal = isFinal))
    }
}

/** A platform that answers `finish()` only when told to, with a grace period of its own. */
internal class SlowSpeechInput(grace: Double) : SpeechInputPlatform {
    private var onEvent: ((SpeechInputEvent) -> Unit)? = null
    override val finishGracePeriod: Double = grace

    override suspend fun requestPermission() {}

    override fun start(onEvent: (SpeechInputEvent) -> Unit) {
        this.onEvent = onEvent
        onEvent(SpeechInputEvent.Transcript("the whole", isFinal = false))
    }

    override fun finish() {}

    override fun cancel() {
        onEvent = null
    }

    fun deliverFinal(text: String) {
        onEvent?.invoke(SpeechInputEvent.Transcript(text, isFinal = true))
    }
}
