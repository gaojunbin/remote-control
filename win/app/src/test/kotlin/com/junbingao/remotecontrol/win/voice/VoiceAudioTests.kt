package com.junbingao.remotecontrol.win.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The Mac's `VoiceAudioTests.swift`. Its downsampler and chunker cases are the platform's here, in
// `platform/DownsampleTests.kt`, beside the code they cover.

/** `web/tests/primarySlot.test.ts`: the composer's one primary slot over every pair of a dictation phase and a polish phase. */
class VoicePrimarySlotTests {
    private val expected: Map<VoiceState, Map<PolishProgress, PrimarySlot>> = mapOf(
        VoiceState.idle to mapOf(PolishProgress.idle to PrimarySlot.send, PolishProgress.polishing to PrimarySlot.working,
                                 PolishProgress.polished to PrimarySlot.send, PolishProgress.failed to PrimarySlot.send),
        VoiceState.starting to PolishProgress.entries.associateWith { PrimarySlot.done },
        VoiceState.listening to PolishProgress.entries.associateWith { PrimarySlot.done },
        VoiceState.finishing to PolishProgress.entries.associateWith { PrimarySlot.working },
        VoiceState.error to mapOf(PolishProgress.idle to PrimarySlot.send, PolishProgress.polishing to PrimarySlot.working,
                                  PolishProgress.polished to PrimarySlot.send, PolishProgress.failed to PrimarySlot.send),
    )

    @Test
    fun everyPairHoldsWhatTheRulingSays() {
        for (voice in VoiceState.entries) {
            for (polish in PolishProgress.entries) {
                assertEquals(expected[voice]?.get(polish), PrimarySlot.of(voice = voice, polish = polish), "$voice × $polish")
            }
        }
    }

    @Test
    fun fromDoneUntilTheLastWordIsInTheFieldTheSlotWaits() {
        assertEquals(PrimarySlot.working, PrimarySlot.of(voice = VoiceState.finishing, polish = PolishProgress.idle))
        assertEquals(PrimarySlot.working, PrimarySlot.of(voice = VoiceState.idle, polish = PolishProgress.polishing))
        assertEquals(PrimarySlot.send, PrimarySlot.of(voice = VoiceState.idle, polish = PolishProgress.polished))
        assertEquals(PrimarySlot.send, PrimarySlot.of(voice = VoiceState.idle, polish = PolishProgress.failed))
    }
}

/** `segments.ts` and `draft.ts`: a long dictation joined in spoken order, and where its words land in the field. */
class VoiceTextTests {
    @Test
    fun segmentsJoinByPositionAndSettleOnlyWhenEveryOneHasEnded() {
        val segments = DictationSegments()
        val first = segments.begin()
        val second = segments.begin()
        val secondMoved = segments.update(second, text = "second half")
        val firstMoved = segments.update(first, text = " first half ")
        val sameAgain = segments.update(first, text = "first half")
        assertTrue(secondMoved && firstMoved && !sameAgain)
        assertEquals("first half second half", segments.joined)
        segments.end(first)
        assertTrue(!segments.isSettled && segments.isOpen(second))
        segments.end(second)
        assertTrue(segments.isSettled)
        assertFalse(segments.update(7, text = "never opened"))
    }

    @Test
    fun aTranscriptIsAppendedAfterASpaceAndReplacesNothing() {
        assertEquals("run the suite", DictationDraft.merge("", "run the suite"))
        assertEquals("after lunch run it", DictationDraft.merge("after lunch", "run it"))
        assertEquals("after lunch\nrun it", DictationDraft.merge("after lunch\n", "run it"))
        assertEquals("after lunch", DictationDraft.merge("after lunch", ""))
    }
}
