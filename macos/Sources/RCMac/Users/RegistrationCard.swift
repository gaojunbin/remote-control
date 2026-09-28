import SwiftUI

/// `.users-registration`: the one switch at the top of Users, with its caption
/// under it (`docs/DESIGN.md` § "Accounts": off on a fresh gateway).
struct RegistrationCard: View {
    let isOpen: Bool
    let onChange: (Bool) -> Void
    @Environment(\.layoutClass) private var layout

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Space.sp4) {
                Text(S.users.registration)
                    .css(FontSize.fs14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Switch(isOn: isOpen, label: S.users.registration, onChange: onChange)
            }
            .frame(minHeight: 40)
            Text(S.users.registrationCaption)
                .css(FontSize.fs12, lineHeight: 1.5)
                .foregroundStyle(Palette.inkTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 2)
        }
        .padding(.top, Space.sp3)
        .padding(.bottom, Space.sp4)
        .padding(.horizontal, layout.maxWidth640 ? Space.sp4 : Space.sp5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .surface()
    }
}
