import SwiftUI

/// `web/src/features/chat/MarkdownText.tsx` and `Markdown.tsx`: the agent's
/// prose, rendered onto the canvas with no bubble — 14 points on a 1.65 line,
/// breaking a word that would not otherwise fit (`docs/DESIGN.md` § "The
/// timeline").
struct MarkdownText: View, Equatable {
    let text: String

    var body: some View {
        switch MarkdownEngine.shared.document(text) {
        case .plain(let plain):
            Text(verbatim: plain)
                .css(FontSize.fs14, lineHeight: 1.65)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        case .blocks(let stack):
            MDStackView(stack: stack)
                .padding(.top, stack.top)
                .padding(.bottom, stack.bottom)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Blocks one after another, the collapsed margin between each pair.
struct MDStackView: View {
    let stack: MDStack

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(stack.blocks.enumerated()), id: \.element.id) { index, block in
                MDBlockView(block: block)
                    .padding(.top, stack.gaps[index])
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// One block of a message.
struct MDBlockView: View {
    let block: MDBlock

    var body: some View {
        switch block.kind {
        case .text(let text): MDParagraph(text: text)
        case .list(let list): MDListView(list: list)
        case .quote(let stack): MDQuoteView(stack: stack)
        case .group(let stack): MDStackView(stack: stack)
        case .code(let code): MDCodeBlock(code: code)
        case .table(let table): MDTableView(table: table)
        case .rule: Rectangle().fill(Palette.line).frame(height: 1)
        }
    }
}

/// A paragraph, a heading or a tight list item's text.
struct MDParagraph: View {
    let text: MDText
    @Environment(\.mdInk) private var ink

    var body: some View {
        MDRichText(inlines: text.inlines,
                   style: TextStyle(size: text.size, weight: text.weight.fontWeight, lineHeight: 1.65,
                                    tracking: text.tracking),
                   weight: text.weight, ink: ink)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Inline content as one selectable `Text` on the browser's line boxes. The
/// code chips' rounded tint and the links' underlines are drawn by a second,
/// identical copy behind it: SwiftUI draws selectable text itself and skips a
/// custom renderer, and the copy lays its lines out exactly where the
/// selectable one does, so what it draws lands under the right words.
struct MDRichText: View {
    let inlines: [MDInline]
    let style: TextStyle
    let weight: MDWeight
    let ink: Color
    var alignment: TextAlignment = .leading

    var body: some View {
        let text = MDInlineText.text(inlines, size: style.size, weight: weight)
        text.textStyle(style)
            .multilineTextAlignment(alignment)
            .foregroundStyle(ink)
            .tint(ink)
            .textSelection(.enabled)
            .background {
                if inlines.contains(where: \.isDecorated) {
                    text.textStyle(style)
                        .multilineTextAlignment(alignment)
                        .foregroundStyle(ink)
                        .textRenderer(MDTextRenderer())
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
    }
}

extension EnvironmentValues {
    /// The ink a message's text inherits: the page's, or a quote's quieter one.
    @Entry var mdInk: Color = Palette.ink
}
