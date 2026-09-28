import SwiftUI

/// `web/src/features/chat/SizedBox.tsx`: a box as wide as the widest thing it
/// can ever hold. The model chip and the card's name row stay the width of the
/// widest model-and-effort pair the agent offers, so choosing a level or a
/// model never shifts what sits beside them (`docs/DESIGN.md` § "The
/// composer"). Every alternative is laid out and not drawn, and none of them
/// is read by assistive technology — measured, never guessed.
struct SizedBox<Shown: View, Ghost: View>: View {
    let alternatives: [LabelPair]
    @ViewBuilder let alternative: (LabelPair) -> Ghost
    @ViewBuilder let shown: () -> Shown

    var body: some View {
        ZStack(alignment: .leading) {
            ForEach(Array(alternatives.enumerated()), id: \.offset) { _, pair in
                alternative(pair).hidden().accessibilityHidden(true)
            }
            shown()
        }
    }
}
