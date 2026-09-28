import RCCore
import SwiftUI

/// `SessionLegend.tsx` with `legend.ts`: what the dots mean, said once above the
/// list and nowhere else — not on the chat sidebar, not on the Devices screen,
/// and not when the list is empty, where the empty state speaks instead. A
/// caption line with no box, no border and no title (`docs/DESIGN.md` § "A
/// legend, once, and quiet").
struct SessionLegend: View {
    /// The four colours a dot takes, in the order the legend reads them. Four,
    /// not five: the pulsing amber of a waiting session and the solid amber of a
    /// finished turn are one colour to the eye, so the legend draws the still
    /// one and "For you" covers both. Built on every read, so the words follow
    /// the interface language.
    static var entries: [Entry] {
        [Entry(tone: .working, label: S.sessions.legendWorking),
         Entry(tone: .live, label: S.sessions.legendAttention),
         Entry(tone: .off, label: S.sessions.legendOff),
         Entry(tone: .failed, label: S.sessions.legendFailed)]
    }

    struct Entry: Identifiable, Equatable {
        let tone: DotTone
        let label: String

        var id: DotTone { tone }
    }

    var body: some View {
        WrapRow(spacing: Space.sp4, lineSpacing: Space.sp1) {
            ForEach(Self.entries) { entry in
                HStack(spacing: Space.sp2) {
                    Dot(.tone(entry.tone))
                    Text(entry.label)
                        .lineLimit(1)
                        .css(FontSize.fs12, lineHeight: 1.45)
                        .foregroundStyle(Palette.inkSecondary)
                }
                .fixedSize()
                .accessibilityElement(children: .combine)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(S.sessions.legend)
    }
}
