import Foundation

public struct LoginResponse: Codable, Sendable {
    public let token: String
    public let exp: Int64
    public let user: UserIdentity

    public init(token: String, exp: Int64, user: UserIdentity) {
        self.token = token
        self.exp = exp
        self.user = user
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        token = try values.decode(String.self, forKey: .token)
        exp = try values.decodeIfPresent(Int64.self, forKey: .exp) ?? 0
        user = try values.decodeIfPresent(UserIdentity.self, forKey: .user) ?? UserIdentity(username: "")
    }
}

public struct SessionInfoResponse: Codable, Sendable {
    public let user: UserIdentity
    public let exp: Int64

    public init(user: UserIdentity, exp: Int64) {
        self.user = user
        self.exp = exp
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        user = try values.decodeIfPresent(UserIdentity.self, forKey: .user) ?? UserIdentity(username: "")
        exp = try values.decodeIfPresent(Int64.self, forKey: .exp) ?? 0
    }
}

public struct PushConfig: Codable, Sendable, Hashable {
    public let webEnabled: Bool
    public let apnsEnabled: Bool

    public init(webEnabled: Bool, apnsEnabled: Bool) {
        self.webEnabled = webEnabled
        self.apnsEnabled = apnsEnabled
    }

    enum CodingKeys: String, CodingKey {
        case webEnabled = "web_enabled"
        case apnsEnabled = "apns_enabled"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        webEnabled = try values.decodeIfPresent(Bool.self, forKey: .webEnabled) ?? false
        apnsEnabled = try values.decodeIfPresent(Bool.self, forKey: .apnsEnabled) ?? false
    }

    public static let disabled = PushConfig(webEnabled: false, apnsEnabled: false)
}

public struct GatewayConfig: Codable, Sendable, Hashable {
    public let publicOrigin: String
    public let stt: STTConfig
    public let push: PushConfig
    public let version: String

    public init(publicOrigin: String, stt: STTConfig, push: PushConfig, version: String) {
        self.publicOrigin = publicOrigin
        self.stt = stt
        self.push = push
        self.version = version
    }

    enum CodingKeys: String, CodingKey {
        case stt, push, version
        case publicOrigin = "public_origin"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        publicOrigin = try values.decodeIfPresent(String.self, forKey: .publicOrigin) ?? ""
        stt = try values.decodeIfPresent(STTConfig.self, forKey: .stt) ?? .disabled
        push = try values.decodeIfPresent(PushConfig.self, forKey: .push) ?? .disabled
        version = try values.decodeIfPresent(String.self, forKey: .version) ?? ""
    }

    public static let empty = GatewayConfig(publicOrigin: "", stt: .disabled, push: .disabled, version: "")
}

public struct DeviceListResponse: Codable, Sendable {
    public let devices: [Device]
    public init(devices: [Device]) { self.devices = devices }
}

public struct DeviceResponse: Codable, Sendable {
    public let device: Device
    public init(device: Device) { self.device = device }
}

public struct SessionListResponse: Codable, Sendable {
    public let sessions: [Session]
    public init(sessions: [Session]) { self.sessions = sessions }
}

public struct InstallCommands: Codable, Sendable, Hashable {
    public let macos: String
    public let linux: String

    public init(macos: String, linux: String) {
        self.macos = macos
        self.linux = linux
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        macos = try values.decodeIfPresent(String.self, forKey: .macos) ?? ""
        linux = try values.decodeIfPresent(String.self, forKey: .linux) ?? ""
    }

    public func command(for platform: DevicePlatform) -> String {
        platform == .macos ? macos : linux
    }
}

/// A single-use pairing grant with a ten-minute lifetime.
public struct PairingGrant: Codable, Sendable, Hashable {
    public let code: String
    public let expiresAt: Int64
    public let install: InstallCommands

    public init(code: String, expiresAt: Int64, install: InstallCommands) {
        self.code = code
        self.expiresAt = expiresAt
        self.install = install
    }

    enum CodingKeys: String, CodingKey {
        case code, install
        case expiresAt = "expires_at"
    }
}

public struct APNSRegistration: Codable, Sendable, Hashable {
    public let token: String
    public let environment: String
    public let bundleID: String

    public init(token: String, environment: String, bundleID: String) {
        self.token = token
        self.environment = environment
        self.bundleID = bundleID
    }

    enum CodingKeys: String, CodingKey {
        case token, environment
        case bundleID = "bundle_id"
    }

    /// Lowercase hex, as APNs hands it over, and never longer than a token can be.
    public static func tokenHex(_ data: Data) throws -> String {
        guard !data.isEmpty, data.count <= 512 else { throw TransportError.invalidResponse }
        return data.map { String(format: "%02x", $0) }.joined()
    }
}

/// The `rc` object carried inside a push payload. It never contains prompt
/// text, tool output or file contents.
public struct PushRoute: Codable, Sendable, Hashable {
    public let version: Int
    public let kind: PushKind
    public let deviceID: String
    public let sessionID: String
    public let deviceName: String
    public let title: String

    public init(version: Int = 1, kind: PushKind, deviceID: String,
                sessionID: String, deviceName: String, title: String) {
        self.version = version
        self.kind = kind
        self.deviceID = deviceID
        self.sessionID = sessionID
        self.deviceName = deviceName
        self.title = title
    }

    enum CodingKeys: String, CodingKey {
        case kind, title
        case version = "v"
        case deviceID = "device_id"
        case sessionID = "session_id"
        case deviceName = "device_name"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        version = try values.decodeIfPresent(Int.self, forKey: .version) ?? 0
        kind = try values.decodeIfPresent(PushKind.self, forKey: .kind) ?? .error
        deviceID = try values.decode(String.self, forKey: .deviceID)
        sessionID = try values.decode(String.self, forKey: .sessionID)
        deviceName = try values.decodeIfPresent(String.self, forKey: .deviceName) ?? ""
        title = try values.decodeIfPresent(String.self, forKey: .title) ?? ""
    }

    /// Parse an APNs `userInfo` dictionary that was serialized before crossing
    /// an actor boundary. A payload larger than 4 KiB is rejected outright.
    public init(userInfo data: Data) throws {
        guard data.count <= 4096 else { throw TransportError.responseTooLarge }
        let json = try JSONDecoder().decode(JSONValue.self, from: data)
        guard let route = json["rc"] else { throw ProtocolFailure.malformed("push payload has no rc object") }
        self = try route.decode(PushRoute.self)
        guard version == 1 else { throw ProtocolFailure.unsupportedVersion(version) }
    }

    /// `remotecontrol://session?device=<id>&id=<session_id>`
    public var deepLink: URL? {
        SessionLink(deviceID: deviceID, sessionID: sessionID).url
    }
}

/// The app's own deep link into one session.
public struct SessionLink: Sendable, Hashable {
    public static let scheme = "remotecontrol"
    public let deviceID: String
    public let sessionID: String

    public init(deviceID: String, sessionID: String) {
        self.deviceID = deviceID
        self.sessionID = sessionID
    }

    public init?(url: URL) {
        guard url.scheme?.lowercased() == Self.scheme, url.host == "session",
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let device = parts.queryItems?.first(where: { $0.name == "device" })?.value, !device.isEmpty,
              let session = parts.queryItems?.first(where: { $0.name == "id" })?.value, !session.isEmpty else {
            return nil
        }
        deviceID = device
        sessionID = session
    }

    public var url: URL? {
        var parts = URLComponents()
        parts.scheme = Self.scheme
        parts.host = "session"
        parts.queryItems = [.init(name: "device", value: deviceID), .init(name: "id", value: sessionID)]
        return parts.url
    }
}
