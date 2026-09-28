import SwiftUI

/// Amendment A44: the permission mode's glyph on the phone, a shield with a
/// prompt, `>_`, inside it (`docs/DESIGN.md` § "The control row").
///
/// SF Symbols has the shield and, separately, the terminal's prompt, but not
/// the two together. So the shield is the symbol itself — its weight and size
/// follow the font like the glyphs beside it — and the prompt is drawn into it
/// at the shield's own stroke, in whatever the foreground is.
public struct PromptShield: View {
    public init() {}

    public var body: some View {
        Image(systemName: "shield")
            .overlay {
                GeometryReader { proxy in
                    PromptShape()
                        .stroke(style: StrokeStyle(lineWidth: proxy.size.width * PromptShape.lineWidth,
                                                   lineCap: .round, lineJoin: .round))
                }
            }
            .accessibilityHidden(true)
    }
}

/// `>` and `_` in the shield symbol's frame. The fractions are measured from
/// the symbol drawn at 200 pt: its outline is 7 % of the frame's width thick,
/// and its inside runs from 19 % to 81 % across the middle and from 15 % down
/// to the point at 85 %, widest in the upper half — which is where the prompt
/// sits, centred across it, the way a terminal draws one.
struct PromptShape: Shape {
    static let lineWidth: CGFloat = 0.07
    /// The chevron's two ends and its point, then the underscore's two ends.
    static let chevron = [CGPoint(x: 0.335, y: 0.375), CGPoint(x: 0.465, y: 0.47),
                          CGPoint(x: 0.335, y: 0.565)]
    static let underscore = [CGPoint(x: 0.54, y: 0.565), CGPoint(x: 0.675, y: 0.565)]

    func path(in rect: CGRect) -> Path {
        func place(_ point: CGPoint) -> CGPoint {
            CGPoint(x: rect.minX + point.x * rect.width, y: rect.minY + point.y * rect.height)
        }
        var path = Path()
        path.addLines(Self.chevron.map(place))
        path.move(to: place(Self.underscore[0]))
        path.addLine(to: place(Self.underscore[1]))
        return path
    }
}
