import Foundation

/// The web's routes (`web/src/App.tsx`), as places in the app. There is no
/// pairing-link route: a host's QR code encodes a URL, which opens in the phone
/// app or a browser (`docs/DESIGN.md` § "The Mac app").
public enum Route: Hashable, Sendable {
    /// `/` and every path no route claims: decides where an open lands.
    case landing
    case login
    case devices
    /// `/devices/:deviceId` (A33).
    case device(id: String)
    /// `/devices/:deviceId/terminal` (A38), full window.
    case terminal(deviceId: String)
    case sessions
    /// `/sessions/:deviceId/:sessionId`, full window.
    case chat(deviceId: String, sessionId: String)
    case settings
    /// `/users` (A24), the admin's.
    case users

    /// The web path of this place, which is how a scenario or a test can name it.
    public var path: String {
        switch self {
        case .landing: "/"
        case .login: "/login"
        case .devices: "/devices"
        case .device(let id): "/devices/\(Self.encode(id))"
        case .terminal(let deviceId): "/devices/\(Self.encode(deviceId))/terminal"
        case .sessions: "/sessions"
        case .chat(let deviceId, let sessionId): "/sessions/\(Self.encode(deviceId))/\(Self.encode(sessionId))"
        case .settings: "/settings"
        case .users: "/users"
        }
    }

    /// The place a web path names; a path no route claims lands, as `*` does.
    public init(path: String) {
        let parts = path.split(separator: "/", omittingEmptySubsequences: true).map {
            String($0).removingPercentEncoding ?? String($0)
        }
        switch parts.count {
        case 1:
            switch parts[0] {
            case "login": self = .login
            case "devices": self = .devices
            case "sessions": self = .sessions
            case "settings": self = .settings
            case "users": self = .users
            default: self = .landing
            }
        case 2 where parts[0] == "devices": self = .device(id: parts[1])
        case 3 where parts[0] == "devices" && parts[2] == "terminal": self = .terminal(deviceId: parts[1])
        case 3 where parts[0] == "sessions": self = .chat(deviceId: parts[1], sessionId: parts[2])
        default: self = .landing
        }
    }

    /// Drawn inside the topbar layout, as the web's `AppLayout` routes are.
    public var isInLayout: Bool {
        switch self {
        case .devices, .device, .sessions, .settings, .users: true
        case .landing, .login, .terminal, .chat: false
        }
    }

    /// One of the three tabs the topbar marks, when this place is under one.
    /// `NavLink` marks a tab for its path and every path below it.
    public var tab: Route? {
        switch self {
        case .devices, .device, .terminal: .devices
        case .sessions, .chat: .sessions
        case .settings: .settings
        default: nil
        }
    }

    private static func encode(_ part: String) -> String {
        part.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(["/"])) ?? part
    }
}
