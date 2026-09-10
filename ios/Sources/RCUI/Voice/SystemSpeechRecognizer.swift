import Foundation
import RCCore
#if os(iOS)
@preconcurrency import AVFoundation
@preconcurrency import Speech

/// On-device dictation. Audio never leaves the phone and is never written to
/// disk; the transcript becomes an editable draft and never submits itself.
@MainActor public final class SystemSpeechRecognizer: SpeechInputPlatform {
    private let localeIdentifier: String
    public init(localeIdentifier: String) { self.localeIdentifier = localeIdentifier }

    private var engine: AVAudioEngine?
    private var input: AVAudioInputNode?
    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var interruption: NSObjectProtocol?
    private var routeChange: NSObjectProtocol?
    private var hasTap = false
    private var ownsAudioSession = false
    private var audioEnded = false

    public func requestPermission() async throws {
        let speech = await Self.speechAuthorization()
        guard speech == .authorized else { throw SpeechInputFailure.speechPermission }
        guard !Task.isCancelled else { throw CancellationError() }
        let microphone = await AVAudioApplication.requestRecordPermission()
        guard microphone else { throw SpeechInputFailure.microphonePermission }
    }

    public func start(onEvent: @escaping @Sendable (SpeechInputEvent) -> Void) throws {
        cancel()
        // On-device recognition is the promise this backend makes; without a
        // local model the caller must fall back to the gateway instead.
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: localeIdentifier)),
              recognizer.supportsOnDeviceRecognition else {
            throw SpeechInputFailure.unsupported
        }
        guard recognizer.isAvailable else { throw SpeechInputFailure.unavailable }
        let audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession.setCategory(.record, mode: .measurement, options: [.duckOthers])
            try audioSession.setActive(true)
            ownsAudioSession = true
            guard audioSession.isInputAvailable else { throw SpeechInputFailure.recording }
            let engine = AVAudioEngine()
            let request = SFSpeechAudioBufferRecognitionRequest()
            request.requiresOnDeviceRecognition = true
            request.shouldReportPartialResults = true
            request.addsPunctuation = true
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate.isFinite, format.sampleRate > 0, format.channelCount > 0 else { throw SpeechInputFailure.recording }
            self.engine = engine; self.input = input; self.request = request; self.recognizer = recognizer
            audioEnded = false
            // Let the input node supply its current hardware format. Forcing a
            // previously read format can conflict with an audio route change.
            input.installTap(onBus: 0, bufferSize: 1024, format: nil, block: Self.audioTap(request: request, onEvent: onEvent))
            hasTap = true
            task = recognizer.recognitionTask(with: request, resultHandler: Self.recognitionHandler(onEvent: onEvent))
            interruption = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: audioSession,
                queue: .main, using: Self.interruptionHandler(onEvent: onEvent))
            routeChange = NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: audioSession,
                queue: .main, using: Self.routeChangeHandler(onEvent: onEvent))
            engine.prepare()
            try engine.start()
        } catch {
            cancel()
            throw (error as? SpeechInputFailure) ?? .recording
        }
    }

    public func finish() {
        stopCapture()
        endAudio()
    }

    public func cancel() {
        stopCapture()
        endAudio()
        task?.cancel(); task = nil; request = nil; engine = nil; input = nil; recognizer = nil
    }

    private func stopCapture() {
        if let interruption { NotificationCenter.default.removeObserver(interruption); self.interruption = nil }
        if let routeChange { NotificationCenter.default.removeObserver(routeChange); self.routeChange = nil }
        engine?.stop()
        if hasTap { input?.removeTap(onBus: 0); hasTap = false }
        if ownsAudioSession {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            ownsAudioSession = false
        }
    }

    private func endAudio() {
        guard !audioEnded, let request else { return }
        audioEnded = true
        request.endAudio()
    }

    // These Objective-C callbacks may run on framework-owned threads. Creating
    // them inside a MainActor method silently adds Swift 6 executor assertions;
    // factory isolation must be explicit even when a callback captures no self.
    nonisolated private static func speechAuthorization() async -> SFSpeechRecognizerAuthorizationStatus {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
        }
    }

    nonisolated private static func audioTap(request: SFSpeechAudioBufferRecognitionRequest,
                                             onEvent: @escaping @Sendable (SpeechInputEvent) -> Void) -> AVAudioNodeTapBlock {
        { buffer, _ in
            request.append(buffer)
            guard let samples = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return }
            let count = Int(buffer.frameLength)
            var sum: Float = 0
            for index in 0..<count { sum += samples[index] * samples[index] }
            let rms = sqrt(Double(sum) / Double(count))
            let normalized = max(0, min(1, (20 * log10(max(rms, 0.0001)) + 55) / 55))
            onEvent(.level(normalized))
        }
    }

    nonisolated private static func recognitionHandler(onEvent: @escaping @Sendable (SpeechInputEvent) -> Void)
        -> (SFSpeechRecognitionResult?, Error?) -> Void {
        { result, error in
            if let result { onEvent(.transcript(result.bestTranscription.formattedString, isFinal: result.isFinal)) }
            if error != nil, result?.isFinal != true { onEvent(.failure(.recognition)) }
        }
    }

    nonisolated private static func interruptionHandler(onEvent: @escaping @Sendable (SpeechInputEvent) -> Void)
        -> @Sendable (Notification) -> Void {
        { notification in
            if let value = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
               value == AVAudioSession.InterruptionType.began.rawValue { onEvent(.failure(.interrupted)) }
        }
    }

    nonisolated private static func routeChangeHandler(onEvent: @escaping @Sendable (SpeechInputEvent) -> Void)
        -> @Sendable (Notification) -> Void {
        { notification in
            if let reason = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
               reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue { onEvent(.failure(.interrupted)) }
        }
    }
}
#else
/// The preview host has no speech framework; dictation reports as unsupported.
@MainActor public final class SystemSpeechRecognizer: SpeechInputPlatform {
    public init(localeIdentifier: String) {}
    public func requestPermission() async throws { throw SpeechInputFailure.unsupported }
    public func start(onEvent: @escaping @Sendable (SpeechInputEvent) -> Void) throws {
        throw SpeechInputFailure.unsupported
    }
    public func finish() {}
    public func cancel() {}
}
#endif
