import RCCore
import SwiftUI

/// `labelOf` in `Composer.tsx`: the label an agent's list gives an id, or the
/// id itself. The agent's own ids need not appear in its lists — `auto` is a
/// real Claude permission mode the device does not advertise — so an unknown
/// one is shown as it arrived (A17).
enum ComposerLabels {
    static func label(_ options: [AgentOption], _ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return options.first { $0.id == value }?.label ?? value
    }

    /// "Opus 4.6 High": what runs, and how hard, in one line.
    static func line(_ pair: LabelPair) -> String {
        [pair.model, pair.effort].compactMap { $0 }.joined(separator: " ")
    }
}

/// The model chip's contents: the lightning while a faster tier is on, then
/// "<model> <effort>". Every copy is drawn the same way, because the hidden
/// ones are what give the chip its width.
struct ModelChipLabel: View {
    let pair: LabelPair
    let glyph: Bool
    var fallback = ""

    var body: some View {
        HStack(spacing: 5) {
            if glyph { Icon(.zap, size: 12).foregroundStyle(Palette.attention) }
            Text(ComposerLabels.line(pair).isEmpty ? fallback : ComposerLabels.line(pair))
                .css(FontSize.fs12)
        }
    }
}

/// A17: one value a terminal chose, where its picker would be. It is the shape
/// of the trigger beside it, opens nothing, and carries the whole sentence for
/// assistive technology, because on its own "auto" says nothing about who set
/// it. The caller draws none for a value the device has not seen.
struct TerminalSettingChip: View {
    let name: String
    let text: String
    let speed: String?

    var body: some View {
        let shown = speed.map { "\(text) · \($0)" } ?? text
        let sentence = S.composer.setInTerminal(name, shown)
        HStack(spacing: 4) {
            if speed != nil { Icon(.zap, size: 12).foregroundStyle(Palette.attention) }
            Text(text).css(FontSize.fs12)
        }
        .lineLimit(1)
        .fixedSize()
        .padding(.horizontal, 11)
        .frame(height: 26)
        .foregroundStyle(Palette.inkSecondary)
        .background(Capsule().fill(Palette.surfaceMuted))
        .help(sentence)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(sentence)
    }
}
