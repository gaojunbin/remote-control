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
    public private(set) var username = ""
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

    // MARK: - Derived views

    public var isSignedIn: Bool { phase != .signedOut }

    public var account: String { "\(endpoint?.origin ?? "demo")|\(username)" }

    public func device(_ id: String) -> Device? { devices.first { $0.deviceID == id } }

    public func session(deviceID: String, sessionID: String) -> Session? {
        sessions.first { $0.deviceID == deviceID && $0.sessionID == sessionID }
    }

    public var onlineDevices: [Device] { devices.filter(\.online) }

    /// "2 devices · 1 waiting" for the sessions footer.
    public var inventorySummary: String {
        let waiting = sessions.filter { $0.state.isBlockedOnUser }.count
        let deviceWord = devices.count == 1 ? "device" : "devices"
        return waiting == 0
            ? "\(devices.count) \(deviceWord)"
            : "\(devices.count) \(deviceWord) · \(waiting) waiting"
    }

    // MARK: - Authentication

    public func signIn(origin: String, password: String, username: String?) async {
        errorMessage = nil
        do {
            let endpoint = try GatewayEndpoint(origin)
            let api = makeAPI(endpoint)
            let response = try await api.login(password: password, username: username)
            guard !Task.isCancelled else { return }
            adopt(api: api, endpoint: endpoint, username: response.user.username)
            await start()
        } catch {
            phase = .signedOut
            errorMessage = message(for: error)
        }
    }

    /// Reconnect on launch when a keychain token is still valid.
    public func restore(origin: String, username: String) async -> Bool {
        guard let endpoint = try? GatewayEndpoint(origin) else { return false }
        let api = makeAPI(endpoint)
        guard await api.restoreToken(username: username) else { return false }
        do {
            let info = try await api.session()
            guard !Task.isCancelled else { return false }
            adopt(api: api, endpoint: endpoint, username: info.user.username)
            await start()
            return true
        } catch {
            return false
        }
    }

    /// Enter the offline demo. It never constructs a network transport.
    public func enterDemo(api: any GatewayAPI, channel: any GatewayChannel) async {
        isDemo = true
        endpoint = api.endpoint
        username = "demo"
        self.api = api
        self.channel = channel
        await start(channel: channel)
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
        hasSnapshot = false
        isDemo = false
        phase = .signedOut
    }

    private func adopt(api: any GatewayAPI, endpoint: GatewayEndpoint, username: String) {
        self.api = api
        self.endpoint = endpoint
        self.username = username
        isDemo = false
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
        guard let api, !isDemo, let value = try? await api.config(), !Task.isCancelled else { return }
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
            await endSession(message: "Your session expired. Sign in again.")
        case .forbidden:
            await endSession(message: "This gateway refused the connection. Ask whoever runs it for access.")
        case .replaced:
            phase = .superseded
            errorMessage = "Another app took over this connection."
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
            username = hello.user.username.isEmpty ? username : hello.user.username
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

    /// Archive or unarchive from the session list. The reply updates the list,
    /// and a failure surfaces instead of disappearing into a `try?`.
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

    public func message(for error: any Error) -> String {
        if let transport = error as? TransportError { return transport.errorDescription ?? "\(transport)" }
        if let gateway = error as? GatewayErrorBody { return gateway.message }
        if let failure = error as? ProtocolFailure { return failure.errorDescription ?? "\(failure)" }
        return error.localizedDescription
    }

    public func clearError() { errorMessage = nil }
}
