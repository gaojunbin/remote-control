import AppKit

/// How `.composer-input` sets its words: 14 points, a line box of 21 (the
/// body's `line-height: 1.5`), 7 points above the first line and below the
/// last, and each line's baseline where the browser puts it in its box.
@MainActor
enum ComposerFieldText {
    static let style = TextStyle(size: FontSize.fs14)
    static let lineBox = style.lineBox
    static let padding: CGFloat = 7
    /// `min-height: 34px` under one line of 21 and its padding, which is 35.
    static let minHeight = lineBox + 2 * padding
    /// `max-height: 220px`: past it the field scrolls inside.
    static let maxHeight: CGFloat = 220

    static var paragraph: NSParagraphStyle {
        let paragraph = NSMutableParagraphStyle()
        paragraph.minimumLineHeight = lineBox
        paragraph.maximumLineHeight = lineBox
        return paragraph
    }

    static var attributes: [NSAttributedString.Key: Any] {
        [.font: style.nsFont, .foregroundColor: NSColor(Palette.ink), .paragraphStyle: paragraph]
    }

    /// The field's height for these words at this width: one box per line,
    /// the padding, and the web's floor and ceiling.
    static func height(ofContent content: CGFloat) -> CGFloat {
        min(max(minHeight, content + 2 * padding), maxHeight)
    }
}

/// Puts every line of the field in the browser's box: `lineBox` tall, with
/// the baseline `style.baseline` from its top, whatever font a character fell
/// back to. TextKit would otherwise sit the words at the bottom of the box.
final class ComposerLineBoxes: NSObject, NSLayoutManagerDelegate {
    private let lineBox: CGFloat
    private let baseline: CGFloat

    init(lineBox: CGFloat, baseline: CGFloat) {
        self.lineBox = lineBox
        self.baseline = baseline
    }

    @MainActor
    static func field() -> ComposerLineBoxes {
        ComposerLineBoxes(lineBox: ComposerFieldText.lineBox, baseline: ComposerFieldText.style.baseline)
    }

    func layoutManager(_ layoutManager: NSLayoutManager,
                       shouldSetLineFragmentRect lineFragmentRect: UnsafeMutablePointer<NSRect>,
                       lineFragmentUsedRect: UnsafeMutablePointer<NSRect>,
                       baselineOffset: UnsafeMutablePointer<CGFloat>,
                       in textContainer: NSTextContainer, forGlyphRange glyphRange: NSRange) -> Bool {
        lineFragmentRect.pointee.size.height = lineBox
        lineFragmentUsedRect.pointee.size.height = lineBox
        baselineOffset.pointee = baseline
        return true
    }
}

/// Measures the field's words at a width without touching the field itself,
/// which SwiftUI may ask about at widths it never gives it.
@MainActor
final class ComposerFieldMeasure {
    private let storage = NSTextStorage()
    private let layout = NSLayoutManager()
    private let container = NSTextContainer()
    private let boxes = ComposerLineBoxes.field()
    private var last: (text: String, width: CGFloat, height: CGFloat)?

    init() {
        container.lineFragmentPadding = 0
        layout.delegate = boxes
        layout.addTextContainer(container)
        storage.addLayoutManager(layout)
    }

    /// The height of the words' lines, the extra line after a final newline included.
    func contentHeight(of text: String, width: CGFloat) -> CGFloat {
        if let last, last.text == text, last.width == width { return last.height }
        container.size = NSSize(width: max(1, width), height: .greatestFiniteMagnitude)
        storage.setAttributedString(NSAttributedString(string: text, attributes: ComposerFieldText.attributes))
        layout.ensureLayout(for: container)
        let height = layout.usedRect(for: container).height
        last = (text, width, height)
        return height
    }
}
