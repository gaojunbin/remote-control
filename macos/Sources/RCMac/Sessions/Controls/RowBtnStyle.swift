import SwiftUI

/// `.btn.small` stretched to 32 points, as `.picker-new-row .btn` sets it so the
/// new folder row's buttons stand as tall as its field. The foundation's
/// `BtnStyle` has no height of its own to give; the rest is its: a pill of
/// 13-point, 500-weight type, the tint one step darker under the pointer, the
/// default faded and the primary grey while disabled.
struct RowBtnStyle: ButtonStyle {
    let primary: Bool

    func makeBody(configuration: Configuration) -> some View {
        RowBtnBody(configuration: configuration, primary: primary)
    }
}

private struct RowBtnBody: View {
    let configuration: ButtonStyleConfiguration
    let primary: Bool
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.isFocused) private var isFocused
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovered = false

    static let height: CGFloat = 32

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: isFocused ? 4 : Self.height / 2, style: .circular)
        let lit = isEnabled && (isHovered || configuration.isPressed)
        HStack(spacing: Space.sp2) { configuration.label }
            .font(TextStyle(size: FontSize.fs13, weight: .medium).font)
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
            .padding(.horizontal, Space.sp3)
            .frame(height: Self.height)
            .foregroundStyle(primary ? Palette.inkInverse : Palette.ink)
            .background(shape.fill(fill(lit: lit)))
            .contentShape(shape)
            .opacity(isEnabled || primary ? 1 : 0.45)
            .focusOutline(isFocused)
            .onHover { isHovered = $0 }
            .pointerStyle(isEnabled ? .link : nil)
            .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: lit)
    }

    private func fill(lit: Bool) -> Color {
        guard primary else { return lit ? Palette.surfaceActive : Palette.surfaceMuted }
        guard isEnabled else { return Color(hex: 0xB9B9B4) }
        return lit ? Color(hex: 0x262626) : Palette.ink
    }
}
