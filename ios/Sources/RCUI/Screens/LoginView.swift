import SwiftUI
import RCCore

/// Gateway address, username and password — and, where the gateway allows it,
/// the same three fields as a way to create the account instead.
///
/// `docs/DESIGN.md` § "Accounts": the username is remembered per gateway so the
/// next sign-in is the password alone, a disabled account is told so, and a
/// wrong password is never told which half was wrong. "Create an account" is
/// offered only when `GET /api/health` says registration is open, and this
/// screen is the only place that asks.
struct LoginView: View {
    @Environment(AppModel.self) private var model
    @State private var origin = ""
    @State private var username = ""
    @State private var password = ""
    @State private var isWorking = false
    @State private var isRegistering = false
    @State private var registrationOpen = false
    @FocusState private var focus: Field?

    private enum Field { case origin, username, password }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.large) {
                heading
                fields
                if let error = model.connection.errorMessage {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(Theme.danger)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("login.error")
                }
                primary
                if isRegistering {
                    switchBack
                } else {
                    if registrationOpen { createLink }
                    demo
                }
            }
            .padding(.horizontal, Theme.Space.page)
            .padding(.bottom, Theme.Space.large)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .pageBackground()
        .scrollDismissesKeyboard(.interactively)
        .dismissesKeyboardOnBackgroundTap()
        .onAppear {
            origin = model.settings.lastOrigin
            // The username belongs to the gateway, not to the phone: the form
            // offers whoever last signed in on the address it is prefilled with.
            username = model.settings.username(for: origin)
        }
        // The gateway is typed, so what it allows can only be known once there
        // is an address to ask. Asking again on every keystroke would be one
        // request per character, so the field is left to settle first.
        .task(id: origin) {
            guard !origin.trimmed.isEmpty else {
                registrationOpen = false
                return
            }
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            registrationOpen = await model.connection.registrationOpen(origin: origin.trimmed)
        }
    }

    private var heading: some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            AppMark(size: 52)
            Text("Remote Control")
                .font(.largeTitle.weight(.semibold))
                .foregroundStyle(Theme.ink)
            Text(isRegistering
                 ? L10n.string("Pick a username and a password for this gateway.")
                 : L10n.string("Drive your coding agents on your own machines, from your phone."))
                .font(.subheadline)
                .foregroundStyle(Theme.inkSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, Theme.Space.large)
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: Theme.Space.medium) {
            VStack(alignment: .leading, spacing: Theme.Space.tight) {
                FieldLabel("Gateway")
                TextField("https://rc.example.com", text: $origin)
                    .textContentType(.URL)
                    .plainTextEntry()
                    .keyboardTypeURL()
                    .focused($focus, equals: .origin)
                    .submitLabel(.next)
                    .onSubmit { focus = .username }
                    .font(Theme.monoBody)
                    .frame(minHeight: Theme.Touch.minimum)
                    .accessibilityIdentifier("login.gateway")
            }
            Divider().overlay(Theme.border)
            VStack(alignment: .leading, spacing: Theme.Space.tight) {
                FieldLabel("Username")
                TextField("you", text: $username)
                    .textContentType(.username)
                    .plainTextEntry()
                    .focused($focus, equals: .username)
                    .submitLabel(.next)
                    .onSubmit { focus = .password }
                    .frame(minHeight: Theme.Touch.minimum)
                    .accessibilityIdentifier("login.username")
            }
            Divider().overlay(Theme.border)
            VStack(alignment: .leading, spacing: Theme.Space.tight) {
                FieldLabel("Password")
                SecureField(isRegistering
                            ? L10n.string("At least 8 characters")
                            : L10n.string("Your password"), text: $password)
                    .textContentType(isRegistering ? .newPassword : .password)
                    .focused($focus, equals: .password)
                    .submitLabel(.go)
                    .onSubmit { Task { await submit() } }
                    .frame(minHeight: Theme.Touch.minimum)
                    .accessibilityIdentifier("login.password")
            }
        }
        .card()
    }

    private var primary: some View {
        Button {
            Task { await submit() }
        } label: {
            HStack(spacing: Theme.Space.small) {
                if isWorking { ProgressView().tint(Theme.onAccent) }
                if isRegistering {
                    Text("Create account")
                } else {
                    Text("Connect")
                }
            }
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(isWorking || !isComplete)
        .accessibilityIdentifier("login.connect")
    }

    private var createLink: some View {
        Button("Create an account") {
            model.connection.clearError()
            password = ""
            isRegistering = true
            focus = .username
        }
        .font(.subheadline)
        .buttonStyle(.plain)
        .foregroundStyle(Theme.ink)
        .frame(minHeight: Theme.Touch.minimum)
        .accessibilityIdentifier("login.register")
    }

    private var switchBack: some View {
        Button("Sign in instead") {
            model.connection.clearError()
            password = ""
            isRegistering = false
            focus = .password
        }
        .font(.subheadline)
        .buttonStyle(.plain)
        .foregroundStyle(Theme.ink)
        .frame(minHeight: Theme.Touch.minimum)
        .accessibilityIdentifier("login.signInInstead")
    }

    private var demo: some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            Button("Try the demo") {
                Task { await model.enterDemo() }
            }
            .font(.subheadline)
            .buttonStyle(.plain)
            .foregroundStyle(Theme.ink)
            .frame(minHeight: Theme.Touch.minimum)
            .accessibilityIdentifier("login.demo")

            Text("The demo runs entirely on this device with sample data. Nothing is sent anywhere.")
                .font(.footnote)
                .foregroundStyle(Theme.inkSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Registering states the password rule up front, because the gateway will
    /// refuse a shorter one and a button that is about to be refused is worse
    /// than one that waits. Signing in asks for whatever the password is.
    private var isComplete: Bool {
        guard !origin.trimmed.isEmpty, !username.trimmed.isEmpty else { return false }
        return isRegistering ? AccountRules.isPasswordLongEnough(password) : !password.isEmpty
    }

    private func submit() async {
        guard !isWorking, isComplete else { return }
        isWorking = true
        defer { isWorking = false }
        let address = origin.trimmed
        let name = username.trimmed.lowercased()
        if isRegistering {
            await model.register(origin: address, username: name, password: password)
        } else {
            await model.signIn(origin: address, username: name, password: password)
        }
        if model.isSignedIn { password = "" }
    }
}

extension View {
    /// A URL keyboard on iOS, and nothing to do anywhere else.
    func keyboardTypeURL() -> some View {
        #if os(iOS)
        self.keyboardType(.URL)
        #else
        self
        #endif
    }
}

#Preview("Login") {
    LoginView().environment(AppModel(arguments: []))
}
