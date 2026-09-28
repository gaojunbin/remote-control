import SwiftUI

/// `.composer-bottom .pill`: the control row's chips and popover triggers — a
/// pill two points shorter than the rest of the app's, in 12-point type.
struct ComposerChipStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        ComposerChipBody(configuration: configuration)
    }
}

private struct ComposerChipBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.isFocused) private var isFocused
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovered = false

    var body: some View {
        let lit = isEnabled && (isHovered || configuration.isPressed)
        let shape = RoundedRectangle(cornerRadius: isFocused ? 4 : 13, style: .circular)
        HStack(spacing: 6) { configuration.label }
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
            .padding(.horizontal, 11)
            .frame(height: 26)
            .foregroundStyle(isEnabled ? Palette.ink : Palette.inkTertiary)
            .background(shape.fill(lit ? Palette.surfaceActive : Palette.surfaceMuted))
            .contentShape(shape)
            .opacity(isEnabled ? 1 : 0.6)
            .focusOutline(isFocused)
            .onHover { isHovered = $0 }
            .pointerStyle(isEnabled ? .link : nil)
            .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: lit)
    }
}

/// `.pill.send-alt`: the ⋯ beside Send, a 28-point circle in the pill's tint
/// holding the one character in the secondary ink.
struct SendAltStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        SendAltBody(configuration: configuration)
    }
}

private struct SendAltBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isFocused) private var isFocused
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovered = false

    var body: some View {
        let lit = isHovered || configuration.isPressed
        let shape = RoundedRectangle(cornerRadius: isFocused ? 4 : 14, style: .circular)
        configuration.label
            .frame(width: 28, height: 28)
            .foregroundStyle(Palette.inkSecondary)
            .background(shape.fill(lit ? Palette.surfaceActive : Palette.surfaceMuted))
            .contentShape(shape)
            .focusOutline(isFocused)
            .onHover { isHovered = $0 }
            .pointerStyle(.link)
            .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: lit)
    }
}

/// `.link-btn`: words that act, underlined two points below their baseline, in
/// the ink of the line they sit in unless told otherwise; disabled, the
/// tertiary ink.
struct LinkButton: View {
    let title: String
    let size: CGFloat
    var color: Color = Palette.ink
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            LinkText(title: title, size: size)
                .foregroundStyle(isEnabled ? color : Palette.inkTertiary)
        }
        .buttonStyle(.plain)
        .pointerStyle(isEnabled ? .link : nil)
    }
}

/// The words of a `.link-btn`, with the underline where the browser draws
/// `text-underline-offset: 2px`: its thickness below a gap under the baseline.
struct LinkText: View {
    let title: String
    let size: CGFloat

    var body: some View {
        let style = TextStyle(size: size)
        Text(title)
            .textStyle(style)
            .overlay(alignment: .topLeading) {
                GeometryReader { proxy in
                    Rectangle()
                        .frame(width: proxy.size.width, height: 1)
                        .offset(y: style.baseline + 2)
                }
            }
    }
}
