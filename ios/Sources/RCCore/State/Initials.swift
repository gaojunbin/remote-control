import Foundation

/// The one or two characters in the circle at the top of Settings.
///
/// A username is not a person's name, so there is nothing clever to do with it:
/// two words give a letter each (`j.gao` → `JG`), one word gives its first two
/// letters (`admin` → `AD`), and a script whose characters are words of their
/// own gives one (`李雷` → `李`).
public enum Initials {
    private static let separators: Set<Character> = [".", "-", "_"]

    public static func of(_ username: String) -> String {
        let words = username.split { separators.contains($0) || $0.isWhitespace }
        guard let first = words.first, let head = first.first else { return "" }
        if words.count > 1, let second = words[words.index(after: words.startIndex)].first {
            return String([head, second]).uppercased()
        }
        guard !isWordOnItsOwn(head) else { return String(head) }
        return String(first.prefix(2)).uppercased()
    }

    /// A character that is already a word: CJK ideographs, kana and Hangul. Two
    /// of them in a circle read as a name cut in half rather than as initials.
    private static func isWordOnItsOwn(_ character: Character) -> Bool {
        guard let scalar = character.unicodeScalars.first else { return false }
        switch scalar.value {
        case 0x3040...0x30FF, 0x3400...0x4DBF, 0x4E00...0x9FFF,
             0xAC00...0xD7AF, 0xF900...0xFAFF:
            return true
        default:
            return false
        }
    }
}
