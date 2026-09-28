import SwiftUI

/// `.spinner`: a 14-point ring of `--line-strong` whose top quarter is ink,
/// turning once every 0.7 s.
public struct Spinner: View {
    let size: CGFloat
    @State private var turning = false

    public init(size: CGFloat = 14) { self.size = size }

    public var body: some View {
        ZStack {
            Circle().inset(by: 1).stroke(Palette.lineStrong, lineWidth: 2)
            // A CSS border-top on a circle is the arc between the two upper
            // diagonals: a quarter turn centred on the top.
            Circle().inset(by: 1)
                .trim(from: 0.625, to: 0.875)
                .stroke(Palette.ink, lineWidth: 2)
        }
        .frame(width: size, height: size)
        .rotationEffect(.degrees(turning ? 360 : 0))
        .onAppear {
            withAnimation(.linear(duration: 0.7).repeatForever(autoreverses: false)) { turning = true }
        }
        .accessibilityHidden(true)
    }
}
