@preconcurrency import AVFoundation
import Foundation

/// `web/src/features/voice/recorder.ts` on the Mac: the microphone through
/// `AVAudioEngine`, its first channel resampled to the 16 kHz PCM16LE frames
/// the web's worklet sends (`PcmChunker`), and the peak of every block as the
/// waveform's level.
///
/// The system asks for the microphone the first time dictation starts, which
/// is a press on the mic and nothing else (`docs/DESIGN.md` § "The Mac app").
@MainActor
final class MicRecorder: VoiceRecorder {
    private let handlers: RecorderHandlers
    private var engine: AVAudioEngine?
    private var chunker: PcmChunker?

    init(handlers: RecorderHandlers) { self.handlers = handlers }

    func start() async -> Bool {
        guard await Self.microphoneAllowed() else {
            handlers.onError(.denied)
            return false
        }
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.channelCount > 0, format.sampleRate >= Downsample.targetRate else {
            handlers.onError(.unsupported)
            return false
        }
        chunker = PcmChunker(inputRate: format.sampleRate)
        input.installTap(onBus: 0, bufferSize: 4096, format: format,
                         block: Self.tap { [weak self] samples in self?.consume(samples) })
        do {
            engine.prepare()
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            chunker = nil
            handlers.onError(.failed)
            return false
        }
        self.engine = engine
        return true
    }

    func stop() async {
        if let tail = chunker?.flush() { handlers.onFrame(tail) }
        chunker = nil
        guard let engine else { return }
        self.engine = nil
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
    }

    /// One block of the first channel, on the main actor.
    private func consume(_ samples: [Float]) {
        guard chunker != nil else { return }
        handlers.onLevel(Double(Downsample.peakLevel(samples)))
        do {
            for frame in try chunker?.push(samples) ?? [] { handlers.onFrame(frame) }
        } catch {
            handlers.onError(.unsupported)
        }
    }

    private static func microphoneAllowed() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: true
        case .notDetermined: await AVCaptureDevice.requestAccess(for: .audio)
        default: false
        }
    }

    /// The tap runs on the audio thread. It copies the block and hands it to
    /// the main queue, which keeps the blocks in the order they were heard.
    nonisolated private static func tap(_ deliver: @escaping @MainActor @Sendable ([Float]) -> Void)
        -> AVAudioNodeTapBlock {
        { buffer, _ in
            guard let channel = buffer.floatChannelData, buffer.frameLength > 0 else { return }
            let samples = Array(UnsafeBufferPointer(start: channel[0], count: Int(buffer.frameLength)))
            DispatchQueue.main.async { MainActor.assumeIsolated { deliver(samples) } }
        }
    }
}
