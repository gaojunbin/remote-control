import Foundation

/// How far the message field is allowed to grow.
///
/// One rule, shared by every place the app takes a message: the composer and
/// the field an agent's question offers for a written answer. SwiftUI measures
/// the wrapped text and grows the field within this range; past the top of it
/// the field stops growing and scrolls inside itself, so a dictated paragraph
/// never pushes the conversation off the screen.
public enum ComposerLayout {
    /// An empty or short draft is a single quiet row.
    public static let minimumLines = 1
    /// Eight lines is where growing stops and scrolling starts.
    public static let maximumLines = 8

    public static let growth = minimumLines...maximumLines

    /// The lines a draft occupies counting only its own line breaks, clamped to
    /// the range above. Soft wrapping can only add to this, never subtract, so
    /// a draft this call reports as capped is certainly capped on screen.
    public static func lines(in draft: String) -> Int {
        min(max(breaks(in: draft), minimumLines), maximumLines)
    }

    /// Whether the field has stopped growing and the text moves inside it.
    public static func scrolls(_ draft: String) -> Bool {
        breaks(in: draft) > maximumLines
    }

    private static func breaks(in draft: String) -> Int {
        draft.reduce(1) { count, character in character.isNewline ? count + 1 : count }
    }
}
