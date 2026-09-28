import Foundation

/// `web/src/features/voice/downsample.ts`: audio for the speech socket — mono
/// Float32 at whatever rate the input runs, out as 16 kHz PCM16LE in fixed-size
/// frames. Pure, so the conversion is checked without a microphone.
enum Downsample {
    static let targetRate: Double = 16_000
    /// About 120 ms per frame at 16 kHz, inside the 100–200 ms the contract recommends.
    static let frameSamples = 1920

    struct RateTooLow: Error {}

    /// A sample clamped to ±1 and scaled into the signed 16-bit range, rounded
    /// as `Math.round` rounds: halves go up.
    static func pcm16(_ sample: Float) -> Int16 {
        let clamped = Double(max(-1, min(1, sample)))
        return Int16(((clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff) + 0.5).rounded(.down))
    }

    /// A box filter: each output sample is the average of the source window it
    /// covers, so the step down does not alias.
    static func to16k(_ input: [Float], rate: Double) throws(RateTooLow) -> [Float] {
        if rate == targetRate || input.isEmpty { return input }
        guard rate >= targetRate else { throw RateTooLow() }
        let ratio = rate / targetRate
        let count = Int((Double(input.count) / ratio).rounded(.down))
        var output = [Float](repeating: 0, count: count)
        for index in 0..<count {
            let start = Int((Double(index) * ratio).rounded(.down))
            let end = min(input.count, Int((Double(index + 1) * ratio).rounded(.down)))
            guard end > start else { continue }
            var sum: Float = 0
            for sample in input[start..<end] { sum += sample }
            output[index] = sum / Float(end - start)
        }
        return output
    }

    /// The loudest sample of a block, which drives the waveform.
    static func peakLevel(_ input: [Float]) -> Float {
        min(1, input.reduce(0) { max($0, abs($1)) })
    }

    /// Little-endian PCM16, as the socket sends it.
    static func encode(_ samples: ArraySlice<Float>) -> Data {
        var data = Data(capacity: samples.count * 2)
        for sample in samples {
            let value = UInt16(bitPattern: pcm16(sample))
            data.append(UInt8(value & 0xff))
            data.append(UInt8(value >> 8))
        }
        return data
    }
}

/// Collects resampled audio and hands it on in frames of one size; a partial
/// frame waits for the next block, or for `flush`.
struct PcmChunker {
    private let inputRate: Double
    private let frameSamples: Int
    private var buffer: [Float] = []

    init(inputRate: Double, frameSamples: Int = Downsample.frameSamples) {
        self.inputRate = inputRate
        self.frameSamples = frameSamples
    }

    mutating func push(_ block: [Float]) throws(Downsample.RateTooLow) -> [Data] {
        buffer += try Downsample.to16k(block, rate: inputRate)
        var frames: [Data] = []
        while buffer.count >= frameSamples {
            frames.append(Downsample.encode(buffer[..<frameSamples]))
            buffer.removeFirst(frameSamples)
        }
        return frames
    }

    /// What is left, as one short frame: the contract allows a partial one.
    mutating func flush() -> Data? {
        guard !buffer.isEmpty else { return nil }
        defer { buffer = [] }
        return Downsample.encode(buffer[...])
    }
}
