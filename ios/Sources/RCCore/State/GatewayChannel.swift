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
    func login(password: String, username: String?) async throws -> LoginResponse
    func session() async throws -> SessionInfoResponse
    func logout() async throws
    func config() async throws -> GatewayConfig
    func devices() async throws -> [Device]
    func renameDevice(_ deviceID: String, name: String) async throws -> Device
    func revokeDevice(_ deviceID: String) async throws
    func beginPairing() async throws -> PairingGrant
    func cancelPairing(code: String) async throws
    func sessions(deviceID: String?, archived: Bool?) async throws -> [Session]
    func registerPush(_ registration: APNSRegistration) async throws
    func unregisterPush(token: String) async throws
    func restoreToken(username: String) async -> Bool
    func bearerToken() async -> String?
    func forgetToken(username: String) async
}

extension GatewayHTTPClient: GatewayAPI {}
