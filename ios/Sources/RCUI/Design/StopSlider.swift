import SwiftUI

/// The effort slider from `docs/DESIGN.md` § "The model card": a thick pill
/// track filled to the thumb in the accent colour, one small dot at every stop
/// so the number of levels is visible before the thumb moves, and a white disc
/// that snaps to the stops. Nothing else is drawn — no numbers, no labels under
/// the track.
///
/// `index` follows the thumb while it is moving, which is what the word beside
/// the model name reads. `onCommit` fires once, when the thumb is let go, so
/// dragging across four levels is one request rather than four. A tap anywhere
/// on the track moves to the nearest stop and commits it.
struct StopSlider: View {
    /// How many levels there are. One or none draws nothing at all.
    let stops: Int
    @Binding var index: Int
    /// What assistive technology reads as the slider's value — the effort word.
    let value: String
    let onCommit: (Int) -> Void

    private static let trackHeight: CGFloat = 28
    private static let thumbSize: CGFloat = 24
    private static let dotSize: CGFloat = 6

    var body: some View {
        GeometryReader { proxy in
            let travel = max(0, proxy.size.width - Self.thumbSize)
            let centre = Self.thumbSize / 2 + step(over: travel) * CGFloat(index)
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.quietFill)
                Capsule().fill(Theme.accent).frame(width: centre)
                dots(travel: travel, filledUpTo: centre)
                thumb.offset(x: centre - Self.thumbSize / 2)
            }
            .frame(height: Self.trackHeight)
            .contentShape(Rectangle())
            // Zero minimum distance so a tap on a stop is a drag that begins
            // and ends there: one gesture covers both ways of moving.
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { index = stop(at: $0.location.x, travel: travel) }
                    .onEnded {
                        let landed = stop(at: $0.location.x, travel: travel)
                        index = landed
                        onCommit(landed)
                    }
            )
        }
        .frame(height: Self.trackHeight)
        // One selection haptic per stop the thumb crosses, so the levels can
        // be counted without looking.
        .sensoryFeedback(.selection, trigger: index)
        .accessibilityElement()
        .accessibilityValue(value)
        .accessibilityAdjustableAction { direction in
            let moved = direction == .increment ? index + 1 : index - 1
            guard moved >= 0, moved < stops else { return }
            index = moved
            onCommit(moved)
        }
    }

    /// One dot per stop, on top of the fill and under the thumb. The dots on
    /// the filled part are drawn in white so they stay visible against the
    /// accent colour.
    private func dots(travel: CGFloat, filledUpTo: CGFloat) -> some View {
        let step = step(over: travel)
        return ForEach(0..<max(stops, 0), id: \.self) { stop in
            let centre = Self.thumbSize / 2 + step * CGFloat(stop)
            Circle()
                .fill(centre <= filledUpTo ? AnyShapeStyle(Color.white.opacity(0.6))
                                           : AnyShapeStyle(Theme.inkTertiary))
                .frame(width: Self.dotSize, height: Self.dotSize)
                .offset(x: centre - Self.dotSize / 2)
        }
        .accessibilityHidden(true)
    }

    private var thumb: some View {
        Circle()
            .fill(Color.white)
            .frame(width: Self.thumbSize, height: Self.thumbSize)
            .shadow(color: .black.opacity(0.18), radius: 3, y: 1)
    }

    private func step(over travel: CGFloat) -> CGFloat {
        stops > 1 ? travel / CGFloat(stops - 1) : 0
    }

    private func stop(at x: CGFloat, travel: CGFloat) -> Int {
        guard stops > 1, travel > 0 else { return 0 }
        let fraction = (x - Self.thumbSize / 2) / travel
        return min(max(0, Int((fraction * CGFloat(stops - 1)).rounded())), stops - 1)
    }
}
