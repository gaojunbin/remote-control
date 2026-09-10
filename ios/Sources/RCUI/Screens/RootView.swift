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

    public var body: some View {
        ZStack {
            if model.isSignedIn {
                MainShell()
                    .environment(model)
                    .disabled(model.isLocked)
                    .accessibilityHidden(model.isLocked)
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
        .pageBackground()
        .task { await model.restoreOrPrompt() }
        .onOpenURL { model.handle(url: $0) }
        .onChange(of: scenePhase) { _, phase in
            guard phase != .active else { return }
            model.lockIfNeeded()
            Task { await model.persistForBackground() }
        }
        #if os(iOS)
        .overlay { PrivacyShield(visible: scenePhase != .active).allowsHitTesting(false) }
        #endif
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
            NavigationStack(path: $model.path) {
                SessionsView()
                    .navigationDestination(for: String.self) { key in
                        ChatView(sessionKey: key)
                    }
            }
            .tabItem { Label("Sessions", systemImage: "bubble.left.and.text.bubble.right") }
            .tag(AppModel.Tab.sessions)

            NavigationStack {
                DevicesView()
            }
            .tabItem { Label("Devices", systemImage: "desktopcomputer") }
            .tag(AppModel.Tab.devices)

            NavigationStack {
                SettingsView()
            }
            .tabItem { Label("Settings", systemImage: "gearshape") }
            .tag(AppModel.Tab.settings)
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
        if isDemo { return "Demo · nothing leaves this device" }
        switch phase {
        case .signedOut: return nil
        case .connecting: return "Connecting"
        case .syncing: return "Syncing"
        case .connected: return nil
        case .reconnecting: return "Reconnecting"
        case .expired: return "Your session expired. Sign in again."
        case .forbidden: return "This gateway refused the connection."
        case .superseded: return "Another app took over this connection."
        case .incompatible(let version):
            return "The gateway speaks protocol \(version); this app speaks \(RemoteProtocol.version). Update both."
        }
    }
}

#Preview("Root") {
    RootView(model: AppModel(arguments: ["--demo"]))
}
