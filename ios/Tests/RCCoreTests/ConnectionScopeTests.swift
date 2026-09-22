import Testing
import Foundation
@testable import RCCore

/// A reply belongs to the connection that asked for it.
///
/// `restore` and `start` both confirm a connection behind the screens they have
/// already drawn — `/api/session` for the account, `/api/config` for what the
/// gateway offers. Signing out and in again while one of those is in flight
/// used to land the old gateway's answer on the new connection.
@Suite("Connection scope")
struct ConnectionScopeTests {
    @MainActor
    private func store(_ gateways: [ScopedGateway], directory: URL) -> ConnectionStore {
        ConnectionStore(cache: LocalCache(directory: directory),
                        makeAPI: { endpoint in
                            gateways.first { $0.endpoint.origin == endpoint.origin } ?? gateways[0]
                        },
                        makeChannel: { _ in InertChannel() })
    }

    private func scratch() -> URL {
        URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "rc-scope-\(UUID().uuidString)", directoryHint: .isDirectory)
    }

    @Test("A late /api/session answer cannot rewrite the account that signed in after it")
    @MainActor
    func lateAccountConfirmation() async throws {
        let directory = scratch()
        defer { try? FileManager.default.removeItem(at: directory) }
        let first = ScopedGateway(origin: "https://a.example.invalid",
                                  identity: UserIdentity(username: "alice", role: .admin),
                                  sessionDelay: .milliseconds(400))
        let second = ScopedGateway(origin: "https://b.example.invalid",
                                   identity: UserIdentity(username: "bob", role: .member))
        let store = store([first, second], directory: directory)

        #expect(await store.restore(origin: "https://a.example.invalid", username: "alice"))
        #expect(store.username == "alice")
        await store.signOut()
        await store.signIn(origin: "https://b.example.invalid", username: "bob", password: "secret")
        #expect(store.username == "bob")

        // Long enough for the first gateway's `/api/session` to answer.
        try await Task.sleep(for: .milliseconds(700))
        #expect(store.username == "bob", "the account the person signed in as stands")
        #expect(!store.isAdmin, "and so does its role, which gates the admin screens")
        #expect(store.account == "https://b.example.invalid|bob",
                "the cache and the drafts are keyed by this")
        #expect(store.phase != .signedOut, "a stale 401 cannot end a session that is fine")
    }

    @Test("A late /api/config answer from a gateway you left cannot block the app")
    @MainActor
    func lateConfiguration() async throws {
        let directory = scratch()
        defer { try? FileManager.default.removeItem(at: directory) }
        // Amendment A31: this gateway will not talk to any build this app can be.
        let old = ScopedGateway(origin: "https://old.example.invalid",
                                identity: UserIdentity(username: "me"),
                                configDelay: .milliseconds(400),
                                minimumAppVersion: "99.0.0")
        let good = ScopedGateway(origin: "https://good.example.invalid",
                                 identity: UserIdentity(username: "me"))
        let store = store([old, good], directory: directory)

        await store.signIn(origin: "https://old.example.invalid", username: "me", password: "secret")
        #expect(store.updateRequired == nil)
        await store.signOut()
        await store.signIn(origin: "https://good.example.invalid", username: "me", password: "secret")

        try await Task.sleep(for: .milliseconds(700))
        #expect(store.updateRequired == nil,
                "a gateway that states no minimum does not show the Update required screen")
        #expect(store.config.publicOrigin == "https://good.example.invalid",
                "and the config on screen is the one this connection answered with")
    }

    @Test("Signing out clears the blocking screen the gateway put up")
    @MainActor
    func signOutClearsTheUpdateScreen() async throws {
        let directory = scratch()
        defer { try? FileManager.default.removeItem(at: directory) }
        let demanding = ScopedGateway(origin: "https://old.example.invalid",
                                      identity: UserIdentity(username: "me"),
                                      minimumAppVersion: "99.0.0")
        let store = store([demanding], directory: directory)

        await store.signIn(origin: "https://old.example.invalid", username: "me", password: "secret")
        try await Task.sleep(for: .milliseconds(200))
        #expect(store.updateRequired != nil, "the gateway's minimum still reaches the app")
        await store.signOut()
        #expect(store.updateRequired == nil)
    }
}

/// A gateway that answers the two routes the connection scope depends on, each
/// after a delay of the test's choosing, and refuses everything else.
private actor ScopedGateway: GatewayAPI {
    nonisolated let endpoint: GatewayEndpoint
    private let identity: UserIdentity
    private let sessionDelay: Duration
    private let configDelay: Duration
    private let minimumAppVersion: String?

    init(origin: String, identity: UserIdentity, sessionDelay: Duration = .zero,
         configDelay: Duration = .zero, minimumAppVersion: String? = nil) {
        endpoint = try! GatewayEndpoint(origin)
        self.identity = identity
        self.sessionDelay = sessionDelay
        self.configDelay = configDelay
        self.minimumAppVersion = minimumAppVersion
    }

    private var apps: AppsInfo? {
        minimumAppVersion.map { AppsInfo(ios: AppSupport(minimumVersion: $0)) }
    }

    func health() async throws -> HealthResponse {
        HealthResponse(version: "test", protocolVersion: RemoteProtocol.version,
                       registrationOpen: false, apps: apps)
    }

    func login(username: String, password: String) async throws -> LoginResponse {
        LoginResponse(token: "t", exp: 0, user: identity)
    }

    func register(username: String, password: String) async throws -> LoginResponse {
        try await login(username: username, password: password)
    }

    func session() async throws -> SessionInfoResponse {
        try? await Task.sleep(for: sessionDelay)
        return SessionInfoResponse(user: identity, exp: 0)
    }

    func config() async throws -> GatewayConfig {
        try? await Task.sleep(for: configDelay)
        return GatewayConfig(publicOrigin: endpoint.origin, stt: .disabled, push: .disabled,
                             version: "test", apps: apps)
    }

    func restoreToken(username: String) async -> Bool { true }
    func bearerToken() async -> String? { "t" }
    func forgetToken(username: String) async {}
    func logout() async throws {}
    func changePassword(current: String, new: String) async throws {}

    // Nothing below is reached by these tests; a call is a bug in one of them.
    func polishModels() async throws -> PolishModelsResponse { throw TransportError.notConnected }
    func preferences() async throws -> PreferencesResponse { throw TransportError.notConnected }
    func patchPreferences(_ changes: PreferencePatch) async throws -> PreferencesResponse {
        throw TransportError.notConnected
    }
    func polish(_ request: PolishRequest) async throws -> PolishResponse { throw TransportError.notConnected }
    func devices() async throws -> [Device] { throw TransportError.notConnected }
    func renameDevice(_ deviceID: String, name: String) async throws -> Device {
        throw TransportError.notConnected
    }
    func revokeDevice(_ deviceID: String) async throws { throw TransportError.notConnected }
    func beginPairing() async throws -> PairingGrant { throw TransportError.notConnected }
    func cancelPairing(code: String) async throws { throw TransportError.notConnected }
    func claimPairingRequest(token: String) async throws -> PairingClaim {
        throw TransportError.notConnected
    }
    func sessions(deviceID: String?, archived: Bool?) async throws -> [Session] {
        throw TransportError.notConnected
    }
    func users() async throws -> UserListResponse { throw TransportError.notConnected }
    func createUser(username: String, password: String, role: UserRole) async throws -> UserRecord {
        throw TransportError.notConnected
    }
    func patchUser(_ username: String, state: UserState?, role: UserRole?,
                   password: String?) async throws -> UserRecord {
        throw TransportError.notConnected
    }
    func deleteUser(_ username: String) async throws { throw TransportError.notConnected }
    func setRegistration(open: Bool) async throws -> Bool { throw TransportError.notConnected }
    func registerPush(_ registration: APNSRegistration) async throws {
        throw TransportError.notConnected
    }
    func unregisterPush(token: String) async throws { throw TransportError.notConnected }
}

/// A channel that never connects and never speaks: these tests are about what
/// the HTTP routes do to the store, not about the socket.
private final class InertChannel: GatewayChannel {
    nonisolated let events: AsyncStream<GatewayEvent>

    init() { events = AsyncStream<GatewayEvent>.makeStream().stream }

    func connect() async {}
    func disconnect() async {}
    func request(_ request: GatewayRequest) async throws -> JSONValue { throw TransportError.notConnected }
}
