import SwiftUI

/// What the app's line-drawn marks share (`docs/DESIGN.md` § "The device row"
/// and § "The session row"): lucide's 24-unit grid, a 1.5-unit stroke, round
/// caps and joins, no fill. The laptop before a device and the folder before a
/// working directory are both drawn to it, so they read as one hand.
public enum OutlineGlyph {
    public static let grid: CGFloat = 24
    public static let strokeUnits: CGFloat = 1.5

    public static func strokeWidth(for size: CGFloat) -> CGFloat {
        size * strokeUnits / grid
    }

    public static func stroke(for size: CGFloat) -> StrokeStyle {
        StrokeStyle(lineWidth: strokeWidth(for: size), lineCap: .round, lineJoin: .round)
    }
}
