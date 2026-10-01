package com.junbingao.remotecontrol.android.screens.chat.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.android.permissions.PermissionRequest
import com.junbingao.remotecontrol.android.shell.AppModelHarness
import com.junbingao.remotecontrol.android.voice.SystemSpeechRecognizer
import com.junbingao.remotecontrol.core.state.VoiceBackend
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ios/VerificationUI` → "Speech backend selection": the scripted platform only behind an explicit
 * launch argument in a debug build, the phone's recogniser wherever the gateway cannot transcribe,
 * and the long dictation the field-following test needs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ChatSpeechBackendTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val microphone = object : PermissionRequest {
        override fun isGranted() = false
        override suspend fun request() = false
    }

    @Test
    fun aShippingPathNeverSelectsTheScriptedPlatform() = runTest {
        val model = AppModelHarness(context, Files.createTempDirectory("chat-backend").toFile()).model(this)
        model.settings.voiceBackend = VoiceBackend.gateway
        val plain = SpeechBackend.make(model.settings, model.connection, LaunchOptions(emptyList(), debug = true), context, microphone, backgroundScope)
        assertFalse("a shipping path never selects the scripted platform", plain.isScripted)
        // No gateway client to stream to: the phone's own recogniser listens instead.
        assertTrue(plain.platform is SystemSpeechRecognizer)
        val release = SpeechBackend.make(model.settings, model.connection, LaunchOptions(listOf("--voice-preview"), debug = false), context, microphone, backgroundScope)
        assertFalse("release builds have no scripted speech platform", release.isScripted)
    }

    @Test
    fun theScriptedPlatformNeedsAnExplicitLaunchArgument() = runTest {
        val model = AppModelHarness(context, Files.createTempDirectory("chat-backend").toFile()).model(this)
        val scripted = SpeechBackend.make(model.settings, model.connection, LaunchOptions(listOf("--voice-preview"), debug = true), context, microphone, backgroundScope)
        assertTrue("the scripted platform needs an explicit launch argument", scripted.isScripted)
        val controller = VoiceInputController(scripted.platform, backgroundScope)
        controller.start()
        runCurrent()
        assertEquals("the short sentence, fillers and all", ScriptedSpeechInput.shortTranscript, controller.transcript)
        assertEquals("at ordinary speech", ScriptedSpeechInput.defaultLevel, controller.inputLevel, 0.0001)
        controller.cancel()
    }

    @Test
    fun theLevelAndTheLongDictationAreTheLaunchsToChoose() = runTest {
        val options = LaunchOptions(listOf("--voice-preview", "--voice-level=0.5", "--voice-transcript=long"), debug = true)
        val controller = VoiceInputController(SpeechBackend.scriptedPlatform(options, backgroundScope), backgroundScope)
        controller.start()
        runCurrent()
        assertEquals("`--voice-level=` holds the platform at one level", 0.5, controller.inputLevel, 0.0001)
        assertTrue("`--voice-transcript=long` starts on a part of the long dictation", controller.transcript.length < ScriptedSpeechInput.longTranscript.length)
        assertTrue(ScriptedSpeechInput.longTranscript.startsWith(controller.transcript))
        controller.cancel()
    }
}
