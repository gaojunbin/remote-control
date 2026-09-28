import RCCore
import SwiftUI

/// `/users` (A24), `web/src/features/users/UsersPage.tsx`: the admin's
/// accounts screen — the registration switch, one row per account, and Add
/// user as the page's primary button. The gateway answers `403` to everyone
/// else, so a member who reaches the address is sent to Sessions before
/// anything is asked for, rather than shown an empty page that failed.
public struct UsersPage: View {
    @Environment(MacAppModel.self) private var model
    @Environment(\.previewStage) private var stage
    @State private var adding: AddUserForm?
    @State private var resetting: ResetPasswordForm?
    @State private var deleting: DeleteUserForm?

    public init() {}

    public var body: some View {
        let users = SettingsFeature.state(of: model).users
        let connection = model.connection
        VStack(alignment: .leading, spacing: 0) {
            PageHead(S.users.title) {
                Btn(S.users.add, icon: .plus, variant: .primary) { adding = AddUserForm() }
            }
            VStack(alignment: .leading, spacing: 0) {
                RegistrationCard(isOpen: users.registrationOpen) { open in
                    Task { await users.openRegistration(open, on: connection) }
                }
                if let error = users.error {
                    FormError(error).padding(.top, Space.sp4)
                }
                if users.loaded {
                    accounts(users).padding(.top, Space.sp4)
                }
            }
            .frame(maxWidth: 620, alignment: .leading)
        }
        .task {
            guard connection.isAdmin else {
                model.router.replace(.sessions)
                return
            }
            await users.load(on: connection)
            openStagedDialog(users)
        }
        .addUserModal(adding, isPresented: $adding.settingsModalOpen, users: users, connection: connection)
        .resetPasswordModal(resetting, isPresented: $resetting.settingsModalOpen, users: users,
                            connection: connection)
        .deleteUserModal(deleting, isPresented: $deleting.settingsModalOpen, users: users, connection: connection)
    }

    private func accounts(_ users: UsersModel) -> some View {
        VStack(spacing: 0) {
            ForEach(users.users) { user in
                let actionable = !user.isOperator
                UserRow(user: user, actionable: actionable,
                        menuOpen: stage == "users.menu" && actionable && user.id == firstActionable(users),
                        onResetPassword: { resetting = ResetPasswordForm(username: user.username) },
                        onToggleState: { Task { await users.toggleState(of: user, on: model.connection) } },
                        onDelete: { deleting = DeleteUserForm(user: user) })
            }
        }
        .surface()
    }

    private func firstActionable(_ users: UsersModel) -> String? {
        users.users.first { !$0.isOperator }?.id
    }

    /// A render's stage opens a dialog the page would open on a click.
    private func openStagedDialog(_ users: UsersModel) {
        guard let user = users.users.first(where: { !$0.isOperator }) else {
            if stage == "users.add" { adding = AddUserForm() }
            return
        }
        switch stage {
        case "users.add": adding = AddUserForm()
        case "users.reset": resetting = ResetPasswordForm(username: user.username)
        case "users.delete": deleting = DeleteUserForm(user: user)
        default: break
        }
    }
}
