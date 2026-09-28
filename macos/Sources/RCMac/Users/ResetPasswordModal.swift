import Foundation
import Observation
import RCCore
import SwiftUI

/// `ResetPasswordModal.tsx` — A24: the admin sets a member's password
/// outright. It never asks for the old one — the admin does not have it — and
/// the account's open sign-ins stay valid.
@MainActor
@Observable
final class ResetPasswordForm {
    let username: String
    var password = ""
    private(set) var busy = false
    private(set) var error: String?

    init(username: String) { self.username = username }

    var ready: Bool { password.count >= AccountRules.passwordLength.lowerBound }

    func submit(to users: UsersModel, on connection: ConnectionStore) async -> Bool {
        guard let store = users.store(on: connection) else { return false }
        busy = true
        error = nil
        do {
            try await store.resetPassword(of: username, to: password)
            await users.load(on: connection)
            return true
        } catch {
            self.error = AccountErrors.userErrorText(error, conflict: S.account.notAllowed)
            busy = false
            return false
        }
    }
}

struct ResetPasswordFields: View {
    @Bindable var form: ResetPasswordForm

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            FieldLabel(S.account.newPassword)
            WebField(text: $form.password, secure: true)
            if let error = form.error {
                FormError(error).padding(.top, Space.sp3)
            }
        }
    }
}

extension View {
    /// The Reset password modal, open while there is a form for it.
    func resetPasswordModal(_ form: ResetPasswordForm?, isPresented: Binding<Bool>, users: UsersModel,
                            connection: ConnectionStore) -> some View {
        modal(isPresented: isPresented, title: S.users.resetPasswordTitle(form?.username ?? ""), width: 420) {
            if let form { ResetPasswordFields(form: form) }
        } footer: {
            if let form {
                Btn(S.common.cancel) { isPresented.wrappedValue = false }
                Btn(S.common.save, variant: .primary, busy: form.busy) {
                    Task { if await form.submit(to: users, on: connection) { isPresented.wrappedValue = false } }
                }
                .disabled(!form.ready)
            }
        }
    }
}
