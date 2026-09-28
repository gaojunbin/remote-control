import SwiftUI

/// The SVG elliptical arc (`A rx ry rotation large-arc sweep x y`), converted
/// from the endpoint form the path data uses to the centre form a curve can be
/// drawn from (SVG 1.1, appendix F.6.5), and drawn as cubic Béziers of at most
/// a quarter turn each.
enum SVGArc {
    static func add(to path: inout Path, from p0: CGPoint, to p1: CGPoint, radii: CGSize,
                    rotation degrees: CGFloat, largeArc: Bool, sweep: Bool) {
        var rx = abs(radii.width)
        var ry = abs(radii.height)
        guard p0 != p1 else { return }
        guard rx > 0, ry > 0 else {
            path.addLine(to: p1)
            return
        }
        let phi = degrees * .pi / 180
        let cosPhi = cos(phi), sinPhi = sin(phi)
        // Step 1: the start point in the ellipse's own frame, relative to the midpoint.
        let dx = (p0.x - p1.x) / 2, dy = (p0.y - p1.y) / 2
        let x1 = cosPhi * dx + sinPhi * dy
        let y1 = -sinPhi * dx + cosPhi * dy
        // Radii too small to reach are scaled up just enough (F.6.6).
        let lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if lambda > 1 {
            rx *= lambda.squareRoot()
            ry *= lambda.squareRoot()
        }
        // Step 2: the centre in that frame.
        let numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        let denominator = rx * rx * y1 * y1 + ry * ry * x1 * x1
        var factor = (max(0, numerator) / denominator).squareRoot()
        if largeArc == sweep { factor = -factor }
        let cx1 = factor * rx * y1 / ry
        let cy1 = -factor * ry * x1 / rx
        // Step 3: the centre in user space.
        let cx = cosPhi * cx1 - sinPhi * cy1 + (p0.x + p1.x) / 2
        let cy = sinPhi * cx1 + cosPhi * cy1 + (p0.y + p1.y) / 2
        // Step 4: the start angle and the sweep.
        let ux = (x1 - cx1) / rx, uy = (y1 - cy1) / ry
        let vx = (-x1 - cx1) / rx, vy = (-y1 - cy1) / ry
        let theta1 = angle(from: CGPoint(x: 1, y: 0), to: CGPoint(x: ux, y: uy))
        var delta = angle(from: CGPoint(x: ux, y: uy), to: CGPoint(x: vx, y: vy))
        if !sweep, delta > 0 { delta -= 2 * .pi }
        if sweep, delta < 0 { delta += 2 * .pi }

        let segments = max(1, Int((abs(delta) / (.pi / 2)).rounded(.up)))
        let step = delta / CGFloat(segments)
        let k = 4 / 3 * tan(step / 4)
        func point(_ theta: CGFloat) -> CGPoint {
            CGPoint(x: cx + rx * cos(theta) * cosPhi - ry * sin(theta) * sinPhi,
                    y: cy + rx * cos(theta) * sinPhi + ry * sin(theta) * cosPhi)
        }
        func derivative(_ theta: CGFloat) -> CGPoint {
            CGPoint(x: -rx * sin(theta) * cosPhi - ry * cos(theta) * sinPhi,
                    y: -rx * sin(theta) * sinPhi + ry * cos(theta) * cosPhi)
        }
        var theta = theta1
        for index in 0..<segments {
            let next = theta + step
            let a = point(theta), b = point(next)
            let da = derivative(theta), db = derivative(next)
            let end = index == segments - 1 ? p1 : b
            path.addCurve(to: end,
                          control1: CGPoint(x: a.x + k * da.x, y: a.y + k * da.y),
                          control2: CGPoint(x: b.x - k * db.x, y: b.y - k * db.y))
            theta = next
        }
    }

    private static func angle(from u: CGPoint, to v: CGPoint) -> CGFloat {
        let dot = u.x * v.x + u.y * v.y
        let length = (u.x * u.x + u.y * u.y).squareRoot() * (v.x * v.x + v.y * v.y).squareRoot()
        var value = acos(max(-1, min(1, dot / length)))
        if u.x * v.y - u.y * v.x < 0 { value = -value }
        return value
    }
}
