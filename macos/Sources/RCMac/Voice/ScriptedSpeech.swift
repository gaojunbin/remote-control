import Foundation

/// Dictation with neither a microphone nor a gateway: the offline demo's, and
/// a preview's, which must never ask for the microphone. The words are the web
/// mock's own script (`web/mock/server.ts`, `WS /ws/stt`) — three partials that
/// grow a sentence, and a final transcript with the fillers and stammers
/// spoken words carry, so polish has something to do.
enum ScriptedSpeech {
    static let partials = [
        "also add a retry",
        "also add a retry to the token refresh path",
        "also add a retry to the token refresh path and re-run the suite"
    ]
    static let final =
        "um so also add a retry to the the token refresh path and re-run the suite on on the CI runner too"

    /// The mock sends a partial every two seconds; a preview asks for them
    /// sooner, so a picture taken a second in has words in it, and can ask for
    /// a final transcript that never comes, to show the wait for it.
    static func services(partialEvery interval: Duration = .seconds(2), finishes: Bool = true) -> SpeechServices {
        SpeechServices(recorder: { handlers in ScriptedRecorder(handlers: handlers) },
                       socket: { onEvent in ScriptedSocket(interval: interval, finishes: finishes, onEvent: onEvent) })
    }
}

/// A microphone that hears someone talking: a level that rises and falls.
@MainActor
final class ScriptedRecorder: VoiceRecorder {
    private let handlers: RecorderHandlers
    private var talking: Task<Void, Never>?

    init(handlers: RecorderHandlers) { self.handlers = handlers }

    func start() async -> Bool {
        let onLevel = handlers.onLevel
        talking = Task {
            var step = 0.0
            while !Task.isCancelled {
                onLevel(0.45 + 0.35 * sin(step) * cos(step * 0.37))
                step += 0.9
                try? await Task.sleep(for: .milliseconds(90))
            }
        }
        return true
    }

    func stop() async {
        talking?.cancel()
        talking = nil
    }
}

/// A socket that transcribes the script, whatever it is sent.
@MainActor
final class ScriptedSocket: SpeechStream {
    private let interval: Duration
    private let finishes: Bool
    private let onEvent: @MainActor (SpeechEvent) -> Void
    private var speaking: Task<Void, Never>?
    private var finishing: Task<Void, Never>?

    init(interval: Duration, finishes: Bool, onEvent: @escaping @MainActor (SpeechEvent) -> Void) {
        self.interval = interval
        self.finishes = finishes
        self.onEvent = onEvent
    }

    func start() async throws {
        let interval = interval
        speaking = Task { [weak self] in
            for text in ScriptedSpeech.partials {
                try? await Task.sleep(for: interval)
                guard !Task.isCancelled else { return }
                self?.onEvent(.partial(text))
            }
        }
    }

    func append(_ frame: Data) {}

    func stop() {
        speaking?.cancel()
        guard finishes else { return }
        finishing = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            self?.onEvent(.final(ScriptedSpeech.final))
            self?.onEvent(.closed)
        }
    }

    func cancel() {
        speaking?.cancel()
        finishing?.cancel()
    }
}
