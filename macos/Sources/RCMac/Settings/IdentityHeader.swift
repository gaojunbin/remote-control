import RCCore
import SwiftUI

/// `IdentityHeader.tsx`: who is signed in and where, as the screen's first
/// element and on the canvas rather than on a surface (`docs/DESIGN.md` §
/// "The Settings screen"). It replaces the rows Signed in as, Connection,
/// Gateway and Gateway version. The host is the one the topbar prints, by the
/// same rule, so the two never disagree.
struct IdentityHeader: View {
    @Environment(MacAppModel.self) private var model

    var body: some View {
        let connection = model.connection
        let name = connection.username.isEmpty ? "—" : connection.username
        let word = IdentityDot.word(connection.phase)
        HStack(spacing: Space.sp4) {
            // The topbar's circle at the size a header takes.
            Text(Identity.initials(name))
                .css(FontSize.fs16, weight: .semibold, tracking: 0.02)
                .foregroundStyle(Palette.inkInverse)
                .frame(width: 44, height: 44)
                .background(Circle().fill(Palette.ink))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 0) {
                Text(name)
                    .css(FontSize.fs17, weight: .semibold, lineHeight: 1.35, tracking: -0.01)
                    .accessibilityAddTraits(.isHeader)
                SettingsShrinkRow(spacing: Space.sp2, shrinking: 3) {
                    Text(S.roleLabel(connection.user.role.rawValue)).css(FontSize.fs13, lineHeight: 1.4)
                    Text("·").css(FontSize.fs13, lineHeight: 1.4).accessibilityHidden(true)
                    Dot(.tone(IdentityDot.tone(connection.phase)))
                        .help(word)
                        .accessibilityElement()
                        .accessibilityLabel(word)
                    MiddleTruncatedHost(text: Identity.gatewayHost(model.origin))
                }
                .foregroundStyle(Palette.inkSecondary)
                .padding(.top, 1)
            }
            .frame(minWidth: 0, alignment: .leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, Space.sp6)
    }
}
