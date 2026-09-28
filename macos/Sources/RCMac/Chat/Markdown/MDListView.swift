import AppKit
import SwiftUI

/// `ul` and `ol` in `.md`: 20 points of gutter, each marker on its item's
/// first baseline and set in the item's own type — "•", then "◦" inside
/// another list, then "▪", and "1." for an ordered list — ending where the
/// item starts, as the browser's outside `::marker` does.
struct MDListView: View {
    let list: MDList

    static let gutter: CGFloat = 20

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(list.items.enumerated()), id: \.offset) { index, item in
                HStack(alignment: .firstTextBaseline, spacing: 0) {
                    marker(index).frame(width: Self.gutter, alignment: .trailing)
                    MDStackView(stack: item)
                }
                .padding(.top, index == 0 ? 0 : max(list.items[index - 1].bottom, item.top))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func marker(_ index: Int) -> some View {
        if list.ordered {
            MDNumberMarker(text: "\(list.start + index).")
        } else {
            MDSymbolMarker(symbol: list.depth == 0 ? .disc : list.depth == 1 ? .circle : .square)
        }
    }
}

/// A numbered item's `::marker`: its number and a period in the item's type,
/// followed by the space the browser sets after it, ending where the item
/// starts and overflowing the gutter to the left when it is wider.
private struct MDNumberMarker: View {
    let text: String
    @Environment(\.mdInk) private var ink

    var body: some View {
        Text(verbatim: text)
            .css(MDBuilder.body, lineHeight: 1.65)
            .foregroundStyle(ink)
            .fixedSize()
            .padding(.trailing, Self.space)
            .frame(width: 0, alignment: .trailing)
            .frame(width: MDListView.gutter, alignment: .trailing)
    }

    /// The width of a space at the body size, which ends every marker.
    @MainActor static let space: CGFloat = {
        (" " as NSString).size(withAttributes: [.font: SystemFace.font(size: MDBuilder.body)]).width
    }()
}

/// A bullet as Chrome paints `disc`, `circle` and `square`: a shape, not a
/// glyph, a little under half the font's ascent across, set on the first line
/// by the ascent and 15 points before the item's text (Blink's `ListMarker`).
private struct MDSymbolMarker: View {
    enum Symbol { case disc, circle, square }

    let symbol: Symbol
    @Environment(\.mdInk) private var ink

    var body: some View {
        let style = TextStyle(size: MDBuilder.body, lineHeight: 1.65)
        let geometry = Self.geometry
        Text(verbatim: " ")
            .textStyle(style)
            .hidden()
            .frame(width: MDListView.gutter)
            .overlay(alignment: .topLeading) {
                shape
                    .frame(width: geometry.side, height: geometry.side)
                    .offset(x: MDListView.gutter - geometry.inset, y: style.baseline - geometry.rise)
            }
            .accessibilityHidden(true)
    }

    @ViewBuilder private var shape: some View {
        switch symbol {
        case .disc: Circle().fill(ink)
        case .circle: Circle().inset(by: 0.5).stroke(ink, lineWidth: 1)
        case .square: Rectangle().fill(ink)
        }
    }

    /// From the font's rounded ascent: the bullet's side, how far above the
    /// baseline its top is, and how far before the item's text it starts.
    @MainActor static let geometry: (side: CGFloat, rise: CGFloat, inset: CGFloat) = {
        let ascent = SystemFace.font(size: MDBuilder.body).ascender.rounded()
        let twoThirds = ascent * 2 / 3
        let side = (twoThirds + 1) / 2
        let top = 3 * (ascent - twoThirds) / 2
        // The marker box starts `offset + 7 + 1` before the text, where
        // `offset` is two thirds of the ascent in whole points, and the
        // bullet one point into it.
        let inset = (twoThirds.rounded(.down) + 7 + 1) - 1
        return (side, ascent - top, inset)
    }()
}

/// `.md blockquote`: a 2-point rule on the left, 12 points of padding, the
/// quieter ink.
struct MDQuoteView: View {
    let stack: MDStack

    var body: some View {
        MDStackView(stack: stack)
            .environment(\.mdInk, Palette.inkSecondary)
            .padding(.leading, Space.sp3 + 2)
            .background(alignment: .leading) { Rectangle().fill(Palette.line).frame(width: 2) }
    }
}
