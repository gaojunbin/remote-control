import SwiftUI

/// `/settings`, `web/src/features/settings/SettingsPage.tsx`, in the shape
/// `docs/DESIGN.md` § "The Settings screen" gives it: a header saying who and
/// where, four groups named for the question each answers, and the versions as
/// one caption line.
public struct SettingsPage: View {
    public init() {}

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PageHead(S.settings.title)
            VStack(alignment: .leading, spacing: Space.sp6) {
                VStack(alignment: .leading, spacing: 0) {
                    IdentityHeader()
                    AccountGroup()
                }
                WhileAwayGroup()
                VoiceGroup()
                VStack(spacing: 0) {
                    ReadingGroup()
                    VersionsLine()
                }
            }
            .frame(maxWidth: 620, alignment: .leading)
        }
    }
}
