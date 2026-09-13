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
/// One child task per connection scope reads the socket stream. Cancelling that
/// task is what ends a scope, so there are no generation counters to keep in
/// sync after every await.
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
    @ObservationIgnored private var frameHandlers: [String: @MainActor (AppFrame) -> Void] = [:]

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
        guard let endpoint = try? GatewayEndpoint(origin) else { return false }
        return (try? await makeAPI(endpoint).health().registrationOpen) ?? false
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
        errorMessage = nil
        do {
            let endpoint = try GatewayEndpoint(origin)
            let api = makeAPI(endpoint)
            let response = try await call(api)
            guard !Task.isCancelled else { return }
            adopt(api: api, endpoint: endpoint, user: response.user)
            await start()
        } catch {
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
        Task { [weak self] in await self?.confirmStoredAccount(api: api) }
        return true
    }

    /// The stored token against `/api/session`, behind the screens it already
    /// unlocked. A 401 is the one answer that sends the user back to the form.
    private func confirmStoredAccount(api: any GatewayAPI) async {
        do {
            let info = try await api.session()
            guard !Task.isCancelled, !info.user.username.isEmpty else { return }
            user = info.user
        } catch TransportError.unauthorized {
            phase = .expired
            await endSession(message: L10n.string("Your session expired. Sign in again."))
        } catch {
            // A gateway that did not answer has said nothing about the token.
        }
    }

    /// Enter the offline demo. It never constructs a network transport.
    public func enterDemo(api: any GatewayAPI, channel: any GatewayChannel) async {
        isDemo = true
        endpoint = api.endpoint
        // The sample gateway names its own account, and it is the operator's,
        // so every screen an admin has is reachable from the demo.
        user = (try? await api.session().user) ?? UserIdentity(username: "")
        self.api = api
        self.channel = channel
        await start(channel: channel)
        // The demo answers `/api/config` from memory and reaches nothing, and
        // the screens read the served client build from it (A22).
        await loadConfig()
    }

    public func signOut() async {
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
        phase = .signedOut
    }

    private func adopt(api: any GatewayAPI, endpoint: GatewayEndpoint, user: UserIdentity) {
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
        Task { [weak self] in await self?.loadConfig() }
    }

    private func start(channel: any GatewayChannel) async {
        pump?.cancel()
        phase = .connecting
        await paintFromCache()
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
    private func paintFromCache() async {
        guard let origin = endpoint?.origin, !isDemo,
              let workspace = await cache.load(origin: origin, username: username) else { return }
        guard !Task.isCancelled, !hasSnapshot else { return }
        devices = workspace.devices
        sessions = workspace.sessions
    }

    public func cachedTranscript(sessionID: String, deviceID: String) async -> [SessionEvent] {
        guard let origin = endpoint?.origin, !isDemo,
              let workspace = await cache.load(origin: origin, username: username) else { return [] }
        return workspace.transcripts["\(deviceID)/\(sessionID)"] ?? []
    }

    public func persist(transcript: [SessionEvent], sessionID: String, deviceID: String) async {
        guard let origin = endpoint?.origin, !isDemo else { return }
        var workspace = await cache.load(origin: origin, username: username) ?? CachedWorkspace()
        workspace.devices = devices
        workspace.sessions = sessions
        workspace.transcripts["\(deviceID)/\(sessionID)"] = Array(transcript.suffix(LocalCache.eventLimit))
        workspace.savedAt = Int64(Date().timeIntervalSince1970 * 1000)
        await cache.save(workspace, origin: origin, username: username)
    }

    public func persistInventory() async {
        guard let origin = endpoint?.origin, !isDemo else { return }
        var workspace = await cache.load(origin: origin, username: username) ?? CachedWorkspace()
        workspace.devices = devices
        workspace.sessions = sessions
        workspace.savedAt = Int64(Date().timeIntervalSince1970 * 1000)
        await cache.save(workspace, origin: origin, username: username)
    }

    private func loadConfig() async {
        guard let api, let value = try? await api.config(), !Task.isCancelled else { return }
        config = value
        // `hello` and `/api/config` describe the same gateway. The one that
        // arrives later wins, and this call always follows the hello it races.
        stt = value.stt
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
                sessions[index] = session
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

    /// Archive a session from the list, which stops it on the device. The reply
    /// updates the list, and a failure surfaces instead of disappearing into a
    /// `try?`. The flag is cleared by the device when the session comes back to
    /// life (A15), never by a control in the app.
    public func setArchived(_ archived: Bool, session: Session) async {
        guard let channel else { return }
        do {
            let result = try await channel.request(.archive(sessionID: session.sessionID, archived: archived),
                                                   as: SessionResult.self)
            apply(.sessionUpdated(result.session))
        } catch {
            errorMessage = message(for: error)
        }
    }

    public func message(for error: any Error) -> String { GatewayMessage.text(for: error) }

    public func clearError() { errorMessage = nil }
}
