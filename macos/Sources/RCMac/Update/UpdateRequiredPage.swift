import RCCore
import SwiftUI

/// Amendment A45 — PROTOCOL 8.16: the one screen a Mac app older than its
/// gateway shows, in the web's visual language on the login page's card. Below
/// the minimum nothing else is reachable, because everything else would fail
/// in ways the app cannot explain. Two ways forward and no third: fetch the
/// newer build where the operator says it is, or sign out and go to another
/// gateway. The words are the iPhone app's, because it is the same screen.
struct UpdateRequiredPage: View {
    let requirement: AppUpdateRequirement
    @Environment(MacAppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @Environment(\.layoutClass) private var layout

    var body: some View {
        ScrollView {
            // `.login` centres the card; the browser puts it on a whole point.
            WholePointCenter(minimumHeight: layout.height) {
                card
                    .frame(maxWidth: 380)
                    .padding(Space.sp6)
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        .background(Palette.canvas)
        .overlay(alignment: .top) { WindowStrip().frame(height: LayoutSize.headerH) }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Space.sp3) {
                Mark(size: 26)
                Text(S.mac.updateTitle)
                    .css(FontSize.fs22, weight: .semibold, tracking: -0.01)
                    .accessibilityAddTraits(.isHeader)
            }
            .padding(.bottom, Space.sp2)
            Hint(S.mac.updateBody)
                .padding(.bottom, Space.sp6)

            VersionLine(label: S.mac.updateThisApp, value: requirement.current.description)
            VersionLine(label: S.mac.updateGatewayNeeds, value: requirement.minimum.description)
                .padding(.bottom, Space.sp6)

            if let url = requirement.updateURL {
                Btn(Self.openTitle(url), variant: .primary, size: .block) { openURL(url) }
                    .keyboardShortcut(.defaultAction)
            }
            Btn(S.settings.signOut, variant: requirement.updateURL == nil ? .primary : .ghost, size: .block) {
                Task { await model.signOut() }
            }
            .padding(.top, requirement.updateURL == nil ? 0 : Space.sp2)
        }
        .padding(Space.sp8)
        .card(shadow: Shadow.one)
    }

    /// TestFlight and the App Store are the two places Apple hands a build out
    /// from, and the button says which; anywhere else is a download page.
    static func openTitle(_ url: URL) -> String {
        let host = url.host()?.lowercased() ?? ""
        if host.contains("testflight") { return S.mac.updateOpenTestFlight }
        if host == "apps.apple.com" || host.hasSuffix(".apps.apple.com") { return S.mac.updateOpenAppStore }
        return S.mac.updateOpenDownload
    }
}

/// One of the two versions the screen compares: the label in the secondary
/// ink, the number in mono.
private struct VersionLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: Space.sp3) {
            Text(label)
                .foregroundStyle(Palette.inkSecondary)
            Spacer(minLength: Space.sp3)
            Text(value)
                .font(.system(size: FontSize.fs13, design: .monospaced))
                .foregroundStyle(Palette.ink)
        }
        .css(FontSize.fs14)
        .padding(.vertical, Space.sp2)
    }
}
