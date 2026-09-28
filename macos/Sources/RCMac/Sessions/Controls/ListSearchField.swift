import SwiftUI

/// `.search-field` where the lists size it themselves: the Sessions page's is
/// 34 points tall and at most 320 wide until 640, where it takes the whole line;
/// the chat sidebar's is 32 tall with no cap (`.sidebar-search`). The same pill
/// as the foundation's `SearchField` otherwise — the icon in the tertiary ink,
/// a muted tint that deepens while the field has focus.
struct ListSearchField: View {
    @Binding var text: String
    let placeholder: String
    var iconSize: CGFloat = 15
    var height: CGFloat = 34
    var maxWidth: CGFloat? = 320
    @FocusState private var focused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: Space.sp2) {
            Icon(.search, size: iconSize)
                .foregroundStyle(Palette.inkTertiary)
            // The browser keeps an input's own 2 points of padding, which the
            // stylesheet never resets.
            FieldText(text: $text, placeholder: placeholder)
                .textFieldStyle(.plain)
                .foregroundStyle(Palette.ink)
                .focused($focused)
                .padding(.leading, 2)
        }
        .padding(.horizontal, Space.sp3)
        .frame(maxWidth: maxWidth ?? .infinity)
        .frame(height: height)
        .background(Capsule().fill(focused ? Palette.surfaceActive : Palette.surfaceMuted))
        .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: focused)
        .contentShape(Capsule())
        .onTapGesture { focused = true }
    }
}
