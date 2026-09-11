import Foundation

/// The transcript of a dictation that outlived a single recognition request.
///
/// Neither speech backend can listen forever. Apple's recognizer ends a
/// server-backed request after roughly a minute, and the gateway accepts at
/// most 120 s of audio per utterance. Both therefore roll over to a fresh
/// request while the microphone keeps running, and each request owns one slot
/// here.
///
/// Slots are filled out of order: a new segment's first partial can arrive
/// before the previous segment's final does. The text is therefore joined by
/// position rather than by arrival, and a segment stays *open* until its
/// backend has said the last word about it, so a dictation is only finished
/// when every slot has settled.
public struct TranscriptSegments: Equatable, Sendable {
    private var texts: [String] = []
    private var open: Set<Int> = []

    public init() {}

    /// Open a slot for a new recognition request and return its index.
    @discardableResult
    public mutating func begin() -> Int {
        let index = texts.count
        texts.append("")
        open.insert(index)
        return index
    }

    /// The slot currently receiving audio, which is the last one opened.
    public var active: Int? { texts.isEmpty ? nil : texts.count - 1 }

    public var count: Int { texts.count }

    /// Replace one segment's text, and say whether the joined transcript moved.
    ///
    /// Every backend reports the whole segment on each result rather than a
    /// delta, so this is a replacement. A result for a slot that was never
    /// opened is ignored rather than trusted.
    @discardableResult
    public mutating func update(_ index: Int, text: String) -> Bool {
        guard texts.indices.contains(index) else { return false }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard texts[index] != trimmed else { return false }
        texts[index] = trimmed
        return true
    }

    /// Mark a segment finished. Its backend will say nothing more about it.
    public mutating func end(_ index: Int) {
        open.remove(index)
    }

    /// True once every segment has finished, which is the only moment a
    /// transcript can be called final.
    public var isSettled: Bool { open.isEmpty }

    /// Whether this segment's backend may still say something about it.
    public func isOpen(_ index: Int) -> Bool { open.contains(index) }

    public func text(at index: Int) -> String? {
        texts.indices.contains(index) ? texts[index] : nil
    }

    /// Every segment in the order its audio was spoken, empty ones dropped.
    public var joined: String {
        texts.filter { !$0.isEmpty }.joined(separator: " ")
    }
}
