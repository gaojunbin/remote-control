import Foundation

/// The wire protocol version this build speaks. A `hello` carrying any other
/// value is a hard error: the app refuses to guess the meaning of a frame.
public enum RemoteProtocol {
    public static let version = 1
}

/// A lowercase string enumeration from the wire.
///
/// Decoding never fails on a value this build has not heard of, so a newer
/// gateway can add a state or a tool kind without breaking older apps. Callers
/// compare against the named constants and fall back to `rawValue` for display.
public protocol WireEnum: RawRepresentable, Codable, Hashable, Sendable, CustomStringConvertible
where RawValue == String {
    init(rawValue: String)
}

extension WireEnum {
    public init(from decoder: Decoder) throws {
        self.init(rawValue: try decoder.singleValueContainer().decode(String.self))
    }
    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
    public var description: String { rawValue }
}

/// Machine platform of a device.
public struct DevicePlatform: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let macos = DevicePlatform(rawValue: "macos")
    public static let linux = DevicePlatform(rawValue: "linux")
}

/// Lifecycle of one session, as reported by the owning device.
public struct SessionState: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let starting = SessionState(rawValue: "starting")
    public static let idle = SessionState(rawValue: "idle")
    public static let running = SessionState(rawValue: "running")
    public static let needsApproval = SessionState(rawValue: "needs_approval")
    public static let needsInput = SessionState(rawValue: "needs_input")
    public static let error = SessionState(rawValue: "error")
    public static let stopped = SessionState(rawValue: "stopped")
    public static let readonly = SessionState(rawValue: "readonly")

    /// `needs_approval` and `needs_input` are sub-states of a running turn.
    public var isWorking: Bool { self == .running || self == .needsApproval || self == .needsInput }
    public var isBlockedOnUser: Bool { self == .needsApproval || self == .needsInput }
}

/// Who owns a session's input right now.
public struct SessionControl: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let remote = SessionControl(rawValue: "remote")
    public static let terminal = SessionControl(rawValue: "terminal")
    /// Amendment A10: a live CLI process owns the session and the device is
    /// attached to it, so this app types into the same conversation.
    public static let shared = SessionControl(rawValue: "shared")
    public static let none = SessionControl(rawValue: "none")
}

/// Amendment A10: how a device can attach to an agent's terminal sessions.
public struct AgentAttach: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    /// The Claude channel shim loaded by the CLI.
    public static let channel = AgentAttach(rawValue: "channel")
    /// The Codex shared app-server daemon.
    public static let daemon = AgentAttach(rawValue: "daemon")
}

/// Amendment A10: what became of a message sent into a `shared` session.
public struct MessageDelivery: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    /// Held by the device until the terminal-driven turn ends.
    public static let pending = MessageDelivery(rawValue: "pending")
    /// Injected into the live CLI session.
    public static let delivered = MessageDelivery(rawValue: "delivered")
    /// Taken by the CLI as mid-turn data; the device will inject it again.
    public static let absorbed = MessageDelivery(rawValue: "absorbed")
}

/// Who created a session, or what triggered a turn or message.
public struct EventSource: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let remote = EventSource(rawValue: "remote")
    public static let terminal = EventSource(rawValue: "terminal")
    public static let queue = EventSource(rawValue: "queue")
    public static let policy = EventSource(rawValue: "policy")
}

/// Coarse classification of a tool call, used to pick an icon and a summary.
public struct ToolKind: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let shell = ToolKind(rawValue: "shell")
    public static let read = ToolKind(rawValue: "read")
    public static let edit = ToolKind(rawValue: "edit")
    public static let write = ToolKind(rawValue: "write")
    public static let search = ToolKind(rawValue: "search")
    public static let web = ToolKind(rawValue: "web")
    public static let mcp = ToolKind(rawValue: "mcp")
    public static let subagent = ToolKind(rawValue: "subagent")
    public static let todo = ToolKind(rawValue: "todo")
    public static let other = ToolKind(rawValue: "other")
}

public struct ToolStatus: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let running = ToolStatus(rawValue: "running")
    public static let succeeded = ToolStatus(rawValue: "succeeded")
    public static let failed = ToolStatus(rawValue: "failed")
    public static let cancelled = ToolStatus(rawValue: "cancelled")

    public var isFinished: Bool { self != .running }
}

/// Lifecycle of an approval or a question card.
public struct RequestStatus: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let pending = RequestStatus(rawValue: "pending")
    public static let resolved = RequestStatus(rawValue: "resolved")
    public static let expired = RequestStatus(rawValue: "expired")

    public var isActionable: Bool { self == .pending }
}

/// Visual weight the device asks for on an approval option. Ids stay opaque.
public struct OptionStyle: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let primary = OptionStyle(rawValue: "primary")
    public static let secondary = OptionStyle(rawValue: "secondary")
    public static let danger = OptionStyle(rawValue: "danger")
}

public struct NoticeLevel: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let info = NoticeLevel(rawValue: "info")
    public static let warn = NoticeLevel(rawValue: "warn")
    public static let error = NoticeLevel(rawValue: "error")
}

public struct StopReason: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let completed = StopReason(rawValue: "completed")
    public static let interrupted = StopReason(rawValue: "interrupted")
    public static let error = StopReason(rawValue: "error")
}

public struct TodoStatus: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let pending = TodoStatus(rawValue: "pending")
    public static let inProgress = TodoStatus(rawValue: "in_progress")
    public static let completed = TodoStatus(rawValue: "completed")
}

/// How the device should treat a `session.send` that arrives during a turn.
public struct SendMode: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let auto = SendMode(rawValue: "auto")
    public static let queue = SendMode(rawValue: "queue")
    public static let interrupt = SendMode(rawValue: "interrupt")
}

/// What the device did with an accepted `session.send`.
public struct SendAcceptance: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let sent = SendAcceptance(rawValue: "sent")
    public static let queued = SendAcceptance(rawValue: "queued")
    public static let steered = SendAcceptance(rawValue: "steered")
}

/// Optional device behaviour an app must check before offering an affordance.
public struct AgentCapability: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let worktree = AgentCapability(rawValue: "worktree")
    public static let takeover = AgentCapability(rawValue: "takeover")
    public static let interrupt = AgentCapability(rawValue: "interrupt")
    public static let queue = AgentCapability(rawValue: "queue")
    public static let steer = AgentCapability(rawValue: "steer")
    public static let attachments = AgentCapability(rawValue: "attachments")
    public static let effort = AgentCapability(rawValue: "effort")
    public static let history = AgentCapability(rawValue: "history")
}

/// Category carried by a push payload.
public struct PushKind: WireEnum {
    public let rawValue: String
    public init(rawValue: String) { self.rawValue = rawValue }
    public static let needsApproval = PushKind(rawValue: "needs_approval")
    public static let needsInput = PushKind(rawValue: "needs_input")
    public static let turnCompleted = PushKind(rawValue: "turn_completed")
    public static let error = PushKind(rawValue: "error")
}

extension ToolKind {
    /// Classify a tool from its name, for the rare event that omits `tool_kind`.
    /// The wire field wins whenever it is present (amendment A1).
    public static func derived(fromTool tool: String) -> ToolKind {
        let name = tool.lowercased()
        if name.hasPrefix("mcp__") { return .mcp }
        switch name {
        case "bash", "shell", "terminal", "run", "exec", "command": return .shell
        case "read", "cat", "view", "notebookread", "readfile": return .read
        case "edit", "multiedit", "notebookedit", "applypatch", "apply_patch", "update": return .edit
        case "write", "create", "writefile": return .write
        case "grep", "glob", "search", "find", "ls", "list": return .search
        case "webfetch", "websearch", "fetch", "browser": return .web
        case "task", "agent", "subagent", "dispatch": return .subagent
        case "todowrite", "todo", "todos", "plan": return .todo
        default: return .other
        }
    }
}
