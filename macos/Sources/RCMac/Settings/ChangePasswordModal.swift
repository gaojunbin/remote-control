import Foundation
import Observation
import RCCore
import SwiftUI

/// `ChangePasswordModal.tsx` — A24: an account changes its own password here.
/// `admin`'s is `RC_PASSWORD` and the gateway refuses it, which is why the row
/// that opens this is every other account's. One of these is made for each
/// opening, so every opening starts empty.
@MainActor
@Observable
final class ChangePasswordForm {
    var current = ""
    var next = ""
    private(set) var busy = false
    private(set) var error: String?

    var ready: Bool { !current.isEmpty && next.count >= AccountRules.passwordLength.lowerBound }

    /// True once the gateway has taken the new password.
    func submit(on connection: ConnectionStore) async -> Bool {
        busy = true
        error = nil
        do {
            try await connection.changePassword(current: current, new: next)
            return true
        } catch {
            self.error = Self.errorText(error)
            busy = false
            return false
        }
    }

    /// `passwordErrorText`: the refusal read from its status. A `401` reaches
    /// this side as `TransportError.unauthorized`, and here it is the current
    /// password that was wrong, not the session.
    static func errorText(_ error: any Error) -> String {
        switch error as? TransportError {
        case .unauthorized: S.account.wrongCurrentPassword
        case .http(status: 403, code: _): S.account.notAllowed
        case .http(status: 400, code: _): S.account.rules
        default: S.errors.generic
        }
    }
}

struct ChangePasswordFields: View {
    @Bindable var form: ChangePasswordForm

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            FieldLabel(S.account.currentPassword)
            WebField(text: $form.current, secure: true)
            FieldLabel(S.account.newPassword).padding(.top, Space.sp4)
            WebField(text: $form.next, secure: true)
            if let error = form.error {
                FormError(error).padding(.top, Space.sp3)
            }
        }
    }
}

extension View {
    /// The Change password modal, open while there is a form for it.
    func changePasswordModal(_ form: ChangePasswordForm?, isPresented: Binding<Bool>,
                             connection: ConnectionStore) -> some View {
        modal(isPresented: isPresented, title: S.settings.changePassword, width: 420) {
            if let form { ChangePasswordFields(form: form) }
        } footer: {
            if let form {
                Btn(S.common.cancel) { isPresented.wrappedValue = false }
                Btn(S.common.save, variant: .primary, busy: form.busy) {
                    Task { if await form.submit(on: connection) { isPresented.wrappedValue = false } }
                }
                .disabled(!form.ready)
            }
        }
    }
}
