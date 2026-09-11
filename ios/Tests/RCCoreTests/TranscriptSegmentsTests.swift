import Testing
import Foundation
@testable import RCCore

/// Dictation has no maximum duration, so both speech backends roll their
/// recognition request over while the microphone keeps running. These are the
/// rules that keep the resulting text in the order it was spoken.
@Suite("Transcript segments")
struct TranscriptSegmentsTests {
    @Test("A fresh transcript is settled and empty")
    func empty() {
        let segments = TranscriptSegments()
        #expect(segments.joined == "")
        #expect(segments.count == 0)
        #expect(segments.active == nil)
        #expect(segments.isSettled)
    }

    @Test("Each request owns a slot, and the last one opened is the live one")
    func slots() {
        var segments = TranscriptSegments()
        let first = segments.begin()
        #expect(first == 0)
        #expect(segments.active == 0)
        let second = segments.begin()
        #expect(second == 1)
        #expect(segments.active == 1)
        #expect(segments.count == 2)
    }

    @Test("Segments are joined in the order the audio was spoken")
    func joinedInOrder() {
        var segments = TranscriptSegments()
        let first = segments.begin()
        let second = segments.begin()
        segments.update(second, text: "on the CI runner too.")
        segments.update(first, text: "Re-run the auth suite")
        #expect(segments.joined == "Re-run the auth suite on the CI runner too.")
    }

    @Test("A late final from the previous request refines its own slot, not the end")
    func lateFinal() {
        var segments = TranscriptSegments()
        let first = segments.begin()
        segments.update(first, text: "re-run the auth")
        let second = segments.begin()
        segments.update(second, text: "on CI")
        // The previous request's final lands after the next one has spoken.
        segments.update(first, text: "Re-run the auth suite")
        #expect(segments.joined == "Re-run the auth suite on CI")
    }

    @Test("Each result replaces its segment rather than accumulating")
    func replacement() {
        var segments = TranscriptSegments()
        let index = segments.begin()
        segments.update(index, text: "re-run")
        segments.update(index, text: "re-run the auth suite")
        #expect(segments.joined == "re-run the auth suite")
    }

    @Test("An unchanged result does not report a change")
    func unchangedResult() {
        var segments = TranscriptSegments()
        let index = segments.begin()
        let changed = segments.update(index, text: "re-run")
        let repeated = segments.update(index, text: "re-run")
        // Backends pad partials differently; the surrounding space is not a change.
        let padded = segments.update(index, text: "  re-run \n")
        #expect(changed)
        #expect(!repeated)
        #expect(!padded)
    }

    @Test("A result for a slot that was never opened is ignored")
    func unknownSlot() {
        var segments = TranscriptSegments()
        segments.begin()
        let accepted = segments.update(7, text: "from nowhere")
        #expect(!accepted)
        #expect(segments.text(at: 7) == nil)
        #expect(segments.joined == "")
    }

    @Test("An empty segment leaves no gap in the joined text")
    func silentSegment() {
        var segments = TranscriptSegments()
        let first = segments.begin()
        segments.begin()
        let third = segments.begin()
        segments.update(first, text: "start")
        segments.update(third, text: "end")
        #expect(segments.joined == "start end")
    }

    @Test("A transcript is final only once every request has said its last word")
    func settling() {
        var segments = TranscriptSegments()
        let first = segments.begin()
        let second = segments.begin()
        #expect(!segments.isSettled)
        #expect(segments.isOpen(first))
        segments.end(second)
        #expect(!segments.isSettled, "the earlier request is still transcribing")
        segments.end(first)
        #expect(segments.isSettled)
        #expect(!segments.isOpen(first))
    }

    @Test("Ending a segment twice is not an error")
    func endTwice() {
        var segments = TranscriptSegments()
        let index = segments.begin()
        segments.end(index)
        segments.end(index)
        #expect(segments.isSettled)
    }

    @Test("A segment that ended keeps its text")
    func endedSegmentKeepsText() {
        var segments = TranscriptSegments()
        let index = segments.begin()
        segments.update(index, text: "the whole sentence")
        segments.end(index)
        #expect(segments.text(at: index) == "the whole sentence")
        #expect(segments.joined == "the whole sentence")
    }
}
