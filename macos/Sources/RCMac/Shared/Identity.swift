import Foundation

/// `web/src/lib/identity.ts`: who is signed in and where, in the two forms the
/// app draws them — the initials in a circle and the gateway's host. Both are
/// pure, and the top bar and the Settings header read the same rule, so the two
/// never disagree (`docs/DESIGN.md` § "The Settings screen").
public enum Identity {
    /// One or two characters for the circle: the first letter of each of the
    /// first two parts, or the first two letters of a single part. A script
    /// without letter case — Chinese above all — reads as one character rather
    /// than two. The parts are what the gateway lets a username be split by:
    /// `.`, `_`, `-` and whitespace.
    public static func initials(_ username: String) -> String {
        let parts = username.split { $0 == "." || $0 == "_" || $0 == "-" || $0.isWhitespace }
        guard let first = parts.first else { return "?" }
        if parts.count > 1, let second = parts[1].first, let head = first.first {
            return "\(head)\(second)".uppercased()
        }
        guard let head = first.unicodeScalars.first, head.isASCII,
              CharacterSet.alphanumerics.contains(head) else {
            return String(first.prefix(1))
        }
        return String(first.prefix(2)).uppercased()
    }

    /// The gateway origin without its scheme and without any path: `host[:port]`.
    public static func gatewayHost(_ origin: String) -> String {
        var text = origin
        if let scheme = text.range(of: "^[A-Za-z][A-Za-z0-9+.-]*://", options: .regularExpression) {
            text.removeSubrange(scheme)
        }
        if let slash = text.firstIndex(of: "/") { text = String(text[..<slash]) }
        return text
    }
}
