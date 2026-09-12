import Foundation

/// Amendment A23: the link a host prints as a QR code, `<origin>/pair#<token>`.
///
/// The origin is part of the payload and is checked against the gateway this
/// app is signed in to. A code for someone else's gateway is not an error to
/// recover from: it simply is not ours, and the scanner says so and keeps
/// looking.
public struct PairingClaimLink: Sendable, Hashable {
    public let token: String

    /// Parse a scanned payload, or return nil when it is not this gateway's
    /// pairing link. The comparison is against the canonical origin, so a
    /// trailing slash, a default port or a capitalised host still matches.
    public init?(payload: String, gateway: GatewayEndpoint) {
        let trimmed = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let parts = URLComponents(string: trimmed),
              let fragment = parts.fragment, !fragment.isEmpty,
              parts.path == "/pair" || parts.path == "/pair/",
              let scheme = parts.scheme, let host = parts.host else { return nil }
        var origin = URLComponents()
        origin.scheme = scheme.lowercased()
        origin.host = host.lowercased()
        origin.port = parts.port
        if origin.scheme == "https", origin.port == 443 { origin.port = nil }
        if origin.scheme == "http", origin.port == 80 { origin.port = nil }
        guard origin.url?.absoluteString == gateway.origin else { return nil }
        // A claim token is Crockford base32. Anything else was not printed by a
        // client of ours and is never sent to the gateway.
        let allowed = CharacterSet(charactersIn: "0123456789ABCDEFGHJKMNPQRSTVWXYZ")
        let candidate = fragment.uppercased()
        guard (8...64).contains(candidate.count),
              candidate.unicodeScalars.allSatisfy(allowed.contains) else { return nil }
        token = candidate
    }

    /// The same test against every origin this app knows the gateway by. A
    /// person who signed in on the LAN address reads a QR code that carries the
    /// gateway's public one, and both are this gateway.
    public init?(payload: String, gateways: [GatewayEndpoint]) {
        guard let match = gateways.lazy.compactMap({ PairingClaimLink(payload: payload, gateway: $0) }).first
        else { return nil }
        self = match
    }
}
