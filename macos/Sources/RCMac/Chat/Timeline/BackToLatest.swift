import SwiftUI

/// `BackToLatest` in `Timeline.tsx`: the way back down, a round button
/// centred at the foot of the transcript, lifted on the shadow alone. It
/// widens into a capsule around that centre when it has a count to carry, and
/// its words are in its accessible name.
struct BackToLatest: View {
    let missed: Int
    let action: () -> Void
    @State private var hovered = false
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let count = missed > 0 ? S.chat.newUpdates(missed) : nil
        Button(action: action) {
            HStack(spacing: Space.sp1) {
                Icon(.arrowDown, size: 16)
                if let count {
                    Text(count)
                        .css(FontSize.fs13, weight: .medium)
                        .monospacedDigit()
                        .fixedSize()
                }
            }
            .padding(.horizontal, count == nil ? 0 : Space.sp3)
            .frame(minWidth: 36, minHeight: 36)
            .foregroundStyle(Palette.ink)
            .background(Capsule().fill(hovered ? Palette.surfaceHover : Palette.surface))
            .boxShadow(Shadow.pop, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.chatBare)
        .onHover { hovered = $0 }
        .pointerStyle(.link)
        .help(S.chat.backToLatest)
        .accessibilityLabel(count.map { "\(S.chat.backToLatest), \($0)" } ?? S.chat.backToLatest)
        // `back-to-latest-in`: from 92 % and transparent over 150 ms.
        .scaleEffect(shown ? 1 : 0.92)
        .opacity(shown ? 1 : 0)
        .onAppear {
            withAnimation(reduceMotion ? nil : .timingCurve(0.22, 0.61, 0.36, 1, duration: 0.15)) { shown = true }
        }
    }
}
