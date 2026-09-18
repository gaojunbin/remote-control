import SwiftUI
import RCCore

/// The Account group: what this account has, and how to leave it.
///
/// `docs/DESIGN.md` § "Account, in Settings": the admin is offered the accounts
/// screen and no password row — the operator's password is the gateway's own
/// `RC_PASSWORD` — and every other account the other way round. Who is signed
/// in is the header above, not a row here.
struct SettingsAccountGroup: View {
    @Environment(AppModel.self) private var model
    /// Held so a change of interface language rebuilds the sentences where
    /// they stand, rather than leaving them in the language they were built in.
    let language: InterfaceLanguage
    let users: () -> Void
    let changePassword: () -> Void
    let signOut: () -> Void

    var body: some View {
        SettingsGroup("Account") {
            if model.connection.isAdmin {
                Button(action: users) {
                    SettingsActionLabel("Users",
                                        sentence: L10n.string("Accounts on this gateway, and whether anyone can create one."),
                                        leadsOn: true)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("settings.users")
            } else {
                Button(action: changePassword) {
                    SettingsActionLabel("Change password",
                                        sentence: L10n.string("The current password and the new one."),
                                        leadsOn: true)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("settings.changePassword")
            }
            Button(action: signOut) {
                SettingsActionLabel("Sign out", sentence: Self.signOutSentence, tint: Theme.danger)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("settings.signOut")
        }
    }

    /// The row's sentence is also the confirmation's message: what the dialog
    /// explains is what the row promised.
    static var signOutSentence: String {
        L10n.string("Cached sessions and drafts leave this device. Nothing changes on your machines.")
    }
}
