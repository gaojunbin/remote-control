import SwiftUI

/// Chrome's placeholder grey, which the web's fields keep: none of its
/// stylesheets set `::placeholder`.
let placeholderInk = Color(hex: 0x757575)

/// The text of a `.field`: what was typed, or the placeholder in the web's
/// grey while nothing has been. It takes no focus and draws no edge of its
/// own, so a form can give it both: `.focused(…)` and `.fieldChrome(…)`.
public struct FieldText: View {
    @Binding var text: String
    let placeholder: String
    let secure: Bool
    let mono: Bool

    public init(text: Binding<String>, placeholder: String = "", secure: Bool = false, mono: Bool = false) {
        _text = text
        self.placeholder = placeholder
        self.secure = secure
        self.mono = mono
    }

    public var body: some View {
        ZStack(alignment: .leading) {
            if text.isEmpty {
                Text(placeholder)
                    .foregroundStyle(placeholderInk)
                    .lineLimit(1)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            entry.accessibilityLabel(placeholder)
        }
        .font(mono ? .system(size: FontSize.fs13, design: .monospaced) : .web(size: FontSize.fs14))
    }

    @ViewBuilder private var entry: some View {
        if secure {
            SecureField("", text: $text)
        } else {
            TextField("", text: $text).autocorrectionDisabled()
        }
    }
}

extension View {
    /// `.field`: 40 points tall, a 1-point `--line` edge on a 12-point radius
    /// over the surface, the text 12 points inside the edge. Focused, it is
    /// what the browser draws for a focused input: the edge turns ink and a
    /// 3-point ring of 6 % ink surrounds it (`.field:focus`), and because a
    /// text field always matches `:focus-visible` the radius drops to 4 and a
    /// 2-point ink outline is drawn 2 points outside.
    public func fieldChrome(focused: Bool) -> some View {
        let shape = RoundedRectangle(cornerRadius: focused ? 4 : Radius.md, style: .circular)
        return textFieldStyle(.plain)
            .foregroundStyle(Palette.ink)
            // The web's 12 points of padding are inside a 1-point border, and
            // the browser's text sits half a point higher than AppKit centres it.
            .padding(.horizontal, Space.sp3 + 1)
            .padding(.bottom, 1)
            .frame(maxWidth: .infinity)
            .frame(height: 40)
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
    }
}

/// A `.field` with its own focus: `FieldText` in the field's chrome.
public struct WebField: View {
    @Binding var text: String
    let placeholder: String
    let secure: Bool
    let mono: Bool
    let autofocus: Bool
    let onSubmit: () -> Void
    @FocusState private var focused: Bool

    public init(text: Binding<String>, placeholder: String = "", secure: Bool = false, mono: Bool = false,
                autofocus: Bool = false, onSubmit: @escaping () -> Void = {}) {
        _text = text
        self.placeholder = placeholder
        self.secure = secure
        self.mono = mono
        self.autofocus = autofocus
        self.onSubmit = onSubmit
    }

    public var body: some View {
        FieldText(text: $text, placeholder: placeholder, secure: secure, mono: mono)
            .focused($focused)
            .onSubmit(onSubmit)
            .fieldChrome(focused: focused)
            .onAppear { if autofocus { focused = true } }
    }
}
