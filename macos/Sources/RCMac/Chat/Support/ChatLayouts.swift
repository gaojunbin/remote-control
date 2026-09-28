import SwiftUI

/// `max-width: N%`: the content is offered at most that share of the width it
/// is given, takes what it needs of it, and sits at the leading or trailing
/// edge of the full width.
struct ChatFractionalWidth: Layout {
    let fraction: CGFloat
    let trailing: Bool

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let content = subviews.first else { return .zero }
        let size = content.sizeThatFits(offer(proposal.width))
        return CGSize(width: proposal.width ?? size.width, height: size.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let content = subviews.first else { return }
        let offered = offer(bounds.width)
        let size = content.sizeThatFits(offered)
        let x = trailing ? bounds.maxX - size.width : bounds.minX
        content.place(at: CGPoint(x: x, y: bounds.minY), proposal: ProposedViewSize(width: size.width, height: size.height))
    }

    private func offer(_ width: CGFloat?) -> ProposedViewSize {
        ProposedViewSize(width: width.map { $0 * fraction }, height: nil)
    }
}

/// An inline-level box on a line of its own, as a block that holds only an
/// inline control draws it: the line takes the height of the parent's strut —
/// its font at its line height — or of the content, whichever reaches further
/// from the shared baseline. A `<button>` inside a `<div>` is 21 points tall
/// on a 14-point, 1.5 line, not the 19.5 of its own 13-point text.
struct ChatStrutLine<Content: View>: View {
    let size: CGFloat
    let lineHeight: CGFloat
    let content: Content

    init(size: CGFloat = FontSize.fs14, lineHeight: CGFloat = 1.5, @ViewBuilder content: () -> Content) {
        self.size = size
        self.lineHeight = lineHeight
        self.content = content()
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Text(verbatim: " ")
                .css(size, lineHeight: lineHeight)
                .fixedSize()
                .frame(width: 0, alignment: .leading)
                .hidden()
                .accessibilityHidden(true)
            content
        }
    }
}

/// A scrolling pane as tall as its content up to `maxHeight`, then scrolling:
/// `max-height` with `overflow: auto` on a `white-space: pre` block, whose
/// long lines scroll sideways. The content is at least as wide as the pane, so
/// what it lays out from the leading edge stays there; a horizontal scroll bar
/// adds its own height, as the browser adds it to an auto-height box.
struct ChatBoundedScroll<Content: View>: View {
    let maxHeight: CGFloat
    let content: Content
    @State private var contentSize: CGSize = .zero
    @State private var viewport: CGFloat = 0

    init(maxHeight: CGFloat, @ViewBuilder content: () -> Content) {
        self.maxHeight = maxHeight
        self.content = content()
    }

    /// The thin scroll bar's gutter (`.scroll-thin`).
    static var scrollbar: CGFloat { 10 }

    var body: some View {
        let wide = contentSize.width > viewport + 0.5
        let height = contentSize.height + (wide ? Self.scrollbar : 0)
        let tall = height > maxHeight
        ScrollView(axes(wide: wide, tall: tall)) {
            content
                .frame(minWidth: viewport, alignment: .leading)
                .onGeometryChange(for: CGSize.self) { $0.size } action: { contentSize = $0 }
                .scrollThin()
        }
        .scrollBounceBehavior(.basedOnSize, axes: [.horizontal, .vertical])
        .frame(height: max(1, min(height, maxHeight)))
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { viewport = $0 }
    }

    /// Only the axes that overflow scroll, so a wheel over a pane that has
    /// nothing to scroll vertically moves the transcript around it.
    private func axes(wide: Bool, tall: Bool) -> Axis.Set {
        var axes: Axis.Set = [.horizontal]
        if tall { axes.insert(.vertical) }
        return wide || tall ? axes : [.horizontal]
    }
}

/// Block children in a horizontally scrolling `pre`: each as wide as the
/// widest of them and at least `minWidth`, so a tinted line runs edge to edge.
struct ChatEqualWidthColumn: Layout {
    var minWidth: CGFloat = 0

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        let width = max(minWidth, sizes.map(\.width).max() ?? 0)
        return CGSize(width: width, height: sizes.reduce(0) { $0 + $1.height })
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for subview in subviews {
            let height = subview.sizeThatFits(.unspecified).height
            subview.place(at: CGPoint(x: bounds.minX, y: y), proposal: ProposedViewSize(width: bounds.width, height: height))
            y += height
        }
    }
}

/// `display: flex; flex-wrap: wrap; gap`: children at their own size, left to
/// right, starting a new line when the next one does not fit.
struct ChatWrapRow: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let width = rows.map(\.width).max() ?? 0
        let height = rows.reduce(0) { $0 + $1.height } + spacing * CGFloat(max(0, rows.count - 1))
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: y + (row.height - size.height) / 2),
                                      proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let needed = row.indices.isEmpty ? size.width : row.width + spacing + size.width
            if needed > width, !row.indices.isEmpty {
                rows.append(row)
                row = Row()
            }
            row.width = row.indices.isEmpty ? size.width : row.width + spacing + size.width
            row.height = max(row.height, size.height)
            row.indices.append(index)
        }
        if !row.indices.isEmpty { rows.append(row) }
        return rows
    }
}

/// `width: fit-content` under a `max-width`: what the content needs, never
/// more than `maximum` — how a box with `margin: auto` sizes in a flex column.
struct ChatFitWidth: Layout {
    let maximum: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let content = subviews.first else { return .zero }
        return content.sizeThatFits(ProposedViewSize(width: min(proposal.width ?? maximum, maximum),
                                                     height: proposal.height))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(bounds.size))
    }
}
