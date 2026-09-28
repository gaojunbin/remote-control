import RCCore
import SwiftUI

/// `AccountGroup.tsx` — A24: the rows this account has. Users belongs to the
/// admin role; a password belongs to whoever has one, and the gateway refuses
/// the change for the built-in `admin` alone, whose password is its own
/// `RC_PASSWORD`, so a second admin account gets both rows. Sign out asks
/// first (`docs/DESIGN.md` § "The Settings screen").
struct AccountGroup: View {
    @Environment(MacAppModel.self) private var model
    @Environment(\.previewStage) private var stage
    @State private var changingPassword: ChangePasswordForm?
    @State private var signingOut = false

    var body: some View {
        let connection = model.connection
        SettingsGroup(S.settings.account) {
            if connection.isAdmin {
                SettingsActionRow(title: S.users.title, sentence: S.settings.usersNote) {
                    model.router.go(.users)
                }
            }
            if !connection.username.isEmpty && connection.username != AccountRules.operatorUsername {
                SettingsActionRow(title: S.settings.changePassword, sentence: S.settings.changePasswordNote) {
                    changingPassword = ChangePasswordForm()
                }
            }
            SettingsActionRow(title: S.settings.signOut, sentence: S.settings.signOutNote, danger: true) {
                signingOut = true
            }
        }
        .changePasswordModal(changingPassword, isPresented: $changingPassword.settingsModalOpen,
                             connection: connection)
        .confirmDialog(isPresented: $signingOut, title: S.settings.signOutConfirm, body: S.settings.signOutNote,
                       confirmLabel: S.settings.signOut, danger: true) {
            Task { await model.signOut() }
        }
        .onAppear {
            switch stage {
            case "settings.sign-out": signingOut = true
            case "settings.change-password": changingPassword = ChangePasswordForm()
            case "settings.change-password-error": refuseStagedPassword()
            default: break
            }
        }
    }

    /// A render of the refusal: a current password the gateway does not take.
    private func refuseStagedPassword() {
        let form = ChangePasswordForm()
        form.current = "wrong"
        form.next = "another-password"
        changingPassword = form
        Task { _ = await form.submit(on: model.connection) }
    }
}
