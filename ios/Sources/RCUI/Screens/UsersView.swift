import SwiftUI
import RCCore

/// The admin's accounts screen (protocol 3.9, A24), and nobody else's.
///
/// `docs/DESIGN.md` § "Accounts": one switch at the top, one row per account,
/// and three actions per row — Reset password, Disable or Enable, Delete —
/// reachable from a trailing swipe and from the context menu, exactly as the
/// web reaches them from its row menu. The `admin` row has none of them.
struct UsersView: View {
    @Environment(AppModel.self) private var model
    @State private var store: UsersStore?
    @State private var isAdding = false
    @State private var resetting: UserRecord?
    @State private var newPassword = ""
    @State private var resetError: String?
    @State private var deleting: UserRecord?
    @State private var actionError: String?

    var body: some View {
        List {
            Section {
                Toggle("Registration", isOn: registrationBinding)
                    .font(Theme.Text.label)
                    .settingsRowLayout()
                    .disabled(store == nil)
                    .accessibilityIdentifier("users.registration")
            } footer: {
                SettingsFooter(L10n.string("Anyone with the gateway address can create an account"))
            }

            Section {
                ForEach(store?.users ?? []) { record in
                    UserRow(record: record)
                        .sessionRowLayout()
                        .accessibilityIdentifier("user.\(record.username)")
                        .contextMenu { if !record.isOperator { actions(for: record) } }
                        // SwiftUI lays a trailing swipe out from the edge
                        // inwards, so the first listed sits nearest the edge
                        // and the row reads Reset · Disable · Delete.
                        .swipeActions(edge: .trailing) {
                            if !record.isOperator {
                                deleteAction(for: record)
                                stateAction(for: record)
                                resetAction(for: record)
                            }
                        }
                }
            } header: {
                FieldLabel("Accounts")
            }

            if let message = actionError ?? store?.errorMessage {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(Theme.danger)
                    .listRowBackground(Color.clear)
                    .accessibilityIdentifier("users.error")
            }
        }
        .groupedList()
        .scrollContentBackground(.hidden)
        .pageBackground()
        .navigationTitle("Users")
        .safeAreaInset(edge: .bottom, spacing: 0) {
            Button {
                isAdding = true
            } label: {
                Label("Add user", systemImage: "plus")
            }
            .buttonStyle(PrimaryButtonStyle())
            .padding(.horizontal, Theme.Space.page)
            .padding(.vertical, Theme.Space.small)
            .barBackground()
            .disabled(store == nil)
            .accessibilityIdentifier("users.add")
        }
        .task {
            if store == nil { store = model.connection.usersStore() }
            await store?.load()
        }
        .sheet(isPresented: $isAdding) {
            if let store {
                AddUserSheet(store: store)
            }
        }
        .alert("Reset password", isPresented: Binding(get: { resetting != nil },
                                                      set: { if !$0 { resetting = nil } })) {
            SecureField("New password", text: $newPassword)
                .textContentType(.newPassword)
            Button("Cancel", role: .cancel) { resetting = nil }
            Button("Set password") { resetPassword() }
        } message: {
            Text(resetError ?? L10n.string("%@ signs in with this password from now on. Other sign-ins stay valid.",
                                           resetting?.username ?? ""))
        }
        .alert("Delete account", isPresented: Binding(get: { deleting != nil },
                                                      set: { if !$0 { deleting = nil } })) {
            Button("Cancel", role: .cancel) { deleting = nil }
            Button("Delete account", role: .destructive) { delete() }
        } message: {
            Text(deleteMessage)
        }
    }

    // MARK: - Actions

    @ViewBuilder
    private func actions(for record: UserRecord) -> some View {
        resetAction(for: record)
        stateAction(for: record)
        deleteAction(for: record)
    }

    private func resetAction(for record: UserRecord) -> some View {
        Button {
            resetting = record
            newPassword = ""
            resetError = nil
        } label: {
            Label("Reset password", systemImage: "key")
        }
        .tint(Theme.inkSecondary)
        .accessibilityIdentifier("user.reset")
    }

    @ViewBuilder
    private func stateAction(for record: UserRecord) -> some View {
        if record.isActive {
            Button {
                setState(.disabled, of: record)
            } label: {
                Label("Disable", systemImage: "pause.circle")
            }
            .tint(Theme.attention)
            .accessibilityIdentifier("user.disable")
        } else {
            Button {
                setState(.active, of: record)
            } label: {
                Label("Enable", systemImage: "play.circle")
            }
            .tint(Theme.inkSecondary)
            .accessibilityIdentifier("user.enable")
        }
    }

    /// The tint is explicit: the app sets its own `.tint` at the root, and a
    /// destructive swipe button takes that over the system red without it.
    private func deleteAction(for record: UserRecord) -> some View {
        Button(role: .destructive) {
            deleting = record
        } label: {
            Label("Delete", systemImage: "trash")
        }
        .tint(Theme.danger)
        .accessibilityIdentifier("user.delete")
    }

    private var registrationBinding: Binding<Bool> {
        Binding(get: { store?.registrationOpen ?? false },
                set: { open in
                    guard let store else { return }
                    Task {
                        do {
                            actionError = nil
                            try await store.setRegistration(open: open)
                        } catch {
                            actionError = AccountError.manage(error)
                        }
                    }
                })
    }

    /// The row is read while the tap is still being handled: dismissing an
    /// alert clears the state a task started from it would have read.
    private func resetPassword() {
        guard let store, let record = resetting else { return }
        let password = newPassword
        newPassword = ""
        Task {
            do {
                actionError = nil
                try await store.resetPassword(of: record.username, to: password)
                resetError = nil
            } catch {
                resetError = AccountError.manage(error)
                resetting = record
            }
        }
    }

    private func setState(_ state: UserState, of record: UserRecord) {
        guard let store else { return }
        Task {
            do {
                actionError = nil
                try await store.setState(state, of: record.username)
            } catch {
                actionError = AccountError.manage(error)
            }
        }
    }

    private func delete() {
        guard let store, let record = deleting else { return }
        Task {
            do {
                actionError = nil
                try await store.delete(record.username)
            } catch {
                actionError = AccountError.manage(error)
            }
        }
    }

    private var deleteMessage: String {
        guard let record = deleting else { return "" }
        switch record.devices {
        case 0:
            return L10n.string("Delete %@? The account, its sign-ins and its push registrations go with it.",
                               record.username)
        case 1:
            return L10n.string(
                "Delete %@ and its %lld device? The token stops working and its sessions leave this gateway. The machine keeps its agents and transcripts.",
                record.username, record.devices)
        default:
            return L10n.string(
                "Delete %@ and its %lld devices? Their tokens stop working and their sessions leave this gateway. The machines keep their agents and transcripts.",
                record.username, record.devices)
        }
    }
}

/// One account: the username, what it is and how it is doing, and when it was
/// last here. The same four facts the web row carries, in the phone's shape.
struct UserRow: View {
    let record: UserRecord

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Theme.Space.medium) {
            VStack(alignment: .leading, spacing: 3) {
                Text(record.username)
                    .font(Theme.Text.label)
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                HStack(spacing: Theme.Space.tight) {
                    Text(record.roleAndState)
                        .font(Theme.Text.meta)
                        .foregroundStyle(record.isActive ? Theme.inkSecondary : Theme.attention)
                    if !record.deviceSummary.isEmpty {
                        Text(verbatim: "·").font(Theme.Text.meta).foregroundStyle(Theme.inkSecondary)
                        Text(record.deviceSummary)
                            .font(Theme.Text.meta)
                            .foregroundStyle(Theme.inkSecondary)
                    }
                }
            }
            Spacer(minLength: Theme.Space.small)
            Text(record.lastLoginSummary())
                .font(Theme.Text.meta)
                .foregroundStyle(Theme.inkSecondary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Username, password, role. Member is the default: an admin is a deliberate
/// choice and never the one made by leaving a control alone.
struct AddUserSheet: View {
    let store: UsersStore
    @Environment(\.dismiss) private var dismiss
    @State private var username = ""
    @State private var password = ""
    @State private var role = UserRole.member
    @State private var isWorking = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.large) {
                    VStack(alignment: .leading, spacing: Theme.Space.medium) {
                        VStack(alignment: .leading, spacing: Theme.Space.tight) {
                            FieldLabel("Username")
                            TextField("their username", text: $username)
                                .plainTextEntry()
                                .frame(minHeight: Theme.Touch.minimum)
                                .accessibilityIdentifier("addUser.username")
                        }
                        Divider().overlay(Theme.border)
                        VStack(alignment: .leading, spacing: Theme.Space.tight) {
                            FieldLabel("Password")
                            SecureField("At least 8 characters", text: $password)
                                .textContentType(.newPassword)
                                .frame(minHeight: Theme.Touch.minimum)
                                .accessibilityIdentifier("addUser.password")
                        }
                        Divider().overlay(Theme.border)
                        VStack(alignment: .leading, spacing: Theme.Space.tight) {
                            FieldLabel("Role")
                            Picker("Role", selection: $role) {
                                Text(UserRole.member.title).tag(UserRole.member)
                                Text(UserRole.admin.title).tag(UserRole.admin)
                            }
                            .pickerStyle(.segmented)
                            .labelsHidden()
                            .accessibilityIdentifier("addUser.role")
                        }
                    }
                    .card()

                    if let error {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(Theme.danger)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("addUser.error")
                    }

                    Text("An admin sees every account on this gateway and can change them. A member sees only its own devices and sessions.")
                        .font(Theme.Text.caption)
                        .foregroundStyle(Theme.inkSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, Theme.Space.page)
                .padding(.bottom, Theme.Space.large)
            }
            .pageBackground()
            .navigationTitle("Add user")
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { add() }
                        .disabled(isWorking || username.trimmed.isEmpty
                                  || !AccountRules.isPasswordLongEnough(password))
                        .accessibilityIdentifier("addUser.add")
                }
            }
        }
        .sheetSize()
    }

    private func add() {
        guard !isWorking else { return }
        isWorking = true
        let name = username.trimmed.lowercased()
        let secret = password
        let chosen = role
        Task {
            defer { isWorking = false }
            do {
                try await store.create(username: name, password: secret, role: chosen)
                dismiss()
            } catch {
                self.error = AccountError.create(error)
            }
        }
    }
}

#Preview("Users") {
    DemoPreview { NavigationStack { UsersView() } }
}
