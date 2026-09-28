import RCCore
import SwiftUI

/// `web/src/features/chat/blocks/ThinkingRow.tsx`: one quiet row, "Thought for
/// 12s" or "Thinking…", that opens to what the agent thought.
struct ThinkingRow: View {
    let text: String
    let thinking: StreamTextPayload
    @State private var open = false
    @State private var hovered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ChatStrutLine {
                Button { open.toggle() } label: {
                    HStack(spacing: 6) {
                        Icon(.chevronRight, size: Self.iconSize)
                            .foregroundStyle(Palette.inkTertiary)
                            .rotationEffect(.degrees(open ? 90 : 0))
                            .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: open)
                        Text(label).css(FontSize.fs13)
                    }
                    .foregroundStyle(hovered && !text.isEmpty ? Palette.ink : Palette.inkSecondary)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.chatBare)
                .disabled(text.isEmpty)
                .onHover { hovered = $0 }
                .pointerStyle(text.isEmpty ? nil : .link)
                // An `inline-flex` box whose items are centred sits on the
                // line by its first item's baseline, which an icon has none
                // of: its bottom edge stands in for one.
                .alignmentGuide(.firstTextBaseline) { box in (box.height + Self.iconSize) / 2 }
            }
            if open && !text.isEmpty {
                Text(verbatim: ChatPreText.display(text))
                    .css(FontSize.fs13, lineHeight: 1.6)
                    .foregroundStyle(Palette.inkSecondary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, Space.sp4 + 2)
                    .background(alignment: .leading) { Rectangle().fill(Palette.line).frame(width: 2) }
                    .padding(.top, Space.sp2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private nonisolated static let iconSize: CGFloat = 12

    private var label: String {
        guard thinking.done else { return S.chat.thinking }
        let spent = Format.duration(Double(thinking.durationMS ?? 0))
        return S.chat.thoughtFor(spent.isEmpty ? "—" : spent)
    }
}
