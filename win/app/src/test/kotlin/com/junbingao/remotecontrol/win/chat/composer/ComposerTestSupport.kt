package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.QuestionItem
import com.junbingao.remotecontrol.core.protocol.QuestionPayload
import com.junbingao.remotecontrol.core.protocol.QueuePayload
import com.junbingao.remotecontrol.core.protocol.QueuedMessage
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendResult
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionResult
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.GatewayChannel
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import com.junbingao.remotecontrol.core.transport.PolishRequest
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.platform.RecorderHandlers
import com.junbingao.remotecontrol.win.platform.VoiceRecorder
import com.junbingao.remotecontrol.win.voice.SpeechEvent
import com.junbingao.remotecontrol.win.voice.SpeechServices
import com.junbingao.remotecontrol.win.voice.SpeechStream
import com.junbingao.remotecontrol.win.voice.VoiceTiming
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds

// The Mac's `ComposerTestSupport.swift`, on the test's own clock: every composer, store and
// dictation of a test runs on its `TestScope`, so a wait is virtual and nothing races.

/** A gateway that answers each request type the way a test says, and keeps every request it was asked. */
class ComposerChannel(session: Session? = null) : GatewayChannel {
    override val events: Flow<GatewayEvent> = emptyFlow()
    private val answers = mutableMapOf<String, suspend (GatewayRequest) -> JsonElement>()
    val requests = mutableListOf<GatewayRequest>()

    init {
        answers["session.send"] = { JSONValue.encode(SendResult(accepted = SendAcceptance.sent)) }
        if (session != null) answers["session.set"] = { JSONValue.encode(SessionResult(session = session)) }
    }

    override suspend fun connect() {}

    override suspend fun disconnect() {}

    fun answer(type: String, with: suspend (GatewayRequest) -> JsonElement) {
        answers[type] = with
    }

    override suspend fun request(request: GatewayRequest): JsonElement {
        requests += request
        val answer = answers[request.type] ?: return JSONValue.emptyObject
        return answer(request)
    }

    fun sent(type: String): List<GatewayRequest> = requests.filter { it.type == type }
}

/** A request's body field, as the Mac's tests read `request["key"]`. */
operator fun GatewayRequest.get(key: String): JsonElement? = body[key]

/** A composer on a scripted gateway, with the host and the speech a test drives. */
class ComposerHarness(
    val scope: TestScope,
    session: Session = ComposerFixture.session(),
    agent: AgentInfo? = DemoFixtures.claude,
) {
    val channel = ComposerChannel(session)
    val chat = ComposerFixture.chat(session, channel, scope.backgroundScope, agent)
    val speech = FakeSpeech()
    val host = FakeComposerHost(agent, speech.services, scope.backgroundScope)
    val composer = ComposerModel(chat, host, timing = VoiceTiming.fast, reading = EmptyCoroutineContext,
                                 clock = scope.testScheduler.timeSource)
}

/** A door a scripted answer waits behind, so a test can look at a request while it is still out. */
class ComposerGate {
    private val open = CompletableDeferred<Unit>()

    suspend fun await() = open.await()

    fun release() {
        open.complete(Unit)
    }
}

/** The app around a composer, as a test sets it. */
class FakeComposerHost(var agentInfo: AgentInfo?, override var speech: SpeechServices, override val tasks: CoroutineScope) : ComposerHost {
    var online = true
    override var sttEnabled = true
    override var polishChoice: PolishChoice? = null
    override val drafts = ComposerDrafts()
    var polishAnswer: suspend (PolishRequest) -> String = { it.text }
    val polishRequests = mutableListOf<PolishRequest>()

    override fun agent(session: Session): AgentInfo? = agentInfo

    override fun deviceOnline(deviceID: String): Boolean = online

    override suspend fun polish(request: PolishRequest): String {
        polishRequests += request
        return polishAnswer(request)
    }
}

/** A microphone and sockets a test drives by hand, as `web/tests/voice.test.ts` drives its `FakeSocket`. */
class FakeSpeech {
    class Socket(private val onEvent: (SpeechEvent) -> Unit) : SpeechStream {
        val frames = mutableListOf<ByteArray>()
        var toldToTranscribe = false
        var toldToDrop = false
        var refuses = false

        override suspend fun start() {
            if (refuses) throw TransportError.Unauthorized
        }

        override fun append(frame: ByteArray) {
            frames += frame
        }

        override fun stop() {
            toldToTranscribe = true
        }

        override fun cancel() {
            toldToDrop = true
        }

        fun deliver(event: SpeechEvent) = onEvent(event)
    }

    class Recorder(val handlers: RecorderHandlers) : VoiceRecorder {
        var starts = true
        var stopped = false

        override suspend fun start(): Boolean = starts

        override suspend fun stop() {
            stopped = true
        }
    }

    val sockets = mutableListOf<Socket>()
    val recorders = mutableListOf<Recorder>()
    var socketsRefuse = false
    var recorderStarts = true

    /** The controller keeps these for its life, and with them this: a dictation left running past the end of a test still finds its speech. */
    val services: SpeechServices
        get() = SpeechServices(
            recorder = { handlers -> Recorder(handlers).also { it.starts = recorderStarts; recorders += it } },
            socket = { onEvent -> Socket(onEvent).also { it.refuses = socketsRefuse; sockets += it } },
        )

    /** The speaker's level, as the microphone reports it. */
    fun speak(level: Double) {
        recorders.lastOrNull()?.handlers?.onLevel?.invoke(level)
    }
}

/** The timings of `useVoice`, a hundred times shorter. */
val VoiceTiming.Companion.fast: VoiceTiming
    get() = VoiceTiming(segment = 300.milliseconds, segmentLimit = 1300.milliseconds, finalTimeout = 300.milliseconds, poll = 5.milliseconds)

/** Wait for a condition the test's scheduler will reach, for at most two virtual seconds. */
fun TestScope.eventually(condition: () -> Boolean): Boolean {
    repeat(400) {
        runCurrent()
        if (condition()) return true
        advanceTimeBy(5)
    }
    runCurrent()
    return condition()
}

/** Let `ms` virtual milliseconds pass, and everything due in them run. */
fun TestScope.pass(ms: Long) {
    advanceTimeBy(ms)
    runCurrent()
}

/** A session of the shapes the composer meets, and the conversation around it. */
object ComposerFixture {
    fun session(agent: String = "claude", state: SessionState = SessionState.idle, control: SessionControl = SessionControl.remote): Session =
        Session(sessionID = "ses-1", deviceID = "dev-1", agent = agent, title = "Fix the flaky test", cwd = "/Users/me/dev",
                state = state, control = control, model = "claude-sonnet-4-5", permissionMode = "acceptEdits", effort = "high")

    fun chat(session: Session, channel: ComposerChannel, tasks: CoroutineScope, agent: AgentInfo? = DemoFixtures.claude): ChatStore =
        ChatStore(session = session, channel = channel, tasks = tasks).also { it.agent = agent }

    /** The device's queue snapshot, as a live frame brings it. */
    fun queue(entries: List<QueuedMessage>, chat: ChatStore, seq: Int = 1) {
        chat.receive(AppFrame.SessionEvent(sessionID = chat.sessionID, deviceID = chat.deviceID,
                                           event = SessionEvent(seq = seq, ts = 1, kind = SessionEvent.queueKind,
                                                                body = SessionEventBody.Queue(QueuePayload(pending = entries)))))
    }

    /** A question the session waits on, as a live frame brings it. */
    fun question(questions: List<QuestionItem>, chat: ChatStore, seq: Int = 1) {
        chat.receive(AppFrame.SessionEvent(sessionID = chat.sessionID, deviceID = chat.deviceID,
                                           event = SessionEvent(seq = seq, ts = 1, kind = SessionEvent.questionKind, blockID = "q-1",
                                                                body = SessionEventBody.Question(QuestionPayload(requestID = "req-q", questions = questions)))))
    }

    fun refusal(code: GatewayErrorCode, message: String): GatewayErrorBody = GatewayErrorBody(code = code, message = message)
}
