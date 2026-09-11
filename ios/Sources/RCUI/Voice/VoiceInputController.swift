import Foundation
import Observation
import RCCore

public enum SpeechInputFailure: Error, Sendable {
    case unsupported, speechPermission, microphonePermission, unavailable, recording, recognition, interrupted
}

public enum SpeechInputEvent: Sendable {
    case transcript(String, isFinal: Bool)
    case level(Double)
    case failure(SpeechInputFailure)
}

@MainActor public protocol SpeechInputPlatform: AnyObject {
    /// How long this backend may take to deliver a final transcript after
    /// `finish()`. On-device recognition answers almost at once; the gateway
    /// has to transcribe up to two minutes of audio.
    var finishGracePeriod: TimeInterval { get }
    func requestPermission() async throws
    func start(onEvent: @escaping @Sendable (SpeechInputEvent) -> Void) throws
    func finish()
    func cancel()
}

extension SpeechInputPlatform {
    public var finishGracePeriod: TimeInterval { 2 }
}

@MainActor @Observable public final class VoiceInputController {
    /// Listening has no deadline. It ends when the user taps Cancel or Done,
    /// when the scene leaves the foreground, or when the backend fails; a
    /// backend whose own request expires rolls over to a new one underneath,
    /// so a long dictation is never cut off from here.
    public static let listeningDeadline: TimeInterval? = nil

    public private(set) var phase: VoiceInputPhase = .idle
    public var transcript = ""
    public private(set) var inputLevel = 0.0
    public private(set) var failure: SpeechInputFailure?
    @ObservationIgnored private let platform: any SpeechInputPlatform
    @ObservationIgnored private var runID = UUID()
    @ObservationIgnored private var sceneIsActive = true
    @ObservationIgnored private var authorizedRun: UUID?
    /// The only timer this controller owns: how long a backend may take to
    /// answer `finish()`. Nothing arms it while listening.
    @ObservationIgnored private var finalTranscriptTimeout: Task<Void, Never>?
    @ObservationIgnored private var permissionTask: Task<Void, Never>?

    public init(platform: any SpeechInputPlatform) {
        self.platform = platform
    }

    /// True only between Done and the backend's last word.
    public var isAwaitingFinalTranscript: Bool { finalTranscriptTimeout != nil }

    public func start() {
        guard !phase.isBusy, sceneIsActive else { return }
        platform.cancel(); clearTimeout(); permissionTask?.cancel()
        runID = UUID(); let run = runID
        authorizedRun = nil
        transcript = ""; failure = nil; inputLevel = 0; phase = .requestingPermission
        permissionTask = Task { [weak self] in
            guard let self else { return }
            do {
                try await platform.requestPermission()
                guard !Task.isCancelled, run == runID else { return }
                authorizedRun = run
                if sceneIsActive { beginCapture(run: run) }
            } catch {
                guard !Task.isCancelled, run == runID else { return }
                fail(error as? SpeechInputFailure ?? .recording)
            }
        }
    }

    /// Stop listening and keep the transcript. Sending stays a separate tap.
    public func finish() {
        guard phase == .listening else { return }
        phase = .finishing; inputLevel = 0; clearTimeout()
        platform.finish()
        let run = runID
        let grace = platform.finishGracePeriod
        finalTranscriptTimeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(grace))
            guard !Task.isCancelled, let self, self.runID == run else { return }
            // The backend never answered. Keep what was recognised rather than
            // discarding the utterance.
            self.complete()
        }
    }

    public func cancel() {
        runID = UUID(); permissionTask?.cancel(); permissionTask = nil
        authorizedRun = nil
        clearTimeout(); platform.cancel()
        phase = .idle; transcript = ""; failure = nil; inputLevel = 0
    }

    /// Drop a failure message once it has been read. The transcript gathered
    /// before the failure is already in the draft and is left alone.
    public func dismissFailure() {
        guard phase == .failed else { return }
        failure = nil; phase = transcript.isEmpty ? .idle : .review
    }

    public func setSceneActive(_ active: Bool, cancelAuthorization: Bool = false) {
        sceneIsActive = active
        if !active && (cancelAuthorization || phase != .requestingPermission) { suspend() }
        if active, let run = authorizedRun { beginCapture(run: run) }
    }

    public func suspend() {
        guard phase.isBusy else { return }
        runID = UUID(); permissionTask?.cancel(); clearTimeout(); platform.cancel()
        authorizedRun = nil
        inputLevel = 0; phase = transcript.isEmpty ? .idle : .review
    }

    private func beginCapture(run: UUID) {
        guard run == runID, authorizedRun == run, sceneIsActive, phase == .requestingPermission else { return }
        authorizedRun = nil
        do {
            try platform.start { [weak self] event in
                Task { @MainActor [weak self] in self?.receive(event, run: run) }
            }
            phase = .listening
        } catch {
            fail(error as? SpeechInputFailure ?? .recording)
        }
    }

    private func receive(_ event: SpeechInputEvent, run: UUID) {
        guard runID == run, phase == .listening || phase == .finishing else { return }
        switch event {
        case .transcript(let text, let isFinal):
            transcript = text
            if isFinal { complete() }
        case .level(let level): if phase == .listening { inputLevel = level.isFinite ? min(1, max(0, level)) : 0 }
        case .failure(let failure): fail(failure)
        }
    }

    private func complete() {
        authorizedRun = nil
        runID = UUID(); clearTimeout(); platform.cancel(); inputLevel = 0
        phase = .review
    }

    /// A failed run keeps whatever was recognised before it broke: the words
    /// are already in the draft, and losing them helps no one.
    private func fail(_ error: SpeechInputFailure) {
        authorizedRun = nil
        runID = UUID(); clearTimeout(); platform.cancel(); inputLevel = 0
        failure = error; phase = .failed
    }

    private func clearTimeout() {
        finalTranscriptTimeout?.cancel()
        finalTranscriptTimeout = nil
    }
}
