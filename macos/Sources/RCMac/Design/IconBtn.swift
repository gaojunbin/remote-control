import SwiftUI

/// `.icon-btn`: a 28-point square with an 8-point radius, no border and no
/// fill until the pointer is over it, the icon in the secondary ink.
public struct IconBtnStyle: ButtonStyle {
    public var side: CGFloat

    public init(side: CGFloat = 28) { self.side = side }

    public func makeBody(configuration: Configuration) -> some View {
        IconBtnBody(configuration: configuration, side: side)
    }
}

extension ButtonStyle where Self == IconBtnStyle {
    public static var iconBtn: IconBtnStyle { IconBtnStyle() }
}

private struct IconBtnBody: View {
    let configuration: ButtonStyleConfiguration
    let side: CGFloat
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.isFocused) private var isFocused
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovered = false

    var body: some View {
        let lit = isEnabled && (isHovered || configuration.isPressed)
        let shape = RoundedRectangle(cornerRadius: isFocused ? 4 : Radius.sm, style: .circular)
        configuration.label
            .frame(width: side, height: side)
            .foregroundStyle(lit ? Palette.ink : Palette.inkSecondary)
            .background(shape.fill(lit ? Palette.surfaceActive : Color.clear))
            .contentShape(shape)
            .focusOutline(isFocused)
            .onHover { isHovered = $0 }
            .pointerStyle(isEnabled ? .link : nil)
            .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: lit)
    }
}

/// An icon button with the accessible name the web gives it as `aria-label`.
/// The web sets no `title` on these, so there is no tooltip here either.
public struct IconBtn: View {
    let icon: LucideIcon
    let size: CGFloat
    let label: String
    let action: () -> Void

    public init(_ icon: LucideIcon, size: CGFloat = 16, label: String, action: @escaping () -> Void) {
        self.icon = icon
        self.size = size
        self.label = label
        self.action = action
    }

    public var body: some View {
        Button(action: action) { Icon(icon, size: size) }
            .buttonStyle(.iconBtn)
            .accessibilityLabel(label)
    }
}
