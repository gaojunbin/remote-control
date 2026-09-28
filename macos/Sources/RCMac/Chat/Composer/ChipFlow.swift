import SwiftUI

/// `display: flex; flex-wrap: wrap; align-items: center; gap: N`: items left to
/// right, a new line where the next one does not fit, each line as tall as
/// its tallest item with the others centred in it.
struct ChipFlow: Layout {
    var spacing: CGFloat = Space.sp2

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let lines = lines(for: subviews, width: proposal.width ?? .infinity)
        let height = lines.map(\.height).reduce(0, +) + spacing * CGFloat(max(0, lines.count - 1))
        let width = lines.map(\.width).max() ?? 0
        return CGSize(width: proposal.width.flatMap { $0.isFinite ? $0 : nil } ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for line in lines(for: subviews, width: bounds.width) {
            var x = bounds.minX
            for (index, size) in zip(line.indices, line.sizes) {
                subviews[index].place(at: CGPoint(x: x, y: y + (line.height - size.height) / 2),
                                      proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += line.height + spacing
        }
    }

    private struct Line {
        var indices: [Int] = []
        var sizes: [CGSize] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func lines(for subviews: Subviews, width: CGFloat) -> [Line] {
        var lines: [Line] = []
        var line = Line()
        for (index, subview) in subviews.enumerated() {
            let size = subview.sizeThatFits(.unspecified)
            let needed = line.indices.isEmpty ? size.width : line.width + spacing + size.width
            if !line.indices.isEmpty, needed > width {
                lines.append(line)
                line = Line()
            }
            line.width = line.indices.isEmpty ? size.width : line.width + spacing + size.width
            line.indices.append(index)
            line.sizes.append(size)
            line.height = max(line.height, size.height)
        }
        if !line.indices.isEmpty { lines.append(line) }
        return lines
    }
}
