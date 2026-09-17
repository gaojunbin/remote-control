import SwiftUI

/// The device row's mark: a minimal outline laptop, the same drawing the web
/// app gets from lucide's `LaptopMinimal` (`docs/DESIGN.md` § "The device
/// row"). A rounded rectangle for the screen over one horizontal line for the
/// base, no fill, round caps and joins. Drawn here rather than taken from SF
/// Symbols, whose laptop has a trapezoid base and a different weight, so the
/// two apps show one glyph.
public struct LaptopShape: Shape {
    /// lucide draws on a 24-unit grid; the screen and the base are placed on it
    /// and scaled to the rect, so any size keeps the same proportions.
    public static let grid: CGFloat = 24
    public static let screen = CGRect(x: 3, y: 4, width: 18, height: 12)
    public static let screenCorner: CGFloat = 2
    public static let baseY: CGFloat = 20
    public static let baseX: ClosedRange<CGFloat> = 2...22

    public init() {}

    public func path(in rect: CGRect) -> Path {
        let unit = min(rect.width, rect.height) / Self.grid
        var path = Path()
        path.addRoundedRect(
            in: CGRect(x: rect.minX + Self.screen.minX * unit,
                       y: rect.minY + Self.screen.minY * unit,
                       width: Self.screen.width * unit,
                       height: Self.screen.height * unit),
            cornerSize: CGSize(width: Self.screenCorner * unit, height: Self.screenCorner * unit))
        path.move(to: CGPoint(x: rect.minX + Self.baseX.lowerBound * unit, y: rect.minY + Self.baseY * unit))
        path.addLine(to: CGPoint(x: rect.minX + Self.baseX.upperBound * unit, y: rect.minY + Self.baseY * unit))
        return path
    }
}

/// `LaptopShape` stroked in the ink at the size of a row title. The stroke is
/// 1.5 units of the 24-unit grid, the same weight the web row uses.
public struct LaptopGlyph: View {
    public static let strokeUnits: CGFloat = 1.5

    /// 20 pt beside a 16 pt callout title, following Dynamic Type with it.
    @ScaledMetric(relativeTo: .callout) private var size: CGFloat = 20

    public init() {}

    public static func strokeWidth(for size: CGFloat) -> CGFloat {
        size * strokeUnits / LaptopShape.grid
    }

    public var body: some View {
        LaptopShape()
            .stroke(Theme.ink, style: StrokeStyle(lineWidth: Self.strokeWidth(for: size),
                                                  lineCap: .round, lineJoin: .round))
            .frame(width: size, height: size)
            // The base line sits on the title's baseline, as the foot of a
            // letter would.
            .alignmentGuide(.firstTextBaseline) { d in d.height * LaptopShape.baseY / LaptopShape.grid }
            .accessibilityHidden(true)
    }
}
