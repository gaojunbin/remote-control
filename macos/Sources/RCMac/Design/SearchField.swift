import SwiftUI

/// `.search-field`: the pill-shaped search the Sessions page and the chat
/// sidebar share — the icon in the tertiary ink, a muted tint that deepens
/// while the field has focus, and at most 320 points wide.
public struct SearchField: View {
    @Binding var text: String
    let placeholder: String
    let iconSize: CGFloat
    @FocusState private var focused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(text: Binding<String>, placeholder: String, iconSize: CGFloat = 15) {
        _text = text
        self.placeholder = placeholder
        self.iconSize = iconSize
    }

    public var body: some View {
        HStack(spacing: Space.sp2) {
            Icon(.search, size: iconSize)
                .foregroundStyle(Palette.inkTertiary)
            FieldText(text: $text, placeholder: placeholder)
                .textFieldStyle(.plain)
                .foregroundStyle(Palette.ink)
                .focused($focused)
        }
        .padding(.horizontal, Space.sp3)
        .frame(maxWidth: 320)
        .frame(height: 34)
        .background(Capsule().fill(focused ? Palette.surfaceActive : Palette.surfaceMuted))
        .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: focused)
        .contentShape(Capsule())
        .onTapGesture { focused = true }
    }
}
