import Foundation
import Observation
import RCCore
import SwiftUI

/// `DeleteUserModal.tsx` — A24: deleting an account revokes its devices and
/// their sessions leave the gateway, so the confirmation names how many go
/// with it.
@MainActor
@Observable
final class DeleteUserForm {
    let user: UserRecord
    private(set) var busy = false
    private(set) var error: String?

    init(user: UserRecord) { self.user = user }

    /// "Delete alice?", or "Delete alice and its 2 devices?" when some go too.
    var body: String {
        user.devices == 0
            ? S.users.deleteBody(user.username)
            : S.users.deleteBodyDevices(user.username, user.devices)
    }

    func submit(to users: UsersModel, on connection: ConnectionStore) async -> Bool {
        guard let store = users.store(on: connection) else { return false }
        busy = true
        error = nil
        do {
            try await store.delete(user.username)
            await users.load(on: connection)
            return true
        } catch {
            self.error = AccountErrors.userErrorText(error, conflict: S.account.notAllowed)
            busy = false
            return false
        }
    }
}

struct DeleteUserFields: View {
    let form: DeleteUserForm

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Hint(form.body)
            if let error = form.error {
                FormError(error).padding(.top, Space.sp3)
            }
        }
    }
}

extension View {
    /// The Delete account modal, open while there is a form for it.
    func deleteUserModal(_ form: DeleteUserForm?, isPresented: Binding<Bool>, users: UsersModel,
                         connection: ConnectionStore) -> some View {
        modal(isPresented: isPresented, title: S.users.deleteTitle, width: 440) {
            if let form { DeleteUserFields(form: form) }
        } footer: {
            if let form {
                Btn(S.common.cancel) { isPresented.wrappedValue = false }
                Btn(S.users.deleteConfirm, variant: .danger, busy: form.busy) {
                    Task { if await form.submit(to: users, on: connection) { isPresented.wrappedValue = false } }
                }
            }
        }
    }
}
