import Foundation
import RCCore
#if os(iOS)
@preconcurrency import AVFoundation
@preconcurrency import Speech

/// On-device dictation. Audio never leaves the phone and is never written to
/// disk; the transcript becomes an editable draft and never submits itself.
///
/// One `SFSpeechAudioBufferRecognitionRequest` does not last: a request stops
/// on its own after roughly a minute. Dictation here has no maximum duration,
/// so the microphone and its tap stay up for the whole session while the
/// requests underneath are rolled over. The replacement is already taking audio
/// when the outgoing request is told to flush, so the seam drops nothing, and
/// each request's transcript keeps its own slot in `TranscriptSegments` — a new
/// segment's first partial often arrives before the old segment's final.
@MainActor public final class SystemSpeechRecognizer: SpeechInputPlatform {
    /// Comfortably inside the recognizer's own limit, so a roll is planned
    /// rather than a recovery from a request that expired mid-sentence.
    static let segmentDuration: TimeInterval = 50
    /// A request that fails this soon after starting is broken rather than
    /// merely out of speech to hear.
    private static let prematureFailure: TimeInterval = 5
    private static let prematureFailureLimit = 3

    private let localeIdentifier: String
    public init(localeIdentifier: String) { self.localeIdentifier = localeIdentifier }

    private let route = RecognitionRoute()
    private var engine: AVAudioEngine?
    private var input: AVAudioInputNode?
    private var recognizer: SFSpeechRecognizer?
    private var tasks: [SFSpeechRecognitionTask] = []
    private var segments = TranscriptSegments()
    private var rollover: Task<Void, Never>?
    private var emit: (@Sendable (SpeechInputEvent) -> Void)?
    private var interruption: NSObjectProtocol?
    private var routeChange: NSObjectProtocol?
    private var hasTap = false
    private var ownsAudioSession = false
    private var isFinishing = false
    private var segmentStarted = Date()
    private var prematureFailures = 0

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
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate.isFinite, format.sampleRate > 0, format.channelCount > 0 else { throw SpeechInputFailure.recording }
            self.engine = engine; self.input = input; self.recognizer = recognizer
            emit = onEvent
            segments = TranscriptSegments()
            isFinishing = false
            prematureFailures = 0
            openSegment()
            // Let the input node supply its current hardware format. Forcing a
            // previously read format can conflict with an audio route change.
            input.installTap(onBus: 0, bufferSize: 1024, format: nil, block: Self.audioTap(route: route, onEvent: onEvent))
            hasTap = true
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
        guard !isFinishing else { return }
        isFinishing = true
        rollover?.cancel(); rollover = nil
        stopCapture()
        // The last request flushes what it holds; its final result settles the
        // segment and publishes the transcript.
        if !route.rollOver(to: nil) { publish() }
    }

    public func cancel() {
        isFinishing = false
        rollover?.cancel(); rollover = nil
        stopCapture()
        route.discard()
        for task in tasks { task.cancel() }
        tasks = []
        segments = TranscriptSegments()
        emit = nil
        engine = nil; input = nil; recognizer = nil
    }

    // MARK: - Segments

    /// Install a fresh request, hand the audio tap over to it, and let the one
    /// it replaces flush what it already heard.
    private func openSegment() {
        guard let recognizer else { return }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = true
        request.addsPunctuation = true
        let index = segments.begin()
        tasks.removeAll { $0.state == .completed }
        // Recognition is running before the tap is handed over, so no buffer
        // reaches a request whose task does not exist yet. The outgoing request
        // keeps taking audio until that instant, which is what closes the seam.
        tasks.append(recognizer.recognitionTask(with: request, resultHandler: handler(segment: index)))
        route.rollOver(to: request)
        segmentStarted = Date()
        armRollover()
    }

    private func armRollover() {
        rollover?.cancel()
        rollover = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.segmentDuration))
            guard !Task.isCancelled, let self, !self.isFinishing, self.emit != nil else { return }
            self.openSegment()
        }
    }

    /// A framework callback, so the factory is explicitly nonisolated: a
    /// closure built inside a `@MainActor` method carries an executor assertion
    /// that trips when Speech calls it back on its own thread.
    nonisolated private func handler(segment index: Int) -> (SFSpeechRecognitionResult?, Error?) -> Void {
        { [weak self] result, error in
            // `SFSpeechRecognitionResult` is not `Sendable`; everything the
            // main actor needs is read out here instead of crossing with it.
            let text = result?.bestTranscription.formattedString
            let isFinal = result?.isFinal ?? false
            let failed = error != nil && !isFinal
            Task { @MainActor [weak self] in
                self?.receive(segment: index, text: text, ended: isFinal || failed, failed: failed)
            }
        }
    }

    private func receive(segment index: Int, text: String?, ended: Bool, failed: Bool) {
        guard emit != nil, segments.text(at: index) != nil else { return }
        if let text, segments.update(index, text: text) { publish() }
        guard ended else { return }
        segments.end(index)
        // A segment that already handed over is simply closing its books.
        guard index == segments.active else { publish(); return }
        if isFinishing { publish(); return }
        if failed, !isRecoverable() {
            emit?(.failure(.recognition))
            return
        }
        openSegment()
    }

    /// A recognizer that fails the instant it starts is broken and the user
    /// should be told. One that fails after a stretch of silence has only run
    /// out of speech to hear, and dictation carries on.
    private func isRecoverable() -> Bool {
        guard Date().timeIntervalSince(segmentStarted) < Self.prematureFailure else {
            prematureFailures = 0
            return true
        }
        prematureFailures += 1
        return prematureFailures < Self.prematureFailureLimit
    }

    private func publish() {
        emit?(.transcript(segments.joined, isFinal: isFinishing && segments.isSettled))
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

    // These Objective-C callbacks may run on framework-owned threads. Creating
    // them inside a MainActor method silently adds Swift 6 executor assertions;
    // factory isolation must be explicit even when a callback captures no self.
    nonisolated private static func speechAuthorization() async -> SFSpeechRecognizerAuthorizationStatus {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
        }
    }

    nonisolated private static func audioTap(route: RecognitionRoute,
                                             onEvent: @escaping @Sendable (SpeechInputEvent) -> Void) -> AVAudioNodeTapBlock {
        { buffer, _ in
            route.append(buffer)
            guard let samples = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return }
            let count = Int(buffer.frameLength)
            var sum: Float = 0
            for index in 0..<count { sum += samples[index] * samples[index] }
            let rms = sqrt(Double(sum) / Double(count))
            let normalized = max(0, min(1, (20 * log10(max(rms, 0.0001)) + 55) / 55))
            onEvent(.level(normalized))
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

/// The audio tap runs on a framework thread while the main actor swaps
/// recognition requests underneath it, so the current request passes through a
/// lock rather than a capture. Installing the replacement and flushing the
/// request it replaces happen under the same lock, so no buffer is ever handed
/// to a request that has already been told there is no more audio.
final class RecognitionRoute: @unchecked Sendable {
    private let lock = NSLock()
    private var request: SFSpeechAudioBufferRecognitionRequest?

    func append(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        defer { lock.unlock() }
        request?.append(buffer)
    }

    /// Returns whether there was a request to flush.
    @discardableResult
    func rollOver(to next: SFSpeechAudioBufferRecognitionRequest?) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let previous = request
        request = next
        previous?.endAudio()
        return previous != nil
    }

    /// Drop the current request without asking it for a result.
    func discard() {
        lock.lock()
        defer { lock.unlock() }
        request = nil
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
