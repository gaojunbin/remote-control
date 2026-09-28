import Foundation
import Testing
@testable import RCMac

/// `web/tests/downsample.test.ts`: any-rate Float32 in, 16 kHz PCM16LE out.
@Suite("Voice downsampler")
struct VoiceDownsampleTests {
    @Test func floatsAreClampedAndScaledIntoTheSigned16BitRange() {
        #expect(Downsample.pcm16(0) == 0)
        #expect(Downsample.pcm16(1) == 32_767)
        #expect(Downsample.pcm16(-1) == -32_768)
        #expect(Downsample.pcm16(4) == 32_767)
        #expect(Downsample.pcm16(-4) == -32_768)
    }

    @Test func sixteenKilohertzPassesThroughUntouched() throws {
        let input: [Float] = [0.1, 0.2, 0.3]
        #expect(try Downsample.to16k(input, rate: Downsample.targetRate) == input)
    }

    @Test func fortyEightKilohertzIsBoxFilteredToAThird() throws {
        let output = try Downsample.to16k([Float](repeating: 1, count: 48_000), rate: 48_000)
        #expect(output.count == 16_000)
        #expect(abs(output[0] - 1) < 1e-6)
        #expect(abs(output[output.count - 1] - 1) < 1e-6)
    }

    @Test func eachSourceWindowIsAveragedRatherThanSampled() throws {
        // 44.1 kHz alternating +1/-1 must average towards zero, not alias to +1.
        let input = (0..<44_100).map { Float($0 % 2 == 0 ? 1 : -1) }
        let output = try Downsample.to16k(input, rate: 44_100)
        #expect(output.count == 16_000)
        #expect(Downsample.peakLevel(output) < 0.7)
    }

    @Test func inputBelowTheTargetRateIsRefused() {
        #expect(throws: Downsample.RateTooLow.self) { try Downsample.to16k([Float](repeating: 0, count: 10), rate: 8_000) }
    }

    @Test func thePeakLevelDrivesTheWaveform() {
        #expect(abs(Downsample.peakLevel([0.1, -0.6, 0.3]) - 0.6) < 1e-6)
        #expect(Downsample.peakLevel([]) == 0)
        #expect(Downsample.peakLevel([9]) == 1)
    }
}

@Suite("PCM chunker")
struct VoiceChunkerTests {
    @Test func framesAre120MillisecondsOfLittleEndianPCM16() throws {
        var chunker = PcmChunker(inputRate: Downsample.targetRate)
        let none = try chunker.push([Float](repeating: 0, count: 1_000))
        #expect(none.isEmpty)
        let frames = try chunker.push([Float](repeating: 0, count: Downsample.frameSamples))
        #expect(frames.count == 1)
        #expect(frames[0].count == Downsample.frameSamples * 2)
    }

    @Test func samplesAreWrittenLittleEndian() throws {
        var chunker = PcmChunker(inputRate: Downsample.targetRate, frameSamples: 2)
        let frames = try chunker.push([1, -1])
        let frame = try #require(frames.first)
        #expect(Array(frame) == [0xff, 0x7f, 0x00, 0x80])
    }

    @Test func theRemainderWaitsForTheNextPush() throws {
        var chunker = PcmChunker(inputRate: Downsample.targetRate, frameSamples: 4)
        let first = try chunker.push([Float](repeating: 0, count: 6))
        let second = try chunker.push([Float](repeating: 0, count: 2))
        #expect(first.count == 1 && second.count == 1)
        let rest = chunker.flush()
        #expect(rest == nil)
    }

    @Test func aPartialTrailingFrameIsFlushedExactlyOnce() throws {
        var chunker = PcmChunker(inputRate: Downsample.targetRate, frameSamples: 4)
        _ = try chunker.push([Float](repeating: 0, count: 3))
        let tail = chunker.flush()
        let again = chunker.flush()
        #expect(tail?.count == 6 && again == nil)
    }

    @Test func audioIsResampledOnTheWayInSoFramesAreAlways16Kilohertz() throws {
        var chunker = PcmChunker(inputRate: 48_000, frameSamples: 100)
        // 30 000 samples at 48 kHz is 10 000 at 16 kHz: a hundred frames.
        let frames = try chunker.push([Float](repeating: 0, count: 30_000))
        #expect(frames.count == 100)
    }
}

/// `web/tests/primarySlot.test.ts`: the composer's one primary slot over every
/// pair of a dictation phase and a polish phase.
@Suite("Primary slot")
struct VoicePrimarySlotTests {
    private let expected: [VoiceState: [PolishProgress: PrimarySlot]] = [
        .idle: [.idle: .send, .polishing: .working, .polished: .send, .failed: .send],
        .starting: [.idle: .done, .polishing: .done, .polished: .done, .failed: .done],
        .listening: [.idle: .done, .polishing: .done, .polished: .done, .failed: .done],
        .finishing: [.idle: .working, .polishing: .working, .polished: .working, .failed: .working],
        .error: [.idle: .send, .polishing: .working, .polished: .send, .failed: .send]
    ]

    @Test func everyPairHoldsWhatTheRulingSays() {
        for voice in VoiceState.allCases {
            for polish in PolishProgress.allCases {
                #expect(PrimarySlot.of(voice: voice, polish: polish) == expected[voice]?[polish],
                        "\(voice) × \(polish)")
            }
        }
    }

    @Test func fromDoneUntilTheLastWordIsInTheFieldTheSlotWaits() {
        #expect(PrimarySlot.of(voice: .finishing, polish: .idle) == .working)
        #expect(PrimarySlot.of(voice: .idle, polish: .polishing) == .working)
        #expect(PrimarySlot.of(voice: .idle, polish: .polished) == .send)
        #expect(PrimarySlot.of(voice: .idle, polish: .failed) == .send)
    }
}

/// `segments.ts` and `draft.ts`: a long dictation joined in spoken order, and
/// where its words land in the field.
@Suite("Dictation text")
struct VoiceTextTests {
    @Test func segmentsJoinByPositionAndSettleOnlyWhenEveryOneHasEnded() {
        var segments = DictationSegments()
        let first = segments.begin()
        let second = segments.begin()
        let secondMoved = segments.update(second, text: "second half")
        let firstMoved = segments.update(first, text: " first half ")
        let sameAgain = segments.update(first, text: "first half")
        #expect(secondMoved && firstMoved && !sameAgain)
        #expect(segments.joined == "first half second half")
        segments.end(first)
        #expect(!segments.isSettled && segments.isOpen(second))
        segments.end(second)
        #expect(segments.isSettled)
        let neverOpened = segments.update(7, text: "never opened")
        #expect(!neverOpened)
    }

    @Test func aTranscriptIsAppendedAfterASpaceAndReplacesNothing() {
        #expect(DictationDraft.merge("", "run the suite") == "run the suite")
        #expect(DictationDraft.merge("after lunch", "run it") == "after lunch run it")
        #expect(DictationDraft.merge("after lunch\n", "run it") == "after lunch\nrun it")
        #expect(DictationDraft.merge("after lunch", "") == "after lunch")
    }
}
