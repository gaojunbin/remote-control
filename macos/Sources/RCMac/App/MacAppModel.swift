import Foundation
import Observation
import RCCore

/// The object every screen reads, as `@Environment(MacAppModel.self)`. It owns
/// the RCCore stores — the connection, the list state, the settings and the
/// account's preferences, the drafts — the router, and what else the web's
/// global stores hold that RCCore models; a conversation, a terminal and an
/// accounts list belong to the page that shows them.
///
/// Built once, by the app (or the renderer), and never again: its initialiser
/// applies the launch arguments, and `--reset-state` applied twice would throw
/// away the account the first run had just signed in.
@MainActor
@Observable
public final class MacAppModel {
    public let options: LaunchOptions
    public let connection: ConnectionStore
    /// Filters, search and the folded groups of the session lists.
    public let sessions: SessionStore
    public let settings: SettingsStore
    /// Amendment A35: the account's resume switch, seeded from `hello`.
    public let preferences = PreferencesStore()
    /// Amendment A41: keeps `settings` equal to the account's preferences, both ways.
    public let preferenceSync: PreferenceSync
    /// Composer drafts, per account and session, on disk.
    public let drafts: DraftStore
    public let router = Router()

    /// Whether there is an account to come back to, from launch until the
    /// keychain has answered. The root draws the canvas and nothing else while
    /// it holds, as the web draws `.boot` while its session check runs.
    public private(set) var isResuming: Bool
    /// Amendment A22: why a device refused the last update it was asked for.
    /// A refusal means nothing started, so no `device.updated` will ever carry
    /// it and the app is the only place it can live (the web's `updateErrors`).
    public internal(set) var deviceUpdateErrors: [String: String] = [:]
    /// Whether the main window is on screen and frontmost, which is when a
    /// conversation shown in it counts as read.
    public internal(set) var isWindowActive = false

    @ObservationIgnored let persistence: Persistence
    @ObservationIgnored let recorder = SignInRecorder()
    @ObservationIgnored var signOutHandlers: [@MainActor () async -> Void] = []
    @ObservationIgnored var isSigningOut = false
    @ObservationIgnored var wasSignedIn = false
    @ObservationIgnored var transitionHandlers: [@MainActor (Session, Session) -> Void] = []
    /// Brings the main window back; the app installs it, a renderer has none.
    @ObservationIgnored public var showWindow: @MainActor () -> Void = {}
    /// The demo, when the launch arguments asked for one. `restoreOrPrompt`
    /// waits on it rather than racing it.
    @ObservationIgnored private var launch: Task<Void, Never>?
    @ObservationIgnored private var hasLaunched = false

    public init(options: LaunchOptions = .current) {
        self.options = options
        let persistence = Persistence(ephemeral: options.ephemeral)
        self.persistence = persistence
        drafts = persistence.drafts
        settings = SettingsStore(defaults: persistence.defaults)
        sessions = SessionStore(defaults: persistence.defaults)
        preferenceSync = PreferenceSync(settings: settings)
        connection = ConnectionFactory.make(options: options, persistence: persistence, recorder: recorder)
        if options.resetState {
            settings.reset()
            sessions.forgetListState()
        }
        // After the reset, so a run can start straight in the language it
        // reads: `--reset-state --language=zh-Hans`.
        if let language = options.language { settings.pinLanguage(language) }
        isResuming = options.demo || !settings.lastOrigin.isEmpty
        router.canSeeUsers = { [weak self] in self?.connection.isAdmin ?? false }
        wireConnection()
        followLanguage()
        followSignedIn()
        let entersDemo = options.demo
        if entersDemo || options.resetState {
            launch = Task {
                if options.resetState { await self.drafts.clearAll() }
                if entersDemo { await self.enterDemo() }
            }
        }
        Features.install(on: self)
    }

    public var isSignedIn: Bool { connection.isSignedIn }
    public var isDemo: Bool { connection.isDemo }

    /// The account drafts and caches are filed under.
    public var account: String { connection.account }

    /// The gateway as the topbar and Settings print it: its public origin when
    /// `/api/config` has said, the address signed in to until then.
    public var origin: String {
        let configured = connection.config.publicOrigin
        return configured.isEmpty ? (connection.endpoint?.origin ?? settings.lastOrigin) : configured
    }

    // MARK: - Extension points

    /// Called on every sign-out, in the order registered, before the connection
    /// goes: a conversation can still write its draft and its transcript under
    /// the account that is leaving. Everything a feature keeps of an account's
    /// is emptied here (`web/src/stores/signOut.ts`).
    public func onSignOut(_ handler: @escaping @MainActor () async -> Void) {
        signOutHandlers.append(handler)
    }

    /// Called with both versions whenever a session the app already knew is
    /// replaced by a newer one — the moments a turn ends, a question arrives,
    /// a limit pauses it.
    public func onSessionTransition(_ handler: @escaping @MainActor (Session, Session) -> Void) {
        transitionHandlers.append(handler)
    }

    // MARK: - Wiring

    private func wireConnection() {
        connection.onSessionTransition = { [weak self] previous, current in
            for handler in self?.transitionHandlers ?? [] { handler(previous, current) }
        }
        // Amendment A35/A41: the account's preferences ride on frames no single
        // screen owns, so the model reads them for the life of the app.
        connection.addFrameHandler("preferences") { [weak self] frame in
            self?.preferences.receive(frame)
            self?.preferenceSync.receive(frame)
            self?.forgetUpdateError(frame)
        }
    }

    /// Every string the app draws follows the signed-in account's language
    /// (A41 makes it the account's): the source `S` reads is moved each time
    /// it moves. Nobody is signed in on the login page, which the web draws in
    /// English whatever the last account chose, because its settings are kept
    /// under a key of their own that no control writes — unless a launch
    /// argument fixed the language for the run.
    private func followLanguage() {
        InterfaceLanguageSource.shared.current = withObservationTracking {
            connection.isSignedIn ? settings.language : (options.language ?? .en)
        } onChange: { [weak self] in
            Task { @MainActor in self?.followLanguage() }
        }
    }

    /// The gateway's own `update_state` speaks for a device again once it sends
    /// that device.
    private func forgetUpdateError(_ frame: AppFrame) {
        if case .deviceUpdated(let device) = frame { deviceUpdateErrors.removeValue(forKey: device.deviceID) }
    }

    /// The demo gateway, entered without a form. It never constructs a transport.
    func enterDemo() async {
        let minimum = options.demoUpdateRequired ? DemoFixtures.laterAppVersion : AppBuild.version
        let gateway = DemoGateway(minimumAppVersion: minimum)
        await connection.enterDemo(api: gateway, channel: gateway)
        attachAccount()
    }

    /// Bind the account's preference stores to the connection's client.
    ///
    /// `stt_language` is the language the iPhone's own recogniser listens for
    /// (A44); the web never writes it, and the Mac has no recogniser. So the
    /// Mac listens for none: an empty dictation language, which `PreferenceSync`
    /// never writes up — it writes only a language a recogniser listens for.
    func attachAccount() {
        settings.voiceLanguage = ""
        preferences.attach(api: connection.api)
        preferenceSync.attach(api: connection.api)
    }

    /// Launch: the demo, a stored token, or the form — and nothing drawn over
    /// the canvas until one of them has answered.
    public func restoreOrPrompt() async {
        // Once per launch: reopening the window after closing it is no launch.
        guard !hasLaunched else { return }
        hasLaunched = true
        await launch?.value
        defer { isResuming = false }
        guard !connection.isSignedIn, !settings.lastOrigin.isEmpty else {
            if connection.isSignedIn { router.signedIn() }
            return
        }
        if await connection.restore(origin: settings.lastOrigin, username: settings.lastUsername) {
            attachAccount()
            router.signedIn()
        }
    }

    /// The quit of an ephemeral run takes what it wrote with it.
    public func discardEphemeralState() { persistence.discard() }
}
