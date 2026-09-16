import SwiftUI
import Observation
import RCCore

/// The object every screen reads. It owns the stores, the open conversation and
/// the navigation the app can be driven into from a notification or a link.
@MainActor
@Observable
public final class AppModel {
    public enum Tab: Hashable { case sessions, devices, settings }

    public let connection: ConnectionStore
    public let sessions = SessionStore()
    public let settings: SettingsStore

    public var tab: Tab = .sessions
    /// Session keys, not sessions. A `Session` changes on every status, meta
    /// and todo event; a destination keyed by the value would rebuild the chat
    /// screen many times a turn and lose its scroll position.
    public var path: [String] = []
    public var chat: ChatStore?
    public var isLocked = false
    public var toast: String?
    public let push: PushController
    /// Raises the app's own banner when a turn ends while it is open.
    public let turns: TurnNotifier
    /// Whether the app is in the foreground. The conversation holds the screen
    /// awake only while it is, and a turn is announced only while it is: a
    /// suspended app cannot watch the stream, and the gateway's push is the
    /// channel that reaches a locked phone.
    public private(set) var isSceneActive = false
    /// Whether there is an account to come back to, from launch until the
    /// keychain has answered. The root draws the page colour and nothing else
    /// while it holds: a sign-in form that flashes for the length of a restore
    /// on every launch reads as a broken app.
    public private(set) var isResuming: Bool

    /// Amendment A22: why a device refused the last update it was asked for.
    /// A refusal means nothing started, so no `device.updated` will ever carry
    /// it and the app is the only place it can live.
    public private(set) var deviceUpdateErrors: [String: String] = [:]

    @ObservationIgnored private let drafts: DraftStore
    @ObservationIgnored private let isUITesting: Bool
    /// Amendment A31: the oldest app build the demo gateway claims to work
    /// with. This build, so the demo runs — unless `--demo-update-required`
    /// asked for a higher one, which is how the blocking screen is driven.
    @ObservationIgnored private let demoMinimumAppVersion: String
    @ObservationIgnored private var pendingLink: SessionLink?
    /// Whether the landing rule has already run for this sign-in. It decides
    /// from the first device list and never again, so a `device.updated` that
    /// empties or fills the list moves nobody, and neither does a tab chosen
    /// by hand afterwards.
    @ObservationIgnored private var hasChosenLandingTab = false
    /// The demo, when the launch arguments asked for one. `restoreOrPrompt`
    /// waits on it rather than racing it, so nothing draws the form in between.
    @ObservationIgnored private var launch: Task<Void, Never>?

    public init(connection: ConnectionStore? = nil,
                settings: SettingsStore = SettingsStore(),
                push: PushController? = nil,
                turns: TurnNotifier? = nil,
                drafts: DraftStore = DraftStore(),
                arguments: [String] = ProcessInfo.processInfo.arguments) {
        self.drafts = drafts
        // `--demo-account` puts the offline gateway behind the sign-in form
        // instead of around it, which is how the account screens are driven
        // with no gateway to reach.
        self.connection = connection ?? (arguments.contains("--demo-account")
            ? ConnectionStore.offlineDemo(registrationOpen: arguments.contains("--registration-open"))
            : ConnectionStore())
        self.settings = settings
        self.push = push ?? PushController(platform: SystemNotifications.shared)
        self.turns = turns ?? TurnNotifier()
        isUITesting = arguments.contains("--ui-testing")
        demoMinimumAppVersion = arguments.contains("--demo-update-required")
            ? DemoFixtures.laterAppVersion : AppBuild.version
        if arguments.contains("--reset-state") {
            self.settings.reset()
            self.sessions.forgetListState()
        }
        // After the reset, so a test can launch straight into the language it
        // is about to read: `--reset-state --language=zh-Hans`. It is pinned,
        // so signing in as an account that stored another language does not
        // take the run out from under the test.
        if let argument = arguments.first(where: { $0.hasPrefix("--language=") }),
           let language = InterfaceLanguage(rawValue: String(argument.dropFirst("--language=".count))) {
            self.settings.pinLanguage(language)
        }
        let entersDemo = arguments.contains("--demo")
        // The demo is an account like any other: the app is coming back to
        // something, so the first frame belongs to the main screens.
        isResuming = entersDemo || !self.settings.lastOrigin.isEmpty
        self.connection.onSessionTransition = { [weak self] previous, current in
            self?.announceTurn(previous: previous, current: current)
        }
        // Drafts are the one piece of state an actor holds, so the reset of
        // them is the first thing the launch task does, ahead of the demo that
        // would otherwise open a session with a previous run's words in it.
        let clearsDrafts = arguments.contains("--reset-state")
        if entersDemo || clearsDrafts {
            launch = Task {
                if clearsDrafts { await self.drafts.clearAll() }
                if entersDemo { await self.enterDemo() }
            }
        }
    }

    public var isDemo: Bool { connection.isDemo }
    public var isSignedIn: Bool { connection.isSignedIn }

    /// The offline demo never constructs a transport.
    public func enterDemo() async {
        // A UI test looks at the moment between a tap and the device's echo, so
        // the scripted device takes its time over it rather than being raced.
        let gateway = DemoGateway(echoDelay: isUITesting ? .seconds(3) : DemoGateway.defaultEchoDelay,
                                  resumeDelay: isUITesting ? nil : DemoGateway.defaultResumeDelay,
                                  minimumAppVersion: demoMinimumAppVersion,
                                  // Amendment A29: "Polishing…" is a state a UI
                                  // test looks at rather than races.
                                  polishDelay: isUITesting ? .seconds(3)
                                                           : DemoGateway.defaultPolishDelay,
                                  // Amendment A33: and so is "Checking…" on a
                                  // device's page.
                                  agentsDelay: isUITesting ? .seconds(3)
                                                           : DemoGateway.defaultAgentsDelay)
        await connection.enterDemo(api: gateway, channel: gateway)
        attachPush()
    }

    /// Launch. Nothing is drawn over the launch background until this returns
    /// one way or the other, so the form appears only where it is the answer.
    public func restoreOrPrompt() async {
        await launch?.value
        guard !connection.isSignedIn, !settings.lastOrigin.isEmpty else {
            isResuming = false
            attachPush()
            return
        }
        _ = await connection.restore(origin: settings.lastOrigin, username: settings.lastUsername)
        isResuming = false
        attachPush()
        applyPendingLink()
    }

    /// Reconcile notifications as soon as the app has an account, so a token is
    /// registered without the user visiting Settings first.
    public func attachPush() {
        push.attach(api: connection.isDemo ? nil : connection.api,
                    enabled: settings.notificationsEnabled) { [weak self] route in
            self?.handle(SessionLink(deviceID: route.deviceID, sessionID: route.sessionID))
        }
    }

    public func signIn(origin: String, username: String, password: String) async {
        await connection.signIn(origin: origin, username: username, password: password)
        adoptAccount()
    }

    /// Creating an account signs it in, so it ends exactly where a sign-in does.
    public func register(origin: String, username: String, password: String) async {
        await connection.register(origin: origin, username: username, password: password)
        adoptAccount()
    }

    /// The app's own settings belong to whoever just signed in, so they are
    /// re-read before any screen draws (`docs/DESIGN.md` § "Accounts").
    private func adoptAccount() {
        if connection.isSignedIn, let endpoint = connection.endpoint {
            settings.remember(origin: endpoint.origin, username: connection.username)
        }
        attachPush()
        applyPendingLink()
    }

    public func signOut() async {
        push.detach()
        await closeChat()
        path.removeAll()
        hasChosenLandingTab = false
        // The account's drafts go with its cached transcripts and its token.
        // Before the connection is torn down, not after: `signOut` empties
        // `user` and drops the endpoint, so `account` would name nobody.
        await drafts.clear(account: connection.account)
        await connection.signOut()
    }

    /// A `hello` snapshot is the whole list of what this account has, so a
    /// draft key it does not name belongs to a session that has been deleted on
    /// the device; left alone, its words would sit on disk for the life of the
    /// install. Called once for each snapshot that arrives.
    ///
    /// Not for the demo: its scripted device resumes and archives sessions as
    /// the run goes on, and nothing it writes is meant to outlive the run.
    public func adoptSnapshot() async {
        guard connection.hasSnapshot, !connection.isDemo else { return }
        await drafts.retain(Set(connection.sessions.map(\.id)), account: connection.account)
    }

    /// `docs/DESIGN.md` § "Three tabs, one order, one landing rule": Sessions
    /// when the account has at least one device, Devices when it has none — a
    /// new account's first job is enrolling a machine, everyone else's is the
    /// conversation. Nothing is decided until the first device list has
    /// arrived, and nothing is decided twice.
    public func decideLandingTab() {
        guard !hasChosenLandingTab, connection.hasSnapshot else { return }
        hasChosenLandingTab = true
        tab = Self.landingTab(hasDevices: !connection.devices.isEmpty)
    }

    /// The rule itself, with nothing around it.
    public static func landingTab(hasDevices: Bool) -> Tab { hasDevices ? .sessions : .devices }

    public func lockIfNeeded() {
        guard settings.appLockEnabled, connection.isSignedIn, !connection.isDemo else { return }
        isLocked = true
    }

    // MARK: - Conversations

    /// Open a session: cached transcript first, then subscribe.
    ///
    /// `inPlace` is what a notification or a link asks for: the conversation
    /// already open is replaced rather than stacked on.
    public func open(_ session: Session, inPlace: Bool = false) async {
        await closeChat()
        guard let channel = connection.channel else { return }
        let store = ChatStore(session: session, channel: channel)
        store.agent = agent(for: session)
        // The preference stays in one place. The transcript reads it, so
        // changing it in Settings redraws an open conversation at once.
        store.detailSource = { [settings] in settings.timelineDetail }
        store.draft = await drafts.draft(account: connection.account, key: session.id)
        chat = store
        connection.addFrameHandler("chat") { [weak store] frame in store?.receive(frame) }
        route(to: session.id, inPlace: inPlace)
        let cached = await connection.cachedTranscript(sessionID: session.sessionID,
                                                       deviceID: session.deviceID)
        await store.open(cached: cached)
    }

    /// Where the navigation stack goes. `docs/DESIGN.md` § "Status vocabulary"
    /// → **A notification opens its session in place**: the stack holds one
    /// conversation, so Back from a session a notification opened returns to
    /// the list rather than to the session it replaced.
    private func route(to key: String, inPlace: Bool) {
        guard path.last != key else { return }
        if inPlace { path = [key] } else { path.append(key) }
    }

    public func closeChat() async {
        guard let store = chat else { return }
        connection.removeFrameHandler("chat")
        await drafts.setDraft(store.draft, account: connection.account, key: store.key)
        await connection.persist(transcript: store.timeline.entries.compactMap(\.sourceEvent),
                                 sessionID: store.sessionID, deviceID: store.deviceID)
        await store.close()
        chat = nil
    }

    /// Close one named conversation and no other.
    ///
    /// SwiftUI delivers the new view's `onAppear` and `.task` before the
    /// covered view's `onDisappear`, so a conversation leaving the screen is
    /// told to close after its replacement is already installed. The key is
    /// what keeps that late callback from closing the store it never owned.
    public func closeChat(key: String) async {
        guard chat?.key == key else { return }
        await closeChat()
    }

    public func saveDraft() async {
        guard let store = chat else { return }
        await drafts.setDraft(store.draft, account: connection.account, key: store.key)
    }

    /// Called when the scene leaves the foreground. iOS may reclaim the process
    /// without another chance to write anything.
    public func persistForBackground() async {
        guard let store = chat else {
            await connection.persistInventory()
            return
        }
        await drafts.setDraft(store.draft, account: connection.account, key: store.key)
        await connection.persist(transcript: store.timeline.entries.compactMap(\.sourceEvent),
                                 sessionID: store.sessionID, deviceID: store.deviceID)
    }

    // MARK: - Links and notifications

    /// A deep link or a notification tap. Authentication is re-validated before
    /// anything is shown, so a stale notification cannot open someone's session.
    public func handle(_ link: SessionLink) {
        guard connection.isSignedIn, connection.hasSnapshot else {
            pendingLink = link
            return
        }
        guard let session = connection.session(deviceID: link.deviceID, sessionID: link.sessionID) else {
            toast = L10n.string("That session is no longer on this gateway.")
            return
        }
        // A link is a destination, so it settles the landing rule too: the
        // first device list must not move the tab out from under it.
        hasChosenLandingTab = true
        tab = .sessions
        Task { await open(session, inPlace: true) }
    }

    public func handle(url: URL) {
        guard let link = SessionLink(url: url) else { return }
        handle(link)
    }

    public func applyPendingLink() {
        guard let link = pendingLink, connection.hasSnapshot else { return }
        pendingLink = nil
        handle(link)
    }

    /// The foreground, which both the awake screen and the app's own banners
    /// are conditioned on.
    public func setSceneActive(_ active: Bool) {
        isSceneActive = active
        syncRemoteBanners()
    }

    /// A push for a transition this app has already announced is not shown a
    /// second time (`docs/DESIGN.md` § "Being told when a turn ends"). It is
    /// only ever this app's own banner that replaces it, so the push is dropped
    /// exactly while this app is open and reading the stream.
    public func syncRemoteBanners() {
        SystemNotifications.shared.suppressesRemoteBanners =
            isSceneActive && connection.phase == .connected
    }

    /// A session the app already knew has been replaced by a newer version of
    /// itself. The three gates live in `TurnNotifier`; the name does not, so it
    /// is resolved here where the device list is.
    private func announceTurn(previous: Session, current: Session) {
        turns.announce(previous: previous, current: current,
                       deviceName: device(for: current)?.name ?? current.deviceID,
                       sceneActive: isSceneActive,
                       enabled: settings.notificationsEnabled,
                       authorization: push.authorization)
    }

    // MARK: - Convenience for the screens

    /// Resolve a path key back to a session: the live one when the gateway
    /// still lists it, otherwise the copy the open chat is holding.
    public func session(key: String) -> Session? {
        connection.sessions.first { $0.id == key } ?? (chat?.key == key ? chat?.session : nil)
    }

    public func device(for session: Session) -> Device? { connection.device(session.deviceID) }

    public func agent(for session: Session) -> AgentInfo? {
        connection.device(session.deviceID)?.agent(session.agent)
    }

    public func pairingFlow() -> PairingFlow? {
        guard let api = connection.api else { return nil }
        return PairingFlow(api: api)
    }

    public func deviceUpdateError(_ deviceID: String) -> String? { deviceUpdateErrors[deviceID] }

    /// Amendment A22: ask a device to fetch the build the gateway serves. The
    /// accepted case says nothing here — `device.updated` carries the state the
    /// row draws from then on.
    public func updateDevice(_ device: Device) async {
        guard let channel = connection.channel, let build = connection.config.servedBuild else { return }
        deviceUpdateErrors.removeValue(forKey: device.deviceID)
        do {
            _ = try await channel.request(.updateDevice(deviceID: device.deviceID, build: build),
                                          as: DeviceUpdateResult.self)
        } catch {
            deviceUpdateErrors[device.deviceID] = connection.message(for: error)
        }
    }

    /// The camera the pairing scanner runs on. The demo has none to reach, so
    /// it takes the stand-in that hands over a printed payload (A23).
    public var codeScanner: any CodeScanning {
        isDemo ? StaticCodeScanner(payload: DemoFixtures.claimURL) : SystemCodeScanner.make()
    }

    public var accessibilityMode: Bool { isUITesting }
}

extension TimelineEntry {
    /// Rebuild a storable event from a rendered row, for the offline cache.
    ///
    /// Streaming rows are stored as their finished form, so a cached transcript
    /// holds whole answers rather than the last delta that happened to arrive.
    var sourceEvent: SessionEvent? {
        let stored: SessionEventBody
        switch body {
        case .status, .meta, .queue:
            return nil
        case .assistantText:
            stored = .assistantText(StreamTextPayload(text: text, done: true))
        case .thinking(let payload):
            stored = .thinking(StreamTextPayload(text: text, done: true, durationMS: payload.durationMS))
        default:
            stored = body
        }
        return SessionEvent(seq: seq, ts: ts, kind: wireKind,
                            blockID: id.hasPrefix("seq:") ? nil : id,
                            parentBlockID: parentID, body: stored)
    }

    private var wireKind: String {
        switch body {
        case .userMessage: SessionEvent.userMessageKind
        case .assistantText: SessionEvent.assistantTextKind
        case .thinking: SessionEvent.thinkingKind
        case .toolCall: SessionEvent.toolCallKind
        case .todos: SessionEvent.todosKind
        case .approval: SessionEvent.approvalKind
        case .question: SessionEvent.questionKind
        case .turnStarted: SessionEvent.turnStartedKind
        case .turnCompleted: SessionEvent.turnCompletedKind
        case .status: SessionEvent.statusKind
        case .meta: SessionEvent.metaKind
        case .queue: SessionEvent.queueKind
        case .notice: SessionEvent.noticeKind
        case .error: SessionEvent.errorKind
        case .unknown(let kind, _): kind
        }
    }
}
