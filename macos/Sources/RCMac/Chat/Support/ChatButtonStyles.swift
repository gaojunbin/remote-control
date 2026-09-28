import SwiftUI

/// `.link-btn`: no border, no fill, no padding, the surrounding size, and an
/// underline 2 points below the text, in the ink — or in the surrounding ink
/// where the rule says `color: inherit`.
struct ChatLinkButtonStyle: ButtonStyle {
    var inheritsColor = false

    func makeBody(configuration: Configuration) -> some View {
        ChatLinkButtonBody(configuration: configuration, inheritsColor: inheritsColor)
    }
}

private struct ChatLinkButtonBody: View {
    let configuration: ButtonStyleConfiguration
    let inheritsColor: Bool
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.isFocused) private var isFocused

    var body: some View {
        configuration.label
            .modifier(ChatInkIfNeeded(apply: !inheritsColor))
            .opacity(isEnabled ? 1 : 0.5)
            .contentShape(Rectangle())
            .focusOutline(isFocused)
            .pointerStyle(isEnabled ? .link : nil)
    }
}

private struct ChatInkIfNeeded: ViewModifier {
    let apply: Bool

    func body(content: Content) -> some View {
        if apply { content.foregroundStyle(Palette.ink) } else { content }
    }
}

extension ButtonStyle where Self == ChatLinkButtonStyle {
    static var chatLink: ChatLinkButtonStyle { ChatLinkButtonStyle() }
    static var chatInheritedLink: ChatLinkButtonStyle { ChatLinkButtonStyle(inheritsColor: true) }
}

/// The underlined words of a `.link-btn` or a Markdown link: the underline
/// sits `offset` below the baseline, one point thick, in the text's own ink.
struct ChatUnderlinedText: View {
    let text: String
    let style: TextStyle
    var offset: CGFloat = 2

    var body: some View {
        Text(text)
            .textStyle(style)
            .overlay(alignment: .topLeading) {
                Rectangle()
                    .frame(height: 1)
                    .offset(y: style.baseline + offset)
                    .allowsHitTesting(false)
            }
    }
}

/// A `<button>` the stylesheet draws itself: no press tint, and no dimming
/// when disabled, because `button { color: inherit }` keeps the ink.
struct ChatBareButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label }
}

extension ButtonStyle where Self == ChatBareButtonStyle {
    static var chatBare: ChatBareButtonStyle { ChatBareButtonStyle() }
}

/// `.btn.small.primary` stretched across a flex column, as the resume form's
/// Set is: the foundation's `.btn` keeps its own width, and a column item in
/// the browser takes the column's.
struct ChatStretchedPrimaryStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        ChatStretchedPrimaryBody(configuration: configuration)
    }
}

private struct ChatStretchedPrimaryBody: View {
    let configuration: ButtonStyleConfiguration
    @Environment(\.isEnabled) private var isEnabled
    @State private var hovered = false

    var body: some View {
        configuration.label
            .font(.web(size: FontSize.fs13, weight: .medium))
            .lineLimit(1)
            .foregroundStyle(Palette.inkInverse)
            .frame(maxWidth: .infinity)
            .frame(height: 28)
            .background(Capsule().fill(fill))
            .contentShape(Capsule())
            .onHover { hovered = $0 }
            .pointerStyle(isEnabled ? .link : nil)
    }

    private var fill: Color {
        guard isEnabled else { return Color(hex: 0xB9B9B4) }
        return hovered || configuration.isPressed ? Color(hex: 0x262626) : Palette.ink
    }
}
