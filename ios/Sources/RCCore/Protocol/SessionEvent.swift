import Foundation

/// The typed body of a session event, selected by the wire `kind`.
///
/// A kind this build does not know is preserved verbatim as `.unknown`, so a
/// newer device can emit a new block type without the app losing the event or
/// the ordering around it.
public enum SessionEventBody: Sendable, Equatable {
    case userMessage(UserMessagePayload)
    case assistantText(StreamTextPayload)
    case thinking(StreamTextPayload)
    case toolCall(ToolCallPayload)
    case todos(TodosPayload)
    case approval(ApprovalPayload)
    case question(QuestionPayload)
    case turnStarted(TurnStartedPayload)
    case turnCompleted(TurnCompletedPayload)
    case status(StatusPayload)
    case meta(MetaPayload)
    case queue(QueuePayload)
    case notice(NoticePayload)
    case error(ErrorPayload)
    case unknown(kind: String, raw: JSONValue)
}

/// One entry in a session timeline, as produced by the owning device.
///
/// `seq` is per-session and monotonically increasing; it is the replay cursor
/// and it wins over `ts` for display order, because `ts` is the device clock.
public struct SessionEvent: Sendable, Equatable, Identifiable {
    public static let userMessageKind = "user_message"
    public static let assistantTextKind = "assistant_text"
    public static let thinkingKind = "thinking"
    public static let toolCallKind = "tool_call"
    public static let todosKind = "todos"
    public static let approvalKind = "approval"
    public static let questionKind = "question"
    public static let turnStartedKind = "turn_started"
    public static let turnCompletedKind = "turn_completed"
    public static let statusKind = "status"
    public static let metaKind = "meta"
    public static let queueKind = "queue"
    public static let noticeKind = "notice"
    public static let errorKind = "error"

    public let seq: Int
    /// Amendment A8: the seq at which this block first appeared. A block that
    /// keeps streaming gets a rising `seq` but keeps its place in the
    /// transcript, which is what this pins down.
    public let firstSeq: Int?
    public let ts: Int64
    public let kind: String
    public let blockID: String?
    public let parentBlockID: String?
    public let body: SessionEventBody
    /// The frame exactly as received, so unknown fields survive a round trip.
    public let raw: JSONValue

    public var id: Int { seq }

    /// Where this event's block belongs in the transcript.
    public var orderSeq: Int { firstSeq ?? seq }

    /// Block events replace each other by `block_id`; non-block events stand alone.
    public var isBlock: Bool { blockID != nil }

    /// True while this event still expects further deltas for the same block.
    public var isStreaming: Bool {
        switch body {
        case .assistantText(let payload), .thinking(let payload): !payload.done
        case .toolCall(let payload): payload.status == .running
        default: false
        }
    }

    public init(seq: Int, ts: Int64, kind: String, blockID: String? = nil,
                parentBlockID: String? = nil, firstSeq: Int? = nil,
                body: SessionEventBody, raw: JSONValue? = nil) {
        self.seq = seq
        self.firstSeq = firstSeq
        self.ts = ts
        self.kind = kind
        self.blockID = blockID
        self.parentBlockID = parentBlockID
        self.body = body
        self.raw = raw ?? .object([:])
    }
}

extension SessionEvent: Codable {
    private enum Keys: String, CodingKey {
        case seq, ts, kind
        case blockID = "block_id"
        case parentBlockID = "parent_block_id"
        case firstSeq = "first_seq"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: Keys.self)
        seq = try values.decodeIfPresent(Int.self, forKey: .seq) ?? 0
        ts = try values.decodeIfPresent(Int64.self, forKey: .ts) ?? 0
        let kind = try values.decode(String.self, forKey: .kind)
        self.kind = kind
        blockID = try values.decodeIfPresent(String.self, forKey: .blockID)
        parentBlockID = try values.decodeIfPresent(String.self, forKey: .parentBlockID)
        firstSeq = try values.decodeIfPresent(Int.self, forKey: .firstSeq)
        let raw = try JSONValue(from: decoder)
        self.raw = raw
        switch kind {
        case Self.userMessageKind: body = .userMessage(try UserMessagePayload(from: decoder))
        case Self.assistantTextKind: body = .assistantText(try StreamTextPayload(from: decoder))
        case Self.thinkingKind: body = .thinking(try StreamTextPayload(from: decoder))
        case Self.toolCallKind: body = .toolCall(try ToolCallPayload(from: decoder))
        case Self.todosKind: body = .todos(try TodosPayload(from: decoder))
        case Self.approvalKind: body = .approval(try ApprovalPayload(from: decoder))
        case Self.questionKind: body = .question(try QuestionPayload(from: decoder))
        case Self.turnStartedKind: body = .turnStarted(try TurnStartedPayload(from: decoder))
        case Self.turnCompletedKind: body = .turnCompleted(try TurnCompletedPayload(from: decoder))
        case Self.statusKind: body = .status(try StatusPayload(from: decoder))
        case Self.metaKind: body = .meta(try MetaPayload(from: decoder))
        case Self.queueKind: body = .queue(try QueuePayload(from: decoder))
        case Self.noticeKind: body = .notice(try NoticePayload(from: decoder))
        case Self.errorKind: body = .error(try ErrorPayload(from: decoder))
        default: body = .unknown(kind: kind, raw: raw)
        }
    }

    public func encode(to encoder: Encoder) throws {
        var object: [String: JSONValue] = [:]
        for (key, value) in try Self.payloadObject(body) { object[key] = value }
        // A field this build does not model still belongs on the wire.
        for (key, value) in raw.objectValue ?? [:] where object[key] == nil { object[key] = value }
        object["seq"] = .integer(Int64(seq))
        object["ts"] = .integer(ts)
        object["kind"] = .string(kind)
        if let blockID { object["block_id"] = .string(blockID) }
        if let parentBlockID { object["parent_block_id"] = .string(parentBlockID) }
        if let firstSeq { object["first_seq"] = .integer(Int64(firstSeq)) }
        try JSONValue.object(object).encode(to: encoder)
    }

    private static func payloadObject(_ body: SessionEventBody) throws -> [String: JSONValue] {
        let value: JSONValue = switch body {
        case .userMessage(let payload): try .encode(payload)
        case .assistantText(let payload), .thinking(let payload): try .encode(payload)
        case .toolCall(let payload): try .encode(payload)
        case .todos(let payload): try .encode(payload)
        case .approval(let payload): try .encode(payload)
        case .question(let payload): try .encode(payload)
        case .turnStarted(let payload): try .encode(payload)
        case .turnCompleted(let payload): try .encode(payload)
        case .status(let payload): try .encode(payload)
        case .meta(let payload): try .encode(payload)
        case .queue(let payload): try .encode(payload)
        case .notice(let payload): try .encode(payload)
        case .error(let payload): try .encode(payload)
        case .unknown(_, let raw): raw
        }
        return value.objectValue ?? [:]
    }
}

extension SessionEvent {
    public var userMessage: UserMessagePayload? {
        if case .userMessage(let payload) = body { payload } else { nil }
    }
    public var streamText: StreamTextPayload? {
        switch body {
        case .assistantText(let payload), .thinking(let payload): payload
        default: nil
        }
    }
    public var toolCall: ToolCallPayload? {
        if case .toolCall(let payload) = body { payload } else { nil }
    }
    public var approval: ApprovalPayload? {
        if case .approval(let payload) = body { payload } else { nil }
    }
    public var question: QuestionPayload? {
        if case .question(let payload) = body { payload } else { nil }
    }
    public var todos: TodosPayload? {
        if case .todos(let payload) = body { payload } else { nil }
    }
}
