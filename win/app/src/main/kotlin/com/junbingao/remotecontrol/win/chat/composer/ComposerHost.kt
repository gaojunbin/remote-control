package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.transport.PolishRequest
import com.junbingao.remotecontrol.core.transport.PolishStrength
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.app.agent
import com.junbingao.remotecontrol.win.app.device
import com.junbingao.remotecontrol.win.app.httpClient
import com.junbingao.remotecontrol.win.platform.MicRecorder
import com.junbingao.remotecontrol.win.voice.GatewaySpeechStream
import com.junbingao.remotecontrol.win.voice.ScriptedSpeech
import com.junbingao.remotecontrol.win.voice.SpeechServices
import kotlinx.coroutines.CoroutineScope

/** A29: the reader's own polish choices, where the gateway can polish at all: the switch on and a model chosen. */
data class PolishChoice(val model: String, val strength: PolishStrength)

/**
 * What the composer reads from the app around it — the props `ChatPage` hands `<Composer>` that are
 * not the conversation's own. The app's is the model; a test's is a stand-in.
 */
interface ComposerHost {
    fun agent(session: Session): AgentInfo?

    fun deviceOnline(deviceID: String): Boolean

    /** The gateway transcribes (`stt.enabled`); the mic is hidden when not. */
    val sttEnabled: Boolean
    val polishChoice: PolishChoice?
    val drafts: ComposerDrafts
    val speech: SpeechServices

    /**
     * Where what the person asked for runs — a send, a command, an attach, a dictation — on the
     * composer's thread and outliving the view, as the Mac starts it in an unstructured `Task`.
     */
    val tasks: CoroutineScope

    /** `POST /api/polish`: the words said cleanly. */
    suspend fun polish(request: PolishRequest): String
}

/**
 * The composer's host in the app: the app model, read live, so a change to a device, the gateway's
 * config or a setting reaches the composer at once.
 */
class AppComposerHost(private val model: WinAppModel, stage: ComposerStage?) : ComposerHost {
    override val drafts: ComposerDrafts = ComposerDrafts.of(model)
    override val tasks: CoroutineScope = model.tasks
    private val polishOverride: (suspend (PolishRequest) -> String)? = stage?.polish
    private val polishChoiceOverride: PolishChoice? = stage?.polishChoice

    override val speech: SpeechServices = when {
        stage != null -> stage.speech(model.tasks)
        // The offline demo has no gateway to transcribe for it, so it speaks the mock gateway's own
        // script and never opens the microphone, as the web against its mock does not need to.
        model.isDemo -> ScriptedSpeech.services(model.tasks)
        else -> gatewaySpeech(model)
    }

    override fun agent(session: Session): AgentInfo? = model.agent(session)

    override fun deviceOnline(deviceID: String): Boolean = model.device(deviceID)?.online ?: false

    override val sttEnabled: Boolean get() = model.connection.stt.enabled

    override val polishChoice: PolishChoice?
        get() {
            polishChoiceOverride?.let { return it }
            val settings = model.settings
            if (!model.connection.polish.enabled || !settings.polishEnabled || settings.polishModel.isEmpty()) return null
            return PolishChoice(model = settings.polishModel, strength = settings.polishStrength)
        }

    override suspend fun polish(request: PolishRequest): String {
        polishOverride?.let { return it(request) }
        val api = model.connection.api ?: throw TransportError.Unauthorized
        return api.polish(request).text
    }

    companion object {
        /**
         * The computer's microphone, and the gateway's `WS /ws/stt` through the core's socket. The
         * client is read when a dictation starts, so a gateway signed into later is the one that
         * transcribes.
         */
        fun gatewaySpeech(model: WinAppModel): SpeechServices = SpeechServices(
            recorder = { handlers -> MicRecorder(handlers) },
            socket = { onEvent -> GatewaySpeechStream(client = model.httpClient, onEvent = onEvent, tasks = model.tasks) },
        )
    }
}
