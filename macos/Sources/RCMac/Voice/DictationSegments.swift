import Foundation

/// `web/src/features/voice/segments.ts`: the transcript of a dictation that
/// outlived a single gateway request.
///
/// The gateway accepts at most 120 s of audio per utterance, so a long
/// dictation rolls over to a fresh socket while the microphone keeps running,
/// and each socket owns one slot here. Slots fill out of order — a new
/// segment's first partial can arrive before the previous segment's final — so
/// the text is joined by position rather than by arrival, and a segment stays
/// open until its socket has said the last word about it.
///
/// The gateway reports the whole segment on every result, so a result replaces
/// the slot's text; the iPhone's `TranscriptSegments` also reads a recogniser
/// that starts its sentence over, which the web's socket never does.
struct DictationSegments {
    private var texts: [String] = []
    private var open: Set<Int> = []

    /// Open a slot for a new socket and return its index.
    mutating func begin() -> Int {
        texts.append("")
        open.insert(texts.count - 1)
        return texts.count - 1
    }

    /// The slot taking audio right now, which is the last one opened.
    var active: Int? { texts.isEmpty ? nil : texts.count - 1 }

    /// Replace one segment's text and say whether the joined transcript moved.
    /// A result for a slot never opened is ignored.
    @discardableResult
    mutating func update(_ index: Int, text: String) -> Bool {
        guard texts.indices.contains(index) else { return false }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard texts[index] != trimmed else { return false }
        texts[index] = trimmed
        return true
    }

    /// Its socket will say nothing more about this segment.
    mutating func end(_ index: Int) { open.remove(index) }

    func isOpen(_ index: Int) -> Bool { open.contains(index) }

    func has(_ index: Int) -> Bool { texts.indices.contains(index) }

    /// True once every segment has finished — the only moment a text is final.
    var isSettled: Bool { open.isEmpty }

    /// Every segment in the order its audio was spoken, empty ones dropped.
    var joined: String { texts.filter { !$0.isEmpty }.joined(separator: " ") }
}
