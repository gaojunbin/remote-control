import SwiftUI
import RCCore

/// Who is signed in, and where.
///
/// `docs/DESIGN.md` § "The Settings screen": the screen opens with this on the
/// canvas rather than in a card — a circle of initials, the username beside it,
/// and `role · host` under them with the connection's dot before the host. It
/// replaced four rows that each said a quarter of it: Gateway, Signed in as,
/// Connection and Gateway version.
///
/// The word for the dot is the accessibility label and is never printed: the
/// colour says it on screen, as it does on every other row in the app.
struct SettingsIdentityHeader: View {
    let user: UserIdentity
    let host: String
    let phase: ConnectionPhase

    @ScaledMetric(relativeTo: .title2) private var diameter: CGFloat = 44

    var body: some View {
        HStack(spacing: Theme.Space.medium) {
            Text(Initials.of(user.username))
                .font(.headline)
                .foregroundStyle(Theme.onAccent)
                .frame(width: diameter, height: diameter)
                .background(Theme.ink, in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(user.username)
                    .font(Theme.Text.title)
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                HStack(spacing: Theme.Space.tight) {
                    Text(user.role.title)
                    Text(verbatim: "·")
                    StatusDot(tone: ConnectionTone.dot(phase))
                    Text(host)
                        .lineLimit(1)
                        // The head of an origin is the same on every gateway a
                        // person has; the tail is which one this is.
                        .truncationMode(.middle)
                }
                .font(Theme.Text.meta)
                .foregroundStyle(Theme.inkSecondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, Theme.Space.small)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(IdentityLine.label(user: user, host: host, phase: phase))
        .accessibilityIdentifier("settings.identity")
    }
}
