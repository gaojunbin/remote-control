import Foundation

/// Attachment metadata carried by a `user_message` event. The bytes themselves
/// only travel outbound, in `session.send`.
public struct AttachmentInfo: Codable, Sendable, Hashable {
    public let name: String
    public let mime: String
    public let size: Int

    public init(name: String, mime: String, size: Int) {
        self.name = name
        self.mime = mime
        self.size = size
    }
}

public struct UserMessagePayload: Codable, Sendable, Hashable {
    public let text: String
    public let attachments: [AttachmentInfo]
    public let source: EventSource
    /// Amendment A10: set only on `shared` sessions. Absent means the message
    /// was an ordinary prompt that reached the agent directly.
    public let delivery: MessageDelivery?

    public init(text: String, attachments: [AttachmentInfo] = [], source: EventSource = .remote,
                delivery: MessageDelivery? = nil) {
        self.text = text
        self.attachments = attachments
        self.source = source
        self.delivery = delivery
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        text = try values.decodeIfPresent(String.self, forKey: .text) ?? ""
        attachments = try values.decodeIfPresent([AttachmentInfo].self, forKey: .attachments) ?? []
        source = try values.decodeIfPresent(EventSource.self, forKey: .source) ?? .remote
        delivery = try values.decodeIfPresent(MessageDelivery.self, forKey: .delivery)
    }
}

/// `assistant_text` and `thinking` share the streaming shape: a `delta` appends
/// to the block, a `done: true` event carries the full `text`.
public struct StreamTextPayload: Codable, Sendable, Hashable {
    public let delta: String?
    public let text: String?
    public let done: Bool
    public let durationMS: Int?

    public init(delta: String? = nil, text: String? = nil, done: Bool = false, durationMS: Int? = nil) {
        self.delta = delta
        self.text = text
        self.done = done
        self.durationMS = durationMS
    }

    enum CodingKeys: String, CodingKey {
        case delta, text, done
        case durationMS = "duration_ms"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        delta = try values.decodeIfPresent(String.self, forKey: .delta)
        text = try values.decodeIfPresent(String.self, forKey: .text)
        done = try values.decodeIfPresent(Bool.self, forKey: .done) ?? false
        durationMS = try values.decodeIfPresent(Int.self, forKey: .durationMS)
    }
}

public struct DiffPayload: Codable, Sendable, Hashable {
    public let path: String
    public let additions: Int
    public let deletions: Int
    public let patch: String?
    public let patchTruncated: Bool

    public init(path: String, additions: Int, deletions: Int, patch: String? = nil, patchTruncated: Bool = false) {
        self.path = path
        self.additions = additions
        self.deletions = deletions
        self.patch = patch
        self.patchTruncated = patchTruncated
    }

    enum CodingKeys: String, CodingKey {
        case path, additions, deletions, patch
        case patchTruncated = "patch_truncated"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        path = try values.decodeIfPresent(String.self, forKey: .path) ?? ""
        additions = try values.decodeIfPresent(Int.self, forKey: .additions) ?? 0
        deletions = try values.decodeIfPresent(Int.self, forKey: .deletions) ?? 0
        patch = try values.decodeIfPresent(String.self, forKey: .patch)
        patchTruncated = try values.decodeIfPresent(Bool.self, forKey: .patchTruncated) ?? false
    }
}

public struct ToolCallPayload: Codable, Sendable, Hashable {
    public let tool: String
    public let kind: ToolKind
    public let title: String
    public let status: ToolStatus
    public let input: JSONValue?
    public let inputTruncated: Bool
    public let output: String?
    public let outputTruncated: Bool
    public let summary: String?
    public let diff: DiffPayload?
    public let startedAt: Int64?
    public let endedAt: Int64?
    public let durationMS: Int?

    /// True when the device withheld part of the payload; the full block is
    /// fetched on demand with `session.block`.
    public var isTruncated: Bool { inputTruncated || outputTruncated || diff?.patchTruncated == true }

    public init(tool: String, kind: ToolKind, title: String, status: ToolStatus,
                input: JSONValue? = nil, inputTruncated: Bool = false,
                output: String? = nil, outputTruncated: Bool = false,
                summary: String? = nil, diff: DiffPayload? = nil,
                startedAt: Int64? = nil, endedAt: Int64? = nil, durationMS: Int? = nil) {
        self.tool = tool
        self.kind = kind
        self.title = title
        self.status = status
        self.input = input
        self.inputTruncated = inputTruncated
        self.output = output
        self.outputTruncated = outputTruncated
        self.summary = summary
        self.diff = diff
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.durationMS = durationMS
    }

    enum CodingKeys: String, CodingKey {
        case tool, title, status, input, output, summary, diff
        case kind = "tool_kind"
        case inputTruncated = "input_truncated"
        case outputTruncated = "output_truncated"
        case startedAt = "started_at"
        case endedAt = "ended_at"
        case durationMS = "duration_ms"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        let tool = try values.decodeIfPresent(String.self, forKey: .tool) ?? ""
        self.tool = tool
        kind = try values.decodeIfPresent(ToolKind.self, forKey: .kind) ?? .derived(fromTool: tool)
        title = try values.decodeIfPresent(String.self, forKey: .title) ?? ""
        status = try values.decodeIfPresent(ToolStatus.self, forKey: .status) ?? .running
        input = try values.decodeIfPresent(JSONValue.self, forKey: .input)
        inputTruncated = try values.decodeIfPresent(Bool.self, forKey: .inputTruncated) ?? false
        output = try values.decodeIfPresent(String.self, forKey: .output)
        outputTruncated = try values.decodeIfPresent(Bool.self, forKey: .outputTruncated) ?? false
        summary = try values.decodeIfPresent(String.self, forKey: .summary)
        diff = try values.decodeIfPresent(DiffPayload.self, forKey: .diff)
        startedAt = try values.decodeIfPresent(Int64.self, forKey: .startedAt)
        endedAt = try values.decodeIfPresent(Int64.self, forKey: .endedAt)
        durationMS = try values.decodeIfPresent(Int.self, forKey: .durationMS)
    }
}

public struct TodoItem: Codable, Sendable, Hashable, Identifiable {
    public let id: String
    public let text: String
    public let status: TodoStatus

    public init(id: String, text: String, status: TodoStatus) {
        self.id = id
        self.text = text
        self.status = status
    }
}

public struct TodosPayload: Codable, Sendable, Hashable {
    public let items: [TodoItem]

    public init(items: [TodoItem]) { self.items = items }

    public var counts: TodoCounts {
        TodoCounts(total: items.count, done: items.filter { $0.status == .completed }.count)
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        items = try values.decodeIfPresent([TodoItem].self, forKey: .items) ?? []
    }
}

/// An option on an approval card. The id is agent-defined and opaque; the UI
/// renders exactly the options it was given and never invents one.
public struct ApprovalOption: Codable, Sendable, Hashable, Identifiable {
    public let id: String
    public let label: String
    public let style: OptionStyle

    public init(id: String, label: String, style: OptionStyle) {
        self.id = id
        self.label = label
        self.style = style
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        label = try values.decodeIfPresent(String.self, forKey: .label) ?? id
        style = try values.decodeIfPresent(OptionStyle.self, forKey: .style) ?? .secondary
    }
}

public struct ApprovalDecision: Codable, Sendable, Hashable {
    public let optionID: String
    public let by: EventSource

    public init(optionID: String, by: EventSource) {
        self.optionID = optionID
        self.by = by
    }

    enum CodingKeys: String, CodingKey {
        case by
        case optionID = "option_id"
    }
}

public struct ApprovalPayload: Codable, Sendable, Hashable {
    public let requestID: String
    public let tool: String
    public let kind: ToolKind
    public let title: String
    public let input: JSONValue?
    public let diff: DiffPayload?
    public let options: [ApprovalOption]
    public let status: RequestStatus
    public let decision: ApprovalDecision?

    /// Amendment A11: the reserved id a device uses when a shared request was
    /// resolved by whoever else holds the session. It is never an option and is
    /// never sent back, so nothing looks it up in `options`.
    public static let elsewhereOptionID = "elsewhere"

    /// What to call the option a resolved request settled on, or nil when there
    /// is nothing to name because it was answered elsewhere. An id this block
    /// never offered still renders verbatim rather than leaving the card blank.
    public var resolvedOptionLabel: String? {
        guard let decision, decision.optionID != Self.elsewhereOptionID else { return nil }
        return options.first { $0.id == decision.optionID }?.label ?? decision.optionID
    }

    /// Every approval carries at least one primary and one danger option, so the
    /// UI can place accept and reject consistently without knowing the ids.
    public var primaryOption: ApprovalOption? { options.first { $0.style == .primary } }
    public var dangerOption: ApprovalOption? { options.first { $0.style == .danger } }
    public var otherOptions: [ApprovalOption] {
        options.filter { $0.id != primaryOption?.id && $0.id != dangerOption?.id }
    }

    public init(requestID: String, tool: String, kind: ToolKind, title: String,
                input: JSONValue? = nil, diff: DiffPayload? = nil,
                options: [ApprovalOption], status: RequestStatus = .pending,
                decision: ApprovalDecision? = nil) {
        self.requestID = requestID
        self.tool = tool
        self.kind = kind
        self.title = title
        self.input = input
        self.diff = diff
        self.options = options
        self.status = status
        self.decision = decision
    }

    enum CodingKeys: String, CodingKey {
        case tool, title, input, diff, options, status, decision
        case kind = "tool_kind"
        case requestID = "request_id"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        requestID = try values.decode(String.self, forKey: .requestID)
        let tool = try values.decodeIfPresent(String.self, forKey: .tool) ?? ""
        self.tool = tool
        kind = try values.decodeIfPresent(ToolKind.self, forKey: .kind) ?? .derived(fromTool: tool)
        title = try values.decodeIfPresent(String.self, forKey: .title) ?? ""
        input = try values.decodeIfPresent(JSONValue.self, forKey: .input)
        diff = try values.decodeIfPresent(DiffPayload.self, forKey: .diff)
        options = try values.decodeIfPresent([ApprovalOption].self, forKey: .options) ?? []
        status = try values.decodeIfPresent(RequestStatus.self, forKey: .status) ?? .pending
        decision = try values.decodeIfPresent(ApprovalDecision.self, forKey: .decision)
    }
}

public struct QuestionOption: Codable, Sendable, Hashable, Identifiable {
    public let id: String
    public let label: String
    public let description: String?

    public init(id: String, label: String, description: String? = nil) {
        self.id = id
        self.label = label
        self.description = description
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        label = try values.decodeIfPresent(String.self, forKey: .label) ?? id
        description = try values.decodeIfPresent(String.self, forKey: .description)
    }
}

public struct QuestionItem: Codable, Sendable, Hashable, Identifiable {
    public let id: String
    public let prompt: String
    public let options: [QuestionOption]
    public let multi: Bool
    public let allowText: Bool
    public let secret: Bool

    public init(id: String, prompt: String, options: [QuestionOption] = [],
                multi: Bool = false, allowText: Bool = false, secret: Bool = false) {
        self.id = id
        self.prompt = prompt
        self.options = options
        self.multi = multi
        self.allowText = allowText
        self.secret = secret
    }

    enum CodingKeys: String, CodingKey {
        case id, prompt, options, multi, secret
        case allowText = "allow_text"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        prompt = try values.decodeIfPresent(String.self, forKey: .prompt) ?? ""
        options = try values.decodeIfPresent([QuestionOption].self, forKey: .options) ?? []
        multi = try values.decodeIfPresent(Bool.self, forKey: .multi) ?? false
        allowText = try values.decodeIfPresent(Bool.self, forKey: .allowText) ?? false
        secret = try values.decodeIfPresent(Bool.self, forKey: .secret) ?? false
    }
}

/// One question's answer: either chosen option ids, or free text.
public enum QuestionAnswer: Codable, Sendable, Hashable {
    case options([String])
    case text(String)

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let ids = try? container.decode([String].self) { self = .options(ids) }
        else { self = .text(try container.decode(String.self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .options(let ids): try container.encode(ids)
        case .text(let value): try container.encode(value)
        }
    }
}

public struct QuestionPayload: Codable, Sendable, Hashable {
    public let requestID: String
    public let questions: [QuestionItem]
    public let status: RequestStatus
    public let answers: [String: QuestionAnswer]?
    /// Amendment A20: on a resolved question, who answered it. An attached
    /// Claude Code session shows its own dialog beside this card and whichever
    /// is answered first wins, so the other side has to be told which that was.
    public let by: EventSource?

    public init(requestID: String, questions: [QuestionItem], status: RequestStatus = .pending,
                answers: [String: QuestionAnswer]? = nil, by: EventSource? = nil) {
        self.requestID = requestID
        self.questions = questions
        self.status = status
        self.answers = answers
        self.by = by
    }

    enum CodingKeys: String, CodingKey {
        case questions, status, answers, by
        case requestID = "request_id"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        requestID = try values.decode(String.self, forKey: .requestID)
        questions = try values.decodeIfPresent([QuestionItem].self, forKey: .questions) ?? []
        status = try values.decodeIfPresent(RequestStatus.self, forKey: .status) ?? .pending
        answers = try values.decodeIfPresent([String: QuestionAnswer].self, forKey: .answers)
        by = try values.decodeIfPresent(EventSource.self, forKey: .by)
    }
}

public struct TurnStartedPayload: Codable, Sendable, Hashable {
    public let turnID: String
    public let trigger: EventSource

    public init(turnID: String, trigger: EventSource) {
        self.turnID = turnID
        self.trigger = trigger
    }

    enum CodingKeys: String, CodingKey {
        case trigger
        case turnID = "turn_id"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        turnID = try values.decodeIfPresent(String.self, forKey: .turnID) ?? ""
        trigger = try values.decodeIfPresent(EventSource.self, forKey: .trigger) ?? .remote
    }
}

public struct TurnCompletedPayload: Codable, Sendable, Hashable {
    public let turnID: String
    public let stopReason: StopReason
    public let durationMS: Int
    public let usage: SessionUsage?

    public init(turnID: String, stopReason: StopReason, durationMS: Int, usage: SessionUsage? = nil) {
        self.turnID = turnID
        self.stopReason = stopReason
        self.durationMS = durationMS
        self.usage = usage
    }

    enum CodingKeys: String, CodingKey {
        case usage
        case turnID = "turn_id"
        case stopReason = "stop_reason"
        case durationMS = "duration_ms"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        turnID = try values.decodeIfPresent(String.self, forKey: .turnID) ?? ""
        stopReason = try values.decodeIfPresent(StopReason.self, forKey: .stopReason) ?? .completed
        durationMS = try values.decodeIfPresent(Int.self, forKey: .durationMS) ?? 0
        usage = try values.decodeIfPresent(SessionUsage.self, forKey: .usage)
    }
}

public struct StatusPayload: Codable, Sendable, Hashable {
    public let state: SessionState
    public let detail: String?

    public init(state: SessionState, detail: String? = nil) {
        self.state = state
        self.detail = detail
    }
}

/// A partial update of the session summary.
public struct MetaPayload: Codable, Sendable, Hashable {
    public let title: String?
    public let model: String?
    public let permissionMode: String?
    public let effort: String?
    public let cwd: String?
    public let git: GitInfo?
    public let control: SessionControl?
    public let agentVersion: String?

    public init(title: String? = nil, model: String? = nil, permissionMode: String? = nil,
                effort: String? = nil, cwd: String? = nil, git: GitInfo? = nil,
                control: SessionControl? = nil, agentVersion: String? = nil) {
        self.title = title
        self.model = model
        self.permissionMode = permissionMode
        self.effort = effort
        self.cwd = cwd
        self.git = git
        self.control = control
        self.agentVersion = agentVersion
    }

    enum CodingKeys: String, CodingKey {
        case title, model, effort, cwd, git, control
        case permissionMode = "permission_mode"
        case agentVersion = "agent_version"
    }
}

/// A message the user sent while a turn was running. `id` is the original
/// `session.send` request id, so the app can match its own optimistic row.
public struct QueuedMessage: Codable, Sendable, Hashable, Identifiable {
    public let id: String
    public let text: String
    public let ts: Int64

    public init(id: String, text: String, ts: Int64) {
        self.id = id
        self.text = text
        self.ts = ts
    }
}

public struct QueuePayload: Codable, Sendable, Hashable {
    public let pending: [QueuedMessage]

    public init(pending: [QueuedMessage]) { self.pending = pending }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        pending = try values.decodeIfPresent([QueuedMessage].self, forKey: .pending) ?? []
    }
}

public struct NoticePayload: Codable, Sendable, Hashable {
    public let level: NoticeLevel
    public let text: String

    public init(level: NoticeLevel, text: String) {
        self.level = level
        self.text = text
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        level = try values.decodeIfPresent(NoticeLevel.self, forKey: .level) ?? .info
        text = try values.decodeIfPresent(String.self, forKey: .text) ?? ""
    }
}

public struct ErrorPayload: Codable, Sendable, Hashable {
    public let message: String
    public let code: String?

    public init(message: String, code: String? = nil) {
        self.message = message
        self.code = code
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        message = try values.decodeIfPresent(String.self, forKey: .message) ?? ""
        code = try values.decodeIfPresent(String.self, forKey: .code)
    }
}
