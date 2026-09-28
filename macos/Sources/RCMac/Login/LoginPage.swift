import RCCore
import SwiftUI

/// `web/src/features/login/LoginPage.tsx`: the card that asks for an account,
/// or makes one (A24) — with one field the web's has no need for, the
/// gateway's address, above the username (`docs/DESIGN.md` § "The Mac app" →
/// **Sign-in names the gateway**). The address and the username are
/// remembered for the next launch; the password never is.
struct LoginPage: View {
    enum Mode { case signIn, register }
    enum Field { case origin, username, password }

    @Environment(MacAppModel.self) private var model
    @Environment(\.previewStage) private var stage
    @State private var mode = Mode.signIn
    @State private var origin = ""
    @State private var username = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    /// A24: whether this gateway takes registrations. The login page is the
    /// only place that asks, because it is the only place that offers it.
    @State private var registrationOpen = false
    @FocusState private var focus: Field?

    private var registering: Bool { mode == .register }

    private var ready: Bool {
        !origin.trimmed.isEmpty && !username.trimmed.isEmpty && !password.isEmpty
    }

    var body: some View {
        ScrollView {
            // `.login` centres the card; the browser puts it on a whole point.
            WholePointCenter(minimumHeight: containerHeight) {
                card
                    .frame(maxWidth: 380)
                    .padding(Space.sp6)
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        .background(Palette.canvas)
        .overlay(alignment: .top) { WindowStrip().frame(height: LayoutSize.headerH) }
        .onAppear(perform: prefill)
        .task(id: origin.trimmed) { await probeRegistration() }
    }

    @Environment(\.layoutClass) private var layout
    private var containerHeight: CGFloat { layout.height }

    private var card: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Space.sp3) {
                Mark(size: 26)
                Text(S.login.title)
                    .css(FontSize.fs22, weight: .semibold, tracking: -0.01)
                    .accessibilityAddTraits(.isHeader)
            }
            .padding(.bottom, Space.sp2)
            Hint(registering ? S.login.registerSubtitle : S.login.subtitle)
                .padding(.bottom, Space.sp6)

            fields

            if let error {
                FormError(error)
                    .padding(.top, -6)
                    .padding(.bottom, Space.sp4)
            }

            Btn(buttonTitle, variant: .primary, size: .block) { submit() }
                .disabled(busy || !ready)
                .keyboardShortcut(.defaultAction)

            switchLink
        }
        .padding(Space.sp8)
        .card(shadow: Shadow.one)
    }

    @ViewBuilder private var fields: some View {
        FieldLabel(S.mac.gateway)
        entry(.origin, text: $origin, placeholder: S.mac.gatewayPlaceholder)
        FieldLabel(S.account.username)
        entry(.username, text: $username, placeholder: S.login.usernamePlaceholder)
        FieldLabel(S.account.password)
        entry(.password, text: $password, placeholder: S.login.passwordPlaceholder)
    }

    private func entry(_ field: Field, text: Binding<String>, placeholder: String) -> some View {
        FieldText(text: text, placeholder: placeholder, secure: field == .password)
            .focused($focus, equals: field)
            .onSubmit(submit)
            .fieldChrome(focused: focus == field)
            .padding(.bottom, Space.sp4)
    }

    private var buttonTitle: String {
        switch (busy, registering) {
        case (true, true): S.login.creating
        case (true, false): S.login.signingIn
        case (false, true): S.login.createAccount
        case (false, false): S.login.submit
        }
    }

    @ViewBuilder private var switchLink: some View {
        if registering {
            LoginSwitch(title: S.login.signInInstead) { switchTo(.signIn) }
        } else if registrationOpen {
            LoginSwitch(title: S.login.createAccountLink) { switchTo(.register) }
        }
    }

    // MARK: - Behaviour

    /// The address and the account the app last signed in with, and the focus
    /// where typing starts: the first field still empty.
    private func prefill() {
        origin = model.settings.lastOrigin
        username = model.settings.username(for: origin)
        focus = origin.isEmpty ? .origin : (username.isEmpty ? .username : .password)
        // A render of a state that takes typing: a refused password, the
        // registration form.
        switch stage {
        case "login.error":
            password = "wrongpass"
            submit()
        case "login.register":
            switchTo(.register)
        default:
            break
        }
    }

    private func switchTo(_ next: Mode) {
        mode = next
        error = nil
        password = ""
        if next == .register { focus = .username }
    }

    private func submit() {
        guard !busy, ready else { return }
        let address = origin.trimmed
        // An address the app would refuse is refused here, in the form's words,
        // before anything is sent.
        guard (try? GatewayEndpoint(address)) != nil else {
            error = S.mac.gatewayInvalid
            return
        }
        busy = true
        error = nil
        let name = username.trimmed
        Task {
            if registering {
                await model.register(origin: address, username: name, password: password)
            } else {
                await model.signIn(origin: address, username: name, password: password)
            }
            busy = false
            guard !model.isSignedIn else { return }
            let failure = model.lastSignInError
            if registering && LoginErrorText.closesRegistration(failure) { registrationOpen = false }
            error = registering ? LoginErrorText.register(failure) : LoginErrorText.signIn(failure)
            password = ""
        }
    }

    /// `GET /api/health` for the address typed, once typing pauses. An
    /// unreachable gateway takes no registrations either.
    private func probeRegistration() async {
        let address = origin.trimmed
        guard (try? GatewayEndpoint(address)) != nil else {
            registrationOpen = false
            return
        }
        try? await Task.sleep(for: .milliseconds(350))
        guard !Task.isCancelled else { return }
        let open = await model.connection.registrationOpen(origin: address)
        guard !Task.isCancelled else { return }
        registrationOpen = open
        if !open && registering { switchTo(.signIn) }
    }
}

/// `.login-switch`: the one line under the button that swaps the card for the
/// other form — 13 points in the accent ink, underlined under the pointer.
private struct LoginSwitch: View {
    let title: String
    let action: () -> Void
    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            Text(title)
                .underline(isHovered)
                .css(FontSize.fs13)
                .foregroundStyle(Palette.accent)
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
        .pointerStyle(.link)
        .frame(maxWidth: .infinity)
        .padding(.top, Space.sp4)
    }
}
