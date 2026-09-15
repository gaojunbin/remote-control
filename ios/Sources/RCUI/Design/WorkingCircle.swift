import SwiftUI
import RCCore

/// The composer's primary slot while the app, and not the person, has the next
/// move: Send's circle with a spinner in it.
///
/// `docs/DESIGN.md` § "The composer" → **Done becomes a spinner, and the
/// spinner becomes Send**. Deliberately not a `Button`, and not a disabled one
/// either — a control that looks live and does nothing is the one thing the row
/// must never show. It carries Send's colour and Send's size so the slot neither
/// moves nor changes height when the words arrive and Send takes it back, and
/// it says in words what it is waiting for, because a spinner alone says only
/// that something is happening.
struct WorkingCircle: View {
    let label: String

    var body: some View {
        ProgressView()
            .progressViewStyle(.circular)
            .tint(Theme.onAccent)
            .frame(width: Theme.Touch.primary, height: Theme.Touch.primary)
            .background(Theme.accent, in: Circle())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(label)
            .accessibilityIdentifier("composer.working")
    }
}
