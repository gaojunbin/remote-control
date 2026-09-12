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
///
/// A slot is not one sentence. A recognizer may start its transcription over
/// inside one request — the speaker paused, and what comes back next is a new
/// sentence rather than a longer version of the old one. The slot then keeps
/// what it had as a settled sub-segment and carries on in a new one, so no word
/// spoken before the pause is dropped. `isFreshStart` is the rule that tells
/// that apart from the recognizer revising its own last few words.
public struct TranscriptSegments: Equatable, Sendable {
    /// What one recognition request has heard: the sub-segments it is finished
    /// with, and the text it may still revise.
    private struct Slot: Equatable, Sendable {
        var settled: [String] = []
        var live = ""

        var text: String {
            (settled + [live]).filter { !$0.isEmpty }.joined(separator: " ")
        }
    }

    /// How much a slot must already hold before a short, unrelated partial is
    /// read as a new sentence. Below it the recognizer is still finding the
    /// first few words, where a rewrite is ordinary and a commit would double
    /// them up.
    private static let restartFloor = 12

    private var slots: [Slot] = []
    private var open: Set<Int> = []

    public init() {}

    /// Open a slot for a new recognition request and return its index.
    @discardableResult
    public mutating func begin() -> Int {
        let index = slots.count
        slots.append(Slot())
        open.insert(index)
        return index
    }

    /// The slot currently receiving audio, which is the last one opened.
    public var active: Int? { slots.isEmpty ? nil : slots.count - 1 }

    public var count: Int { slots.count }

    /// Take one result for a segment, and say whether the joined transcript
    /// moved.
    ///
    /// Every backend reports the whole of what it is transcribing on each
    /// result rather than a delta, so this normally replaces the segment's live
    /// text. When the result is a fresh start instead, the live text is settled
    /// first and the result opens the next sub-segment. A result for a slot that
    /// was never opened is ignored rather than trusted.
    @discardableResult
    public mutating func update(_ index: Int, text: String) -> Bool {
        guard slots.indices.contains(index) else { return false }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard slots[index].live != trimmed else { return false }
        if Self.isFreshStart(after: slots[index].live, next: trimmed) {
            slots[index].settled.append(slots[index].live)
        }
        slots[index].live = trimmed
        return true
    }

    /// Whether a result starts the sentence over rather than carrying the same
    /// one on.
    ///
    /// Three things have to hold at once, and together they leave the
    /// recognizer's own revisions alone:
    ///
    /// 1. The segment already holds a sentence's worth of speech, so the first
    ///    few words of an utterance are never settled behind a rewrite.
    /// 2. The result is shorter than what the segment holds. A recognizer
    ///    extending or revising an utterance keeps roughly what it had; the
    ///    first partial after a restart is a word or two.
    /// 3. The two share no real beginning: the run of characters they agree on,
    ///    ignoring case, spacing and punctuation, covers less than half the new
    ///    result. A rewrite of the last few words agrees on almost all of it.
    static func isFreshStart(after previous: String, next: String) -> Bool {
        let settled = normalized(previous)
        let arriving = normalized(next)
        guard settled.count >= restartFloor, !arriving.isEmpty else { return false }
        guard arriving.count < settled.count else { return false }
        return commonPrefixLength(settled, arriving) * 2 < arriving.count
    }

    /// The comparable shape of a transcript: letters and digits, folded to
    /// lower case. Spacing and punctuation move around between partials and say
    /// nothing about whether this is the same sentence.
    private static func normalized(_ text: String) -> [Character] {
        Array(text.lowercased().filter { $0.isLetter || $0.isNumber })
    }

    private static func commonPrefixLength(_ first: [Character], _ second: [Character]) -> Int {
        var length = 0
        while length < first.count, length < second.count, first[length] == second[length] {
            length += 1
        }
        return length
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
        slots.indices.contains(index) ? slots[index].text : nil
    }

    /// Every segment in the order its audio was spoken, empty ones dropped.
    public var joined: String {
        slots.map(\.text).filter { !$0.isEmpty }.joined(separator: " ")
    }
}
