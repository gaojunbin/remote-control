package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.transport.PolishStrength
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.voice.DictationPolishState
import com.junbingao.remotecontrol.win.voice.PolishSpan
import com.junbingao.remotecontrol.win.voice.PrimarySlot
import com.junbingao.remotecontrol.win.voice.SpeechEvent
import com.junbingao.remotecontrol.win.voice.VoiceState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `web/tests/voice-composer.test.tsx` and `polish-composer.test.tsx`: dictation into the composer's own field, and polish of what it left. */
class ComposerVoiceTests {
    private fun TestScope.listening(harness: ComposerHarness): FakeSpeech.Socket? {
        harness.composer.startVoice()
        if (!eventually { harness.composer.voice.state == VoiceState.listening }) return null
        return harness.speech.sockets.lastOrNull()
    }

    /** Dictate, click Done, and let the gateway's final transcript land. */
    private fun TestScope.dictate(harness: ComposerHarness, final: String) {
        val socket = listening(harness) ?: return
        socket.deliver(SpeechEvent.Partial(final))
        harness.composer.voice.done()
        eventually { socket.toldToTranscribe }
        socket.deliver(SpeechEvent.Final(final))
    }

    @Test
    fun theTranscriptIsAppendedToTheDraftTheMicWasPressedOn() = runTest {
        val harness = ComposerHarness(this)
        harness.composer.setDraft("after lunch")
        val socket = assertNotNull(listening(harness))
        socket.deliver(SpeechEvent.Partial("run the suite"))
        assertEquals("after lunch run the suite", harness.chat.draft)
        socket.deliver(SpeechEvent.Partial("run the whole suite"))
        assertEquals("after lunch run the whole suite", harness.chat.draft)
    }

    @Test
    fun doneLeavesTheTranscriptInTheFieldAndSendsNothing() = runTest {
        val harness = ComposerHarness(this)
        dictate(harness, "run the auth suite")
        assertTrue(eventually { harness.composer.voice.state == VoiceState.idle })
        assertEquals("run the auth suite", harness.chat.draft)
        assertEquals(PrimarySlot.send, harness.composer.slot)
        pass(50)
        assertTrue(harness.channel.sent("session.send").isEmpty())
    }

    @Test
    fun theClickOnDoneIsAnsweredWithTheSpinner() = runTest {
        val harness = ComposerHarness(this)
        val socket = assertNotNull(listening(harness))
        assertEquals(PrimarySlot.done, harness.composer.slot)
        harness.composer.voice.done()
        assertEquals(PrimarySlot.working, harness.composer.slot)
        socket.deliver(SpeechEvent.Final("words"))
        assertEquals(PrimarySlot.send, harness.composer.slot)
    }

    @Test
    fun aKeystrokeTakesTheFieldBackAndKeepsTheWords() = runTest {
        val harness = ComposerHarness(this)
        val socket = assertNotNull(listening(harness))
        socket.deliver(SpeechEvent.Partial("half a"))
        harness.composer.userTyped("half a sentence")
        assertEquals(VoiceState.idle, harness.composer.voice.state)
        assertTrue(socket.toldToDrop)
        socket.deliver(SpeechEvent.Partial("late words"))
        assertEquals("half a sentence", harness.chat.draft)
    }

    @Test
    fun aPressOnTheFieldTakesItBackSoTheWordsCanBeReadBack() = runTest {
        val harness = ComposerHarness(this)
        val socket = assertNotNull(listening(harness))
        socket.deliver(SpeechEvent.Partial("read me back"))
        harness.composer.pointerDownInField()
        assertEquals(VoiceState.idle, harness.composer.voice.state)
        assertEquals("read me back", harness.chat.draft)
    }

    @Test
    fun dictatedWritesFollowTheTailAndTypingDoesNot() = runTest {
        val harness = ComposerHarness(this)
        val socket = assertNotNull(listening(harness))
        val before = harness.composer.tailRequest
        socket.deliver(SpeechEvent.Partial("a line"))
        assertEquals(before + 1, harness.composer.tailRequest)
        socket.deliver(SpeechEvent.Partial("a line"))
        assertEquals(before + 1, harness.composer.tailRequest)
        harness.composer.userTyped("typed")
        assertEquals(before + 1, harness.composer.tailRequest)
    }

    // A29 — polish

    private fun TestScope.polishing(): Pair<ComposerHarness, ComposerGate> {
        val harness = ComposerHarness(this)
        val gate = ComposerGate()
        harness.host.polishChoice = PolishChoice(model = "gpt-4.1-mini", strength = PolishStrength.strong)
        harness.host.polishAnswer = {
            gate.await()
            "  Run the auth suite.\n"
        }
        return harness to gate
    }

    @Test
    fun theDictatedWordsTheChoicesAndTheConversationGoToTheModel() = runTest {
        val (harness, gate) = polishing()
        harness.composer.setDraft("note:")
        dictate(harness, "um run the the auth suite")
        assertTrue(eventually { harness.composer.polish == DictationPolishState.Polishing })
        assertTrue(eventually { harness.host.polishRequests.isNotEmpty() })
        val request = harness.host.polishRequests.first()
        assertEquals("um run the the auth suite", request.text)
        assertTrue(request.model == "gpt-4.1-mini" && request.strength == PolishStrength.strong)
        assertEquals("auto", request.language)
        assertEquals(PrimarySlot.working, harness.composer.slot)
        gate.release()
        assertTrue(eventually { harness.chat.draft == "note: Run the auth suite." })
        assertEquals(DictationPolishState.Polished(span = PolishSpan(base = "note:", dictated = "um run the the auth suite"), text = "Run the auth suite."),
                     harness.composer.polish)
        assertEquals(PrimarySlot.send, harness.composer.slot)
    }

    @Test
    fun undoGivesTheDictatedWordsBack() = runTest {
        val (harness, gate) = polishing()
        dictate(harness, "um run it")
        gate.release()
        assertTrue(eventually { harness.chat.draft == "Run the auth suite." })
        harness.composer.undoPolish()
        assertEquals("um run it", harness.chat.draft)
        assertEquals(DictationPolishState.Idle, harness.composer.polish)
    }

    @Test
    fun theNextEditTakesTheNoteAway() = runTest {
        val (harness, gate) = polishing()
        dictate(harness, "um run it")
        gate.release()
        assertTrue(eventually { harness.chat.draft == "Run the auth suite." })
        harness.composer.userTyped("Run the auth suite. Now.")
        assertEquals(DictationPolishState.Idle, harness.composer.polish)
    }

    @Test
    fun aFailureLeavesTheWordsAsDictatedAndSaysSo() = runTest {
        val harness = ComposerHarness(this)
        harness.host.polishChoice = PolishChoice(model = "m", strength = PolishStrength.moderate)
        harness.host.polishAnswer = { throw TransportError.RequestTimedOut }
        dictate(harness, "um run it")
        assertTrue(eventually { harness.composer.polish == DictationPolishState.Failed })
        assertEquals("um run it", harness.chat.draft)
        assertEquals(PrimarySlot.send, harness.composer.slot)
    }

    @Test
    fun enterWaitsWithSendWhileTheModelIsWriting() = runTest {
        val (harness, gate) = polishing()
        dictate(harness, "um run it")
        assertTrue(eventually { harness.composer.polish == DictationPolishState.Polishing })
        assertTrue(harness.composer.handle(ComposerKey.enter, shift = false, hasMarkedText = false))
        assertTrue(!harness.composer.showsSendMenu)
        pass(50)
        assertTrue(harness.channel.sent("session.send").isEmpty())
        gate.release()
    }

    @Test
    fun typingOverTheWaitGivesSendBackAndTheLateAnswerIsDropped() = runTest {
        val (harness, gate) = polishing()
        dictate(harness, "um run it")
        assertTrue(eventually { harness.composer.polish == DictationPolishState.Polishing })
        harness.composer.userTyped("my own words")
        assertEquals(DictationPolishState.Idle, harness.composer.polish)
        assertEquals(PrimarySlot.send, harness.composer.slot)
        gate.release()
        pass(50)
        assertEquals("my own words", harness.chat.draft)
    }

    @Test
    fun nothingIsPolishedWithoutAModelChosenOnAGatewayThatHasOne() = runTest {
        val harness = ComposerHarness(this)
        dictate(harness, "um run it")
        assertTrue(eventually { harness.composer.voice.state == VoiceState.idle })
        pass(30)
        assertEquals(DictationPolishState.Idle, harness.composer.polish)
        assertTrue(harness.host.polishRequests.isEmpty())
    }
}
