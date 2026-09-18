import Foundation

/// The gateway as the Settings header prints it: the host, with the port where
/// there is one, and no scheme.
///
/// The scheme is the app's business and not the reader's — every gateway is
/// https but a development one — so the line under the username says
/// `rc.example.com` and the header has room for the name beside it.
public enum GatewayHost {
    public static func of(_ origin: String) -> String {
        guard let parts = URLComponents(string: origin), let host = parts.host else {
            return stripScheme(origin)
        }
        guard let port = parts.port else { return host }
        return "\(host):\(port)"
    }

    /// What is left of an origin no `URLComponents` could read.
    private static func stripScheme(_ origin: String) -> String {
        var text = origin
        if let separator = text.range(of: "://") { text = String(text[separator.upperBound...]) }
        while text.hasSuffix("/") { text.removeLast() }
        return text
    }
}
