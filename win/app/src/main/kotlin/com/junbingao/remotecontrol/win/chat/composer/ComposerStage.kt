package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.transport.PolishRequest
import com.junbingao.remotecontrol.core.transport.PolishStrength
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.platform.RecorderError
import com.junbingao.remotecontrol.win.platform.RecorderHandlers
import com.junbingao.remotecontrol.win.platform.VoiceRecorder
import com.junbingao.remotecontrol.win.voice.ScriptedSpeech
import com.junbingao.remotecontrol.win.voice.SpeechServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * A preview's way into the composer's states that take a click, a file or a microphone
 * (`LocalPreviewStage`, `composer.<state>`), and null in the app. A stage never opens the
 * microphone: dictation is the scripted one, with a level and partials of its own, and a stage
 * about polish answers, waits or fails as the picture needs.
 */
class ComposerStage private constructor(val name: String) {
    /** A panel of the composer drawn open. */
    enum class Opening { upNext, modelCard, modelList, permissions, sendMenu }

    val opening: Opening?
        get() = when (name) {
            "upnext" -> Opening.upNext
            "model" -> Opening.modelCard
            "model-list" -> Opening.modelList
            "permissions" -> Opening.permissions
            "send-menu" -> Opening.sendMenu
            else -> null
        }

    /**
     * The script, sooner than the mock's two seconds, so a picture taken a second in has words in
     * it; and a microphone that fails where the picture is about a failure.
     */
    fun speech(tasks: CoroutineScope): SpeechServices {
        val scripted = ScriptedSpeech.services(tasks, partialEvery = 250.milliseconds, finishes = name != "finishing")
        if (name != "voice-error") return scripted
        return SpeechServices(recorder = { handlers -> RefusedRecorder(handlers) }, socket = scripted.socket)
    }

    /** A29: the polish stages turn polish on for the picture, whatever the account chose. */
    val polishChoice: PolishChoice?
        get() = if (name.startsWith("polish")) PolishChoice(model = "gpt-4.1-mini", strength = PolishStrength.moderate) else null

    /** Where the picture needs the model to wait or to fail rather than answer. */
    val polish: (suspend (PolishRequest) -> String)?
        get() = when (name) {
            "polishing" -> { _ -> awaitCancellation() }
            "polish-failed" -> { _ -> throw TransportError.RequestTimedOut }
            else -> null
        }

    /** What the stage does to the composer once it is on screen. */
    suspend fun run(on: ComposerModel) {
        when (name) {
            // Typed, as a person types: the field has the focus and the caret.
            "typed" -> typed("Rename the flaky test and run the suite again", on)
            "long" -> typed(longDraft, on)
            "attachments" -> attachFiles(on)
            "too-many" -> {
                attachFiles(on)
                on.attach((1..8).map { AttachmentSource.Data(name = "photo-$it.jpg", mime = "image/jpeg", data = ByteArray(1500 * it)) })
            }
            "commands" -> typed("/", on)
            "commands-query" -> typed("/re", on)
            "command-hint" -> typed("/review ", on)
            "answer" -> typed("Split it by tenant", on)
            "editing" -> on.chat.timeline.queue.firstOrNull { on.canEdit(it) }?.let { on.edit(it) }
            "listening" -> on.startVoice()
            "finishing", "polishing", "polished", "polish-failed" -> {
                on.startVoice()
                delay(700.milliseconds)
                on.voice.done()
            }
            "voice-error" -> on.startVoice()
        }
    }

    private fun typed(words: String, composer: ComposerModel) {
        composer.userTyped(words)
        composer.requestFocus()
    }

    private fun attachFiles(composer: ComposerModel) {
        typed("Here are the screenshots", composer)
        composer.host.drafts.add(
            listOf(
                ComposerAttachment(name = "screenshot-1.png", mime = "image/png", data = ByteArray(24_000)),
                ComposerAttachment(name = "notes.txt", mime = "text/plain", data = "hello notes".toByteArray()),
            ),
            to = composer.key,
        )
    }

    companion object {
        operator fun invoke(stage: String?): ComposerStage? {
            if (stage == null || !stage.startsWith("composer.")) return null
            return ComposerStage(stage.removePrefix("composer."))
        }

        private val longDraft: String get() = (1..14).joinToString("\n") { "Line $it of a long draft that keeps going" }
    }
}

/** A microphone the system refused, for the picture of what that says. */
private class RefusedRecorder(private val handlers: RecorderHandlers) : VoiceRecorder {
    override suspend fun start(): Boolean {
        handlers.onError(RecorderError.denied)
        return false
    }

    override suspend fun stop() {}
}
