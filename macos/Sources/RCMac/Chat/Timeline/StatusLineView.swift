import SwiftUI

/// `.status-line`: a dot and a sentence, 13 points, in the tone's ink, and a
/// Take over link at its end where the words invite one.
struct StatusLineView: View {
    let line: StatusLineModel
    let onTakeover: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: Space.sp2) {
            Dot(dot, pulses: line.tone == .running)
            Text(verbatim: line.text)
                .css(FontSize.fs13)
                .textSelection(.enabled)
            if line.offersTakeover {
                // The flex gap and the link's own 4 points of margin.
                Button(action: onTakeover) {
                    ChatUnderlinedText(text: S.chat.takeOver, style: TextStyle(size: FontSize.fs13))
                }
                .buttonStyle(.chatInheritedLink)
                .padding(.leading, Space.sp1)
            }
        }
        .foregroundStyle(ink)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
    }

    private var dot: DotStyle {
        switch line.tone {
        case .running: .online
        case .attention: .attention
        case .error: .error
        case .muted: .idle
        }
    }

    private var ink: Color {
        switch line.tone {
        case .running: Palette.running
        case .attention: Palette.attention
        case .error: Palette.danger
        case .muted: Palette.inkSecondary
        }
    }
}
