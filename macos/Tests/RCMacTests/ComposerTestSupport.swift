import Foundation
import RCCore
@testable import RCMac

/// A gateway that answers each request type the way a test says, and keeps
/// every request it was asked.
actor ComposerChannel: GatewayChannel {
    typealias Answer = @Sendable (GatewayRequest) async throws -> JSONValue

    nonisolated let events: AsyncStream<GatewayEvent> = AsyncStream { _ in }
    private var answers: [String: Answer] = [:]
    private(set) var requests: [GatewayRequest] = []

    init(session: Session? = nil) {
        answers["session.send"] = { _ in try JSONValue.encode(SendResult(accepted: .sent)) }
        if let session {
            answers["session.set"] = { _ in try JSONValue.encode(SessionResult(session: session)) }
        }
    }

    func connect() async {}
    func disconnect() async {}

    func answer(_ type: String, with answer: @escaping Answer) { answers[type] = answer }

    func request(_ request: GatewayRequest) async throws -> JSONValue {
        requests.append(request)
        guard let answer = answers[request.type] else { return .object([:]) }
        return try await answer(request)
    }

    func sent(_ type: String) -> [GatewayRequest] { requests.filter { $0.type == type } }

    /// The requests of one type once there are `count` of them, or what there
    /// is after two seconds.
    func waitFor(_ type: String, count: Int = 1) async -> [GatewayRequest] {
        for _ in 0..<400 {
            if sent(type).count >= count { return sent(type) }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return sent(type)
    }
}

extension GatewayRequest {
    subscript(_ key: String) -> JSONValue? { body[key] }
}

/// A composer on a scripted gateway, with the host and the speech a test drives.
@MainActor
struct ComposerHarness {
    let channel: ComposerChannel
    let chat: ChatStore
    let speech = FakeSpeech()
    let host: FakeComposerHost
    let composer: ComposerModel

    init(session: Session = ComposerFixture.session(), agent: AgentInfo? = DemoFixtures.claude) {
        channel = ComposerChannel(session: session)
        chat = ComposerFixture.chat(session, channel: channel, agent: agent)
        host = FakeComposerHost(agent: agent, speech: speech.services)
        composer = ComposerModel(chat: chat, host: host, timing: .fast)
    }
}

/// A door a scripted answer waits behind, so a test can look at a request
/// while it is still out.
actor ComposerGate {
    private var open = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if open { return }
        await withCheckedContinuation { waiting.append($0) }
    }

    func release() {
        open = true
        for continuation in waiting { continuation.resume() }
        waiting = []
    }
}

/// The app around a composer, as a test sets it.
@MainActor
final class FakeComposerHost: ComposerHost {
    var agentInfo: AgentInfo?
    var online = true
    var sttEnabled = true
    var polishChoice: PolishChoice?
    let drafts = ComposerDrafts()
    var speech: SpeechServices
    var polishAnswer: @MainActor (PolishRequest) async throws -> String = { $0.text }
    private(set) var polishRequests: [PolishRequest] = []

    init(agent: AgentInfo? = DemoFixtures.claude, speech: SpeechServices = FakeSpeech().services) {
        agentInfo = agent
        self.speech = speech
    }

    func agent(for session: Session) -> AgentInfo? { agentInfo }
    func deviceOnline(_ deviceID: String) -> Bool { online }

    func polish(_ request: PolishRequest) async throws -> String {
        polishRequests.append(request)
        return try await polishAnswer(request)
    }
}

/// A microphone and sockets a test drives by hand, as `web/tests/voice.test.ts`
/// drives its `FakeSocket`.
@MainActor
final class FakeSpeech {
    final class Socket: SpeechStream {
        let onEvent: @MainActor (SpeechEvent) -> Void
        var frames: [Data] = []
        var toldToTranscribe = false
        var toldToDrop = false
        var refuses = false

        init(onEvent: @escaping @MainActor (SpeechEvent) -> Void) { self.onEvent = onEvent }

        func start() async throws { if refuses { throw TransportError.unauthorized } }
        func append(_ frame: Data) { frames.append(frame) }
        func stop() { toldToTranscribe = true }
        func cancel() { toldToDrop = true }
        func deliver(_ event: SpeechEvent) { onEvent(event) }
    }

    final class Recorder: VoiceRecorder {
        let handlers: RecorderHandlers
        var starts = true
        var stopped = false

        init(handlers: RecorderHandlers) { self.handlers = handlers }

        func start() async -> Bool { starts }
        func stop() async { stopped = true }
    }

    private(set) var sockets: [Socket] = []
    private(set) var recorders: [Recorder] = []
    var socketsRefuse = false
    var recorderStarts = true

    /// The controller keeps these for its life, and with them this: a
    /// dictation left running past the end of a test still finds its speech.
    var services: SpeechServices {
        SpeechServices(
            recorder: { [self] handlers in
                let recorder = Recorder(handlers: handlers)
                recorder.starts = recorderStarts
                recorders.append(recorder)
                return recorder
            },
            socket: { [self] onEvent in
                let socket = Socket(onEvent: onEvent)
                socket.refuses = socketsRefuse
                sockets.append(socket)
                return socket
            })
    }

    /// The speaker's level, as the microphone reports it.
    func speak(at level: Double) { recorders.last?.handlers.onLevel(level) }
}

/// The timings of `useVoice`, a hundred times shorter.
extension VoiceTiming {
    static let fast = VoiceTiming(segment: .milliseconds(300), segmentLimit: .milliseconds(1300),
                                  finalTimeout: .milliseconds(300), poll: .milliseconds(5))
}

/// Wait for a condition the main actor will reach, for at most two seconds.
@MainActor
func composerEventually(_ condition: () -> Bool) async -> Bool {
    for _ in 0..<400 {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return condition()
}

/// A session of the shapes the composer meets, and the conversation around it.
@MainActor
enum ComposerFixture {
    static func session(agent: String = "claude", state: SessionState = .idle,
                        control: SessionControl = .remote) -> Session {
        Session(sessionID: "ses-1", deviceID: "dev-1", agent: agent, title: "Fix the flaky test",
                cwd: "/Users/me/dev", state: state, control: control,
                model: "claude-sonnet-4-5", permissionMode: "acceptEdits", effort: "high")
    }

    static func chat(_ session: Session, channel: ComposerChannel, agent: AgentInfo? = DemoFixtures.claude) -> ChatStore {
        let chat = ChatStore(session: session, channel: channel)
        chat.agent = agent
        return chat
    }

    /// The device's queue snapshot, as a live frame brings it.
    static func queue(_ entries: [QueuedMessage], into chat: ChatStore, seq: Int = 1) {
        chat.receive(.sessionEvent(sessionID: chat.sessionID, deviceID: chat.deviceID,
                                   event: SessionEvent(seq: seq, ts: 1, kind: SessionEvent.queueKind,
                                                       body: .queue(QueuePayload(pending: entries)))))
    }

    /// A question the session waits on, as a live frame brings it.
    static func question(_ questions: [QuestionItem], into chat: ChatStore, seq: Int = 1) {
        chat.receive(.sessionEvent(sessionID: chat.sessionID, deviceID: chat.deviceID,
                                   event: SessionEvent(seq: seq, ts: 1, kind: SessionEvent.questionKind,
                                                       blockID: "q-1",
                                                       body: .question(QuestionPayload(requestID: "req-q",
                                                                                       questions: questions)))))
    }

    nonisolated static func refusal(_ code: GatewayErrorCode, _ message: String) -> GatewayErrorBody {
        GatewayErrorBody(code: code, message: message)
    }
}
