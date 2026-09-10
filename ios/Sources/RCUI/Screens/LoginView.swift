import SwiftUI
import RCCore

/// Gateway address plus password. The address starts empty on a fresh install
/// and is prefilled with the last one that worked afterwards.
struct LoginView: View {
    @Environment(AppModel.self) private var model
    @State private var origin = ""
    @State private var password = ""
    @State private var isWorking = false
    @FocusState private var focus: Field?

    private enum Field { case origin, password }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.large) {
                VStack(alignment: .leading, spacing: Theme.Space.small) {
                    AppMark(size: 52)
                    Text("Remote Control")
                        .font(.largeTitle.weight(.semibold))
                        .foregroundStyle(Theme.ink)
                    Text("Drive Claude Code and Codex on your own machines, from your phone.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.top, Theme.Space.large)

                VStack(alignment: .leading, spacing: Theme.Space.medium) {
                    VStack(alignment: .leading, spacing: Theme.Space.tight) {
                        FieldLabel("Gateway")
                        TextField("https://rc.example.com", text: $origin)
                            .textContentType(.URL)
                            .plainTextEntry()
                            .keyboardTypeURL()
                            .focused($focus, equals: .origin)
                            .submitLabel(.next)
                            .onSubmit { focus = .password }
                            .font(Theme.monoBody)
                            .frame(minHeight: Theme.Touch.minimum)
                            .accessibilityIdentifier("login.gateway")
                    }
                    Divider().overlay(Theme.border)
                    VStack(alignment: .leading, spacing: Theme.Space.tight) {
                        FieldLabel("Password")
                        SecureField("Gateway password", text: $password)
                            .textContentType(.password)
                            .focused($focus, equals: .password)
                            .submitLabel(.go)
                            .onSubmit { Task { await connect() } }
                            .frame(minHeight: Theme.Touch.minimum)
                            .accessibilityIdentifier("login.password")
                    }
                }
                .card()

                if let error = model.connection.errorMessage {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(Theme.danger)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("login.error")
                }

                Button {
                    Task { await connect() }
                } label: {
                    HStack(spacing: Theme.Space.small) {
                        if isWorking { ProgressView().tint(Theme.onAccent) }
                        Text("Connect")
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(isWorking || origin.trimmed.isEmpty || password.isEmpty)
                .accessibilityIdentifier("login.connect")

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
            .padding(.horizontal, Theme.Space.page)
            .padding(.bottom, Theme.Space.large)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .pageBackground()
        .onAppear { origin = model.settings.lastOrigin }
    }

    private func connect() async {
        guard !isWorking else { return }
        isWorking = true
        defer { isWorking = false }
        let username = model.settings.lastUsername.isEmpty ? nil : model.settings.lastUsername
        await model.signIn(origin: origin.trimmed, password: password, username: username)
        if model.isSignedIn { password = "" }
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
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
