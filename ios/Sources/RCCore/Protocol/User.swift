import Foundation

/// What the account routes of protocol 3.9 are gated on (A24).
///
/// A `WireEnum` rather than a closed enumeration: a role this build has never
/// heard of decodes rather than failing, and `isAdmin` answers false for it, so
/// a newer gateway can name a role without an older app showing it the admin's
/// screens by accident.
public struct UserRole: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let admin = UserRole(rawValue: "admin")
    public static let member = UserRole(rawValue: "member")

    /// The one question the app asks of a role. Anything unknown is not admin.
    public var isAdmin: Bool { self == .admin }

    /// The word under the username in Settings and on a Users row.
    public var title: String {
        switch self {
        case .admin: L10n.string("Admin")
        case .member: L10n.string("Member")
        default: rawValue
        }
    }
}

/// Whether an account can sign in. A disabled one cannot, and its devices are
/// refused until it is enabled again (A24).
public struct UserState: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let active = UserState(rawValue: "active")
    public static let disabled = UserState(rawValue: "disabled")

    public var title: String {
        switch self {
        case .active: L10n.string("Active")
        case .disabled: L10n.string("Disabled")
        default: rawValue
        }
    }
}

/// The rules an account is made under, written once. The gateway enforces them;
/// the app repeats the password length because a form that offers a button it
/// knows will be refused is worse than one that waits.
public enum AccountRules {
    /// The operator's account: its password is the gateway's `RC_PASSWORD`, and
    /// it cannot be disabled, demoted, re-passworded or deleted.
    public static let operatorUsername = "admin"
    public static let passwordLength = 8...128

    public static func isPasswordLongEnough(_ password: String) -> Bool {
        password.count >= passwordLength.lowerBound
    }
}

/// The account an app signed in as: `LoginResponse.user`, `AuthSessionResponse.user`
/// and the app `hello`'s `user` (protocol 4.10).
public struct UserIdentity: Codable, Sendable, Hashable {
    public let username: String
    public let role: UserRole

    public init(username: String, role: UserRole = .member) {
        self.username = username
        self.role = role
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        username = try values.decodeIfPresent(String.self, forKey: .username) ?? ""
        role = try values.decodeIfPresent(UserRole.self, forKey: .role) ?? .member
    }
}

/// One account as the admin lists it (protocol 4.10): the same account, with
/// its state, its timestamps and how many devices it has enrolled.
public struct UserRecord: Codable, Sendable, Hashable, Identifiable {
    public let username: String
    public let role: UserRole
    public let state: UserState
    public let createdAt: Int64
    public let lastLoginAt: Int64?
    public let devices: Int

    public var id: String { username }

    /// The operator's row, which offers no actions at all.
    public var isOperator: Bool { username == AccountRules.operatorUsername }

    public var isActive: Bool { state == .active }

    public init(username: String, role: UserRole, state: UserState,
                createdAt: Int64, lastLoginAt: Int64?, devices: Int) {
        self.username = username
        self.role = role
        self.state = state
        self.createdAt = createdAt
        self.lastLoginAt = lastLoginAt
        self.devices = devices
    }

    enum CodingKeys: String, CodingKey {
        case username, role, state, devices
        case createdAt = "created_at"
        case lastLoginAt = "last_login_at"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        username = try values.decodeIfPresent(String.self, forKey: .username) ?? ""
        role = try values.decodeIfPresent(UserRole.self, forKey: .role) ?? .member
        state = try values.decodeIfPresent(UserState.self, forKey: .state) ?? .active
        createdAt = try values.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        lastLoginAt = try values.decodeIfPresent(Int64.self, forKey: .lastLoginAt)
        devices = try values.decodeIfPresent(Int.self, forKey: .devices) ?? 0
    }

    /// `role · state` for the meta line.
    public var roleAndState: String { "\(role.title) · \(state.title)" }

    /// "2 devices", or nothing at all when the account has enrolled none.
    public var deviceSummary: String {
        devices == 0 ? "" : L10n.string(devices == 1 ? "%lld device" : "%lld devices", devices)
    }

    /// The last sign-in as a relative time, or "never".
    public func lastLoginSummary(now: Date = Date()) -> String {
        guard let lastLoginAt, lastLoginAt > 0 else { return L10n.string("never") }
        return RelativeTime.short(since: lastLoginAt, now: now)
    }

    /// The identity this record signs in as, used where the two meet.
    public var identity: UserIdentity { UserIdentity(username: username, role: role) }
}
