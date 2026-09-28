import CoreGraphics
import Foundation

/// Turns the web's hast into the chat's blocks with the margins `chat.css`
/// gives them: a paragraph 12 below, a heading 20 above and 8 below, a list,
/// a quote, a code block and a table 12 below, a rule 16 either side, a list
/// item 4 below — collapsed as the browser collapses them, through every box
/// that has no padding or border of its own to stop them.
enum MDBuilder {
    static func document(_ nodes: [HastNode]) -> ChatMarkdown {
        var builder = Builder()
        var stack = builder.stack(nodes, depth: 0)
        // `.md > *:first-child` and `:last-child` zero their own margins, and
        // `.md` holds what still collapses through them: it is a flex item, a
        // formatting context of its own, so nothing leaves it.
        stack.top = stack.blocks.first.map(\.innerTop) ?? 0
        stack.bottom = stack.blocks.last.map(\.innerBottom) ?? 0
        return .blocks(stack)
    }

    static let body: CGFloat = 14
}

private struct Builder {
    private var nextID = 0

    /// The blocks a container holds, with the inline content between them
    /// gathered into anonymous paragraphs of no margin, as CSS wraps it.
    mutating func stack(_ nodes: [HastNode], depth: Int) -> MDStack {
        var blocks: [MDBlock] = []
        var pending: [HastNode] = []
        func flush() {
            guard pending.contains(where: \.isInlineContent) else {
                pending.removeAll()
                return
            }
            let inlines = MDInlines.build(pending, marks: MDMarks(), base: .regular)
            pending.removeAll()
            guard !inlines.isEmpty else { return }
            blocks.append(block(.text(MDText(inlines: inlines, size: MDBuilder.body, weight: .regular)), top: 0, bottom: 0))
        }
        for node in nodes {
            guard case .element(let element) = node else {
                pending.append(node)
                continue
            }
            switch blockElement(element, depth: depth) {
            case .inline:
                pending.append(node)
            case .hidden:
                flush()
            case .block(let made):
                flush()
                blocks.append(made)
            }
        }
        flush()
        return Self.collapse(blocks)
    }

    private enum Made {
        /// Inline content: part of the anonymous paragraph around it.
        case inline
        /// A block that takes no room, such as the footnotes' screen-reader
        /// heading, positioned out of the flow.
        case hidden
        case block(MDBlock)
    }

    private mutating func blockElement(_ element: HastElement, depth: Int) -> Made {
        switch element.tag {
        case "p":
            return .block(textBlock(element, size: MDBuilder.body, weight: .regular, top: 0, bottom: 12))
        case "h1":
            return .block(textBlock(element, size: 17, weight: .semibold, tracking: -0.01, top: 20, bottom: 8))
        case "h2", "h3", "h4":
            if element.properties.className.contains("sr-only") { return .hidden }
            return .block(textBlock(element, size: 15, weight: .semibold, tracking: -0.01, top: 20, bottom: 8))
        case "h5":
            // No `.md` rule and no reset: the browser's own 0.83em, bold,
            // 1.67em either side.
            let size = MDBuilder.body * 0.83
            return .block(textBlock(element, size: size, weight: .bold, top: size * 1.67, bottom: size * 1.67))
        case "h6":
            let size = MDBuilder.body * 0.67
            return .block(textBlock(element, size: size, weight: .bold, top: size * 2.33, bottom: size * 2.33))
        case "ul", "ol":
            return .block(list(element, depth: depth))
        case "blockquote":
            let inner = stack(element.children, depth: depth)
            return .block(block(.quote(inner), top: 0, bottom: 12, innerTop: inner.top, innerBottom: inner.bottom))
        case "pre":
            return .block(block(.code(MDCodeBuilder.code(element)), top: 0, bottom: 12))
        case "table":
            return .block(block(.table(MDTableBuilder.table(element)), top: 0, bottom: 12))
        case "hr":
            return .block(block(.rule, top: 16, bottom: 16))
        case "section", "div":
            let inner = stack(element.children, depth: depth)
            return .block(block(.group(inner), top: 0, bottom: 0, innerTop: inner.top, innerBottom: inner.bottom))
        default:
            return .inline
        }
    }

    private mutating func textBlock(_ element: HastElement, size: CGFloat, weight: MDWeight, tracking: CGFloat = 0,
                                    top: CGFloat, bottom: CGFloat) -> MDBlock {
        let inlines = MDInlines.build(element.children, marks: MDMarks(), base: weight)
        return block(.text(MDText(inlines: inlines, size: size, weight: weight, tracking: tracking)),
                     top: top, bottom: bottom)
    }

    /// `ul` and `ol`: 12 below, 20 of gutter for the markers, each item 4 below.
    private mutating func list(_ element: HastElement, depth: Int) -> MDBlock {
        var items: [MDStack] = []
        for item in element.elements where item.tag == "li" {
            var content = stack(item.children, depth: depth + 1)
            // The item's own margins, with what collapses through it.
            content.top = max(0, content.top)
            content.bottom = max(4, content.bottom)
            items.append(content)
        }
        let list = MDList(ordered: element.tag == "ol", start: element.properties.start ?? 1, depth: depth,
                          items: items)
        return block(.list(list), top: 0, bottom: 12, innerTop: items.first?.top ?? 0,
                     innerBottom: items.last?.bottom ?? 0)
    }

    private mutating func block(_ kind: MDBlock.Kind, top: CGFloat, bottom: CGFloat, innerTop: CGFloat = 0,
                                innerBottom: CGFloat = 0) -> MDBlock {
        nextID += 1
        return MDBlock(id: nextID, kind: kind, marginTop: top, marginBottom: bottom,
                       innerTop: innerTop, innerBottom: innerBottom)
    }

    /// Adjoining margins collapse to the larger of the two; the first block's
    /// top and the last one's bottom are the container's to collapse further.
    static func collapse(_ blocks: [MDBlock]) -> MDStack {
        var stack = MDStack(blocks: blocks)
        stack.gaps = blocks.indices.map { index in
            index == 0 ? 0 : max(blocks[index - 1].bottom, blocks[index].top)
        }
        stack.top = blocks.first?.top ?? 0
        stack.bottom = blocks.last?.bottom ?? 0
        return stack
    }
}

extension HastNode {
    /// Whether this node is inline content that makes a line box: anything
    /// but the whitespace the pipeline puts between blocks.
    var isInlineContent: Bool {
        switch self {
        case .text(let value): !value.allSatisfy(\.isWhitespace)
        case .element: true
        }
    }
}
