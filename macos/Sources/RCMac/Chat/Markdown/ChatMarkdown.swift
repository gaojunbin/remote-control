import CoreGraphics
import Foundation

/// A message as the chat draws it: the blocks the web's `.md` lays out, each
/// with the margins its rule gives it once they have collapsed the way the
/// browser collapses them (`chat.css` § markdown).
enum ChatMarkdown: Equatable, Sendable {
    /// The pipeline was not there: the text as it arrived (`.md-plain`).
    case plain(String)
    case blocks(MDStack)
}

/// Blocks one after another. `gaps[i]` is the space above block `i`; the
/// stack's first block starts at the top and its last ends at the bottom,
/// because the margins at either end belong to the container around it.
struct MDStack: Equatable, Sendable {
    var blocks: [MDBlock] = []
    var gaps: [CGFloat] = []
    /// What the first block's top margin and the last one's bottom margin come
    /// to after collapsing through the blocks that hold them.
    var top: CGFloat = 0
    var bottom: CGFloat = 0
}

struct MDBlock: Equatable, Sendable, Identifiable {
    let id: Int
    let kind: Kind
    /// This block's own margins, as its rule sets them.
    let marginTop: CGFloat
    let marginBottom: CGFloat
    /// The margins of what it holds that collapse through its edges, where it
    /// has no padding or border to stop them.
    var innerTop: CGFloat = 0
    var innerBottom: CGFloat = 0

    /// The margins it meets its neighbours with.
    var top: CGFloat { max(marginTop, innerTop) }
    var bottom: CGFloat { max(marginBottom, innerBottom) }

    indirect enum Kind: Equatable, Sendable {
        /// A paragraph, a heading, or the text of a tight list item.
        case text(MDText)
        case list(MDList)
        case quote(MDStack)
        /// A box with no edge of its own — the footnotes' `section`.
        case group(MDStack)
        case code(MDCode)
        case table(MDTable)
        case rule
    }
}

/// A run of inline content and the type it is set in.
struct MDText: Equatable, Sendable {
    var inlines: [MDInline]
    var size: CGFloat
    var weight: MDWeight
    var tracking: CGFloat = 0
}

/// CSS weights, so `<strong>` can be `bolder` than whatever it sits in.
enum MDWeight: Int, Sendable, Comparable {
    case regular = 400
    case medium = 500
    case semibold = 600
    case bold = 700
    case black = 900

    static func < (lhs: MDWeight, rhs: MDWeight) -> Bool { lhs.rawValue < rhs.rawValue }

    /// `font-weight: bolder`.
    var bolder: MDWeight {
        switch self {
        case .regular, .medium: .bold
        case .semibold, .bold, .black: .black
        }
    }
}

enum MDInline: Equatable, Sendable {
    case text(String, MDMarks)
    /// `<code>` outside a `<pre>`: a tinted monospace chip.
    case code(String, MDMarks)
    /// `<br>`.
    case lineBreak
    /// A task list item's disabled checkbox.
    case checkbox(checked: Bool)
    /// `<img>`, drawn as its alternative text.
    case image(alt: String)
}

struct MDMarks: Equatable, Sendable {
    var weight: MDWeight? = nil
    var italic = false
    var strike = false
    var link: String? = nil
    var superscript = false
}

struct MDList: Equatable, Sendable {
    var ordered: Bool
    var start: Int
    /// How many lists hold this one, which picks the bullet: disc, circle,
    /// then square.
    var depth: Int
    var items: [MDStack]
}

struct MDCode: Equatable, Sendable {
    /// rehype-highlight marked the block `hljs`: the white inner box and the
    /// theme's ink, highlighted or not.
    var highlighted: Bool
    var lines: [[MDCodeRun]]
    /// What Copy puts on the clipboard: the code element's text.
    var source: String
}

struct MDCodeRun: Equatable, Sendable {
    var text: String
    var style: HighlightStyle
}

struct MDTable: Equatable, Sendable {
    var header: [MDCell]
    var rows: [[MDCell]]
    var columns: Int
}

struct MDCell: Equatable, Sendable {
    enum Align: Sendable { case leading, center, trailing }
    var inlines: [MDInline]
    var align: Align
    var header: Bool
}
