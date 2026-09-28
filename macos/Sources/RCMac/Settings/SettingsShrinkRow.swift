import SwiftUI

/// A flex row whose items keep their own width but one, which takes what is
/// left and no more than it needs: the Settings header's meta line, where only
/// the host gives way (`.settings-identity-host { min-width: 0 }`), and the
/// host itself, where only its head does and the tail keeps the port. Each
/// item is centred on the line, as `align-items: center` puts it.
struct SettingsShrinkRow: Layout {
    var spacing: CGFloat
    /// The index of the item that gives way.
    var shrinking: Int

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = widths(subviews, width: proposal.width)
        let width = sizes.reduce(0) { $0 + $1.width } + spacing * CGFloat(max(0, sizes.count - 1))
        return CGSize(width: width, height: sizes.map(\.height).max() ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        for (index, size) in widths(subviews, width: bounds.width).enumerated() {
            subviews[index].place(at: CGPoint(x: x, y: bounds.midY), anchor: .leading, proposal: ProposedViewSize(size))
            x += size.width + spacing
        }
    }

    private func widths(_ subviews: Subviews, width: CGFloat?) -> [CGSize] {
        var sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        guard let width, sizes.indices.contains(shrinking) else { return sizes }
        let others = sizes.indices.filter { $0 != shrinking }.reduce(0) { $0 + sizes[$1].width }
        let room = max(0, width - others - spacing * CGFloat(max(0, sizes.count - 1)))
        guard room < sizes[shrinking].width else { return sizes }
        sizes[shrinking] = subviews[shrinking].sizeThatFits(ProposedViewSize(width: room, height: nil))
        sizes[shrinking].width = min(sizes[shrinking].width, room)
        return sizes
    }
}
