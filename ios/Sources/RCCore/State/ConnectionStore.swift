import Foundation
import Observation

/// How the app describes its own connection. Each case is distinguishable in
/// the UI, because "the gateway is unreachable" and "your Mac is offline" call
/// for different actions.
public enum ConnectionPhase: Sendable, Equatable {
    case signedOut
    case connecting
    case syncing
    case connected
    case reconnecting
    case expired
    case forbidden
    /// Another connection replaced this one; reconnecting on our own would
    /// just fight it, so the user decides.
    case superseded
    case incompatible(gatewayVersion: Int)

    /// Whether a request issued now can still reach the gateway. A socket that
    /// is connecting or coming back does: the transport holds the request until
    /// the hello lands, so the composer stays live across a reconnect. A
    /// session that ended, expired or was replaced does not.
    public var canReachGateway: Bool {
        switch self {
        case .connecting, .syncing, .connected, .reconnecting: true
        case .signedOut, .expired, .forbidden, .superseded, .incompatible: false
        }
    }
}

/// Gateway identity, authentication, and the live inventory of devices and
/// sessions.
///
/// One child task per connection scope reads the socket stream, and cancelling
/// it is what ends that scope's stream of frames. The HTTP routes that confirm
/// a scope behind the screens are not on that stream, so they carry the scope
/// they were issued in and are checked against it before anything they say is
/// applied — as is every other assignment here that follows an await.
@MainActor
@Observable
public final class ConnectionStore {
    public private(set) var phase: ConnectionPhase = .signedOut
    public private(set) var endpoint: GatewayEndpoint?
    /// The account this connection signed in as, and its role (protocol 4.10).
    /// Everything the socket ever reports belongs to it and to nobody else.
    public private(set) var user = UserIdentity(username: "")
    public private(set) var gatewayVersion = ""
    public private(set) var config: GatewayConfig = .empty
    public private(set) var stt: STTConfig = .disabled
    /// Amendment A29: whether this gateway has a polish model at all. The
    /// switch, the model and the strength are the user's own settings; this is
    /// the only thing the gateway has a say in.
    public private(set) var polish: PolishInfo = .disabled
    /// Amendment A31: this build is older than the gateway will talk to, with
    /// the minimum it asks for and where a newer build is. Nothing else in the
    /// app is reachable while it is set, and only signing out clears it —
    /// which is how a person reaches another gateway.
    public private(set) var updateRequired: AppUpdateRequirement?
    public private(set) var devices: [Device] = []
    public private(set) var sessions: [Session] = []
    public private(set) var errorMessage: String?
    public private(set) var isDemo = false
    /// True once the first `hello` has been applied for the current scope.
    public private(set) var hasSnapshot = false

    @ObservationIgnored public private(set) var api: (any GatewayAPI)?
    @ObservationIgnored public private(set) var channel: (any GatewayChannel)?
    @ObservationIgnored private let cache: LocalCache
    @ObservationIgnored private let makeAPI: @Sendable (GatewayEndpoint) -> any GatewayAPI
    @ObservationIgnored private let makeChannel: @Sendable (any GatewayAPI) -> any GatewayChannel
    @ObservationIgnored private var pump: Task<Void, Never>?
    /// Which signed-in connection this is: one gateway and one account on it.
    /// Everything that awaits captures the scope it was issued in and drops its
    /// answer once the store has moved on — to another account, to another
    /// gateway, or to no connection at all. Without it a slow `/api/session` or
    /// `/api/config` from a gateway the person has left rewrites the live one.
    @ObservationIgnored private var scope = 0
    /// The two calls that confirm a scope after the screens have already been
    /// drawn for it. Held so leaving the scope cancels them.
    @ObservationIgnored private var confirmation: Task<Void, Never>?
    @ObservationIgnored private var configuration: Task<Void, Never>?
    @ObservationIgnored private var frameHandlers: [String: @MainActor (AppFrame) -> Void] = [:]
    /// Called with both versions whenever a session the app already knew is
    /// replaced by a newer one. A session arriving for the first time — the
    /// `hello` list, or one the device just created — is not a transition and
    /// never reaches this. Nothing in the store reads it; the app announces
    /// finished turns from it (`docs/DESIGN.md` § "Being told when a turn ends").
    @ObservationIgnored public var onSessionTransition: (@MainActor (Session, Session) -> Void)?

    public init(cache: LocalCache = LocalCache(),
                makeAPI: @escaping @Sendable (GatewayEndpoint) -> any GatewayAPI = { GatewayHTTPClient(endpoint: $0) },
                makeChannel: @escaping @Sendable (any GatewayAPI) -> any GatewayChannel = { api in
                    GatewaySocket(client: api as? GatewayHTTPClient ?? GatewayHTTPClient(endpoint: api.endpoint))
                }) {
        self.cache = cache
        self.makeAPI = makeAPI
        self.makeChannel = makeChannel
    }

    /// A store whose gateway is the offline demo, reached through the sign-in
    /// form rather than around it. It is how the account screens — signing in
    /// with a username, registering, the admin's Users screen — are driven
    /// without a gateway to reach.
    public static func offlineDemo(registrationOpen: Bool = false) -> ConnectionStore {
        let gateway = DemoGateway(registrationOpen: registrationOpen)
        return ConnectionStore(makeAPI: { _ in gateway }, makeChannel: { _ in gateway })
    }

    // MARK: - Connection scope

    /// Leave the current scope: what is still in flight for it belongs to
    /// nothing, and the tasks that would have applied it are cancelled.
    /// Returns the scope the caller is entering.
    @discardableResult
    private func beginScope() -> Int {
        scope += 1
        confirmation?.cancel()
        confirmation = nil
        configuration?.cancel()
        configuration = nil
        return scope
    }

    /// Whether an answer issued in `value` may still be applied.
    private func isCurrent(_ value: Int) -> Bool { value == scope }

    // MARK: - Derived views

    public var isSignedIn: Bool { phase != .signedOut }

    public var username: String { user.username }

    /// Whether the accounts screen of 3.9 is this person's to see (A24).
    public var isAdmin: Bool { user.role.isAdmin }

    public var account: String { "\(endpoint?.origin ?? "demo")|\(username)" }

    public func device(_ id: String) -> Device? { devices.first { $0.deviceID == id } }

    public func session(deviceID: String, sessionID: String) -> Session? {
        sessions.first { $0.deviceID == deviceID && $0.sessionID == sessionID }
    }

    public var onlineDevices: [Device] { devices.filter(\.online) }

    /// "2 devices · 1 waiting" for the sessions footer.
    public var inventorySummary: String {
        let waiting = sessions.filter { $0.state.isBlockedOnUser }.count
        let devices = L10n.string(self.devices.count == 1 ? "%lld device" : "%lld devices",
                                  self.devices.count)
        return waiting == 0 ? devices : L10n.string("%@ · %lld waiting", devices, waiting)
    }

    // MARK: - Authentication

    /// Whether this gateway is taking registrations, asked before anyone has an
    /// account (A24). The sign-in form is the only caller: "Create an account"
    /// is offered where the gateway says it can be, and nowhere else.
    public func registrationOpen(origin: String) async -> Bool {
        let scope = self.scope
        guard let endpoint = try? GatewayEndpoint(origin) else { return false }
        guard let health = try? await makeAPI(endpoint).health() else { return false }
        // Amendment A31: this route needs no credential, so it is where an app
        // the gateway is too new for finds out — before it has typed a password.
        // Only while the form is still on the gateway it asked about: a slow
        // answer must not block an app that has since signed in somewhere else.
        guard isCurrent(scope) else { return health.registrationOpen }
        note(apps: health.apps)
        return health.registrationOpen
    }

    /// Amendment A31: the first source to say this build is too old wins.
    /// `GET /api/health`, `GET /api/config` and `hello` all carry `apps` and
    /// arrive in no fixed order; nothing after the first refusal can lower the
    /// bar, and only signing out clears it.
    private func note(apps: AppsInfo?) {
        guard updateRequired == nil, let requirement = AppUpdateRequirement.of(apps) else { return }
        updateRequired = requirement
    }

    public func signIn(origin: String, username: String, password: String) async {
        await authenticate(origin: origin, describe: AccountError.signIn) { api in
            try await api.login(username: username, password: password)
        }
    }

    /// `POST /api/register` (A24). Creating an account signs it in, so this is
    /// a sign-in with one different route and one different set of refusals.
    public func register(origin: String, username: String, password: String) async {
        await authenticate(origin: origin, describe: AccountError.register) { api in
            try await api.register(username: username, password: password)
        }
    }

    private func authenticate(origin: String,
                              describe: @escaping (any Error) -> String,
                              call: (any GatewayAPI) async throws -> LoginResponse) async {
        let scope = self.scope
        errorMessage = nil
        do {
            let endpoint = try GatewayEndpoint(origin)
            let api = makeAPI(endpoint)
            let response = try await call(api)
            guard !Task.isCancelled, isCurrent(scope) else { return }
            adopt(api: api, endpoint: endpoint, user: response.user)
            await start()
        } catch {
            // A refusal from a sign-in the person has already left behind must
            // not take down the connection they are on now.
            guard isCurrent(scope) else { return }
            phase = .signedOut
            errorMessage = describe(error)
        }
    }

    /// Reconnect on launch when a keychain token is still valid.
    ///
    /// The account is adopted the moment the keychain answers, so the app draws
    /// its own screens in their connecting state rather than a sign-in form for
    /// the length of a round trip. The token is checked behind them: only a
    /// refusal ends the session, because a gateway that cannot be reached is a
    /// link problem and the socket is already reconnecting through it.
    public func restore(origin: String, username: String) async -> Bool {
        guard let endpoint = try? GatewayEndpoint(origin) else { return false }
        let api = makeAPI(endpoint)
        guard await api.restoreToken(username: username) else { return false }
        // The role is not in the keychain. `/api/session` behind these screens
        // carries it, and until it lands the app draws a member's Settings —
        // one row short rather than one row nobody is allowed to open.
        adopt(api: api, endpoint: endpoint, user: UserIdentity(username: username))
        await start()
        let scope = self.scope
        confirmation = Task { [weak self] in await self?.confirmStoredAccount(api: api, scope: scope) }
        return true
    }

    /// The stored token against `/api/session`, behind the screens it already
    /// unlocked. A 401 is the one answer that sends the user back to the form.
    ///
    /// The answer is applied only while the store is still on the scope that
    /// asked for it. A person who signs out and in as somebody else while this
    /// is in flight would otherwise be told they are the previous account, with
    /// the previous account's role, its cache file and its draft file.
    private func confirmStoredAccount(api: any GatewayAPI, scope: Int) async {
        do {
            let info = try await api.session()
            guard !Task.isCancelled, isCurrent(scope), !info.user.username.isEmpty else { return }
            user = info.user
        } catch TransportError.unauthorized {
            guard !Task.isCancelled, isCurrent(scope) else { return }
            phase = .expired
            await endSession(message: L10n.string("Your session expired. Sign in again."))
        } catch {
            // A gateway that did not answer has said nothing about the token.
        }
    }

    /// Enter the offline demo. It never constructs a network transport.
    public func enterDemo(api: any GatewayAPI, channel: any GatewayChannel) async {
        let scope = beginScope()
        isDemo = true
        endpoint = api.endpoint
        // The sample gateway names its own account, and it is the operator's,
        // so every screen an admin has is reachable from the demo.
        let identity = (try? await api.session().user) ?? UserIdentity(username: "")
        guard isCurrent(scope) else { return }
        user = identity
        self.api = api
        self.channel = channel
        await start(channel: channel)
        // The demo answers `/api/config` from memory and reaches nothing, and
        // the screens read the served client build from it (A22).
        await loadConfig(scope: scope)
    }

    public func signOut() async {
        beginScope()
        pump?.cancel()
        pump = nil
        await channel?.disconnect()
        if let api, !isDemo {
            try? await api.logout()
            await api.forgetToken(username: username)
            await cache.clear(origin: api.endpoint.origin, username: username)
        }
        api = nil
        channel = nil
        devices = []
        sessions = []
        user = UserIdentity(username: "")
        hasSnapshot = false
        isDemo = false
        polish = .disabled
        // Amendment A31: signing out is the way off a gateway this build is too
        // old for, so the blocking screen goes with the connection.
        updateRequired = nil
        phase = .signedOut
    }

    private func adopt(api: any GatewayAPI, endpoint: GatewayEndpoint, user: UserIdentity) {
        beginScope()
        self.api = api
        self.endpoint = endpoint
        self.user = user
        isDemo = false
    }

    /// The admin's accounts screen, built on this connection's own credential.
    public func usersStore() -> UsersStore? {
        guard let api, isAdmin else { return nil }
        return UsersStore(api: api)
    }

    /// `POST /api/password`: the signed-in person changing their own.
    public func changePassword(current: String, new: String) async throws {
        guard let api else { throw TransportError.notConnected }
        try await api.changePassword(current: current, new: new)
    }

    // MARK: - Connection scope

    public func start() async {
        guard let api else { return }
        pump?.cancel()
        pump = nil
        await channel?.disconnect()
        let channel = makeChannel(api)
        self.channel = channel
        await start(channel: channel)
        let scope = self.scope
        configuration?.cancel()
        configuration = Task { [weak self] in await self?.loadConfig(scope: scope) }
    }

    private func start(channel: any GatewayChannel) async {
        pump?.cancel()
        phase = .connecting
        await paintFromCache(scope: scope)
        pump = Task { [weak self] in
            guard let self else { return }
            await channel.connect()
            for await event in channel.events {
                if Task.isCancelled { break }
                await self.receive(event)
            }
        }
    }

    /// Render the last known list immediately, then let `hello` correct it.
    private func paintFromCache(scope: Int) async {
        guard let origin = endpoint?.origin, !isDemo,
              let workspace = await cache.load(origin: origin, username: username) else { return }
        guard !Task.isCancelled, isCurrent(scope), !hasSnapshot else { return }
        devices = workspace.devices
        sessions = workspace.sessions
    }

    public func cachedTranscript(sessionID: String, deviceID: String) async -> [SessionEvent] {
        guard let origin = endpoint?.origin, !isDemo,
              let workspace = await cache.load(origin: origin, username: username) else { return [] }
        return workspace.transcripts["\(deviceID)/\(sessionID)"] ?? []
    }

    public func persist(transcript: [SessionEvent], sessionID: String, deviceID: String) async {
        let scope = self.scope
        guard let origin = endpoint?.origin, !isDemo else { return }
        var workspace = await cache.load(origin: origin, username: username) ?? CachedWorkspace()
        guard isCurrent(scope) else { return }
        workspace.devices = devices
        workspace.sessions = sessions
        workspace.transcripts["\(deviceID)/\(sessionID)"] = Array(transcript.suffix(LocalCache.eventLimit))
        workspace.savedAt = Int64(Date().timeIntervalSince1970 * 1000)
        await cache.save(workspace, origin: origin, username: username)
    }

    public func persistInventory() async {
        let scope = self.scope
        guard let origin = endpoint?.origin, !isDemo else { return }
        var workspace = await cache.load(origin: origin, username: username) ?? CachedWorkspace()
        guard isCurrent(scope) else { return }
        workspace.devices = devices
        workspace.sessions = sessions
        workspace.savedAt = Int64(Date().timeIntervalSince1970 * 1000)
        await cache.save(workspace, origin: origin, username: username)
    }

    /// Everything `/api/config` decides, applied only while the store is still
    /// on the gateway that was asked.
    ///
    /// Amendment A31's `updateRequired` is the reason this matters most: a slow
    /// answer from a gateway the person has left would put the blocking
    /// "Update required" screen over a gateway that states no minimum at all,
    /// and the only way out of it is to sign out again.
    private func loadConfig(scope: Int) async {
        guard let api, let value = try? await api.config() else { return }
        guard !Task.isCancelled, isCurrent(scope) else { return }
        config = value
        // `hello` and `/api/config` describe the same gateway. The one that
        // arrives later wins, and this call always follows the hello it races.
        stt = value.stt
        polish = value.polish
        note(apps: value.apps)
    }

    // MARK: - Frames

    /// Register a listener for a scope such as one open chat. The token is the
    /// caller's own key; unregistering is its responsibility.
    public func addFrameHandler(_ token: String, handler: @escaping @MainActor (AppFrame) -> Void) {
        frameHandlers[token] = handler
    }

    public func removeFrameHandler(_ token: String) {
        frameHandlers.removeValue(forKey: token)
    }

    private func receive(_ event: GatewayEvent) async {
        switch event {
        case .state(let state):
            switch state {
            case .connecting: phase = hasSnapshot ? .reconnecting : .connecting
            case .reconnecting: phase = .reconnecting
            case .connected: phase = .syncing
            case .unauthorized: phase = .expired
            case .disconnected, .idle: if phase != .signedOut { phase = .reconnecting }
            }
        case .failure(let error):
            if case .protocolMismatch(let version) = error {
                phase = .incompatible(gatewayVersion: version)
            }
            errorMessage = error.errorDescription
        case .requestUncertain:
            break
        case .closed(let reason):
            await handle(close: reason)
        case .frame(let frame):
            apply(frame)
            for handler in frameHandlers.values { handler(frame) }
        }
    }

    /// Amendment A4: 4401 and 4403 end the session, 4001 stops the loop without
    /// signing out, and everything else never reaches here.
    private func handle(close reason: SocketCloseReason) async {
        switch reason {
        case .unauthorized:
            await endSession(message: L10n.string("Your session expired. Sign in again."))
        case .forbidden:
            await endSession(message: L10n.string(
                "This gateway refused the connection. Ask whoever runs it for access."))
        case .replaced:
            phase = .superseded
            errorMessage = L10n.string("Another app took over this connection.")
        case .transient:
            break
        }
    }

    /// Return to the login screen, forgetting the token but keeping the cached
    /// lists so the next sign-in paints immediately.
    private func endSession(message: String) async {
        beginScope()
        pump?.cancel()
        pump = nil
        await channel?.disconnect()
        if let api { await api.forgetToken(username: username) }
        channel = nil
        hasSnapshot = false
        phase = .signedOut
        errorMessage = message
    }

    /// Try again after a replaced connection. Nothing else auto-reconnects.
    public func reconnect() async {
        guard phase == .superseded, api != nil else { return }
        await start()
    }

    private func apply(_ frame: AppFrame) {
        switch frame {
        case .hello(let hello):
            gatewayVersion = hello.gatewayVersion
            if !hello.user.username.isEmpty { user = hello.user }
            devices = hello.devices
            sessions = hello.sessions
            stt = hello.stt
            polish = hello.polish
            // Amendment A31: a gateway upgraded under a connected app is caught
            // here, at the next connection, rather than at the next launch.
            note(apps: hello.apps)
            hasSnapshot = true
            phase = .connected
            errorMessage = nil
            Task { [weak self] in await self?.persistInventory() }
        case .deviceUpdated(let device):
            if let index = devices.firstIndex(where: { $0.deviceID == device.deviceID }) {
                devices[index] = device
            } else {
                devices.append(device)
            }
        case .deviceRemoved(let deviceID):
            devices.removeAll { $0.deviceID == deviceID }
            sessions.removeAll { $0.deviceID == deviceID }
        case .sessionUpdated(let session):
            if let index = sessions.firstIndex(where: { $0.id == session.id }) {
                let previous = sessions[index]
                sessions[index] = session
                onSessionTransition?(previous, session)
            } else {
                sessions.append(session)
            }
        case .sessionRemoved(let sessionID, let deviceID):
            // The session id is the key; the device id only narrows it when the
            // gateway sent one (amendment A5).
            sessions.removeAll { $0.sessionID == sessionID && (deviceID == nil || $0.deviceID == deviceID) }
        default:
            break
        }
    }

    /// Close a session from the list: the device interrupts its turn, ends what
    /// it holds for the agent and files the row, and the one reply carries all
    /// three (A39). A failure surfaces instead of disappearing into a `try?`.
    /// Nothing here ever clears the flag — the device does that when the
    /// session comes back to life (A15).
    public func close(session: Session) async {
        guard let channel else { return }
        let scope = self.scope
        do {
            let result = try await channel.request(.archive(sessionID: session.sessionID, archived: true),
                                                   as: SessionResult.self)
            guard isCurrent(scope) else { return }
            apply(.sessionUpdated(result.session))
        } catch {
            guard isCurrent(scope) else { return }
            errorMessage = message(for: error)
        }
    }

    public func message(for error: any Error) -> String { GatewayMessage.text(for: error) }

    public func clearError() { errorMessage = nil }
}
