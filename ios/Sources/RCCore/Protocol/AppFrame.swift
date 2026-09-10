import Foundation

public struct GatewayErrorCode: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let badRequest = GatewayErrorCode(rawValue: "bad_request")
    public static let unauthorized = GatewayErrorCode(rawValue: "unauthorized")
    public static let forbidden = GatewayErrorCode(rawValue: "forbidden")
    public static let notFound = GatewayErrorCode(rawValue: "not_found")
    public static let deviceOffline = GatewayErrorCode(rawValue: "device_offline")
    public static let agentUnavailable = GatewayErrorCode(rawValue: "agent_unavailable")
    public static let conflict = GatewayErrorCode(rawValue: "conflict")
    public static let timeout = GatewayErrorCode(rawValue: "timeout")
    public static let internalError = GatewayErrorCode(rawValue: "internal")
    public static let unsupported = GatewayErrorCode(rawValue: "unsupported")
    public static let tooLarge = GatewayErrorCode(rawValue: "too_large")
}

/// The error object carried by an unsuccessful `reply`.
public struct GatewayErrorBody: Codable, Sendable, Hashable, Error {
    public let code: GatewayErrorCode
    public let message: String

    public init(code: GatewayErrorCode, message: String) {
        self.code = code
        self.message = message
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        code = try values.decodeIfPresent(GatewayErrorCode.self, forKey: .code) ?? .internalError
        message = try values.decodeIfPresent(String.self, forKey: .message) ?? code.rawValue
    }
}

public struct UserIdentity: Codable, Sendable, Hashable {
    public let username: String
    public init(username: String) { self.username = username }
}

public struct STTConfig: Codable, Sendable, Hashable {
    public let enabled: Bool
    public let languages: [String]

    public init(enabled: Bool, languages: [String]) {
        self.enabled = enabled
        self.languages = languages
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try values.decodeIfPresent(Bool.self, forKey: .enabled) ?? false
        languages = try values.decodeIfPresent([String].self, forKey: .languages) ?? []
    }

    public static let disabled = STTConfig(enabled: false, languages: [])
}

/// The first frame the gateway sends on `/ws/app`.
public struct HelloFrame: Codable, Sendable, Hashable {
    public let protocolVersion: Int
    public let gatewayVersion: String
    public let user: UserIdentity
    public let devices: [Device]
    public let sessions: [Session]
    public let stt: STTConfig
    public let serverTime: Int64

    public init(protocolVersion: Int, gatewayVersion: String, user: UserIdentity,
                devices: [Device], sessions: [Session], stt: STTConfig, serverTime: Int64) {
        self.protocolVersion = protocolVersion
        self.gatewayVersion = gatewayVersion
        self.user = user
        self.devices = devices
        self.sessions = sessions
        self.stt = stt
        self.serverTime = serverTime
    }

    enum CodingKeys: String, CodingKey {
        case user, devices, sessions, stt
        case protocolVersion = "protocol"
        case gatewayVersion = "gateway_version"
        case serverTime = "server_time"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        protocolVersion = try values.decodeIfPresent(Int.self, forKey: .protocolVersion) ?? 0
        gatewayVersion = try values.decodeIfPresent(String.self, forKey: .gatewayVersion) ?? ""
        user = try values.decodeIfPresent(UserIdentity.self, forKey: .user) ?? UserIdentity(username: "")
        devices = try values.decodeIfPresent([Device].self, forKey: .devices) ?? []
        sessions = try values.decodeIfPresent([Session].self, forKey: .sessions) ?? []
        stt = try values.decodeIfPresent(STTConfig.self, forKey: .stt) ?? .disabled
        serverTime = try values.decodeIfPresent(Int64.self, forKey: .serverTime) ?? 0
    }
}

public struct PairingStep: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let waiting = PairingStep(rawValue: "waiting")
    public static let enrolled = PairingStep(rawValue: "enrolled")
    public static let online = PairingStep(rawValue: "online")
    public static let agents = PairingStep(rawValue: "agents")

    /// Ordinal used to light up the checklist in the "Add device" sheet.
    public var order: Int {
        switch self {
        case .waiting: 0
        case .enrolled: 1
        case .online: 2
        case .agents: 3
        default: 0
        }
    }
}

public struct PairingProgress: Codable, Sendable, Hashable {
    public let code: String
    public let step: PairingStep
    public let device: Device?

    public init(code: String, step: PairingStep, device: Device? = nil) {
        self.code = code
        self.step = step
        self.device = device
    }
}

/// A frame arriving on `/ws/app`.
public enum AppFrame: Sendable {
    case hello(HelloFrame)
    case deviceUpdated(Device)
    case deviceRemoved(deviceID: String)
    case sessionUpdated(Session)
    case sessionRemoved(sessionID: String, deviceID: String?)
    case sessionEvent(sessionID: String, deviceID: String?, event: SessionEvent)
    case pairingProgress(PairingProgress)
    case ping
    case reply(id: String, result: Result<JSONValue, GatewayErrorBody>)
    case unknown(type: String, raw: JSONValue)

    public init(json: JSONValue) throws {
        guard let object = json.objectValue, let type = object.string("type") else {
            throw ProtocolFailure.malformed("frame has no type")
        }
        switch type {
        case "hello":
            self = .hello(try json.decode(HelloFrame.self))
        case "device.updated":
            guard let device = object["device"] else { throw ProtocolFailure.malformed("device.updated") }
            self = .deviceUpdated(try device.decode(Device.self))
        case "device.removed":
            guard let id = object.string("device_id") else { throw ProtocolFailure.malformed("device.removed") }
            self = .deviceRemoved(deviceID: id)
        case "session.updated":
            guard let session = object["session"] else { throw ProtocolFailure.malformed("session.updated") }
            self = .sessionUpdated(try session.decode(Session.self))
        case "session.removed":
            guard let sessionID = object.string("session_id") else {
                throw ProtocolFailure.malformed("session.removed")
            }
            self = .sessionRemoved(sessionID: sessionID, deviceID: object.string("device_id"))
        case "session.event":
            guard let sessionID = object.string("session_id"), let event = object["event"] else {
                throw ProtocolFailure.malformed("session.event")
            }
            self = .sessionEvent(sessionID: sessionID, deviceID: object.string("device_id"),
                                 event: try event.decode(SessionEvent.self))
        case "pairing.progress":
            self = .pairingProgress(try json.decode(PairingProgress.self))
        case "ping":
            self = .ping
        case "reply":
            guard let id = object.string("id") else { throw ProtocolFailure.malformed("reply") }
            if object.bool("ok") == true {
                self = .reply(id: id, result: .success(object["result"] ?? .object([:])))
            } else {
                let error = try (object["error"] ?? .object([:])).decode(GatewayErrorBody.self)
                self = .reply(id: id, result: .failure(error))
            }
        default:
            self = .unknown(type: type, raw: json)
        }
    }

    public init(data: Data) throws {
        self = try AppFrame(json: JSONDecoder().decode(JSONValue.self, from: data))
    }
}

public enum ProtocolFailure: Error, Equatable, Sendable, LocalizedError {
    case malformed(String)
    case unsupportedVersion(Int)

    public var errorDescription: String? {
        switch self {
        case .malformed(let detail): "The gateway sent a frame this app could not read (\(detail))."
        case .unsupportedVersion(let version):
            "This app speaks protocol \(RemoteProtocol.version); the gateway speaks \(version). Update both sides."
        }
    }
}
