import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Anything that can perform one HTTP request. Tests and the demo gateway
/// substitute their own implementation instead of reaching the network.
public protocol HTTPTransport: Sendable {
    func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

/// A `URLSession` transport that refuses redirects, so a bearer token can never
/// be replayed to a host the user did not type.
public final class URLSessionHTTPTransport: NSObject, HTTPTransport, URLSessionTaskDelegate, @unchecked Sendable {
    private let session: URLSession

    public override init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 30
        session = URLSession(configuration: configuration)
        super.init()
    }

    public func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request, delegate: self)
        guard let http = response as? HTTPURLResponse else { throw TransportError.invalidResponse }
        guard data.count <= 8 * 1024 * 1024 else { throw TransportError.responseTooLarge }
        return (data, http)
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask,
                           willPerformHTTPRedirection response: HTTPURLResponse,
                           newRequest request: URLRequest) async -> URLRequest? {
        nil
    }
}

/// Bearer-token HTTP against one gateway origin.
///
/// The token lives in the keychain, keyed by origin plus username, and is
/// attached to every authenticated call. No cookie storage is involved: this is
/// a native app, so the browser's cookie path in the protocol does not apply.
public actor GatewayHTTPClient {
    public let endpoint: GatewayEndpoint
    private let transport: any HTTPTransport
    private let secrets: any SecretStore
    private var token: String?

    public init(endpoint: GatewayEndpoint,
                transport: any HTTPTransport = URLSessionHTTPTransport(),
                secrets: any SecretStore = KeychainSecretStore()) {
        self.endpoint = endpoint
        self.transport = transport
        self.secrets = secrets
    }

    // MARK: - Token lifecycle

    private func secretKey(_ username: String) -> String { "token:\(endpoint.origin):\(username)" }

    public func restoreToken(username: String) async -> Bool {
        guard let data = try? await secrets.read(key: secretKey(username)),
              let value = String(data: data, encoding: .utf8), !value.isEmpty else { return false }
        token = value
        return true
    }

    public func adoptToken(_ value: String) { token = value }

    public var hasToken: Bool { token != nil }

    public func bearerToken() -> String? { token }

    private func storeToken(_ value: String, username: String) async {
        token = value
        try? await secrets.write(Data(value.utf8), key: secretKey(username))
    }

    public func forgetToken(username: String) async {
        token = nil
        try? await secrets.remove(key: secretKey(username))
    }

    // MARK: - Routes

    public func health() async throws -> JSONValue {
        try await send(.get, "/api/health", authenticated: false)
    }

    public func login(password: String, username: String?) async throws -> LoginResponse {
        var body: [String: JSONValue] = ["password": .string(password)]
        if let username, !username.isEmpty { body["username"] = .string(username) }
        let value = try await send(.post, "/api/login", body: .object(body), authenticated: false)
        let response = try value.decode(LoginResponse.self)
        await storeToken(response.token, username: response.user.username)
        return response
    }

    public func session() async throws -> SessionInfoResponse {
        try await send(.get, "/api/session").decode(SessionInfoResponse.self)
    }

    public func logout() async throws {
        _ = try? await send(.post, "/api/logout")
        token = nil
    }

    public func config() async throws -> GatewayConfig {
        try await send(.get, "/api/config").decode(GatewayConfig.self)
    }

    public func devices() async throws -> [Device] {
        try await send(.get, "/api/devices").decode(DeviceListResponse.self).devices
    }

    public func renameDevice(_ deviceID: String, name: String) async throws -> Device {
        try await send(.patch, "/api/devices/\(escape(deviceID))", body: ["name": .string(name)])
            .decode(DeviceResponse.self).device
    }

    public func revokeDevice(_ deviceID: String) async throws {
        _ = try await send(.delete, "/api/devices/\(escape(deviceID))")
    }

    public func beginPairing() async throws -> PairingGrant {
        try await send(.post, "/api/devices/pairing", body: .object([:])).decode(PairingGrant.self)
    }

    public func cancelPairing(code: String) async throws {
        _ = try await send(.delete, "/api/devices/pairing/\(escape(code))")
    }

    public func sessions(deviceID: String? = nil, archived: Bool? = nil) async throws -> [Session] {
        var query: [URLQueryItem] = []
        if let deviceID { query.append(.init(name: "device_id", value: deviceID)) }
        if let archived { query.append(.init(name: "archived", value: archived ? "true" : "false")) }
        return try await send(.get, "/api/sessions", query: query).decode(SessionListResponse.self).sessions
    }

    public func registerPush(_ registration: APNSRegistration) async throws {
        _ = try await send(.post, "/api/push/apns/register", body: try .encode(registration))
    }

    public func unregisterPush(token deviceToken: String) async throws {
        _ = try await send(.delete, "/api/push/apns/register", body: ["token": .string(deviceToken)])
    }

    // MARK: - Plumbing

    private enum Method: String { case get = "GET", post = "POST", patch = "PATCH", delete = "DELETE" }

    private func escape(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(.init(charactersIn: "-._~"))) ?? value
    }

    private func send(_ method: Method, _ path: String, query: [URLQueryItem] = [],
                      body: JSONValue? = nil, authenticated: Bool = true) async throws -> JSONValue {
        var components = URLComponents(url: endpoint.apiURL(path), resolvingAgainstBaseURL: false)
        if !query.isEmpty { components?.queryItems = query }
        guard let url = components?.url else { throw TransportError.invalidEndpoint }
        var request = URLRequest(url: url)
        request.httpMethod = method.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if authenticated {
            guard let token else { throw TransportError.unauthorized }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            request.httpBody = try JSONEncoder().encode(body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await transport.perform(request)
        if response.statusCode == 401 { throw TransportError.unauthorized }
        let json = data.isEmpty ? JSONValue.object([:]) : (try? JSONDecoder().decode(JSONValue.self, from: data)) ?? .object([:])
        guard (200..<300).contains(response.statusCode) else {
            throw TransportError.http(status: response.statusCode, code: json["error"]?["code"]?.stringValue)
        }
        return json
    }
}
