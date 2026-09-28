import RCCore
import SwiftUI

/// A21: standard → each tier the agent lists → standard, one click at a time.
/// The lightning is tinted while a tier is on, the only sign of it in the card.
struct SpeedToggle: View {
    let speeds: [AgentOption]
    let current: String?
    let onSet: (SessionOptions) -> Void

    var body: some View {
        let tier = speeds.first { $0.id == current }
        Button {
            let position = speeds.firstIndex { $0.id == current }.map { $0 + 1 } ?? 0
            let next = speeds.indices.contains(position) ? speeds[position] : nil
            onSet(SessionOptions(speed: .some(next?.id)))
        } label: {
            Icon(.zap, size: 15)
        }
        .buttonStyle(SpeedToggleStyle(on: tier != nil))
        .accessibilityLabel(S.composer.speed(tier?.label ?? S.composer.speedStandard))
        .accessibilityAddTraits(tier != nil ? .isSelected : [])
    }
}

/// `.speed-toggle`: a 28-point square on an 8-point radius in the muted tint,
/// the attention colours while a tier is on.
private struct SpeedToggleStyle: ButtonStyle {
    let on: Bool

    func makeBody(configuration: Configuration) -> some View {
        SpeedToggleBody(configuration: configuration, on: on)
    }
}

private struct SpeedToggleBody: View {
    let configuration: ButtonStyleConfiguration
    let on: Bool
    @Environment(\.isFocused) private var isFocused
    @State private var isHovered = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: isFocused ? 4 : Radius.sm, style: .circular)
        let lit = isHovered || configuration.isPressed
        configuration.label
            .frame(width: 28, height: 28)
            .foregroundStyle(on ? Palette.attention : Palette.inkSecondary)
            .background(shape.fill(on ? Palette.attentionSoft : (lit ? Palette.surfaceActive : Palette.surfaceMuted)))
            .contentShape(shape)
            .focusOutline(isFocused)
            .onHover { isHovered = $0 }
            .pointerStyle(.link)
    }
}
