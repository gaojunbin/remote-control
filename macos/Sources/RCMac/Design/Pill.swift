import SwiftUI

/// `.pill`: the 28-point capsule chips and popover triggers are drawn as, with
/// a gap of 6 between what it holds. `.pill.quiet` reads as state rather than
/// as a control: no tint, the secondary ink, almost no padding.
public struct PillStyle: ButtonStyle {
    public var quiet: Bool

    public init(quiet: Bool = false) { self.quiet = quiet }

    public func makeBody(configuration: Configuration) -> some View {
        PillBody(configuration: configuration, quiet: quiet)
    }
}

extension ButtonStyle where Self == PillStyle {
    public static var pill: PillStyle { PillStyle() }
    public static var quietPill: PillStyle { PillStyle(quiet: true) }
}

private struct PillBody: View {
    let configuration: ButtonStyleConfiguration
    let quiet: Bool
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.isFocused) private var isFocused
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovered = false

    var body: some View {
        let lit = isEnabled && !quiet && (isHovered || configuration.isPressed)
        let shape = RoundedRectangle(cornerRadius: isFocused ? 4 : 14, style: .circular)
        HStack(spacing: 6) { configuration.label }
            .font(.system(size: FontSize.fs13))
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
            .padding(.horizontal, quiet ? 2 : 11)
            .frame(height: 28)
            .foregroundStyle(foreground)
            .background(shape.fill(quiet ? Color.clear : (lit ? Palette.surfaceActive : Palette.surfaceMuted)))
            .contentShape(shape)
            .opacity(isEnabled ? 1 : 0.6)
            .focusOutline(isFocused)
            .onHover { isHovered = $0 }
            .pointerStyle(isEnabled ? .link : nil)
            .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: lit)
    }

    private var foreground: Color {
        if !isEnabled { return Palette.inkTertiary }
        return quiet ? Palette.inkSecondary : Palette.ink
    }
}
