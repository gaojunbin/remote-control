import SwiftUI
import RCCore

/// Amendment A44: the model card's glyph on the phone, a gauge after ChatGPT's
/// effort icon (`docs/DESIGN.md` § "The control row").
///
/// An open arc from lower left over the top to lower right, on a track in the
/// line colour, filled in green up to a needle whose position is the session's
/// effort: the agent's lowest level at the left end, its highest at the right,
/// the others evenly between (`AgentInfo.effortPosition`). With no position —
/// no levels, or a value the agent does not list — the needle stands upright
/// and nothing is filled. A faster tier adds a small bolt at the corner (A21).
/// Drawn from shapes rather than an image, so it keeps its weight at every
/// Dynamic Type size.
///
/// The hub is a filled disc where the reference draws a ring: at this size a
/// ring with the needle at either end of the arc reads as a magnifying glass.
public struct EffortGauge: View {
    let position: Double?
    let isFast: Bool
    /// About the height of the SF Symbols beside it at the body size, and
    /// growing with them.
    @ScaledMetric(relativeTo: .body) private var size: CGFloat = 21

    public init(position: Double?, isFast: Bool) {
        self.position = position
        self.isFast = isFast
    }

    public var body: some View {
        ZStack {
            GaugeArc(to: 1).stroke(Theme.border, style: GaugeGeometry.stroke(for: size))
            if let position, position > 0 {
                GaugeArc(to: position).stroke(Theme.running, style: GaugeGeometry.stroke(for: size))
            }
            GaugeNeedle(angle: Self.needleAngle(for: position))
                .stroke(Theme.ink, style: GaugeGeometry.stroke(for: size))
            GaugeHub().fill(Theme.ink)
        }
        .frame(width: size, height: size)
        // The bolt sits off the lower-right corner with a clear ring cut out
        // of the arc under it, as a symbol's own badge has, so it never runs
        // into the arc's end or a needle pointing there.
        .mask {
            Rectangle()
                .overlay(alignment: .bottomTrailing) {
                    if isFast { boltSpot(Circle()).blendMode(.destinationOut) }
                }
                .compositingGroup()
        }
        .overlay(alignment: .bottomTrailing) {
            if isFast {
                boltSpot(Image(systemName: "bolt.fill")
                    .font(.system(size: size * 0.42, weight: .semibold))
                    .foregroundStyle(Theme.ink))
            }
        }
        .accessibilityHidden(true)
    }

    /// Where the bolt and the ring around it stand, beyond the corner.
    private func boltSpot<Content: View>(_ content: Content) -> some View {
        content
            .frame(width: size * 0.56, height: size * 0.56)
            .offset(x: size * 0.3, y: size * 0.2)
    }

    /// Where the needle points: along the arc at a position, and straight up
    /// where there is none.
    public static func needleAngle(for position: Double?) -> Angle {
        GaugeGeometry.angle(at: position ?? GaugeGeometry.upright)
    }
}

/// The gauge's proportions, in fractions of its square.
enum GaugeGeometry {
    /// The arc runs clockwise from lower left, through the top, to lower
    /// right, leaving the bottom quarter open.
    static let start = Angle.degrees(135)
    static let sweep = 270.0
    /// Halfway along the sweep is straight up.
    static let upright = 0.5
    static let lineWidth: CGFloat = 0.09
    /// The track's centre line, so its outer edge meets the square's sides.
    static let radius: CGFloat = 0.5 - lineWidth / 2
    /// Where the needle turns and the arc is centred. The arc is open at the
    /// bottom, so this sits below the square's centre: the ink then has as
    /// much room above it as below.
    static let pivot = CGPoint(x: 0.5, y: 0.5 + radius * (1 - sin(.pi / 4)) / 2)
    /// A little wider than the needle, so the pivot reads as round.
    static let hubRadius: CGFloat = 0.085
    /// The needle runs from the pivot to about two thirds of the radius, clear
    /// of the arc it points at.
    static let needleEnd: CGFloat = 0.32

    static func stroke(for size: CGFloat) -> StrokeStyle {
        StrokeStyle(lineWidth: size * lineWidth, lineCap: .round, lineJoin: .round)
    }

    static func angle(at position: Double) -> Angle {
        .degrees(start.degrees + sweep * min(1, max(0, position)))
    }

    static func center(in rect: CGRect) -> CGPoint {
        point(at: .zero, distance: 0, in: rect)
    }

    static func point(at angle: Angle, distance: CGFloat, in rect: CGRect) -> CGPoint {
        let unit = min(rect.width, rect.height)
        return CGPoint(x: rect.minX + (pivot.x + distance * cos(angle.radians)) * unit,
                       y: rect.minY + (pivot.y + distance * sin(angle.radians)) * unit)
    }
}

/// The arc from the gauge's left end to a position along it.
struct GaugeArc: Shape {
    let to: Double

    func path(in rect: CGRect) -> Path {
        let unit = min(rect.width, rect.height)
        var path = Path()
        path.addArc(center: GaugeGeometry.center(in: rect),
                    radius: GaugeGeometry.radius * unit,
                    startAngle: GaugeGeometry.start, endAngle: GaugeGeometry.angle(at: to),
                    clockwise: false)
        return path
    }
}

/// The needle, from the pivot out towards the arc.
struct GaugeNeedle: Shape {
    let angle: Angle

    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: GaugeGeometry.center(in: rect))
        path.addLine(to: GaugeGeometry.point(at: angle, distance: GaugeGeometry.needleEnd, in: rect))
        return path
    }
}

/// The round hub the needle turns on.
struct GaugeHub: Shape {
    func path(in rect: CGRect) -> Path {
        let unit = min(rect.width, rect.height)
        let radius = GaugeGeometry.hubRadius * unit
        let center = GaugeGeometry.center(in: rect)
        return Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius,
                                      width: radius * 2, height: radius * 2))
    }
}
