import Foundation

/// The keys the composer's field answers itself. Everything else is the text
/// view's.
enum ComposerKey: Sendable {
    case up, down, escape, tab, enter

    /// Return and the keypad's Enter are both Enter, as they are to a browser.
    init?(keyCode: UInt16) {
        switch keyCode {
        case 126: self = .up
        case 125: self = .down
        case 53: self = .escape
        case 48: self = .tab
        case 36, 76: self = .enter
        default: return nil
        }
    }
}

/// What one key press does in the field.
enum ComposerKeyAction: Equatable, Sendable {
    /// Not the composer's: the text view does what it always does with it.
    case pass
    /// A27: the panel's highlight moves to this row.
    case highlight(Int)
    /// A27: Esc puts the panel away until the draft changes again.
    case dismissPanel
    /// A27: the highlighted row goes into the field.
    case takeRow
    /// The primary slot's action — Send, Queue, Answer — if the slot holds it.
    case submit
}

/// `onKeyDown` and `onCommandKey` in `Composer.tsx`.
///
/// Enter sends and Shift+Enter breaks the line. **The Enter that confirms an
/// input method's composition never sends** (`docs/DESIGN.md` § "The
/// composer"): under a Chinese or Japanese input method the letters are
/// composed first, and Enter only puts them in the field. The web has to infer
/// that from browser events (`useImeGuard.ts`); AppKit says so directly — the
/// text view holds marked text — so the press is the input method's and
/// nothing here looks at it.
enum ComposerKeys {
    /// The panel as the key finds it: how many rows it shows, which one is
    /// highlighted, and whether taking that row would change the field.
    struct Panel: Equatable, Sendable {
        let rows: Int
        let highlight: Int
        let takingChangesField: Bool
    }

    static func action(for key: ComposerKey, shift: Bool, hasMarkedText: Bool,
                       panel: Panel?) -> ComposerKeyAction {
        if hasMarkedText { return .pass }
        if let panel, panel.rows > 0 {
            let at = min(panel.highlight, panel.rows - 1)
            switch key {
            case .down: return .highlight((at + 1) % panel.rows)
            case .up: return .highlight((at + panel.rows - 1) % panel.rows)
            case .escape: return .dismissPanel
            case .tab: return shift ? .pass : .takeRow
            // The terminal's second Enter: a row already in the field runs.
            case .enter:
                if shift { return .pass }
                return panel.takingChangesField ? .takeRow : .submit
            }
        }
        return key == .enter && !shift ? .submit : .pass
    }
}
