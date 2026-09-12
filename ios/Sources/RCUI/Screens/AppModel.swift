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
    /// Whether there is an account to come back to, from launch until the
    /// keychain has answered. The root draws the page colour and nothing else
    /// while it holds: a sign-in form that flashes for the length of a restore
    /// on every launch reads as a broken app.
    public private(set) var isResuming: Bool

    /// Amendment A22: why a device refused the last update it was asked for.
    /// A refusal means nothing started, so no `device.updated` will ever carry
    /// it and the app is the only place it can live.
    public private(set) var deviceUpdateErrors: [String: String] = [:]

    @ObservationIgnored private let drafts = DraftStore()
    @ObservationIgnored private let isUITesting: Bool
    @ObservationIgnored private var pendingLink: SessionLink?
    /// The demo, when the launch arguments asked for one. `restoreOrPrompt`
    /// waits on it rather than racing it, so nothing draws the form in between.
    @ObservationIgnored private var launch: Task<Void, Never>?

    public init(connection: ConnectionStore = ConnectionStore(),
                settings: SettingsStore = SettingsStore(),
                push: PushController? = nil,
                arguments: [String] = ProcessInfo.processInfo.arguments) {
        self.connection = connection
        self.settings = settings
        self.push = push ?? PushController(platform: SystemNotifications.shared)
        isUITesting = arguments.contains("--ui-testing")
        if arguments.contains("--reset-state") {
            self.settings.remember(origin: "", username: "")
            self.settings.timelineDetail = .simple
            self.settings.language = .en
            self.sessions.forgetListState()
        }
        // After the reset, so a test can launch straight into the language it
        // is about to read: `--reset-state --language=zh-Hans`.
        if let argument = arguments.first(where: { $0.hasPrefix("--language=") }),
           let language = InterfaceLanguage(rawValue: String(argument.dropFirst("--language=".count))) {
            self.settings.language = language
        }
        let entersDemo = arguments.contains("--demo")
        // The demo is an account like any other: the app is coming back to
        // something, so the first frame belongs to the main screens.
        isResuming = entersDemo || !self.settings.lastOrigin.isEmpty
        if entersDemo {
            launch = Task { await self.enterDemo() }
        }
    }

    public var isDemo: Bool { connection.isDemo }
    public var isSignedIn: Bool { connection.isSignedIn }

    /// The offline demo never constructs a transport.
    public func enterDemo() async {
        // A UI test looks at the moment between a tap and the device's echo, so
        // the scripted device takes its time over it rather than being raced.
        let gateway = DemoGateway(echoDelay: isUITesting ? .seconds(3) : DemoGateway.defaultEchoDelay,
                                  resumeDelay: isUITesting ? nil : DemoGateway.defaultResumeDelay)
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

    public func signIn(origin: String, password: String, username: String?) async {
        await connection.signIn(origin: origin, password: password, username: username)
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
        await connection.signOut()
    }

    public func lockIfNeeded() {
        guard settings.appLockEnabled, connection.isSignedIn, !connection.isDemo else { return }
        isLocked = true
    }

    // MARK: - Conversations

    /// Open a session: cached transcript first, then subscribe.
    public func open(_ session: Session) async {
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
        if path.last != session.id { path.append(session.id) }
        let cached = await connection.cachedTranscript(sessionID: session.sessionID,
                                                       deviceID: session.deviceID)
        await store.open(cached: cached)
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
            toast = "That session is no longer on this gateway."
            return
        }
        tab = .sessions
        Task { await open(session) }
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
