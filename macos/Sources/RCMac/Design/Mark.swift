import SwiftUI

/// `web/src/layout/Mark.tsx`: the product mark, the app icon drawn inline at
/// any size — a near-black rounded square with three white dots at the corners
/// of a downward-pointing triangle, joined by grey bars that stop short of the
/// dots. Keep it identical to `web/public/icon.svg`, the iOS `AppIcon` and the
/// Mac's own.
public struct Mark: View {
    let size: CGFloat

    public init(size: CGFloat = 20) { self.size = size }

    public var body: some View {
        Canvas { context, canvas in
            let scale = canvas.width / 64
            context.scaleBy(x: scale, y: scale)
            context.fill(Path(roundedRect: CGRect(x: 0, y: 0, width: 64, height: 64),
                              cornerSize: CGSize(width: 14, height: 14), style: .circular),
                         with: .color(Color(hex: 0x161616)))
            var bars = Path()
            bars.move(to: CGPoint(x: 24.3, y: 22.7)); bars.addLine(to: CGPoint(x: 39.7, y: 22.7))
            bars.move(to: CGPoint(x: 21.74, y: 27.35)); bars.addLine(to: CGPoint(x: 29.06, y: 38.95))
            bars.move(to: CGPoint(x: 42.26, y: 27.35)); bars.addLine(to: CGPoint(x: 34.94, y: 38.95))
            context.stroke(bars, with: .color(Color(hex: 0x9A9A9A)), lineWidth: 2.1)
            for (x, y) in [(18.8, 22.7), (45.2, 22.7), (32.0, 43.6)] {
                context.fill(Path(ellipseIn: CGRect(x: x - 3.9, y: y - 3.9, width: 7.8, height: 7.8)),
                             with: .color(.white))
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
