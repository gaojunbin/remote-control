import AppKit
import SwiftUI

/// Inline code: `.md :not(pre) > code` — the monospace face at 0.92em on a
/// 5-point-radius tint, 5 points of padding either side and 1 above and
/// below. The padding is room in the line, made with kerning; the tint is
/// drawn by `MDTextRenderer`, the only way to round a run's background.
struct MDCodeMark: TextAttribute {
    let id: Int
}

/// A link: underlined 2 points below the baseline, in its own ink.
struct MDLinkMark: TextAttribute {}

/// One paragraph's inline content as one `Text`, so it wraps, selects and
/// hit-tests as one block of text does in the browser.
@MainActor
enum MDInlineText {
    nonisolated static let codePadding: CGFloat = 5

    static func text(_ inlines: [MDInline], size: CGFloat, weight: MDWeight) -> Text {
        var segments: [Text] = []
        var codeID = 0
        for (index, inline) in inlines.enumerated() {
            let nextIsCode = inlines.indices.contains(index + 1) && inlines[index + 1].isCode
            switch inline {
            case .text(let value, let marks):
                segments.append(contentsOf: words(value, marks: marks, size: size, base: weight,
                                                  padsAfter: nextIsCode))
            case .code(let value, let marks):
                if index == 0 || inlines[index - 1].isLineBreak { segments.append(spacer(codePadding)) }
                codeID += 1
                segments.append(contentsOf: code(value, marks: marks, size: size, base: weight, id: codeID))
            case .lineBreak:
                segments.append(Text(verbatim: "\n"))
            case .checkbox(let checked):
                segments.append(Text(Image(nsImage: MDCheckbox.image(checked: checked))))
            case .image(let alt):
                segments.append(Text(verbatim: alt).foregroundStyle(Palette.inkSecondary))
            }
        }
        return concat(segments[...])
    }

    /// The segments joined by interpolation, pairwise, so a long paragraph
    /// nests as deep as its logarithm rather than its length.
    static func concat(_ segments: ArraySlice<Text>) -> Text {
        switch segments.count {
        case 0: return Text(verbatim: "")
        case 1: return segments[segments.startIndex]
        default:
            let middle = segments.startIndex + segments.count / 2
            return Text("\(concat(segments[..<middle]))\(concat(segments[middle...]))")
        }
    }

    /// A text run, with the kerning of its last character carrying the left
    /// padding of a code chip right after it.
    private static func words(_ value: String, marks: MDMarks, size: CGFloat, base: MDWeight,
                              padsAfter: Bool) -> [Text] {
        guard padsAfter, let last = value.last else { return [styled(value, marks: marks, size: size, base: base)] }
        return [styled(String(value.dropLast()), marks: marks, size: size, base: base),
                styled(String(last), marks: marks, size: size, base: base).kerning(codePadding)]
    }

    /// A code chip, its last character kerned by the right padding.
    private static func code(_ value: String, marks: MDMarks, size: CGFloat, base: MDWeight, id: Int) -> [Text] {
        let codeSize = size * 0.92
        let weight = (marks.weight ?? base).fontWeight
        func piece(_ text: String) -> Text {
            var run = AttributedString(text)
            run.font = .system(size: codeSize, weight: weight, design: .monospaced)
            if marks.italic { run.font = run.font?.italic() }
            if let link = marks.link, let url = URL(string: link) { run.link = url }
            var segment = Text(run).customAttribute(MDCodeMark(id: id))
            if marks.strike { segment = segment.strikethrough() }
            if marks.link != nil { segment = segment.customAttribute(MDLinkMark()) }
            return segment
        }
        guard let last = value.last else { return [] }
        return [piece(String(value.dropLast())), piece(String(last)).kerning(codePadding)]
    }

    private static func styled(_ value: String, marks: MDMarks, size: CGFloat, base: MDWeight) -> Text {
        var run = AttributedString(value)
        let runSize = marks.superscript ? size / 1.2 : size
        var font = Font.web(size: runSize, weight: (marks.weight ?? base).fontWeight)
        if marks.italic { font = font.italic() }
        run.font = font
        if let link = marks.link, let url = URL(string: link) { run.link = url }
        var segment = Text(run)
        if marks.strike { segment = segment.strikethrough() }
        if marks.superscript { segment = segment.baselineOffset(size / 3) }
        if marks.link != nil { segment = segment.customAttribute(MDLinkMark()) }
        return segment
    }

    /// Room at the start of a line for a chip's left padding, where there is
    /// no character before it to kern.
    private static func spacer(_ width: CGFloat) -> Text {
        Text(Image(nsImage: MDSpacer.image(width: width)))
    }
}

extension MDInline {
    var isCode: Bool { if case .code = self { true } else { false } }
    var isLineBreak: Bool { if case .lineBreak = self { true } else { false } }

    /// A chip or a link, which the decoration layer draws for.
    var isDecorated: Bool {
        switch self {
        case .code: true
        case .text(_, let marks): marks.link != nil
        default: false
        }
    }
}

extension MDWeight {
    var fontWeight: Font.Weight {
        switch self {
        case .regular: .regular
        case .medium: .medium
        case .semibold: .semibold
        case .bold: .bold
        case .black: .black
        }
    }
}

/// Draws the tint behind every code chip and the underline under every link,
/// and no glyphs: the selectable copy in front of it draws those.
struct MDTextRenderer: TextRenderer {
    /// `--surface-muted`.
    var codeTint = Palette.surfaceMuted

    func draw(layout: Text.Layout, in context: inout GraphicsContext) {
        for line in layout {
            var chips: [Int: CGRect] = [:]
            for run in line {
                guard let mark = run[MDCodeMark.self] else { continue }
                let bounds = run.typographicBounds
                let rect = CGRect(x: bounds.origin.x, y: bounds.origin.y - bounds.ascent.rounded() - 1,
                                  width: bounds.width, height: bounds.ascent.rounded() + bounds.descent.rounded() + 2)
                chips[mark.id] = chips[mark.id].map { $0.union(rect) } ?? rect
            }
            for rect in chips.values {
                let padded = CGRect(x: rect.minX - MDInlineText.codePadding, y: rect.minY,
                                    width: rect.width + MDInlineText.codePadding, height: rect.height)
                context.fill(Path(roundedRect: padded, cornerRadius: 5, style: .circular), with: .color(codeTint))
            }
            for run in line {
                guard run[MDLinkMark.self] != nil else { continue }
                let bounds = run.typographicBounds
                let underline = CGRect(x: bounds.origin.x, y: (bounds.origin.y + 2).rounded(), width: bounds.width,
                                       height: 1)
                context.fill(Path(underline), with: .foreground)
            }
        }
    }
}

/// A transparent image that only takes up room in a line.
enum MDSpacer {
    @MainActor private static var cache: [CGFloat: NSImage] = [:]

    @MainActor static func image(width: CGFloat) -> NSImage {
        if let cached = cache[width] { return cached }
        let image = NSImage(size: NSSize(width: width, height: 1), flipped: false) { _ in true }
        cache[width] = image
        return image
    }
}
