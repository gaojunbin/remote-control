import AppKit
import SwiftUI

/// The web's type, set the way its stylesheets set it: a size, a weight, a
/// `line-height` and a `letter-spacing`, in the system face (`-apple-system`
/// is SF) or its monospaced cut (`ui-monospace` is SF Mono, as Safari draws it).
///
/// A browser gives every line a box of `line-height × font-size` and puts the
/// baseline in it by a rule of its own; SwiftUI gives a line the font's own
/// height. `CSSLineBox` puts SwiftUI's text on the browser's baseline and gives
/// it the browser's height, so a block of text takes the room, and sits where,
/// the web's does. Measured against Chrome at every size and weight the web
/// uses, the baseline sits at ⌊(L − (round(ascent) + round(descent))) ÷ 2⌋ +
/// round(ascent) from the top of each line of height L.
public struct TextStyle: Sendable, Hashable {
    public var size: CGFloat
    public var weight: Font.Weight
    public var mono: Bool
    /// The CSS `line-height` multiplier. The body's is 1.5.
    public var lineHeight: CGFloat
    /// The CSS `letter-spacing` in em.
    public var tracking: CGFloat

    public init(size: CGFloat = FontSize.fs14, weight: Font.Weight = .regular, mono: Bool = false,
                lineHeight: CGFloat = 1.5, tracking: CGFloat = 0) {
        self.size = size
        self.weight = weight
        self.mono = mono
        self.lineHeight = lineHeight
        self.tracking = tracking
    }

    public var font: Font {
        mono ? .system(size: size, weight: weight, design: .monospaced) : .system(size: size, weight: weight)
    }

    /// The AppKit face of the same style, for views drawn with AppKit.
    public var nsFont: NSFont {
        mono ? .monospacedSystemFont(ofSize: size, weight: weight.nsWeight)
             : .systemFont(ofSize: size, weight: weight.nsWeight)
    }

    /// One line's box: `line-height × font-size`.
    public var lineBox: CGFloat { size * lineHeight }

    /// Where the browser puts the baseline in one line's box.
    public var baseline: CGFloat {
        let font = nsFont
        let ascent = font.ascender.rounded()
        let descent = (-font.descender).rounded()
        return ((lineBox - (ascent + descent)) / 2).rounded(.down) + ascent
    }

    /// What goes between two lines so they sit `line-height` apart: the line
    /// box less the height SwiftUI gives a line of this font on its own.
    @MainActor
    public var lineSpacing: CGFloat { max(0, lineBox - LineMetrics.height(of: self)) }
}

/// The height SwiftUI gives one line of a style, measured once per style.
///
/// It is not the font's ascent and descent: SwiftUI rounds them its own way,
/// by a rule no public metric states (15 points for 12-point SF, 19 for both
/// 15 and 16), and it is the same whatever script the line holds, because the
/// primary font decides it. So it is read from SwiftUI itself, the first time a
/// style is drawn, and kept.
@MainActor
enum LineMetrics {
    private struct Key: Hashable {
        let size: CGFloat
        let weight: Font.Weight
        let mono: Bool
    }

    private static var heights: [Key: CGFloat] = [:]

    static func height(of style: TextStyle) -> CGFloat {
        let key = Key(size: style.size, weight: style.weight, mono: style.mono)
        if let known = heights[key] { return known }
        let measured = NSHostingView(rootView: Text("X").font(style.font).fixedSize()).fittingSize.height
        heights[key] = measured
        return measured
    }
}

extension Font.Weight {
    var nsWeight: NSFont.Weight {
        switch self {
        case .ultraLight: .ultraLight
        case .thin: .thin
        case .light: .light
        case .medium: .medium
        case .semibold: .semibold
        case .bold: .bold
        case .heavy: .heavy
        case .black: .black
        default: .regular
        }
    }
}

/// Text on the browser's baselines: the first baseline `baseline` below the
/// top of the first line's box, the last one as far below the top of the last,
/// and the block ending one box below its last line's top.
public struct CSSLineBox: Layout {
    let lineBox: CGFloat
    let baseline: CGFloat

    public init(lineBox: CGFloat, baseline: CGFloat) {
        self.lineBox = lineBox
        self.baseline = baseline
    }

    public func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let text = subviews.first else { return .zero }
        let dimensions = text.dimensions(in: proposal)
        let top = baseline - dimensions[VerticalAlignment.firstTextBaseline]
        return CGSize(width: dimensions.width,
                      height: max(lineBox, top + dimensions[VerticalAlignment.lastTextBaseline] + lineBox - baseline))
    }

    public func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let text = subviews.first else { return }
        let dimensions = text.dimensions(in: ProposedViewSize(width: bounds.width, height: nil))
        // The browser puts a line box that its container centres on a whole
        // pixel — 19.5 points down is drawn at 20 — so the baseline is too.
        let top = bounds.minY.rounded()
        text.place(at: CGPoint(x: bounds.minX, y: top + baseline - dimensions[VerticalAlignment.firstTextBaseline]),
                   proposal: ProposedViewSize(width: bounds.width, height: nil))
    }

    public func explicitAlignment(of guide: VerticalAlignment, in bounds: CGRect, proposal: ProposedViewSize,
                                  subviews: Subviews, cache: inout ()) -> CGFloat? {
        guard guide == .firstTextBaseline || guide == .lastTextBaseline, let text = subviews.first else { return nil }
        let dimensions = text.dimensions(in: ProposedViewSize(width: bounds.width, height: nil))
        return baseline - dimensions[VerticalAlignment.firstTextBaseline] + dimensions[guide]
    }
}

extension View {
    /// Type as a CSS rule sets it — `font-size`, `font-weight`, `line-height`,
    /// `letter-spacing`, the monospaced face for `.mono` — on the browser's
    /// baselines. Put it on text; a view with no text has no baseline to place.
    public func textStyle(_ style: TextStyle) -> some View {
        CSSLineBox(lineBox: style.lineBox, baseline: style.baseline) {
            font(style.font)
                .tracking(style.tracking * style.size)
                .lineSpacing(style.lineSpacing)
        }
    }

    /// The same, spelled inline: `.css(FontSize.fs13, weight: .medium)`.
    public func css(_ size: CGFloat, weight: Font.Weight = .regular, lineHeight: CGFloat = 1.5,
                    mono: Bool = false, tracking: CGFloat = 0) -> some View {
        textStyle(TextStyle(size: size, weight: weight, mono: mono, lineHeight: lineHeight, tracking: tracking))
    }
}
