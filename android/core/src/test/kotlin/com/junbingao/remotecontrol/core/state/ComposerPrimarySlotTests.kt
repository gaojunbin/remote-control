package com.junbingao.remotecontrol.core.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `docs/DESIGN.md` § "The composer" → **Done becomes a spinner, and the spinner becomes Send**: the
 * one slot against the trailing edge, for every pair of phases that can claim it.
 */
class ComposerPrimarySlotTests {
    private companion object {
        val span = DictationSpan(base = "", dictated = "um fix the the dot")
        val answered: PolishPhase = PolishPhase.Polished(span, "Fix the dot.")
    }

    /**
     * Every pair of phases, and the one thing the slot holds for it.
     *
     * Six phases of dictation against four of polish. The table is the ruling written out: Done
     * only while the microphone has the row, the spinner for as long as the words are on their way,
     * and Send the moment the field holds what will be sent.
     */
    @Test
    fun everyPair() {
        val done = ComposerPrimarySlot.done
        val working = ComposerPrimarySlot.working
        val send = ComposerPrimarySlot.send
        val table: List<Triple<VoiceInputPhase, PolishPhase, ComposerPrimarySlot>> = listOf(
            Triple(VoiceInputPhase.requestingPermission, PolishPhase.Idle, done),
            Triple(VoiceInputPhase.requestingPermission, PolishPhase.Polishing, done),
            Triple(VoiceInputPhase.requestingPermission, answered, done),
            Triple(VoiceInputPhase.requestingPermission, PolishPhase.Failed, done),

            Triple(VoiceInputPhase.listening, PolishPhase.Idle, done),
            Triple(VoiceInputPhase.listening, PolishPhase.Polishing, done),
            Triple(VoiceInputPhase.listening, answered, done),
            Triple(VoiceInputPhase.listening, PolishPhase.Failed, done),

            Triple(VoiceInputPhase.finishing, PolishPhase.Idle, working),
            Triple(VoiceInputPhase.finishing, PolishPhase.Polishing, working),
            Triple(VoiceInputPhase.finishing, answered, working),
            Triple(VoiceInputPhase.finishing, PolishPhase.Failed, working),

            Triple(VoiceInputPhase.review, PolishPhase.Idle, send),
            Triple(VoiceInputPhase.review, PolishPhase.Polishing, working),
            Triple(VoiceInputPhase.review, answered, send),
            Triple(VoiceInputPhase.review, PolishPhase.Failed, send),

            Triple(VoiceInputPhase.idle, PolishPhase.Idle, send),
            Triple(VoiceInputPhase.idle, PolishPhase.Polishing, working),
            Triple(VoiceInputPhase.idle, answered, send),
            Triple(VoiceInputPhase.idle, PolishPhase.Failed, send),

            Triple(VoiceInputPhase.failed, PolishPhase.Idle, send),
            Triple(VoiceInputPhase.failed, PolishPhase.Polishing, working),
            Triple(VoiceInputPhase.failed, answered, send),
            Triple(VoiceInputPhase.failed, PolishPhase.Failed, send),
        )
        assertEquals(24, table.size, "six phases of dictation against four of polish")
        for ((voice, polish, expected) in table) {
            assertEquals(expected, ComposerPrimarySlot.of(voice = voice, polish = polish), "${voice.rawValue} with $polish")
        }
    }

    /**
     * Done goes with the microphone, and Send is never offered while polishing.
     *
     * The two sentences the ruling turns on, said again as rules rather than as a table: a dead Done
     * and a Send that would send half a thought are the two things the slot must never be.
     */
    @Test
    fun theTwoThingsTheRowMustNotShow() {
        for (polish in listOf(PolishPhase.Idle, PolishPhase.Polishing, answered, PolishPhase.Failed)) {
            assertNotEquals(ComposerPrimarySlot.done, ComposerPrimarySlot.of(voice = VoiceInputPhase.finishing, polish = polish),
                            "Done is not drawn once the microphone is off")
        }
        for (voice in listOf(VoiceInputPhase.idle, VoiceInputPhase.review, VoiceInputPhase.failed)) {
            assertNotEquals(ComposerPrimarySlot.send, ComposerPrimarySlot.of(voice = voice, polish = PolishPhase.Polishing),
                            "Send is not drawn while the model is still writing")
        }
    }

    /**
     * An edit on its way back into the line holds a spinner where Send was.
     *
     * Amendment A43: an edited queued message on its way back into the line holds the slot the way a
     * polish still out does, so it is not sent twice.
     */
    @Test
    fun anEditOnItsWayBack() {
        for (voice in listOf(VoiceInputPhase.idle, VoiceInputPhase.review, VoiceInputPhase.failed)) {
            assertEquals(ComposerPrimarySlot.working, ComposerPrimarySlot.of(voice = voice, polish = PolishPhase.Idle, returning = true))
            assertEquals(ComposerPrimarySlot.send, ComposerPrimarySlot.of(voice = voice, polish = PolishPhase.Idle, returning = false))
        }
    }
}
