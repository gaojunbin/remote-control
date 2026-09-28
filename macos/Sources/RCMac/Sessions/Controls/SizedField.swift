import SwiftUI

/// `.field` at a height the foundation's `WebField` does not offer: the New
/// session drawer's working directory (46 points, 15-point mono, room at the
/// trailing edge for the line that says whether it exists) and the directory
/// picker's new folder name (32 points). The chrome is the web's: a 1-point
/// `--line` edge on a 12-point radius, and when focused the ink edge, the 3-point
/// ring and the `:focus-visible` outline on a 4-point radius.
struct SizedField: View {
    @Binding var text: String
    var placeholder = ""
    var mono = false
    var fontSize: CGFloat
    var height: CGFloat
    var trailingPadding: CGFloat = Space.sp3
    var autofocus = false
    /// Bumped to take the focus back, as a browser field keeps it through
    /// being disabled for a moment.
    var refocus = 0
    var onFocus: (Bool) -> Void = { _ in }
    var onSubmit: () -> Void = {}
    @FocusState private var focused: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: focused ? 4 : Radius.md, style: .circular)
        ZStack(alignment: .leading) {
            if text.isEmpty {
                Text(placeholder)
                    .foregroundStyle(placeholderInk)
                    .lineLimit(1)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            TextField("", text: $text)
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
                .focused($focused)
                .onSubmit(onSubmit)
                .accessibilityLabel(placeholder)
        }
        .font(TextStyle(size: fontSize, mono: mono).font)
        .foregroundStyle(Palette.ink)
        // The web's padding is inside a 1-point border, and the browser's text
        // sits half a point higher than AppKit centres it.
        .padding(.leading, Space.sp3 + 1)
        .padding(.trailing, trailingPadding + 1)
        .padding(.bottom, 1)
        .frame(maxWidth: .infinity)
        .frame(height: height)
        .background(shape.fill(Palette.surface))
        .overlay(shape.strokeBorder(focused ? Palette.ink : Palette.line, lineWidth: 1))
        .background {
            if focused {
                RoundedRectangle(cornerRadius: 4 + 3, style: .circular)
                    .fill(Color(rgb: (17, 17, 17), opacity: 0.06))
                    .padding(-3)
            }
        }
        .focusOutline(focused)
        .onAppear { if autofocus { focused = true } }
        .onChange(of: refocus) { focused = true }
        .onChange(of: focused) { _, now in onFocus(now) }
    }
}
