import AppKit
import SwiftUI

/// `CodeBlock` in `Markdown.tsx` with `chat.css`'s `.md-pre` and the github
/// theme: a bordered block on the sunken surface, 12 points of padding, 12-point
/// monospace on a 1.6 line, scrolling sideways rather than wrapping. A block
/// rehype-highlight marked `hljs` holds a white inner box of its own, 12 points
/// in again (`pre code.hljs`), in the theme's ink. The copy control shows while
/// the pointer is over the block.
struct MDCodeBlock: View {
    let code: MDCode
    @State private var hovered = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            pre
            MDCopyButton(source: code.source)
                .padding(6)
                .opacity(hovered ? 1 : 0)
                .animation(Motion.ease(Motion.durFast), value: hovered)
        }
        .onHover { hovered = $0 }
    }

    @ViewBuilder private var pre: some View {
        if code.highlighted {
            ChatBoundedScroll(maxHeight: .infinity) { lines.padding(Space.sp3) }
                .background(Color.white)
                .padding(Space.sp3)
                .chatBox(radius: Radius.sm, background: Palette.surfaceSunken)
        } else {
            ChatBoundedScroll(maxHeight: .infinity) { lines.padding(Space.sp3) }
                .chatBox(radius: Radius.sm, background: Palette.surfaceSunken)
        }
    }

    private var lines: some View {
        MDInlineText.concat(code.lines.enumerated().flatMap { index, line in
            (index == 0 ? [] : [Text(verbatim: "\n")]) + line.map(Self.segment)
        }[...])
        .css(FontSize.fs12, lineHeight: 1.6, mono: true)
        .fixedSize()
        .textSelection(.enabled)
    }

    private static func segment(_ run: MDCodeRun) -> Text {
        var text = AttributedString(run.text)
        text.foregroundColor = Color(hex: run.style.color)
        if let background = run.style.background { text.backgroundColor = Color(hex: background) }
        var segment = Text(text)
        if run.style.bold { segment = segment.bold() }
        if run.style.italic { segment = segment.italic() }
        return segment
    }
}

/// `.md-copy`: a 26-point square on the surface with a light edge, the copy
/// icon in the secondary ink, a check for a moment once the code is copied.
private struct MDCopyButton: View {
    let source: String
    @State private var copied = false

    var body: some View {
        Button(action: copy) {
            Icon(copied ? .check : .copy, size: 13)
                .frame(width: 24, height: 24)
                .foregroundStyle(Palette.inkSecondary)
                .padding(1)
                .background(RoundedRectangle(cornerRadius: 6, style: .circular).fill(Palette.surface))
                .chatBorder(ChatBorder(width: 1, radius: 6), color: Palette.line)
                .contentShape(Rectangle())
        }
        .buttonStyle(.chatBare)
        .pointerStyle(.link)
        .accessibilityLabel(S.chat.copyCode)
    }

    private func copy() {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(source, forType: .string)
        copied = true
        Task {
            try? await Task.sleep(for: .milliseconds(1400))
            copied = false
        }
    }
}
