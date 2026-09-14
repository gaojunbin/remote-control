import Foundation

/// `GET /api/health`, the one route an app reads before it has an account.
///
/// `registrationOpen` is the only reason the sign-in form calls it: it decides
/// whether "Create an account" is offered at all (protocol 3.1, A24).
public struct HealthResponse: Codable, Sendable, Hashable {
    public let version: String
    public let protocolVersion: Int
    public let registrationOpen: Bool
    /// Amendment A31: the oldest app build this gateway works with. This route
    /// is the one that answers before anyone has a credential, so an app too
    /// old for the gateway is stopped at the sign-in form.
    public let apps: AppsInfo?

    public init(version: String, protocolVersion: Int, registrationOpen: Bool,
                apps: AppsInfo? = nil) {
        self.version = version
        self.protocolVersion = protocolVersion
        self.registrationOpen = registrationOpen
        self.apps = apps
    }

    enum CodingKeys: String, CodingKey {
        case version, auth, apps
        case protocolVersion = "protocol"
    }

    private enum AuthKeys: String, CodingKey {
        case registrationOpen = "registration_open"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        version = try values.decodeIfPresent(String.self, forKey: .version) ?? ""
        protocolVersion = try values.decodeIfPresent(Int.self, forKey: .protocolVersion) ?? 0
        let auth = try? values.nestedContainer(keyedBy: AuthKeys.self, forKey: .auth)
        registrationOpen = (try? auth?.decodeIfPresent(Bool.self, forKey: .registrationOpen)) ?? false
        apps = try values.decodeIfPresent(AppsInfo.self, forKey: .apps)
    }

    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(version, forKey: .version)
        try values.encode(protocolVersion, forKey: .protocolVersion)
        try values.encodeIfPresent(apps, forKey: .apps)
        var auth = values.nestedContainer(keyedBy: AuthKeys.self, forKey: .auth)
        try auth.encode(registrationOpen, forKey: .registrationOpen)
    }
}

/// `GET /api/users` (protocol 3.9). The switch at the top of the screen and the
/// rows under it arrive together, so the page never draws one without the other.
public struct UserListResponse: Codable, Sendable, Hashable {
    public let users: [UserRecord]
    public let registrationOpen: Bool

    public init(users: [UserRecord], registrationOpen: Bool) {
        self.users = users
        self.registrationOpen = registrationOpen
    }

    enum CodingKeys: String, CodingKey {
        case users
        case registrationOpen = "registration_open"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        users = try values.decodeIfPresent([UserRecord].self, forKey: .users) ?? []
        registrationOpen = try values.decodeIfPresent(Bool.self, forKey: .registrationOpen) ?? false
    }
}

/// `POST /api/users` and `PATCH /api/users/{username}` (protocol 3.9).
public struct UserResponse: Codable, Sendable, Hashable {
    public let user: UserRecord
    public init(user: UserRecord) { self.user = user }
}

/// `PATCH /api/registration` (protocol 3.9).
public struct RegistrationResponse: Codable, Sendable, Hashable {
    public let open: Bool
    public init(open: Bool) { self.open = open }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        open = try values.decodeIfPresent(Bool.self, forKey: .open) ?? false
    }
}
