import SwiftUI

/// One element of an icon, on lucide's 24-unit grid.
enum IconNode: Sendable {
    case path(String)
    case circle(cx: CGFloat, cy: CGFloat, r: CGFloat)
    case ellipse(cx: CGFloat, cy: CGFloat, rx: CGFloat, ry: CGFloat)
    case rect(x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat, rx: CGFloat, ry: CGFloat)
    case line(x1: CGFloat, y1: CGFloat, x2: CGFloat, y2: CGFloat)
    case polyline(String)
    case polygon(String)

    var path: Path {
        switch self {
        case .path(let data):
            return SVGPath.parse(data)
        case .circle(let cx, let cy, let r):
            return Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
        case .ellipse(let cx, let cy, let rx, let ry):
            return Path(ellipseIn: CGRect(x: cx - rx, y: cy - ry, width: 2 * rx, height: 2 * ry))
        case .rect(let x, let y, let width, let height, let rx, let ry):
            let rect = CGRect(x: x, y: y, width: width, height: height)
            guard rx > 0 || ry > 0 else { return Path(rect) }
            return Path(roundedRect: rect, cornerSize: CGSize(width: rx, height: ry), style: .circular)
        case .line(let x1, let y1, let x2, let y2):
            var path = Path()
            path.move(to: CGPoint(x: x1, y: y1))
            path.addLine(to: CGPoint(x: x2, y: y2))
            return path
        case .polyline(let points), .polygon(let points):
            var path = Path()
            path.addLines(SVGPath.points(points))
            if case .polygon = self { path.closeSubpath() }
            return path
        }
    }
}

extension LucideIcon {
    /// Every element of the icon as one path on the 24-unit grid, read once.
    var path: Path { LucideIconPaths.table[self] ?? Path() }
}

private enum LucideIconPaths {
    static let table: [LucideIcon: Path] = Dictionary(uniqueKeysWithValues: LucideIcon.allCases.map { icon in
        var path = Path()
        for node in icon.nodes { path.addPath(node.path) }
        return (icon, path)
    })
}

/// A lucide icon as lucide-react draws it: a `size`-point square, the 24-unit
/// grid scaled into it, a stroke of 2 grid units that scales with the icon
/// (lucide's default, not `absoluteStrokeWidth`), round caps and joins, no
/// fill, in whatever foreground style its surroundings set — lucide's
/// `currentColor`.
public struct Icon: View {
    public let icon: LucideIcon
    public let size: CGFloat
    public let strokeWidth: CGFloat

    public init(_ icon: LucideIcon, size: CGFloat = 24, strokeWidth: CGFloat = 2) {
        self.icon = icon
        self.size = size
        self.strokeWidth = strokeWidth
    }

    public var body: some View {
        IconShape(path: icon.path)
            .stroke(style: StrokeStyle(lineWidth: strokeWidth * size / 24, lineCap: .round, lineJoin: .round))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// A path on the 24-unit grid, scaled into whatever square it is given.
struct IconShape: Shape {
    let path: Path

    func path(in rect: CGRect) -> Path {
        let scale = min(rect.width, rect.height) / 24
        return path.applying(CGAffineTransform(scaleX: scale, y: scale)
            .concatenating(CGAffineTransform(translationX: rect.minX, y: rect.minY)))
    }
}
