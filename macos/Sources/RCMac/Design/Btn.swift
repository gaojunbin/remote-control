import SwiftUI

/// `.btn`'s variants: the default tinted button, the one filled primary per
/// surface, the danger tint and the ghost (`docs/DESIGN.md` § "Buttons").
public enum ButtonVariant: Sendable {
    case standard
    case primary
    case danger
    case ghost
}

/// `.btn`, `.btn.small` and `.btn.block`.
public enum ButtonSize: Sendable {
    case regular
    case small
    /// The full width of its container, one step taller: the sign-in button.
    case block

    var height: CGFloat {
        switch self {
        case .regular: 36
        case .small: 28
        case .block: 44
        }
    }

    var padding: CGFloat {
        switch self {
        case .regular, .block: Space.sp4
        case .small: Space.sp3
        }
    }

    var fontSize: CGFloat {
        switch self {
        case .regular: FontSize.fs14
        case .small: FontSize.fs13
        case .block: FontSize.fs15
        }
    }
}

/// `web/src/components/ui.css` `.btn`: a pill with no border, 500-weight type
/// and a gap of 8 between an icon and its label. The pointer and a press tint
/// it one step darker; disabled fades it, except the primary, which turns grey.
public struct BtnStyle: ButtonStyle {
    public var variant: ButtonVariant
    public var size: ButtonSize

    public init(_ variant: ButtonVariant = .standard, size: ButtonSize = .regular) {
        self.variant = variant
        self.size = size
    }

    public func makeBody(configuration: Configuration) -> some View {
        BtnBody(configuration: configuration, variant: variant, size: size)
    }
}

extension ButtonStyle where Self == BtnStyle {
    /// `.buttonStyle(.btn(.primary))`, `.buttonStyle(.btn(.ghost, size: .small))`.
    public static func btn(_ variant: ButtonVariant = .standard, size: ButtonSize = .regular) -> BtnStyle {
        BtnStyle(variant, size: size)
    }
}

private struct BtnBody: View {
    let configuration: ButtonStyleConfiguration
    let variant: ButtonVariant
    let size: ButtonSize
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.isFocused) private var isFocused
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovered = false

    var body: some View {
        // `:focus-visible` gives every focused control a 4 px radius, the pill
        // included, and an ink outline that follows it.
        let shape = RoundedRectangle(cornerRadius: isFocused ? 4 : size.height / 2, style: .circular)
        HStack(spacing: Space.sp2) { configuration.label }
            .font(.system(size: size.fontSize, weight: .medium))
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
            .padding(.horizontal, size.padding)
            .frame(maxWidth: size == .block ? .infinity : nil)
            .frame(height: size.height)
            .foregroundStyle(foreground)
            .background(shape.fill(background))
            .contentShape(shape)
            .opacity(isEnabled || variant == .primary ? 1 : 0.45)
            .focusOutline(isFocused)
            .onHover { isHovered = $0 }
            .pointerStyle(isEnabled ? .link : nil)
            .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: isHovered)
            .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: isEnabled)
    }

    /// `:hover` and `:active` tint alike: a press is always under the pointer.
    private var isLit: Bool { isEnabled && (isHovered || configuration.isPressed) }

    private var background: Color {
        switch variant {
        case .standard: isLit ? Palette.surfaceActive : Palette.surfaceMuted
        case .primary: isEnabled ? (isLit ? Color(hex: 0x262626) : Palette.ink) : Color(hex: 0xB9B9B4)
        case .danger: isLit ? Color(hex: 0xFBDEDB) : Palette.dangerSoft
        case .ghost: isLit ? Palette.surfaceMuted : Color.clear
        }
    }

    private var foreground: Color {
        switch variant {
        case .standard, .ghost: Palette.ink
        case .primary: Palette.inkInverse
        case .danger: Palette.danger
        }
    }
}

/// `web/src/components/Button.tsx`: a `.btn` with an optional leading icon and
/// the busy state — a spinner before the label, and no second press.
public struct Btn: View {
    let title: String
    let icon: LucideIcon?
    let variant: ButtonVariant
    let size: ButtonSize
    let busy: Bool
    let action: () -> Void

    public init(_ title: String, icon: LucideIcon? = nil, variant: ButtonVariant = .standard,
                size: ButtonSize = .regular, busy: Bool = false, action: @escaping () -> Void) {
        self.title = title
        self.icon = icon
        self.variant = variant
        self.size = size
        self.busy = busy
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(spacing: Space.sp2) {
                if busy { Spinner() }
                if let icon { Icon(icon, size: size == .small ? 14 : 15) }
                Text(title).css(size.fontSize, weight: .medium)
            }
        }
        .buttonStyle(.btn(variant, size: size))
        .disabled(busy)
    }
}
