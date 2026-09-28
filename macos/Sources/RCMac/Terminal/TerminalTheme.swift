import AppKit
import SwiftTerm

/// `terminalTheme.ts` — A38: how the emulator is dressed.
///
/// The app is one light canvas (`docs/DESIGN.md`), so the terminal is light
/// too: a dark rectangle dropped into these screens would read as a different
/// application. The sixteen ANSI colours are chosen for ink on paper — the same
/// greens, ambers and reds the rest of the app uses, darkened enough to stay
/// legible on a near-white background — rather than taken from a palette meant
/// for a black one. The face is `ui-monospace` at 13 points, SF Mono here as
/// in Safari.
enum TerminalTheme {
    static let fontSize: CGFloat = 13
    /// §7.3 keeps 64 KiB on the device; the emulator holds its own lines.
    static let scrollback = 5_000

    static let background: UInt32 = 0xFAFAF9
    static let foreground: UInt32 = 0x111111
    static let cursor: UInt32 = 0x111111
    static let cursorAccent: UInt32 = 0xFAFAF9

    /// Black, red, green, yellow, blue, magenta, cyan, white, then the bright eight.
    static let ansi: [UInt32] = [
        0x111111, 0xC23A2C, 0x1F7A4D, 0x8A6100, 0x2A56B0, 0x8A4FA0, 0x1F6F79, 0x6B6B6B,
        0x767570, 0xD23F31, 0x22A06B, 0xB07C00, 0x1F4FA0, 0x7A3F92, 0x12656F, 0x111111
    ]

    static var font: NSFont { .monospacedSystemFont(ofSize: fontSize, weight: .regular) }

    @MainActor
    static func dress(_ view: TerminalView) {
        view.font = font
        view.nativeForegroundColor = color(foreground)
        view.nativeBackgroundColor = color(background)
        view.caretColor = color(cursor)
        view.caretTextColor = color(cursorAccent)
        view.selectedTextBackgroundColor = NSColor(srgbRed: 17 / 255, green: 17 / 255, blue: 17 / 255, alpha: 0.14)
        view.installColors(ansi.map(ansiColor))
        // xterm.js leaves Option to type the characters macOS puts on it.
        view.optionAsMetaKey = false
        let terminal = view.getTerminal()
        terminal.changeHistorySize(scrollback)
        terminal.setCursorStyle(.blinkBlock)
    }

    static func color(_ hex: UInt32) -> NSColor {
        NSColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }

    /// SwiftTerm's colours are sixteen bits a channel.
    private static func ansiColor(_ hex: UInt32) -> SwiftTerm.Color {
        SwiftTerm.Color(red: UInt16((hex >> 16) & 0xFF) * 257, green: UInt16((hex >> 8) & 0xFF) * 257,
                        blue: UInt16(hex & 0xFF) * 257)
    }
}
