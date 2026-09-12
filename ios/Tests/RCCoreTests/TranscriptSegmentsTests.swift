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

    /// The defect this rule exists for: the speaker pauses, the recognizer
    /// starts its transcription over inside the same request, and the sentence
    /// already spoken used to be replaced by the new one.
    @Test("A partial that starts the sentence over keeps what was already said")
    func restartInsideOneRequest() {
        var segments = TranscriptSegments()
        let index = segments.begin()
        segments.update(index, text: "The weather in Berlin")
        segments.update(index, text: "The weather in Berlin is cold today.")
        segments.update(index, text: "Tomorrow")
        #expect(segments.joined == "The weather in Berlin is cold today. Tomorrow")
        segments.update(index, text: "Tomorrow I will take the train.")
        #expect(segments.joined == "The weather in Berlin is cold today. Tomorrow I will take the train.")
        #expect(segments.count == 1, "one request still owns one slot")
    }

    @Test("A recognizer rewriting its own last words revises rather than starts over")
    func revisionReplacesTheSegment() {
        var segments = TranscriptSegments()
        let index = segments.begin()
        segments.update(index, text: "Re-run the auth suite on the CI runner")
        segments.update(index, text: "Re-run the auth suite on the CI runner too.")
        segments.update(index, text: "Re-run the auth suite on the SI runner")
        #expect(segments.joined == "Re-run the auth suite on the SI runner")
    }

    @Test("The first words of an utterance are corrected, never settled behind the correction")
    func shortSegmentIsNeverSettled() {
        var segments = TranscriptSegments()
        let index = segments.begin()
        segments.update(index, text: "Hello")
        segments.update(index, text: "Halo")
        #expect(segments.joined == "Halo")
    }

    @Test("A restart is told apart from a revision by what the two have in common")
    func freshStartRule() {
        // A new sentence: shorter, and agreeing on nothing but a first letter.
        #expect(TranscriptSegments.isFreshStart(after: "The weather in Berlin is cold today.",
                                                next: "Tomorrow"))
        // The same sentence, longer: an extension.
        #expect(!TranscriptSegments.isFreshStart(after: "The weather in Berlin",
                                                 next: "The weather in Berlin is cold"))
        // The same sentence with its tail rewritten, and a word shorter.
        #expect(!TranscriptSegments.isFreshStart(after: "The weather in Berlin is cold today.",
                                                 next: "The weather in Berlin is cold"))
        // Too little heard so far to call anything a second sentence.
        #expect(!TranscriptSegments.isFreshStart(after: "Hello", next: "Halo"))
        // Punctuation and spacing alone never make a restart.
        #expect(!TranscriptSegments.isFreshStart(after: "the weather in berlin is cold today",
                                                 next: "The weather, in Berlin"))
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
