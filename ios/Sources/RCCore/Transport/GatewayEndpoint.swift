import Foundation

public enum TransportError: Error, LocalizedError, Sendable, Equatable {
    case invalidEndpoint
    case invalidResponse
    case responseTooLarge
    case unauthorized
    case http(status: Int, code: String?)
    case notConnected
    case deliveryUncertain
    case requestTimedOut
    case protocolMismatch(Int)
    case secureStorageUnavailable

    public var errorDescription: String? {
        switch self {
        case .invalidEndpoint:
            L10n.string("Enter the full gateway address, for example https://rc.example.com.")
        case .invalidResponse:
            L10n.string("The gateway sent a response this app could not read.")
        case .responseTooLarge:
            L10n.string("That response was too large to load.")
        case .unauthorized:
            L10n.string("Your session expired. Sign in again.")
        case .http(let status, _):
            switch status {
            case 403: L10n.string("The gateway refused this request.")
            case 404: L10n.string("That device or session no longer exists.")
            case 429: L10n.string("Too many attempts. Wait a moment and try again.")
            case 503: L10n.string("The gateway does not have this feature enabled.")
            default: L10n.string("The gateway request failed (%lld).", status)
            }
        case .notConnected:
            L10n.string("Not connected to the gateway.")
        case .deliveryUncertain:
            L10n.string("Delivery unconfirmed. Check the session before sending again.")
        case .requestTimedOut:
            L10n.string("The gateway did not answer in time.")
        case .protocolMismatch(let version):
            L10n.string("This app speaks protocol %lld; the gateway speaks %lld. Update both sides.",
                        RemoteProtocol.version, version)
        case .secureStorageUnavailable:
            L10n.string("Could not reach the keychain. Unlock this device and try again.")
        }
    }
}

/// A canonical gateway origin: scheme, host and port only.
///
/// https is required, because a bearer token travels on every request. Plain
/// http is accepted only for loopback and private-network hosts, so a developer
/// can point the app at a gateway running on their own machine.
public struct GatewayEndpoint: Hashable, Codable, Sendable {
    public let origin: String
    public let isSecure: Bool

    public var url: URL { URL(string: origin) ?? URL(fileURLWithPath: "/") }

    /// `wss://host/ws/app`, or `ws://` for a development origin.
    public func socketURL(path: String) -> URL {
        var parts = URLComponents(url: url, resolvingAgainstBaseURL: false)
        parts?.scheme = isSecure ? "wss" : "ws"
        parts?.path = path
        return parts?.url ?? url
    }

    public func apiURL(_ path: String) -> URL {
        url.appending(path: path.hasPrefix("/") ? String(path.dropFirst()) : path)
    }

    public init(_ text: String) throws {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let withScheme = trimmed.contains("://") ? trimmed : "https://" + trimmed
        guard var parts = URLComponents(string: withScheme),
              let scheme = parts.scheme?.lowercased(),
              let host = parts.host?.lowercased(), !host.isEmpty,
              parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil,
              parts.path.isEmpty || parts.path == "/",
              parts.port.map({ (1...65535).contains($0) }) ?? true else {
            throw TransportError.invalidEndpoint
        }
        switch scheme {
        case "https": isSecure = true
        case "http" where Self.isDevelopmentHost(host): isSecure = false
        default: throw TransportError.invalidEndpoint
        }
        parts.scheme = isSecure ? "https" : "http"
        parts.host = host
        if isSecure, parts.port == 443 { parts.port = nil }
        if !isSecure, parts.port == 80 { parts.port = nil }
        parts.path = ""
        guard let canonical = parts.url?.absoluteString else { throw TransportError.invalidEndpoint }
        origin = canonical
    }

    /// Loopback and RFC 1918 addresses, plus `.local` names from Bonjour.
    public static func isDevelopmentHost(_ host: String) -> Bool {
        if host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]" { return true }
        if host.hasSuffix(".local") || host.hasSuffix(".localhost") { return true }
        let parts = host.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4, parts.allSatisfy({ (0...255).contains($0) }) else { return false }
        if parts[0] == 10 || parts[0] == 127 { return true }
        if parts[0] == 192 && parts[1] == 168 { return true }
        if parts[0] == 172 && (16...31).contains(parts[1]) { return true }
        return false
    }

    public init(from decoder: Decoder) throws {
        try self.init(decoder.singleValueContainer().decode(String.self))
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(origin)
    }
}

extension GatewayEndpoint {
    /// A never-reachable origin, so nothing in the package needs a force unwrap
    /// to hold a `GatewayEndpoint`.
    public static let placeholder = GatewayEndpoint(origin: "https://invalid.invalid", isSecure: true)

    private init(origin: String, isSecure: Bool) {
        self.origin = origin
        self.isSecure = isSecure
    }
}

extension GatewayEndpoint: CustomStringConvertible {
    public var description: String { origin }
}
