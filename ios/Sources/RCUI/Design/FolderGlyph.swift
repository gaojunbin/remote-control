import SwiftUI

/// The mark before a session's working directory: lucide's `Folder`, drawn
/// here so it matches the web app's and the laptop before a device. A closed
/// folder seen from the front — a body with a tab rising on the left of its
/// top edge — every corner rounded to the same radius, no fill.
public struct FolderShape: Shape {
    /// The outline's corners on the grid, walked clockwise from the bottom
    /// left corner: along the bottom, up the right side, along the top to the
    /// tab, up the tab's slope, along the tab's top, and down the left side.
    public static let corners: [CGPoint] = [
        CGPoint(x: 2, y: 20), CGPoint(x: 22, y: 20), CGPoint(x: 22, y: 6),
        CGPoint(x: 11.02, y: 6), CGPoint(x: 8.99, y: 3), CGPoint(x: 2, y: 3),
    ]
    public static let cornerRadius: CGFloat = 2

    public init() {}

    public func path(in rect: CGRect) -> Path {
        let unit = min(rect.width, rect.height) / OutlineGlyph.grid
        func place(_ p: CGPoint) -> CGPoint {
            CGPoint(x: rect.minX + p.x * unit, y: rect.minY + p.y * unit)
        }
        let corners = Self.corners
        var path = Path()
        // Start midway along the bottom edge, so every corner is one arc.
        path.move(to: place(CGPoint(x: (corners[0].x + corners[1].x) / 2, y: corners[0].y)))
        for index in 1...corners.count {
            let corner = corners[index % corners.count]
            let next = corners[(index + 1) % corners.count]
            path.addArc(tangent1End: place(corner), tangent2End: place(next),
                        radius: Self.cornerRadius * unit)
        }
        path.closeSubpath()
        return path
    }
}

/// `FolderShape` stroked in the ink beside a footnote-sized path, following
/// Dynamic Type with it.
public struct FolderGlyph: View {
    @ScaledMetric(relativeTo: .footnote) private var size: CGFloat = 14

    public init() {}

    public var body: some View {
        FolderShape()
            .stroke(Theme.ink, style: OutlineGlyph.stroke(for: size))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}
