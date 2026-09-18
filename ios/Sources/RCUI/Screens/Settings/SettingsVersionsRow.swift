import SwiftUI
import RCCore

/// The line that closes the screen: this build, the gateway's, and the protocol
/// both speak, with Diagnostics beside it.
///
/// `docs/DESIGN.md` § "The Settings screen": one caption in the tertiary ink,
/// centred, on the canvas rather than in a group. There is no About group; a
/// version is something to read once, not a setting.
struct SettingsVersionsRow: View {
    let gatewayVersion: String
    /// Held so a change of interface language rebuilds the line where it
    /// stands: `Gateway` and `Protocol` are words, and the numbers are not.
    let language: InterfaceLanguage
    let diagnostics: () -> Void

    var body: some View {
        // Side by side where they fit, stacked where the phone is too narrow
        // for the line and the button on one row.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Theme.Space.small) { versions; button }
            VStack(spacing: Theme.Space.tight) { versions; button }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Theme.Space.small)
    }

    private var versions: some View {
        Text(VersionsLine.text(app: AppBuild.version, gateway: gatewayVersion,
                               protocolVersion: RemoteProtocol.version))
            .font(Theme.Text.caption)
            .foregroundStyle(Theme.inkTertiary)
            .multilineTextAlignment(.center)
            .accessibilityIdentifier("settings.versions")
    }

    private var button: some View {
        Button("Diagnostics", action: diagnostics)
            .font(Theme.Text.caption)
            .accessibilityIdentifier("settings.diagnostics")
    }
}
