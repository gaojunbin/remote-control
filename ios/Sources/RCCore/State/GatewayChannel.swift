import Foundation

/// The socket surface the stores depend on. `GatewaySocket` is the real one;
/// the in-memory demo gateway is the other.
public protocol GatewayChannel: Sendable {
    var events: AsyncStream<GatewayEvent> { get }
    func connect() async
    func disconnect() async
    @discardableResult
    func request(_ request: GatewayRequest) async throws -> JSONValue
}

extension GatewayChannel {
    public func request<T: Decodable>(_ request: GatewayRequest, as type: T.Type) async throws -> T {
        try await self.request(request).decode(type)
    }
}

extension GatewaySocket: GatewayChannel {}

/// The HTTP surface the stores depend on.
public protocol GatewayAPI: Sendable {
    var endpoint: GatewayEndpoint { get }
    func health() async throws -> HealthResponse
    func login(username: String, password: String) async throws -> LoginResponse
    func register(username: String, password: String) async throws -> LoginResponse
    func changePassword(current: String, new: String) async throws
    func session() async throws -> SessionInfoResponse
    func logout() async throws
    func config() async throws -> GatewayConfig
    /// Amendment A35: the account's preferences, read and written through the
    /// gateway so the phone, the browser and every device agree.
    func preferences() async throws -> PreferencesResponse
    func patchPreferences(resumeAfterLimit: Bool?) async throws -> PreferencesResponse
    /// Amendment A29: dictation polish, which the app offers only where the
    /// gateway reports `polish.enabled`.
    func polishModels() async throws -> PolishModelsResponse
    func polish(_ request: PolishRequest) async throws -> PolishResponse
    func devices() async throws -> [Device]
    func renameDevice(_ deviceID: String, name: String) async throws -> Device
    func revokeDevice(_ deviceID: String) async throws
    func beginPairing() async throws -> PairingGrant
    func cancelPairing(code: String) async throws
    func claimPairingRequest(token: String) async throws -> PairingClaim
    func sessions(deviceID: String?, archived: Bool?) async throws -> [Session]
    func users() async throws -> UserListResponse
    func createUser(username: String, password: String, role: UserRole) async throws -> UserRecord
    func patchUser(_ username: String, state: UserState?, role: UserRole?,
                   password: String?) async throws -> UserRecord
    func deleteUser(_ username: String) async throws
    func setRegistration(open: Bool) async throws -> Bool
    func registerPush(_ registration: APNSRegistration) async throws
    func unregisterPush(token: String) async throws
    func restoreToken(username: String) async -> Bool
    func bearerToken() async -> String?
    func forgetToken(username: String) async
}

extension GatewayHTTPClient: GatewayAPI {}
