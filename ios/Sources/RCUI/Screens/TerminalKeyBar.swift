import SwiftUI
import RCCore
#if os(iOS)
import UIKit
#endif

/// The one thing that decides whether a shell is usable on a phone
/// (`docs/DESIGN.md` § "The terminal" → **The phone's key bar**).
///
/// One row above the keyboard, scrolling sideways when it must, in the order
/// the design lists: Esc · Tab · Ctrl · ↑ · ↓ · ← · → · Ctrl-C · Ctrl-D ·
/// Ctrl-Z · Ctrl-R · Ctrl-L · | · / · - · ~ · Paste. Every cap sends the bytes
/// `TerminalKey` names and nothing app-specific; Ctrl is sticky and shows it.
struct TerminalKeyBar: View {
    /// Whether the next key is sent as a control character.
    let isControlArmed: Bool
    let onKey: (TerminalKey) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Theme.Space.tight) {
                ForEach(TerminalKey.bar) { key in
                    cap(key)
                }
            }
            .padding(.horizontal, Theme.Space.small)
            .padding(.vertical, Theme.Space.tight)
        }
        .frame(maxWidth: .infinity)
        .barBackground()
        .overlay(alignment: .top) { Divider() }
        .accessibilityIdentifier("terminal.keyBar")
    }

    private func cap(_ key: TerminalKey) -> some View {
        Button { onKey(key) } label: {
            Text(verbatim: key.cap)
                .font(.system(.footnote, design: key == .paste ? .default : .monospaced))
                .foregroundStyle(armed(key) ? Theme.onAccent : Theme.ink)
                .padding(.horizontal, Theme.Space.small)
                .frame(minWidth: 40, minHeight: 34)
                .background(armed(key) ? Theme.accent : Theme.quietFill,
                            in: RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(key.spokenName)
        .accessibilityAddTraits(armed(key) ? [.isSelected] : [])
        .accessibilityIdentifier("terminal.key.\(key.rawValue)")
    }

    private func armed(_ key: TerminalKey) -> Bool { key == .control && isControlArmed }
}

/// What the clipboard holds, as bytes to type. The phone is the only place this
/// is asked for, and nothing else in the app reads the pasteboard.
enum TerminalPasteboard {
    static func bytes() -> Data? {
        #if os(iOS)
        guard let text = UIPasteboard.general.string, !text.isEmpty else { return nil }
        let bytes = Data(text.utf8)
        return bytes.count <= TerminalLimits.maxInputBytes ? bytes : nil
        #else
        return nil
        #endif
    }
}
