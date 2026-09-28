import SwiftUI

/// A CSS border as the browser paints it: the band between the rounded
/// border box and the padding box, whose corners are the border radius less
/// the width of the side each one meets. With a width on only some sides —
/// the tool row's top and bottom rules under a 12-point radius — the band
/// tapers to nothing along each corner, as Chrome draws it.
struct ChatBorder: Shape {
    var top: CGFloat
    var leading: CGFloat
    var bottom: CGFloat
    var trailing: CGFloat
    var radius: CGFloat

    init(top: CGFloat = 0, leading: CGFloat = 0, bottom: CGFloat = 0, trailing: CGFloat = 0, radius: CGFloat = 0) {
        self.top = top
        self.leading = leading
        self.bottom = bottom
        self.trailing = trailing
        self.radius = radius
    }

    /// The same width on every side.
    init(width: CGFloat, radius: CGFloat) {
        self.init(top: width, leading: width, bottom: width, trailing: width, radius: radius)
    }

    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.addPath(Self.rounded(rect, radii: CornerRadii(uniform: radius)))
        let inner = CGRect(x: rect.minX + leading, y: rect.minY + top,
                           width: max(0, rect.width - leading - trailing),
                           height: max(0, rect.height - top - bottom))
        let radii = CornerRadii(
            topLeading: CGSize(width: max(0, radius - leading), height: max(0, radius - top)),
            topTrailing: CGSize(width: max(0, radius - trailing), height: max(0, radius - top)),
            bottomLeading: CGSize(width: max(0, radius - leading), height: max(0, radius - bottom)),
            bottomTrailing: CGSize(width: max(0, radius - trailing), height: max(0, radius - bottom)))
        path.addPath(Self.rounded(inner, radii: radii))
        return path
    }

    /// Drawn with the even-odd rule, so the inner box is a hole.
    var fillStyle: FillStyle { FillStyle(eoFill: true) }

    struct CornerRadii {
        var topLeading: CGSize
        var topTrailing: CGSize
        var bottomLeading: CGSize
        var bottomTrailing: CGSize

        init(topLeading: CGSize, topTrailing: CGSize, bottomLeading: CGSize, bottomTrailing: CGSize) {
            self.topLeading = topLeading
            self.topTrailing = topTrailing
            self.bottomLeading = bottomLeading
            self.bottomTrailing = bottomTrailing
        }

        init(uniform value: CGFloat) {
            let size = CGSize(width: value, height: value)
            self.init(topLeading: size, topTrailing: size, bottomLeading: size, bottomTrailing: size)
        }
    }

    /// A rectangle with an elliptical radius at each corner, clamped the way
    /// CSS clamps radii that do not fit.
    static func rounded(_ rect: CGRect, radii: CornerRadii) -> Path {
        guard rect.width > 0, rect.height > 0 else { return Path() }
        var r = radii
        let scale = min(1,
                        rect.width / max(0.0001, r.topLeading.width + r.topTrailing.width),
                        rect.width / max(0.0001, r.bottomLeading.width + r.bottomTrailing.width),
                        rect.height / max(0.0001, r.topLeading.height + r.bottomLeading.height),
                        rect.height / max(0.0001, r.topTrailing.height + r.bottomTrailing.height))
        if scale < 1 {
            for keyPath in [\CornerRadii.topLeading, \.topTrailing, \.bottomLeading, \.bottomTrailing] {
                r[keyPath: keyPath] = CGSize(width: r[keyPath: keyPath].width * scale,
                                             height: r[keyPath: keyPath].height * scale)
            }
        }
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + r.topLeading.width, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - r.topTrailing.width, y: rect.minY))
        corner(&path, center: CGPoint(x: rect.maxX - r.topTrailing.width, y: rect.minY + r.topTrailing.height),
               radii: r.topTrailing, from: -90)
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r.bottomTrailing.height))
        corner(&path, center: CGPoint(x: rect.maxX - r.bottomTrailing.width, y: rect.maxY - r.bottomTrailing.height),
               radii: r.bottomTrailing, from: 0)
        path.addLine(to: CGPoint(x: rect.minX + r.bottomLeading.width, y: rect.maxY))
        corner(&path, center: CGPoint(x: rect.minX + r.bottomLeading.width, y: rect.maxY - r.bottomLeading.height),
               radii: r.bottomLeading, from: 90)
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r.topLeading.height))
        corner(&path, center: CGPoint(x: rect.minX + r.topLeading.width, y: rect.minY + r.topLeading.height),
               radii: r.topLeading, from: 180)
        path.closeSubpath()
        return path
    }

    /// A quarter ellipse, clockwise from `start` degrees.
    private static func corner(_ path: inout Path, center: CGPoint, radii: CGSize, from start: Double) {
        guard radii.width > 0, radii.height > 0 else {
            path.addLine(to: CGPoint(x: center.x + radii.width * cos((start + 90) * .pi / 180),
                                     y: center.y + radii.height * sin((start + 90) * .pi / 180)))
            return
        }
        let steps = 16
        for step in 1...steps {
            let angle = (start + 90 * Double(step) / Double(steps)) * .pi / 180
            path.addLine(to: CGPoint(x: center.x + radii.width * cos(angle), y: center.y + radii.height * sin(angle)))
        }
    }
}

extension View {
    /// A CSS border drawn over this view, the way `border` paints on top of
    /// the element's own background.
    func chatBorder(_ border: ChatBorder, color: Color) -> some View {
        overlay { border.fill(color, style: border.fillStyle).allowsHitTesting(false) }
    }
}
