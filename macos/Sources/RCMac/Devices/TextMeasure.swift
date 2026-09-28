import AppKit

/// Two CSS text rules the lists lean on that SwiftUI has no word for.
enum TextMeasure {
    /// `ch`: the advance of "0" in the system face at `size`, which the web's
    /// `max-width: 46ch` measures a paragraph in.
    static func ch(_ size: CGFloat) -> CGFloat {
        ("0" as NSString).size(withAttributes: [.font: TextStyle(size: size).nsFont]).width
    }

    /// `word-break: break-all`: a one-liner may break between any two letters
    /// or digits, as a long URL in a terminal would, rather than only where a
    /// word ends; punctuation keeps its own rules, so `--` stays whole. The
    /// text drawn carries a zero-width space between each such pair; the text
    /// copied is the original.
    static func breakAll(_ text: String) -> String {
        var result = ""
        var previous: Character?
        for character in text {
            if let previous, isLetterOrDigit(previous), isLetterOrDigit(character) { result.append("\u{200B}") }
            result.append(character)
            previous = character
        }
        return result
    }

    private static func isLetterOrDigit(_ character: Character) -> Bool {
        character.isLetter || character.isNumber
    }
}
