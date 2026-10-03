import SwiftUI

/// Amendment A47: the red dot of a session that stopped working and waits for
/// the person (`docs/DESIGN.md` § "A red dot for a session that stopped and
/// waits for you"). 8 points of the Danger red, drawn over the title it sits
/// beside, centred in the row's leading gutter and on the title's line, so it
/// moves nothing when it comes or goes. The row says it in words; the dot
/// itself is not read out.
struct UnseenDot: View {
    /// From the row's leading edge to the title's.
    let reach: CGFloat
    /// The gutter at the row's leading edge the dot is centred in.
    let gutter: CGFloat

    static let size: CGFloat = 8

    var body: some View {
        Circle()
            .fill(Palette.danger)
            .frame(width: Self.size, height: Self.size)
            .offset(x: (gutter - Self.size) / 2 - reach)
            .accessibilityHidden(true)
    }
}

extension View {
    /// The title a session's red dot is drawn beside, while it has one.
    func unseenTitle(_ unseen: Bool, reach: CGFloat, gutter: CGFloat) -> some View {
        overlay(alignment: .leading) {
            if unseen { UnseenDot(reach: reach, gutter: gutter) }
        }
    }
}
