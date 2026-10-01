package com.junbingao.remotecontrol.android.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.permissions.PermissionRequest
import com.junbingao.remotecontrol.core.transport.STTSocket
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import com.junbingao.remotecontrol.core.transport.GatewayHTTPClient
import com.junbingao.remotecontrol.core.persistence.MemorySecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone's recogniser as the composer hears it: runs joined into one transcript in the order
 * they were spoken, final only once every run has settled, and the iPhone's grace for each backend.
 */
@RunWith(AndroidJUnit4::class)
class SystemSpeechRecognizerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Runs a test scripts: what each run heard, as Android's recogniser would report it. */
    private class ScriptedRuns : RecognitionRuns {
        var emit: ((RecognitionEvent) -> Unit)? = null
        var finished = false

        override fun start(onEvent: (RecognitionEvent) -> Unit) {
            emit = onEvent
        }

        override fun finish() {
            finished = true
        }

        override fun cancel() {
            emit = null
        }

        fun say(event: RecognitionEvent) = emit?.invoke(event)
    }

    private val granted = object : PermissionRequest {
        override fun isGranted() = true
        override suspend fun request() = true
    }

    private val refused = object : PermissionRequest {
        override fun isGranted() = false
        override suspend fun request() = false
    }

    @Test
    fun runsAreJoinedInTheOrderTheyWereSpoken() {
        val runs = ScriptedRuns()
        val recognizer = SystemSpeechRecognizer(context, "en-US", granted, runs)
        val heard = mutableListOf<SpeechInputEvent>()
        recognizer.start { heard += it }
        runs.say(RecognitionEvent.Began(0))
        runs.say(RecognitionEvent.Partial(0, "find the"))
        runs.say(RecognitionEvent.Final(0, "find the race"))
        runs.say(RecognitionEvent.Ended(0))
        runs.say(RecognitionEvent.Began(1))
        runs.say(RecognitionEvent.Partial(1, "and fix it"))
        runs.say(RecognitionEvent.Level(0.5))
        val transcripts = heard.filterIsInstance<SpeechInputEvent.Transcript>()
        assertEquals(listOf("find the", "find the race", "find the race", "find the race and fix it"), transcripts.map { it.text })
        assertTrue("nothing is final while listening", transcripts.none { it.isFinal })
        assertEquals(SpeechInputEvent.Level(0.5), heard.last())
    }

    @Test
    fun finishingWaitsForTheLastRunAndSaysTheFinalWordOnce() {
        val runs = ScriptedRuns()
        val recognizer = SystemSpeechRecognizer(context, "en-US", granted, runs)
        val heard = mutableListOf<SpeechInputEvent.Transcript>()
        recognizer.start { (it as? SpeechInputEvent.Transcript)?.let(heard::add) }
        runs.say(RecognitionEvent.Began(0))
        runs.say(RecognitionEvent.Partial(0, "ship it"))
        recognizer.finish()
        assertTrue(runs.finished)
        runs.say(RecognitionEvent.Final(0, "ship it now"))
        runs.say(RecognitionEvent.Ended(0))
        runs.say(RecognitionEvent.Finished)
        assertEquals(listOf(false, false, true), heard.map { it.isFinal })
        assertEquals("ship it now", heard.last().text)
    }

    @Test
    fun aFailureEndsDictationWithItsReason() {
        val runs = ScriptedRuns()
        val recognizer = SystemSpeechRecognizer(context, "en-US", granted, runs)
        val heard = mutableListOf<SpeechInputEvent>()
        recognizer.start { heard += it }
        runs.say(RecognitionEvent.Failed(SpeechInputFailure.Recognition))
        assertEquals(SpeechInputEvent.Failure(SpeechInputFailure.Recognition), heard.single())
    }

    @Test
    fun aRefusedMicrophoneIsTheFailureThatStopsIt() = runTest {
        val recognizer = SystemSpeechRecognizer(context, "en-US", refused, ScriptedRuns())
        val failure = runCatching { recognizer.requestPermission() }.exceptionOrNull()
        assertEquals(SpeechInputFailure.MicrophonePermission, failure)
    }

    @Test
    fun theFinishGraceBelongsToTheBackend() {
        assertEquals("on-device recognition answers within two seconds", 2.0,
                     SystemSpeechRecognizer(context, "en-US", granted, ScriptedRuns()).finishGracePeriod, 0.0)
        val client = GatewayHTTPClient(GatewayEndpoint("https://rc.example.com"), secrets = MemorySecretStore())
        val gateway = GatewaySpeechRecognizer(client, granted, capture = object : AudioCapture {
            override fun start(onChunk: (ByteArray, Double) -> Unit, onFailure: (SpeechInputFailure) -> Unit) {}
            override fun stop() {}
        })
        assertTrue("the gateway grace outlasts the socket's own deadline",
                   gateway.finishGracePeriod >= STTSocket.finalTimeout.inWholeSeconds)
        gateway.release()
    }
}
