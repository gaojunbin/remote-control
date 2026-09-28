import SwiftUI

/// `.md-table` and `.md table`: a bordered, 8-point-radius box holding a
/// full-width table of 13-point cells, 7 by 12 points of padding, a rule under
/// every row but the last, the header on the sunken surface in 500 weight.
struct MDTableView: View {
    let table: MDTable

    var body: some View {
        MDTableLayout(columns: max(1, table.columns)) {
            ForEach(Array(cells.enumerated()), id: \.offset) { _, cell in
                MDTableCell(cell: cell.cell, ruled: cell.ruled)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .chatBox(radius: Radius.sm)
    }

    /// Row by row, every row padded out to the table's columns, as the
    /// browser adds anonymous cells.
    private var cells: [(cell: MDCell?, ruled: Bool)] {
        var out: [(MDCell?, Bool)] = []
        let rows = (table.header.isEmpty ? [] : [table.header]) + table.rows
        for (index, row) in rows.enumerated() {
            let header = !table.header.isEmpty && index == 0
            // `.md tr:last-child td` drops the rule; a header row keeps it.
            let ruled = header || index < rows.count - 1
            for column in 0..<max(1, table.columns) {
                out.append((column < row.count ? row[column] : nil, ruled))
            }
        }
        return out
    }
}

private struct MDTableCell: View {
    let cell: MDCell?
    let ruled: Bool

    var body: some View {
        let header = cell?.header ?? false
        Group {
            if let cell {
                let weight: MDWeight = cell.header ? .medium : .regular
                MDRichText(inlines: cell.inlines,
                           style: TextStyle(size: FontSize.fs13, weight: weight.fontWeight, lineHeight: 1.65),
                           weight: weight, ink: Palette.ink, alignment: textAlignment(cell.align))
                    .frame(maxWidth: .infinity, alignment: frameAlignment(cell.align))
            } else {
                Color.clear.frame(height: 0)
            }
        }
        .padding(.vertical, 7)
        .padding(.horizontal, Space.sp3)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: frameAlignment(cell?.align ?? .leading))
        .padding(.bottom, ruled ? 1 : 0)
        .background(header ? Palette.surfaceSunken : Color.clear)
        .overlay(alignment: .bottom) {
            if ruled { Rectangle().fill(Palette.line).frame(height: 1) }
        }
    }

    private func textAlignment(_ align: MDCell.Align) -> TextAlignment {
        switch align {
        case .leading: .leading
        case .center: .center
        case .trailing: .trailing
        }
    }

    private func frameAlignment(_ align: MDCell.Align) -> Alignment {
        switch align {
        case .leading: .leading
        case .center: .center
        case .trailing: .trailing
        }
    }
}

/// The browser's automatic table layout, for a table 100% wide: a column is
/// as wide as its widest cell on one line, and the room left over is shared
/// out in proportion to those widths; a table too narrow for that squeezes each
/// column towards the least its cells can wrap to. Every cell of a row is as
/// tall as the tallest, its content centred in it.
struct MDTableLayout: Layout {
    let columns: Int

    /// The narrowest a cell's content can wrap to, with its padding: a word
    /// breaks anywhere in `.md`, so that is about a character.
    static let minimumCell: CGFloat = 24 + 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let widths = columnWidths(available: proposal.width, subviews: subviews)
        let heights = rowHeights(widths: widths, subviews: subviews)
        return CGSize(width: proposal.width ?? widths.reduce(0, +), height: heights.reduce(0, +))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let widths = columnWidths(available: bounds.width, subviews: subviews)
        let heights = rowHeights(widths: widths, subviews: subviews)
        var y = bounds.minY
        for (row, height) in heights.enumerated() {
            var x = bounds.minX
            for column in 0..<columns {
                let index = row * columns + column
                guard index < subviews.count else { break }
                subviews[index].place(at: CGPoint(x: x, y: y),
                                      proposal: ProposedViewSize(width: widths[column], height: height))
                x += widths[column]
            }
            y += height
        }
    }

    private func columnWidths(available: CGFloat?, subviews: Subviews) -> [CGFloat] {
        var most = Array(repeating: CGFloat(0), count: columns)
        for (index, subview) in subviews.enumerated() {
            let column = index % columns
            most[column] = max(most[column], subview.sizeThatFits(.unspecified).width)
        }
        guard let available else { return most }
        let least = Array(repeating: Self.minimumCell, count: columns)
        let total = most.reduce(0, +)
        if total <= available, total > 0 {
            return most.map { $0 + (available - total) * $0 / total }
        }
        let floor = least.reduce(0, +)
        guard available > floor, total > floor else { return least }
        let share = (available - floor) / (total - floor)
        return zip(least, most).map { $0 + ($1 - $0) * share }
    }

    private func rowHeights(widths: [CGFloat], subviews: Subviews) -> [CGFloat] {
        let rows = (subviews.count + columns - 1) / columns
        return (0..<rows).map { row in
            (0..<columns).reduce(CGFloat(0)) { tallest, column in
                let index = row * columns + column
                guard index < subviews.count else { return tallest }
                let size = subviews[index].sizeThatFits(ProposedViewSize(width: widths[column], height: nil))
                return max(tallest, size.height)
            }
        }
    }
}
