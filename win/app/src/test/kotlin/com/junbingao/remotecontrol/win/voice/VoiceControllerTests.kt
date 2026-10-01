package com.junbingao.remotecontrol.win.voice

import com.junbingao.remotecontrol.win.chat.composer.FakeSpeech
import com.junbingao.remotecontrol.win.chat.composer.eventually
import com.junbingao.remotecontrol.win.chat.composer.fast
import com.junbingao.remotecontrol.win.chat.composer.pass
import com.junbingao.remotecontrol.win.platform.RecorderError
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `web/tests/voice.test.ts`: the dictation state machine — the mic button starts it, only Done and
 * a reach for the field end it, nothing is ever sent, and a dictation longer than one utterance is
 * chained across sockets without losing the seam. On a clock a hundred times shorter, and virtual.
 */
class VoiceControllerTests {
    private val speech = FakeSpeech()
    private val transcripts = mutableListOf<Pair<String, Boolean>>()

    private fun TestScope.controller(): VoiceController {
        val voice = VoiceController(services = speech.services, timing = VoiceTiming.fast, tasks = backgroundScope, clock = testScheduler.timeSource)
        voice.onTranscript = { text, isFinal -> transcripts += text to isFinal }
        return voice
    }

    private fun TestScope.listening(voice: VoiceController): Boolean {
        voice.start()
        return eventually { voice.state == VoiceState.listening }
    }

    @Test
    fun itListensFromTheMomentTheMicIsPressed() = runTest {
        val voice = controller()
        assertTrue(listening(voice))
        assertEquals(1, speech.sockets.size)
    }

    @Test
    fun doneKeepsTheTranscriptAndNothingIsSent() = runTest {
        val voice = controller()
        assertTrue(listening(voice))
        val socket = speech.sockets.first()
        socket.deliver(SpeechEvent.Partial("run the auth suite"))
        assertEquals("run the auth suite" to false, transcripts.last())

        voice.done()
        assertEquals(VoiceState.finishing, voice.state)
        assertTrue(eventually { socket.toldToTranscribe })
        socket.deliver(SpeechEvent.Final("run the auth suite on CI"))
        assertEquals("run the auth suite on CI" to true, transcripts.last())
        assertEquals(VoiceState.idle, voice.state)
    }

    @Test
    fun cancelPublishesNothingAndDropsTheUtterance() = runTest {
        val voice = controller()
        assertTrue(listening(voice))
        val socket = speech.sockets.first()
        socket.deliver(SpeechEvent.Partial("forget this"))
        voice.cancel()
        assertEquals(VoiceState.idle, voice.state)
        assertTrue(socket.toldToDrop)
        assertTrue(transcripts.none { it.second })
    }

    @Test
    fun thereIsNoTimeLimit() = runTest {
        val voice = controller()
        assertTrue(listening(voice))
        speech.speak(0.5)
        pass(1_500)
        assertEquals(VoiceState.listening, voice.state)
        assertTrue(transcripts.none { it.second })
        voice.cancel()
    }

    @Test
    fun segmentsChainAcrossSocketsAndJoinInSpokenOrder() = runTest {
        val voice = controller()
        assertTrue(listening(voice))
        speech.sockets[0].deliver(SpeechEvent.Partial("first half"))
        // The replacement is taking audio before the outgoing one transcribes.
        assertTrue(eventually { speech.sockets.size == 2 })
        assertTrue(speech.sockets[0].toldToTranscribe)

        speech.sockets[1].deliver(SpeechEvent.Partial("second half"))
        speech.sockets[0].deliver(SpeechEvent.Final("first half exactly"))
        assertEquals("first half exactly second half" to false, transcripts.last())

        voice.done()
        assertTrue(eventually { speech.sockets[1].toldToTranscribe })
        speech.sockets[1].deliver(SpeechEvent.Final("second half exactly"))
        assertEquals("first half exactly second half exactly" to true, transcripts.last())
    }

    @Test
    fun aCutWaitsForThePause() = runTest {
        val voice = controller()
        assertTrue(listening(voice))
        speech.speak(0.6)
        pass(500)
        assertEquals(1, speech.sockets.size)
        speech.speak(0.0)
        assertTrue(eventually { speech.sockets.size == 2 })
        assertEquals(VoiceState.listening, voice.state)
        voice.cancel()
    }

    @Test
    fun aGatewayFailureKeepsWhatWasRecognised() = runTest {
        val voice = controller()
        assertTrue(listening(voice))
        speech.sockets[0].deliver(SpeechEvent.Partial("half a sentence"))
        speech.sockets[0].deliver(SpeechEvent.Failed("backend unavailable"))
        assertEquals(VoiceState.error, voice.state)
        assertEquals("backend unavailable", voice.error)
        assertEquals("half a sentence" to true, transcripts.last())
    }

    @Test
    fun aFailureWithoutWordsOfItsOwnSaysTranscriptionFailed() = runTest {
        val voice = controller()
        assertTrue(listening(voice))
        speech.sockets[0].deliver(SpeechEvent.Failed(null))
        assertEquals(S.voice.failed, voice.error)
    }

    @Test
    fun aSocketThatCannotConnectFailsTheDictation() = runTest {
        speech.socketsRefuse = true
        val voice = controller()
        voice.start()
        assertTrue(eventually { voice.state == VoiceState.error })
        assertEquals(S.voice.failed, voice.error)
    }

    @Test
    fun aRefusedMicrophoneSaysSo() = runTest {
        val voice = controller()
        voice.start()
        val recorder = speech.recorders.first()
        recorder.handlers.onError(RecorderError.denied)
        assertEquals(VoiceState.error, voice.state)
        assertEquals(S.voice.denied, voice.error)
    }

    @Test
    fun aGatewayThatNeverAnswersDoneKeepsTheWords() = runTest {
        val voice = controller()
        assertTrue(listening(voice))
        speech.sockets[0].deliver(SpeechEvent.Partial("the words so far"))
        voice.done()
        assertTrue(eventually { voice.state == VoiceState.idle })
        assertEquals("the words so far" to true, transcripts.last())
        assertNull(voice.error)
    }

    /** The composer taken away mid-dictation — the device offline, the terminal back in charge — ends the run and keeps the words. */
    @Test
    fun aComposerThatStopsTakingDictationEndsTheRun() = runTest {
        val voice = controller()
        assertTrue(listening(voice))
        speech.sockets[0].deliver(SpeechEvent.Partial("half a sentence"))
        voice.enabled = false
        assertEquals(VoiceState.idle, voice.state)
        assertTrue(speech.sockets[0].toldToDrop)
        assertEquals("half a sentence" to false, transcripts.last())
    }

    @Test
    fun anIdleControllerIsLeftAlone() = runTest {
        val voice = controller()
        voice.enabled = false
        assertEquals(VoiceState.idle, voice.state)
        assertTrue(speech.sockets.isEmpty())
        voice.start()
        assertEquals(VoiceState.idle, voice.state)
    }

    @Test
    fun theElapsedClockRunsWhileListeningAndStopsAtDone() = runTest {
        val voice = controller()
        assertTrue(listening(voice))
        assertTrue(eventually { voice.elapsedMs > 20 })
        voice.done()
        val stopped = voice.elapsedMs
        pass(60)
        assertEquals(stopped, voice.elapsedMs)
        assertFalse(voice.state == VoiceState.listening)
    }
}
