import RCCore
import SwiftUI

/// `VersionsLine.tsx`: the caption that closes the screen, and not a group.
/// The Mac app is installed apart from the gateway that serves the web, so the
/// line starts with its own version, as the iPhone app's does (`docs/DESIGN.md`
/// § "The Mac app" → **The Mac's own words**). Every number is read from the
/// running code: this bundle, the gateway's `hello` — or `GET /api/config`
/// before one has landed — and the protocol this build speaks, which a gateway
/// speaking another would have refused.
struct VersionsLine: View {
    @Environment(MacAppModel.self) private var model

    var body: some View {
        Text(S.mac.versions(AppBuild.version, gatewayVersion, "v\(RemoteProtocol.version)"))
            .css(FontSize.fs12, lineHeight: 1.5)
            .foregroundStyle(Palette.inkTertiary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.top, Space.sp8)
    }

    private var gatewayVersion: String {
        let connection = model.connection
        if !connection.gatewayVersion.isEmpty { return connection.gatewayVersion }
        return connection.config.version.isEmpty ? "—" : connection.config.version
    }
}
