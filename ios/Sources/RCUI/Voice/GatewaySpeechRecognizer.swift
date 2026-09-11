import Foundation
import RCCore
#if os(iOS)
@preconcurrency import AVFoundation

/// Dictation through the gateway's streaming endpoint.
///
/// The microphone is captured at whatever format the hardware offers and
/// converted to the PCM16LE, 16 kHz mono frames the protocol specifies. Audio
/// leaves the phone here, which is exactly the difference from on-device
/// recognition and is stated in the voice settings.
///
/// One socket carries one utterance and the gateway caps that at 120 s, so a
/// long dictation is cut into segments and their transcripts joined in order.
/// The protocol is untouched: each segment is an ordinary `WS /ws/stt` session.
/// The replacement socket is connected and taking audio before the outgoing one
/// is told to stop, so the seam drops nothing, and the cut waits for the first
/// quiet moment after the segment length rather than landing mid-word.
@MainActor public final class GatewaySpeechRecognizer: SpeechInputPlatform {
    /// Well inside the gateway's 120 s and 4 MiB budget for one utterance.
    static let segmentDuration: TimeInterval = 30
    /// How long a cut may wait for a silence before it is taken anyway.
    static let segmentLimit: TimeInterval = 45
    /// Normalised input level under which the speaker counts as between words.
    static let silenceLevel = 0.12

    private let client: GatewayHTTPClient
    private let language: String
    private let route = STTAudioRoute()
    private var engine: AVAudioEngine?
    private var input: AVAudioInputNode?
    private var sockets: [STTSocket] = []
    private var readers: [Task<Void, Never>] = []
    private var segments = TranscriptSegments()
    private var rollover: Task<Void, Never>?
    private var emit: (@Sendable (SpeechInputEvent) -> Void)?
    private var interruption: NSObjectProtocol?
    private var routeChange: NSObjectProtocol?
    private var hasTap = false
    private var ownsAudioSession = false
    private var isFinishing = false

    public init(client: GatewayHTTPClient, language: String) {
        self.client = client
        self.language = language
    }

    /// Each socket arms its own deadline at `STTSocket.finalTimeout`; this one
    /// only has to outlast it, so a stalled gateway still ends the session.
    public var finishGracePeriod: TimeInterval { STTSocket.finalTimeout + 3 }

    public func requestPermission() async throws {
        guard await AVAudioApplication.requestRecordPermission() else {
            throw SpeechInputFailure.microphonePermission
        }
    }

    public func start(onEvent: @escaping @Sendable (SpeechInputEvent) -> Void) throws {
        cancel()
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .measurement, options: [.duckOthers])
            try session.setActive(true)
            ownsAudioSession = true
            guard session.isInputAvailable else { throw SpeechInputFailure.recording }

            emit = onEvent
            segments = TranscriptSegments()
            isFinishing = false

            let engine = AVAudioEngine()
            let input = engine.inputNode
            let hardware = input.outputFormat(forBus: 0)
            guard hardware.sampleRate > 0, hardware.channelCount > 0 else { throw SpeechInputFailure.recording }
            guard let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: STTSocket.sampleRate,
                                             channels: 1, interleaved: true),
                  let converter = AVAudioConverter(from: hardware, to: target) else {
                throw SpeechInputFailure.recording
            }
            self.engine = engine
            self.input = input
            // A nil format lets the node supply its live hardware format, so an
            // audio route change cannot leave the tap on a stale description.
            input.installTap(onBus: 0, bufferSize: 2048, format: nil,
                             block: Self.tap(converter: converter, target: target, route: route, onEvent: onEvent))
            hasTap = true
            interruption = NotificationCenter.default.addObserver(
                forName: AVAudioSession.interruptionNotification, object: session, queue: .main,
                using: Self.interruptionHandler(onEvent: onEvent))
            routeChange = NotificationCenter.default.addObserver(
                forName: AVAudioSession.routeChangeNotification, object: session, queue: .main,
                using: Self.routeChangeHandler(onEvent: onEvent))
            engine.prepare()
            try engine.start()

            rollover = Task { [weak self] in await self?.segmentLoop(opening: true) }
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
        let last = route.exchange(nil)
        Task { await last?.stop() }
        publish()
    }

    public func cancel() {
        isFinishing = false
        rollover?.cancel(); rollover = nil
        stopCapture()
        route.exchange(nil)
        for reader in readers { reader.cancel() }
        readers = []
        let closing = sockets
        sockets = []
        Task { for socket in closing { await socket.cancel() } }
        segments = TranscriptSegments()
        emit = nil
        engine = nil
        input = nil
    }

    // MARK: - Segments

    /// Open the first segment, then roll over for as long as the user talks.
    private func segmentLoop(opening: Bool) async {
        if opening {
            guard await openSegment() else { return }
        }
        while !Task.isCancelled, !isFinishing {
            try? await Task.sleep(for: .seconds(Self.segmentDuration))
            guard !Task.isCancelled, !isFinishing else { return }
            await waitForSilence()
            guard !Task.isCancelled, !isFinishing else { return }
            guard await openSegment() else { return }
        }
    }

    /// Hold the cut until the speaker pauses, and take it anyway if they do not.
    private func waitForSilence() async {
        let forced = Date().addingTimeInterval(Self.segmentLimit - Self.segmentDuration)
        while !Task.isCancelled, route.lastLevel > Self.silenceLevel, Date() < forced {
            try? await Task.sleep(for: .milliseconds(200))
        }
    }

    /// Connect a fresh socket, hand the audio over to it, and let the one it
    /// replaces transcribe what it already holds.
    private func openSegment() async -> Bool {
        let index = segments.begin()
        let socket = STTSocket(client: client, language: language)
        sockets.append(socket)
        readers.append(Task { [weak self] in
            for await event in socket.events {
                await self?.receive(event, segment: index)
            }
        })
        do {
            try await socket.start()
        } catch {
            segments.end(index)
            emit?(.failure(.unavailable))
            return false
        }
        // Connecting is the one await here, so the session can have ended while
        // it ran. A socket nobody is going to speak into is closed, not armed.
        guard !Task.isCancelled, !isFinishing else {
            segments.end(index)
            await socket.cancel()
            return false
        }
        let previous = route.exchange(socket)
        Task { await previous?.stop() }
        return true
    }

    private func receive(_ event: STTEvent, segment index: Int) {
        guard emit != nil, segments.text(at: index) != nil else { return }
        switch event {
        case .partial(let text):
            if segments.update(index, text: text) { publish() }
        case .final(let text, _):
            segments.update(index, text: text)
            segments.end(index)
            publish()
        case .failed:
            segments.end(index)
            // A segment that already handed the microphone on keeps whatever it
            // transcribed; only the live one can end the dictation.
            if index == segments.active { emit?(.failure(.recognition)) } else { publish() }
        case .closed:
            // A socket closes after its final as a matter of course; only an
            // unannounced close still has a segment to settle.
            guard segments.isOpen(index) else { return }
            segments.end(index)
            publish()
        }
    }

    private func publish() {
        emit?(.transcript(segments.joined, isFinal: isFinishing && segments.isSettled))
    }

    private func stopCapture() {
        if let interruption { NotificationCenter.default.removeObserver(interruption); self.interruption = nil }
        if let routeChange { NotificationCenter.default.removeObserver(routeChange); self.routeChange = nil }
        engine?.stop()
        if hasTap { input?.removeTap(onBus: 0); hasTap = false }
        engine = nil
        input = nil
        if ownsAudioSession {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            ownsAudioSession = false
        }
    }

    // These callbacks run on framework-owned threads. Building them inside a
    // MainActor method silently adds Swift 6 executor assertions, so the
    // factories are explicitly nonisolated even though they capture no self.
    nonisolated private static func tap(converter: AVAudioConverter, target: AVAudioFormat,
                                        route: STTAudioRoute,
                                        onEvent: @escaping @Sendable (SpeechInputEvent) -> Void) -> AVAudioNodeTapBlock {
        { buffer, _ in
            guard buffer.frameLength > 0 else { return }
            let ratio = target.sampleRate / buffer.format.sampleRate
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 1024
            guard let converted = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return }
            var consumed = false
            var error: NSError?
            converter.convert(to: converted, error: &error) { _, status in
                if consumed { status.pointee = .noDataNow; return nil }
                consumed = true
                status.pointee = .haveData
                return buffer
            }
            guard error == nil, converted.frameLength > 0, let channel = converted.int16ChannelData else { return }
            let byteCount = Int(converted.frameLength) * MemoryLayout<Int16>.size
            let pcm = Data(bytes: channel[0], count: byteCount)

            var sum = 0.0
            for index in 0..<Int(converted.frameLength) {
                let value = Double(channel[0][index]) / 32768
                sum += value * value
            }
            let rms = (sum / Double(converted.frameLength)).squareRoot()
            let normalized = max(0, min(1, (20 * log10(max(rms, 0.0001)) + 55) / 55))
            route.send(pcm, level: normalized)
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
               reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue {
                onEvent(.failure(.interrupted))
            }
        }
    }
}

/// The audio tap runs on a framework thread while the main actor swaps sockets
/// underneath it, so the socket taking audio and the level last measured from
/// it both pass through a lock rather than a capture. The level is what lets a
/// segment boundary wait for a pause in the speech.
final class STTAudioRoute: @unchecked Sendable {
    private let lock = NSLock()
    private var socket: STTSocket?
    private var level = 0.0

    var lastLevel: Double { lock.withLock { level } }

    func send(_ pcm: Data, level: Double) {
        lock.lock()
        let socket = self.socket
        self.level = level.isFinite ? level : 0
        lock.unlock()
        guard let socket else { return }
        Task { await socket.append(pcm) }
    }

    /// Install a socket and hand back the one it replaces.
    @discardableResult
    func exchange(_ next: STTSocket?) -> STTSocket? {
        lock.lock()
        defer { lock.unlock() }
        let previous = socket
        socket = next
        return previous
    }
}
#else
@MainActor public final class GatewaySpeechRecognizer: SpeechInputPlatform {
    public init(client: GatewayHTTPClient, language: String) {}
    public var finishGracePeriod: TimeInterval { STTSocket.finalTimeout + 3 }
    public func requestPermission() async throws { throw SpeechInputFailure.unsupported }
    public func start(onEvent: @escaping @Sendable (SpeechInputEvent) -> Void) throws {
        throw SpeechInputFailure.unsupported
    }
    public func finish() {}
    public func cancel() {}
}
#endif
