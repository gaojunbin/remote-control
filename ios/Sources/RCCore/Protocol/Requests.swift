import Foundation

/// Bounds the device enforces. The app checks them first so a rejected send
/// never costs the user their draft.
public enum RequestLimits {
    public static let maxTextBytes = 64 * 1024
    public static let maxAttachments = 8
    public static let maxAttachmentBytes = 6 * 1024 * 1024
    public static let historyPageSize = 200
    public static let maxHistoryPageSize = 1000
}

/// An attachment travelling to the device, base64 encoded.
public struct OutboundAttachment: Sendable, Hashable, Identifiable {
    /// Two files can share a name; a row and its remove button cannot.
    public let id: UUID
    public let name: String
    public let mime: String
    public let data: Data

    public init(id: UUID = UUID(), name: String, mime: String, data: Data) {
        self.id = id
        self.name = name
        self.mime = mime
        self.data = data
    }

    public var info: AttachmentInfo { AttachmentInfo(name: name, mime: mime, size: data.count) }

    var json: JSONValue {
        .object(["name": .string(name), "mime": .string(mime), "data_base64": .string(data.base64EncodedString())])
    }
}

public enum AttachmentError: Error, Equatable, Sendable, LocalizedError {
    case tooMany(Int)
    case tooLarge(name: String)
    case textTooLong

    public var errorDescription: String? {
        switch self {
        case .tooMany(let limit): L10n.string("Attach at most %lld files to one message.", limit)
        case .tooLarge(let name): L10n.string("%@ is larger than 6 MB. Attach a smaller file.", name)
        case .textTooLong: L10n.string("That message is longer than 64 KB. Shorten it or attach a file.")
        }
    }
}

/// One request frame, ready to send. The `id` is chosen once and reused on a
/// retry so a device can recognise the duplicate and reply with the original
/// result instead of sending the message twice.
public struct GatewayRequest: Sendable, Hashable {
    public let id: String
    public let type: String
    public let body: [String: JSONValue]
    /// Requests the gateway answers locally can time out faster than forwarded ones.
    public let expectsReply: Bool

    public init(id: String = UUID().uuidString, type: String,
                body: [String: JSONValue] = [:], expectsReply: Bool = true) {
        self.id = id
        self.type = type
        self.body = body
        self.expectsReply = expectsReply
    }

    public var json: JSONValue {
        var object = body
        object["type"] = .string(type)
        if expectsReply { object["id"] = .string(id) }
        return .object(object)
    }

    public func encoded() throws -> Data { try JSONEncoder().encode(json) }
}

/// Amendment A21: what a request asks of a session's speed. An optional cannot
/// say it, because "leave the tier alone" and "put it back to standard" are
/// different requests and the second one is `speed: null` on the wire.
public enum SpeedChange: Codable, Sendable, Hashable {
    case tier(String)
    case standard

    /// The tier id, or nil for the standard speed.
    public init(id: String?) { self = id.map(SpeedChange.tier) ?? .standard }

    public var id: String? {
        if case .tier(let value) = self { return value }
        return nil
    }

    var json: JSONValue { id.map(JSONValue.string) ?? .null }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        self = container.decodeNil() ? .standard : .tier(try container.decode(String.self))
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .tier(let id): try container.encode(id)
        case .standard: try container.encodeNil()
        }
    }
}

extension GatewayRequest {
    public static func pong() -> GatewayRequest {
        GatewayRequest(type: "pong", expectsReply: false)
    }

    public static func subscribe(sessionID: String, sinceSeq: Int? = nil) -> GatewayRequest {
        var body: [String: JSONValue] = ["session_id": .string(sessionID)]
        if let sinceSeq { body["since_seq"] = .integer(Int64(sinceSeq)) }
        return GatewayRequest(type: "session.subscribe", body: body)
    }

    public static func unsubscribe(sessionID: String) -> GatewayRequest {
        GatewayRequest(type: "session.unsubscribe", body: ["session_id": .string(sessionID)], expectsReply: false)
    }

    public static func createSession(deviceID: String, agent: String, cwd: String,
                                     model: String? = nil, permissionMode: String? = nil,
                                     effort: String? = nil, speed: SpeedChange? = nil,
                                     worktree: Bool? = nil,
                                     firstMessage: String? = nil, title: String? = nil) -> GatewayRequest {
        var body: [String: JSONValue] = [
            "device_id": .string(deviceID), "agent": .string(agent), "cwd": .string(cwd)
        ]
        if let model { body["model"] = .string(model) }
        if let permissionMode { body["permission_mode"] = .string(permissionMode) }
        if let effort { body["effort"] = .string(effort) }
        if let speed { body["speed"] = speed.json }
        if let worktree { body["worktree"] = .bool(worktree) }
        if let firstMessage, !firstMessage.isEmpty { body["first_message"] = .string(firstMessage) }
        if let title, !title.isEmpty { body["title"] = .string(title) }
        return GatewayRequest(type: "session.create", body: body)
    }

    public static func send(id: String = UUID().uuidString, sessionID: String, text: String,
                            attachments: [OutboundAttachment] = [],
                            mode: SendMode = .auto) throws -> GatewayRequest {
        guard text.utf8.count <= RequestLimits.maxTextBytes else { throw AttachmentError.textTooLong }
        guard attachments.count <= RequestLimits.maxAttachments else {
            throw AttachmentError.tooMany(RequestLimits.maxAttachments)
        }
        if let oversize = attachments.first(where: { $0.data.count > RequestLimits.maxAttachmentBytes }) {
            throw AttachmentError.tooLarge(name: oversize.name)
        }
        var body: [String: JSONValue] = [
            "session_id": .string(sessionID), "text": .string(text), "mode": .string(mode.rawValue)
        ]
        if !attachments.isEmpty { body["attachments"] = .array(attachments.map(\.json)) }
        return GatewayRequest(id: id, type: "session.send", body: body)
    }

    public static func stop(sessionID: String) -> GatewayRequest {
        GatewayRequest(type: "session.stop", body: ["session_id": .string(sessionID)])
    }

    public static func approve(sessionID: String, requestID: String,
                               optionID: String, message: String? = nil) -> GatewayRequest {
        var body: [String: JSONValue] = [
            "session_id": .string(sessionID), "request_id": .string(requestID), "option_id": .string(optionID)
        ]
        if let message, !message.isEmpty { body["message"] = .string(message) }
        return GatewayRequest(type: "session.approve", body: body)
    }

    public static func answer(sessionID: String, requestID: String,
                              answers: [String: QuestionAnswer]) throws -> GatewayRequest {
        GatewayRequest(type: "session.answer", body: [
            "session_id": .string(sessionID),
            "request_id": .string(requestID),
            "answers": try .encode(answers)
        ])
    }

    public static func set(sessionID: String, model: String? = nil, permissionMode: String? = nil,
                           effort: String? = nil, speed: SpeedChange? = nil,
                           title: String? = nil) -> GatewayRequest {
        var body: [String: JSONValue] = ["session_id": .string(sessionID)]
        if let model { body["model"] = .string(model) }
        if let permissionMode { body["permission_mode"] = .string(permissionMode) }
        if let effort { body["effort"] = .string(effort) }
        if let speed { body["speed"] = speed.json }
        if let title { body["title"] = .string(title) }
        return GatewayRequest(type: "session.set", body: body)
    }

    public static func history(sessionID: String, beforeSeq: Int? = nil,
                               limit: Int = RequestLimits.historyPageSize) -> GatewayRequest {
        var body: [String: JSONValue] = [
            "session_id": .string(sessionID),
            "limit": .integer(Int64(min(max(1, limit), RequestLimits.maxHistoryPageSize)))
        ]
        if let beforeSeq { body["before_seq"] = .integer(Int64(beforeSeq)) }
        return GatewayRequest(type: "session.history", body: body)
    }

    public static func block(sessionID: String, blockID: String) -> GatewayRequest {
        GatewayRequest(type: "session.block",
                       body: ["session_id": .string(sessionID), "block_id": .string(blockID)])
    }

    public static func queueRemove(sessionID: String, queuedID: String) -> GatewayRequest {
        GatewayRequest(type: "session.queue_remove",
                       body: ["session_id": .string(sessionID), "queued_id": .string(queuedID)])
    }

    public static func takeover(sessionID: String) -> GatewayRequest {
        GatewayRequest(type: "session.takeover", body: ["session_id": .string(sessionID)])
    }

    public static func archive(sessionID: String, archived: Bool) -> GatewayRequest {
        GatewayRequest(type: "session.archive",
                       body: ["session_id": .string(sessionID), "archived": .bool(archived)])
    }

    public static func delete(sessionID: String) -> GatewayRequest {
        GatewayRequest(type: "session.delete", body: ["session_id": .string(sessionID)])
    }

    public static func dirs(deviceID: String, path: String? = nil) -> GatewayRequest {
        var body: [String: JSONValue] = ["device_id": .string(deviceID)]
        if let path { body["path"] = .string(path) }
        return GatewayRequest(type: "device.dirs", body: body)
    }

    public static func git(deviceID: String, path: String) -> GatewayRequest {
        GatewayRequest(type: "device.git", body: ["device_id": .string(deviceID), "path": .string(path)])
    }

    public static func agents(deviceID: String) -> GatewayRequest {
        GatewayRequest(type: "device.agents", body: ["device_id": .string(deviceID)])
    }

    /// Amendment A22: fetch exactly the build the gateway serves, install it and
    /// restart. The build travels with the request so a device that has already
    /// moved on refuses it rather than reinstalling what it runs.
    public static func updateDevice(deviceID: String, build: String) -> GatewayRequest {
        GatewayRequest(type: "device.update",
                       body: ["device_id": .string(deviceID), "build": .string(build)])
    }
}
