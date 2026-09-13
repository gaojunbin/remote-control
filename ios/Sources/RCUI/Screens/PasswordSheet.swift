import SwiftUI
import RCCore

/// Changing your own password (`POST /api/password`, A24).
///
/// Two fields and nothing else: the one you have and the one you want. Other
/// sign-ins of the account stay valid, which the sheet says, because a person
/// changing a password usually wants to know whether it signs them out.
struct PasswordSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var current = ""
    @State private var replacement = ""
    @State private var isWorking = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.large) {
                    VStack(alignment: .leading, spacing: Theme.Space.medium) {
                        VStack(alignment: .leading, spacing: Theme.Space.tight) {
                            FieldLabel("Current password")
                            SecureField("Your password", text: $current)
                                .textContentType(.password)
                                .frame(minHeight: Theme.Touch.minimum)
                                .accessibilityIdentifier("password.current")
                        }
                        Divider().overlay(Theme.border)
                        VStack(alignment: .leading, spacing: Theme.Space.tight) {
                            FieldLabel("New password")
                            SecureField("At least 8 characters", text: $replacement)
                                .textContentType(.newPassword)
                                .frame(minHeight: Theme.Touch.minimum)
                                .accessibilityIdentifier("password.new")
                        }
                    }
                    .card()

                    if let error {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(Theme.danger)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("password.error")
                    }

                    Text("You stay signed in here and anywhere else you are signed in.")
                        .font(Theme.Text.caption)
                        .foregroundStyle(Theme.inkSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, Theme.Space.page)
                .padding(.bottom, Theme.Space.large)
            }
            .pageBackground()
            .navigationTitle("Change password")
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(isWorking || current.isEmpty
                                  || !AccountRules.isPasswordLongEnough(replacement))
                        .accessibilityIdentifier("password.save")
                }
            }
        }
        .sheetSize()
    }

    private func save() {
        guard !isWorking else { return }
        isWorking = true
        let old = current
        let new = replacement
        Task {
            defer { isWorking = false }
            do {
                try await model.connection.changePassword(current: old, new: new)
                dismiss()
            } catch {
                self.error = AccountError.passwordChange(error)
            }
        }
    }
}

#Preview("Change password") {
    DemoPreview { PasswordSheet() }
}
