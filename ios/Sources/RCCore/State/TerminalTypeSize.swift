import Foundation

/// How large the terminal's type is, in points (amendment A38,
/// `docs/DESIGN.md` § "The terminal": a pinch changes the type size, which is
/// remembered).
///
/// The bounds are the ones a phone can actually show: below the minimum a
/// character is smaller than a touch, above the maximum a shell prompt no
/// longer fits on one line at any width.
public enum TerminalTypeSize {
    public static let minimum: Double = 8
    public static let maximum: Double = 24
    /// What a terminal opens at before anyone has pinched one.
    public static let standard: Double = 12

    public static func clamp(_ value: Double) -> Double {
        guard value.isFinite else { return standard }
        return min(max(minimum, value), maximum)
    }

    /// The size a pinch of this scale lands on, rounded to a whole point so a
    /// slow pinch steps rather than shimmers.
    public static func scaled(_ base: Double, by scale: Double) -> Double {
        guard scale.isFinite, scale > 0 else { return clamp(base) }
        return clamp((base * scale).rounded())
    }
}
