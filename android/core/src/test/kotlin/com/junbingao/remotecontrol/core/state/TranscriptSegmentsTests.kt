package com.junbingao.remotecontrol.core.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dictation has no maximum duration, so both speech backends roll their recognition request over
 * while the microphone keeps running. These are the rules that keep the resulting text in the order
 * it was spoken.
 */
class TranscriptSegmentsTests {
    /** A fresh transcript is settled and empty. */
    @Test
    fun empty() {
        val segments = TranscriptSegments()
        assertEquals("", segments.joined)
        assertEquals(0, segments.count)
        assertNull(segments.active)
        assertTrue(segments.isSettled)
    }

    /** Each request owns a slot, and the last one opened is the live one. */
    @Test
    fun slots() {
        val segments = TranscriptSegments()
        val first = segments.begin()
        assertEquals(0, first)
        assertEquals(0, segments.active)
        val second = segments.begin()
        assertEquals(1, second)
        assertEquals(1, segments.active)
        assertEquals(2, segments.count)
    }

    /** Segments are joined in the order the audio was spoken. */
    @Test
    fun joinedInOrder() {
        val segments = TranscriptSegments()
        val first = segments.begin()
        val second = segments.begin()
        segments.update(second, text = "on the CI runner too.")
        segments.update(first, text = "Re-run the auth suite")
        assertEquals("Re-run the auth suite on the CI runner too.", segments.joined)
    }

    /** A late final from the previous request refines its own slot, not the end. */
    @Test
    fun lateFinal() {
        val segments = TranscriptSegments()
        val first = segments.begin()
        segments.update(first, text = "re-run the auth")
        val second = segments.begin()
        segments.update(second, text = "on CI")
        // The previous request's final lands after the next one has spoken.
        segments.update(first, text = "Re-run the auth suite")
        assertEquals("Re-run the auth suite on CI", segments.joined)
    }

    /** Each result replaces its segment rather than accumulating. */
    @Test
    fun replacement() {
        val segments = TranscriptSegments()
        val index = segments.begin()
        segments.update(index, text = "re-run")
        segments.update(index, text = "re-run the auth suite")
        assertEquals("re-run the auth suite", segments.joined)
    }

    /** An unchanged result does not report a change. */
    @Test
    fun unchangedResult() {
        val segments = TranscriptSegments()
        val index = segments.begin()
        val changed = segments.update(index, text = "re-run")
        val repeated = segments.update(index, text = "re-run")
        // Backends pad partials differently; the surrounding space is not a change.
        val padded = segments.update(index, text = "  re-run \n")
        assertTrue(changed)
        assertFalse(repeated)
        assertFalse(padded)
    }

    /**
     * The defect this rule exists for: the speaker pauses, the recognizer starts its transcription
     * over inside the same request, and the sentence already spoken used to be replaced by the new
     * one.
     */
    @Test
    fun restartInsideOneRequest() {
        val segments = TranscriptSegments()
        val index = segments.begin()
        segments.update(index, text = "The weather in Berlin")
        segments.update(index, text = "The weather in Berlin is cold today.")
        segments.update(index, text = "Tomorrow")
        assertEquals("The weather in Berlin is cold today. Tomorrow", segments.joined)
        segments.update(index, text = "Tomorrow I will take the train.")
        assertEquals("The weather in Berlin is cold today. Tomorrow I will take the train.", segments.joined)
        assertEquals(1, segments.count, "one request still owns one slot")
    }

    /** A recognizer rewriting its own last words revises rather than starts over. */
    @Test
    fun revisionReplacesTheSegment() {
        val segments = TranscriptSegments()
        val index = segments.begin()
        segments.update(index, text = "Re-run the auth suite on the CI runner")
        segments.update(index, text = "Re-run the auth suite on the CI runner too.")
        segments.update(index, text = "Re-run the auth suite on the SI runner")
        assertEquals("Re-run the auth suite on the SI runner", segments.joined)
    }

    /** The first words of an utterance are corrected, never settled behind the correction. */
    @Test
    fun shortSegmentIsNeverSettled() {
        val segments = TranscriptSegments()
        val index = segments.begin()
        segments.update(index, text = "Hello")
        segments.update(index, text = "Halo")
        assertEquals("Halo", segments.joined)
    }

    /** A restart is told apart from a revision by what the two have in common. */
    @Test
    fun freshStartRule() {
        // A new sentence: shorter, and agreeing on nothing but a first letter.
        assertTrue(TranscriptSegments.isFreshStart(after = "The weather in Berlin is cold today.", next = "Tomorrow"))
        // The same sentence, longer: an extension.
        assertFalse(TranscriptSegments.isFreshStart(after = "The weather in Berlin", next = "The weather in Berlin is cold"))
        // The same sentence with its tail rewritten, and a word shorter.
        assertFalse(TranscriptSegments.isFreshStart(after = "The weather in Berlin is cold today.",
                                                    next = "The weather in Berlin is cold"))
        // Too little heard so far to call anything a second sentence.
        assertFalse(TranscriptSegments.isFreshStart(after = "Hello", next = "Halo"))
        // Punctuation and spacing alone never make a restart.
        assertFalse(TranscriptSegments.isFreshStart(after = "the weather in berlin is cold today",
                                                    next = "The weather, in Berlin"))
    }

    /** A result for a slot that was never opened is ignored. */
    @Test
    fun unknownSlot() {
        val segments = TranscriptSegments()
        segments.begin()
        val accepted = segments.update(7, text = "from nowhere")
        assertFalse(accepted)
        assertNull(segments.text(at = 7))
        assertEquals("", segments.joined)
    }

    /** An empty segment leaves no gap in the joined text. */
    @Test
    fun silentSegment() {
        val segments = TranscriptSegments()
        val first = segments.begin()
        segments.begin()
        val third = segments.begin()
        segments.update(first, text = "start")
        segments.update(third, text = "end")
        assertEquals("start end", segments.joined)
    }

    /** A transcript is final only once every request has said its last word. */
    @Test
    fun settling() {
        val segments = TranscriptSegments()
        val first = segments.begin()
        val second = segments.begin()
        assertFalse(segments.isSettled)
        assertTrue(segments.isOpen(first))
        segments.end(second)
        assertFalse(segments.isSettled, "the earlier request is still transcribing")
        segments.end(first)
        assertTrue(segments.isSettled)
        assertFalse(segments.isOpen(first))
    }

    /** Ending a segment twice is not an error. */
    @Test
    fun endTwice() {
        val segments = TranscriptSegments()
        val index = segments.begin()
        segments.end(index)
        segments.end(index)
        assertTrue(segments.isSettled)
    }

    /** A segment that ended keeps its text. */
    @Test
    fun endedSegmentKeepsText() {
        val segments = TranscriptSegments()
        val index = segments.begin()
        segments.update(index, text = "the whole sentence")
        segments.end(index)
        assertEquals("the whole sentence", segments.text(at = index))
        assertEquals("the whole sentence", segments.joined)
    }
}
