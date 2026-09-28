import Foundation
import Observation
import RCCore
import SwiftUI

/// `AddUserModal.tsx` — A24: the admin makes an account without waiting for
/// anyone to register. One of these is made for each opening, so every opening
/// starts empty.
@MainActor
@Observable
final class AddUserForm {
    var username = ""
    var password = ""
    /// Member first, and the default: an admin is the exception on this screen.
    var role: UserRole = .member
    private(set) var busy = false
    private(set) var error: String?

    var ready: Bool {
        !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && password.count >= AccountRules.passwordLength.lowerBound
    }

    /// True once the account exists and the list has been read again.
    func submit(to users: UsersModel, on connection: ConnectionStore) async -> Bool {
        guard let store = users.store(on: connection) else { return false }
        busy = true
        error = nil
        do {
            try await store.create(username: username.trimmingCharacters(in: .whitespacesAndNewlines),
                                   password: password, role: role)
            await users.load(on: connection)
            return true
        } catch {
            self.error = AccountErrors.userErrorText(error, conflict: S.account.taken)
            busy = false
            return false
        }
    }
}

/// The modal's `.form-stack`: username, password, the role, and the refusal.
struct AddUserFields: View {
    @Bindable var form: AddUserForm

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            FieldLabel(S.account.username)
            WebField(text: $form.username)
            FieldLabel(S.account.password).padding(.top, Space.sp4)
            WebField(text: $form.password, secure: true)
            FieldLabel(S.account.role).padding(.top, Space.sp4)
            Segmented(value: form.role, options: [UserRole.member, .admin].map {
                SegmentOption(value: $0, label: S.roleLabel($0.rawValue))
            }, ariaLabel: S.account.role) { form.role = $0 }
            if let error = form.error {
                FormError(error).padding(.top, Space.sp3)
            }
        }
    }
}

extension View {
    /// The Add user modal, open while there is a form for it.
    func addUserModal(_ form: AddUserForm?, isPresented: Binding<Bool>, users: UsersModel,
                      connection: ConnectionStore) -> some View {
        modal(isPresented: isPresented, title: S.users.add, width: 420) {
            if let form { AddUserFields(form: form) }
        } footer: {
            if let form {
                Btn(S.common.cancel) { isPresented.wrappedValue = false }
                Btn(S.users.add, variant: .primary, busy: form.busy) {
                    Task { if await form.submit(to: users, on: connection) { isPresented.wrappedValue = false } }
                }
                .disabled(!form.ready)
            }
        }
    }
}
