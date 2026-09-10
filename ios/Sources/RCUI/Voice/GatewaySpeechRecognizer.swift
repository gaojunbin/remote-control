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
@MainActor public final class GatewaySpeechRecognizer: SpeechInputPlatform {
    private let client: GatewayHTTPClient
    private let language: String
    private var engine: AVAudioEngine?
    private var input: AVAudioInputNode?
    private var socket: STTSocket?
    private var reader: Task<Void, Never>?
    private var interruption: NSObjectProtocol?
    private var routeChange: NSObjectProtocol?
    private var hasTap = false
    private var ownsAudioSession = false

    public init(client: GatewayHTTPClient, language: String) {
        self.client = client
        self.language = language
    }

    /// The socket arms its own deadline at `STTSocket.finalTimeout`; this one
    /// only has to outlast it, so a stalled gateway still ends the panel.
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

            let socket = STTSocket(client: client, language: language)
            self.socket = socket
            reader = Task { [weak self] in
                for await event in socket.events {
                    switch event {
                    case .partial(let text): onEvent(.transcript(text, isFinal: false))
                    case .final(let text, _): onEvent(.transcript(text, isFinal: true))
                    case .failed: onEvent(.failure(.recognition))
                    case .closed: break
                    }
                }
                _ = self
            }
            Task { @MainActor [weak self] in
                do { try await socket.start() }
                catch {
                    onEvent(.failure(.unavailable))
                    self?.teardown()
                }
            }

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
                             block: Self.tap(converter: converter, target: target, socket: socket, onEvent: onEvent))
            hasTap = true
            interruption = NotificationCenter.default.addObserver(
                forName: AVAudioSession.interruptionNotification, object: session, queue: .main,
                using: Self.interruptionHandler(onEvent: onEvent))
            routeChange = NotificationCenter.default.addObserver(
                forName: AVAudioSession.routeChangeNotification, object: session, queue: .main,
                using: Self.routeChangeHandler(onEvent: onEvent))
            engine.prepare()
            try engine.start()
        } catch {
            cancel()
            throw (error as? SpeechInputFailure) ?? .recording
        }
    }

    public func finish() {
        stopCapture()
        let socket = socket
        Task { await socket?.stop() }
    }

    public func cancel() {
        stopCapture()
        let socket = socket
        self.socket = nil
        reader?.cancel()
        reader = nil
        Task { await socket?.cancel() }
    }

    private func teardown() {
        stopCapture()
        socket = nil
        reader?.cancel()
        reader = nil
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
                                        socket: STTSocket,
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
            Task { await socket.append(pcm) }

            guard let samples = converted.int16ChannelData?[0] else { return }
            var sum = 0.0
            for index in 0..<Int(converted.frameLength) {
                let value = Double(samples[index]) / 32768
                sum += value * value
            }
            let rms = (sum / Double(converted.frameLength)).squareRoot()
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
               reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue {
                onEvent(.failure(.interrupted))
            }
        }
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
