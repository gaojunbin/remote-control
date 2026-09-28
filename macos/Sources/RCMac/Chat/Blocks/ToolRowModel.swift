import Foundation
import RCCore

/// What `web/src/features/chat/blocks/ToolRow.tsx` decides before it draws a
/// tool call: whether there is anything to open, what opening shows, and the
/// word at the row's trailing edge.
public struct ToolRowModel: Equatable, Sendable {
    public let running: Bool
    public let failed: Bool
    public let hasDetail: Bool
    /// A running tool shows its live output; everything else only once opened.
    public let expanded: Bool
    public let showsInput: Bool
    public let showsOutput: Bool
    public let showsDiff: Bool
    /// A27: a slash command's block is titled with the command itself, so the
    /// name and the title are one and the same word, printed once.
    public let showsTitle: Bool

    public init(_ tool: ToolCallPayload, open: Bool) {
        running = tool.status == .running
        failed = tool.status == .failed
        hasDetail = tool.input != nil || tool.output != nil || tool.diff?.patch != nil
        expanded = open || running
        showsInput = open && tool.input != nil
        showsOutput = open ? tool.output != nil : running && !(tool.output ?? "").isEmpty
        showsDiff = open && !(tool.diff?.patch ?? "").isEmpty
        showsTitle = tool.title != tool.tool
    }

    /// "running 3.2s", the duration, Expand or Collapse, or nothing.
    public func trailing(_ tool: ToolCallPayload, now: Int64 = Format.nowMillis) -> String {
        if running {
            let elapsed = tool.startedAt.map { Format.duration(Double(max(0, now - $0))) } ?? ""
            return "\(S.chat.running) \(elapsed)"
        }
        if let ms = tool.durationMS { return Format.duration(Double(ms)) }
        guard hasDetail else { return "" }
        return expanded ? S.common.collapse : S.common.expand
    }
}

/// `toolIcons.tsx`: one lucide icon per `tool_kind`, the wrench for the rest.
public enum ToolIcon {
    public static func icon(_ kind: ToolKind) -> LucideIcon {
        switch kind {
        case .shell: .terminal
        case .read: .fileText
        case .edit: .fileEdit
        case .write: .filePlus
        case .search: .search
        case .web: .globe
        case .mcp: .plug
        case .subagent: .bot
        case .todo: .listChecks
        default: .wrench
        }
    }
}
