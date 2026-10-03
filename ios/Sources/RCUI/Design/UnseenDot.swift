import SwiftUI
import RCCore

/// Amendment A47: the red dot of a session that stopped working and waits for
/// the person (`docs/DESIGN.md` § "A red dot for a session that stopped and
/// waits for you"). 8 points of the Danger red, drawn over the title it sits
/// beside and centred in the row's leading gutter, so it moves nothing when it
/// comes or goes. The row says it in words; the dot itself is not read out.
struct UnseenDot: View {
    /// The width of the gutter to the leading side of the title.
    let gutter: CGFloat

    static let size: CGFloat = 8

    /// What a screen reader hears on a row that carries the dot.
    static var label: String { L10n.string("not yet opened") }

    var body: some View {
        Circle()
            .fill(Theme.danger)
            .frame(width: Self.size, height: Self.size)
            .offset(x: -(gutter + Self.size) / 2)
            .accessibilityHidden(true)
    }
}
