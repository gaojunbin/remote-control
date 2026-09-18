import SwiftUI
import RCCore

/// Amendment A31 — the one screen an app older than its gateway may show.
///
/// The gateway states the oldest build it works with. Below it nothing else is
/// reachable, because everything else would fail in ways a phone cannot
/// explain. Two ways forward and no third: fetch the newer build where the
/// operator says it is, or sign out and go to another gateway.
struct UpdateRequiredView: View {
    let requirement: AppUpdateRequirement
    let signOut: () -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(spacing: Theme.Space.large) {
            Spacer(minLength: 0)
            VStack(spacing: Theme.Space.small) {
                Image(systemName: "arrow.up.circle")
                    .font(.system(size: 40, weight: .light))
                    .foregroundStyle(Theme.inkSecondary)
                    .accessibilityHidden(true)
                Text("Update required")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                    // Identifiers go on the elements themselves: one put on
                    // the container would overwrite every identifier inside it.
                    .accessibilityIdentifier("update.title")
                Text("This gateway needs a newer version of the app.")
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSecondary)
                    .multilineTextAlignment(.center)
            }

            VStack(spacing: 0) {
                ValueRow("This app", value: requirement.current.description)
                Divider().overlay(Theme.hairline)
                ValueRow("Gateway needs", value: requirement.minimum.description)
            }
            .padding(.horizontal, Theme.Space.medium)
            .background(Theme.surface,
                        in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
            .accessibilityIdentifier("update.versions")

            VStack(spacing: Theme.Space.small) {
                if let url = requirement.updateURL {
                    Button(openTitle(url)) { openURL(url) }
                        .buttonStyle(PrimaryButtonStyle())
                        .accessibilityIdentifier("update.open")
                }
                Button("Sign out", action: signOut)
                    .font(Theme.Text.label)
                    .foregroundStyle(Theme.inkSecondary)
                    .accessibilityIdentifier("update.signOut")
            }
            Spacer(minLength: 0)
        }
        .padding(Theme.Space.page)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .pageBackground()
    }

    /// TestFlight and the App Store are the two places a build comes from, and
    /// the link says which. Anywhere else is named by what the button does.
    private func openTitle(_ url: URL) -> String {
        url.host()?.contains("testflight") == true
            ? L10n.string("Open TestFlight") : L10n.string("Open the App Store")
    }
}

#Preview("Update required") {
    UpdateRequiredView(
        requirement: AppUpdateRequirement(
            current: AppVersion("0.1.0"), minimum: AppVersion("1.0.0"),
            updateURL: URL(string: "https://testflight.apple.com/join/EXAMPLE")),
        signOut: {})
}
