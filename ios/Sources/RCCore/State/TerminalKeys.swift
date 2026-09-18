import Foundation

/// The phone's key bar, as a table from key to bytes (amendment A38,
/// `docs/DESIGN.md` § "The terminal" → **The phone's key bar**).
///
/// Nothing here is app-specific: every key sends the sequence a terminal
/// expects, so the shell on the other side cannot tell the bar from a keyboard.
/// It is a value type on purpose — the bar draws it and the tests read it, and
/// neither needs a view to do so.
public enum TerminalKey: String, Sendable, Hashable, CaseIterable, Identifiable {
    case escape
    case tab
    case control
    case up
    case down
    case left
    case right
    case controlC
    case controlD
    case controlZ
    case controlR
    case controlL
    case pipe
    case slash
    case dash
    case tilde
    case paste

    public var id: String { rawValue }

    /// The bar, in the order the design lists it. `allCases` follows the
    /// declaration, and the order is the design's ruling rather than an
    /// accident of it, so it is stated here.
    public static let bar: [TerminalKey] = [
        .escape, .tab, .control, .up, .down, .left, .right,
        .controlC, .controlD, .controlZ, .controlR, .controlL,
        .pipe, .slash, .dash, .tilde, .paste
    ]

    /// What the key sends, or nil for the two that act rather than type: the
    /// sticky Ctrl, which changes the next key, and Paste, which reads the
    /// clipboard.
    public var bytes: [UInt8]? {
        switch self {
        case .escape: [0x1b]
        case .tab: [0x09]
        case .control: nil
        case .up: [0x1b, 0x5b, 0x41]
        case .down: [0x1b, 0x5b, 0x42]
        case .right: [0x1b, 0x5b, 0x43]
        case .left: [0x1b, 0x5b, 0x44]
        case .controlC: [0x03]
        case .controlD: [0x04]
        case .controlZ: [0x1a]
        case .controlR: [0x12]
        case .controlL: [0x0c]
        case .pipe: [0x7c]
        case .slash: [0x2f]
        case .dash: [0x2d]
        case .tilde: [0x7e]
        case .paste: nil
        }
    }

    /// The cap, written the way the design writes it. These are key names and
    /// not the app's own words, so they are the same in every language — all
    /// but Paste, which is a verb.
    public var cap: String {
        switch self {
        case .escape: "Esc"
        case .tab: "Tab"
        case .control: "Ctrl"
        case .up: "↑"
        case .down: "↓"
        case .left: "←"
        case .right: "→"
        case .controlC: "Ctrl-C"
        case .controlD: "Ctrl-D"
        case .controlZ: "Ctrl-Z"
        case .controlR: "Ctrl-R"
        case .controlL: "Ctrl-L"
        case .pipe: "|"
        case .slash: "/"
        case .dash: "-"
        case .tilde: "~"
        case .paste: L10n.string("Paste")
        }
    }

    /// What assistive technology says instead of the glyph, where the glyph is
    /// not a word. A cap that already reads aloud is its own label.
    public var spokenName: String {
        switch self {
        case .up: L10n.string("Up arrow")
        case .down: L10n.string("Down arrow")
        case .left: L10n.string("Left arrow")
        case .right: L10n.string("Right arrow")
        case .pipe: L10n.string("Pipe")
        case .slash: L10n.string("Slash")
        case .dash: L10n.string("Hyphen")
        case .tilde: L10n.string("Tilde")
        default: cap
        }
    }
}

/// The bytes one printable character sends when Ctrl is held.
///
/// The rule is the one every terminal uses: a character in the `@`…`_` band is
/// sent with its top bits cleared, lower case counting as upper. Space is NUL
/// and `?` is DEL, which are the two the band does not cover.
public enum TerminalControlBytes {
    public static func forCharacter(_ character: Character) -> [UInt8]? {
        guard let ascii = character.asciiValue else { return nil }
        if ascii == 0x20 { return [0x00] }
        if ascii == 0x3f { return [0x7f] }
        let upper = (0x61...0x7a).contains(ascii) ? ascii - 0x20 : ascii
        guard (0x40...0x5f).contains(upper) else { return nil }
        return [upper & 0x1f]
    }
}

/// The sticky Ctrl: armed by a tap, spent by the next key, and visible while it
/// waits (`docs/DESIGN.md` § "The terminal").
///
/// A second tap disarms it, so an armed bar is never a trap. Anything that is
/// not one character — a paste, a key that already sends a sequence — spends
/// the latch without being changed, because a person who armed Ctrl and then
/// pressed an arrow meant the arrow.
public struct ControlLatch: Sendable, Equatable {
    public private(set) var isArmed = false

    public init() {}

    /// The tap on Ctrl itself.
    public mutating func toggle() { isArmed.toggle() }

    public mutating func disarm() { isArmed = false }

    /// What to send for these bytes, spending the latch if it was armed.
    public mutating func apply(_ bytes: [UInt8]) -> [UInt8] {
        guard isArmed else { return bytes }
        isArmed = false
        guard bytes.count == 1, let scalar = Unicode.Scalar(UInt32(bytes[0])),
              let control = TerminalControlBytes.forCharacter(Character(scalar)) else {
            return bytes
        }
        return control
    }
}
