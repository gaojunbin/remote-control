import SwiftUI

extension View {
    /// A box as the browser draws one: `border` wide on every side in
    /// `borderColor`, the corner `radius` on its outer edge, the background
    /// under the border, and — for `overflow: hidden` — what it holds clipped
    /// to the rounded padding box inside the border.
    func chatBox(radius: CGFloat, border: CGFloat = 1, borderColor: Color = Palette.line,
                background: Color? = nil, clips: Bool = true) -> some View {
        let inner = RoundedRectangle(cornerRadius: max(0, radius - border), style: .circular)
        return modifier(ChatClipIf(clips: clips, shape: inner))
            .padding(border)
            .background {
                if let background { RoundedRectangle(cornerRadius: radius, style: .circular).fill(background) }
            }
            .chatBorder(ChatBorder(width: border, radius: radius), color: borderColor)
    }
}

private struct ChatClipIf<S: Shape>: ViewModifier {
    let clips: Bool
    let shape: S

    func body(content: Content) -> some View {
        if clips { content.clipShape(shape) } else { content }
    }
}
