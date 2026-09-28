import RCCore
import SwiftUI

/// One stop per effort level, in the order the agent lists them. The pill is
/// filled to the thumb and carries one dot per stop, so the number of levels
/// can be read before anything moves; nothing else is drawn, because the word
/// beside the model name is what the thumb is saying. The value is sent only
/// when the thumb is released — the range input's `change`, not its `input` —
/// and an arrow key moves one stop and sends it, as the input's keyboard does.
struct EffortSlider: View {
    let efforts: [AgentOption]
    /// The live stop, owned by the card so the word above can read it; -1
    /// while the session's effort is none of the agent's.
    @Binding var index: Int
    let onCommit: (Int) -> Void
    @FocusState private var focused: Bool

    /// Half a thumb: the span the thumb's centre travels is inset by it.
    private static let inset: CGFloat = 12
    private static let height: CGFloat = 28

    var body: some View {
        let value = max(0, index)
        let last = max(1, efforts.count - 1)
        GeometryReader { proxy in
            let travel = max(0, proxy.size.width - 2 * Self.inset)
            let thumb = Self.inset + CGFloat(value) / CGFloat(last) * travel
            ZStack(alignment: .topLeading) {
                Capsule().fill(Palette.surfaceMuted)
                // Filled from the track's end to the thumb's centre. The
                // lowest stop fills nothing: its cap would only show around
                // the thumb.
                if value > 0 {
                    Capsule().fill(Palette.accent).frame(width: thumb)
                }
                ForEach(efforts.indices, id: \.self) { stop in
                    Circle()
                        .fill(value > 0 && stop <= value ? Color.white.opacity(0.55) : Palette.inkTertiary)
                        .frame(width: 6, height: 6)
                        .position(x: Self.inset + CGFloat(stop) / CGFloat(last) * travel, y: Self.height / 2)
                }
                thumbDisc.position(x: thumb, y: Self.height / 2)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { drag in index = stop(at: drag.location.x, travel: travel, last: last) }
                .onEnded { drag in onCommit(stop(at: drag.location.x, travel: travel, last: last)) })
        }
        .frame(height: Self.height)
        .focusable(interactions: .activate)
        .focused($focused)
        .onKeyPress(keys: [.leftArrow, .downArrow, .rightArrow, .upArrow, .home, .end]) { press in
            step(press.key, value: value)
            return .handled
        }
        .accessibilityElement()
        .accessibilityLabel(S.composer.effort)
        .accessibilityValue(efforts.indices.contains(value) ? efforts[value].label : "")
        .accessibilityAdjustableAction { direction in
            step(direction == .increment ? .rightArrow : .leftArrow, value: value)
        }
    }

    /// A white disc on a quiet shadow, ringed while the keyboard has it.
    private var thumbDisc: some View {
        ZStack {
            if focused {
                Circle().fill(Color(rgb: (17, 17, 17), opacity: 0.22)).frame(width: 30, height: 30)
            }
            Circle()
                .fill(Palette.surface)
                .frame(width: 24, height: 24)
                .boxShadow(BoxShadow(layers: [.init(color: Color(rgb: (0, 0, 0), opacity: 0.24), x: 0, y: 1, blur: 3)]),
                           in: Circle())
        }
    }

    private func stop(at x: CGFloat, travel: CGFloat, last: Int) -> Int {
        guard travel > 0 else { return 0 }
        let fraction = min(1, max(0, (x - Self.inset) / travel))
        return min(efforts.count - 1, Int((fraction * CGFloat(last)).rounded()))
    }

    private func step(_ key: KeyEquivalent, value: Int) {
        let next: Int
        switch key {
        case .leftArrow, .downArrow: next = max(0, value - 1)
        case .rightArrow, .upArrow: next = min(efforts.count - 1, value + 1)
        case .home: next = 0
        default: next = efforts.count - 1
        }
        index = next
        onCommit(next)
    }
}
