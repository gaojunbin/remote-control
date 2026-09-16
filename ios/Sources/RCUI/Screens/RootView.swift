import SwiftUI
import RCCore

/// The app shell: sign in, or the three tabs plus the conversation stack.
public struct RootView: View {
    @State private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    /// The model is always passed in: it applies the launch arguments and owns
    /// the connection, so exactly one may exist per process. A default argument
    /// here would build a fresh one on every pass through the scene body.
    public init(model: AppModel) {
        _model = State(initialValue: model)
    }

    /// Amendment A31: the gateway will not talk to this build.
    private var mustUpdate: Bool { model.connection.updateRequired != nil }

    public var body: some View {
        ZStack {
            if model.isSignedIn {
                MainShell()
                    .environment(model)
                    .disabled(model.isLocked)
                    .accessibilityHidden(model.isLocked)
            } else if model.isResuming {
                // The launch background and nothing else while the keychain is
                // being read. The form is an answer, not a waiting room.
                Color.clear.accessibilityHidden(true)
            } else {
                LoginView()
                    .environment(model)
            }
            #if !os(iOS)
            if model.isLocked {
                AppLockView { model.isLocked = false }
                    .transition(.opacity)
            }
            #endif
        }
        // Amendment A31: below the gateway's minimum nothing here is reachable —
        // not by a tap and not by a screen reader — and the screen over it is
        // the only thing left to do.
        .disabled(mustUpdate)
        .accessibilityHidden(mustUpdate)
        #if os(iOS)
        .overlay {
            // Its own window, above the alert level, so an open sheet cannot
            // show queued prompt text over the lock.
            AppLockWindow(locked: model.isLocked) { model.isLocked = false }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        #endif
        .tint(Theme.accent)
        // Every `Text` in the app resolves through this, so the interface
        // language reaches every open screen the moment it is chosen.
        .environment(\.locale, model.settings.language.locale)
        .pageBackground()
        .task { await model.restoreOrPrompt() }
        .onOpenURL { model.handle(url: $0) }
        .onAppear { model.setSceneActive(SceneRule.isForeground(scenePhase)) }
        // Only the background is leaving the app. Control Centre, the app
        // switcher's peek, an incoming-call banner and a system alert make the
        // scene inactive and nothing more, and the app lock's own footer
        // promises it engages when the app returns from the background.
        .onChange(of: scenePhase) { _, phase in
            model.setSceneActive(SceneRule.isForeground(phase))
            guard SceneRule.isBackground(phase) else { return }
            model.lockIfNeeded()
            Task { await model.persistForBackground() }
        }
        // The other half of the suppression rule: a push is dropped only while
        // this app is both open and reading the stream it would duplicate.
        .onChange(of: model.connection.phase) { _, _ in model.syncRemoteBanners() }
        #if os(iOS)
        .overlay { PrivacyShield(visible: SceneRule.shields(scenePhase)).allowsHitTesting(false) }
        #endif
        // Amendment A31: over everything, including the sign-in form, because
        // `GET /api/health` answers before anyone has a credential. Nothing
        // underneath is reachable until the app is updated or signed out.
        .overlay {
            if let requirement = model.connection.updateRequired {
                UpdateRequiredView(requirement: requirement) {
                    Task { await model.signOut() }
                }
                // An overlay added after the locale was set sits outside it, so
                // this screen asks for the interface language of its own accord
                // rather than reading the phone's.
                .environment(\.locale, model.settings.language.locale)
                .transition(.opacity)
            }
        }
        .overlay(alignment: .bottom) {
            if let toast = model.toast {
                Text(toast)
                    .font(.footnote)
                    .foregroundStyle(Theme.onAccent)
                    .padding(.horizontal, Theme.Space.medium)
                    .padding(.vertical, Theme.Space.small)
                    .background(Theme.accent, in: Capsule())
                    .padding(.bottom, 80)
                    .task {
                        try? await Task.sleep(for: .seconds(3))
                        model.toast = nil
                    }
            }
        }
    }
}

/// Three destinations. Opening a conversation hides the tab bar and leaves the
/// screen to the transcript and the composer.
private struct MainShell: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.tab) {
            NavigationStack {
                DevicesView()
            }
            .tabItem { Label("Devices", systemImage: "desktopcomputer") }
            .tag(AppModel.Tab.devices)

            NavigationStack(path: $model.path) {
                SessionsView()
                    .navigationDestination(for: String.self) { key in
                        ChatView(sessionKey: key)
                    }
            }
            .tabItem { Label("Sessions", systemImage: "bubble.left.and.text.bubble.right") }
            .tag(AppModel.Tab.sessions)

            NavigationStack {
                SettingsView()
            }
            .tabItem { Label("Settings", systemImage: "gearshape") }
            .tag(AppModel.Tab.settings)
        }
        // The landing rule reads the first device list, which arrives with the
        // hello. `initial` covers the shell appearing after the snapshot is
        // already in — a relaunch on a warm connection. The same snapshot is
        // the whole list of what this account has, so it is also where drafts
        // for sessions that no longer exist are forgotten.
        .onChange(of: model.connection.hasSnapshot, initial: true) { _, _ in
            model.decideLandingTab()
            Task { await model.adoptSnapshot() }
        }
    }
}

/// A one-line description of the connection, in the vocabulary the product
/// promises: each state is distinguishable and none of them is a guess.
public struct ConnectionSummary: View {
    let phase: ConnectionPhase
    let isDemo: Bool
    var reconnect: (() -> Void)?

    public init(phase: ConnectionPhase, isDemo: Bool, reconnect: (() -> Void)? = nil) {
        self.phase = phase
        self.isDemo = isDemo
        self.reconnect = reconnect
    }

    public var body: some View {
        if let text {
            HStack(spacing: Theme.Space.tight) {
                if showsProgress { ProgressView().controlSize(.mini) }
                Text(text).font(.footnote).foregroundStyle(Theme.inkSecondary)
                if phase == .superseded, let reconnect {
                    Spacer(minLength: Theme.Space.tight)
                    Button("Reconnect", action: reconnect)
                        .buttonStyle(ChipButtonStyle())
                        .accessibilityIdentifier("connection.reconnect")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Theme.Space.page)
            .padding(.vertical, Theme.Space.tight)
            // The banner sits in the top safe-area inset, so it needs a bar of
            // its own: scrolled rows pass underneath it, not through it. The
            // fill is inside the branch, so a screen with nothing to say paints
            // no strip at all.
            .barBackground()
            .accessibilityIdentifier("connection.status")
        }
    }

    private var showsProgress: Bool {
        switch phase {
        case .connecting, .syncing, .reconnecting: true
        default: false
        }
    }

    private var text: String? {
        if isDemo { return L10n.string("Demo · nothing leaves this device") }
        switch phase {
        case .signedOut: return nil
        case .connecting: return L10n.string("Connecting")
        case .syncing: return L10n.string("Syncing")
        case .connected: return nil
        case .reconnecting: return L10n.string("Reconnecting")
        case .expired: return L10n.string("Your session expired. Sign in again.")
        case .forbidden: return L10n.string("This gateway refused the connection.")
        case .superseded: return L10n.string("Another app took over this connection.")
        case .incompatible(let version):
            return L10n.string("The gateway speaks protocol %lld; this app speaks %lld. Update both.",
                               version, RemoteProtocol.version)
        }
    }
}

#Preview("Root") {
    RootView(model: AppModel(arguments: ["--demo"]))
}
