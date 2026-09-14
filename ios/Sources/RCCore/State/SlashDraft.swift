import Foundation

/// What a draft beginning with `/` means (amendment A27).
///
/// One rule, read by the panel, by the hint under it and by Send, so the three
/// can never disagree about whether what was typed is a command or a message.
/// The draft is a command draft only when the slash is its very first
/// character: a message that mentions a path halfway through a sentence is
/// prose, and a terminal treats it the same way.
public struct SlashDraft: Sendable, Hashable {
    /// The word after the slash, exactly as typed.
    public let name: String
    /// Everything after the first run of spaces, or nil when nothing follows.
    public let argument: String?
    /// A space has been typed, so the name is finished and the panel is done.
    public let isComplete: Bool

    public init(name: String, argument: String?, isComplete: Bool) {
        self.name = name
        self.argument = argument
        self.isComplete = isComplete
    }

    /// The draft read as a command, or nil when it is ordinary text.
    public static func parse(_ draft: String) -> SlashDraft? {
        guard draft.hasPrefix("/") else { return nil }
        let body = draft.dropFirst()
        guard let separator = body.firstIndex(where: \.isWhitespace) else {
            return SlashDraft(name: String(body), argument: nil, isComplete: false)
        }
        let rest = body[body.index(after: separator)...].trimmingCharacters(in: .whitespacesAndNewlines)
        return SlashDraft(name: String(body[..<separator]), argument: rest.isEmpty ? nil : rest,
                          isComplete: true)
    }

    /// The commands whose name begins with what has been typed. Case is
    /// ignored: a phone capitalises the first letter of what looks like a
    /// sentence, and `/Compact` means what `/compact` means.
    public static func filter(_ commands: [Command], query: String) -> [Command] {
        guard !query.isEmpty else { return commands }
        let needle = query.lowercased()
        return commands.filter { $0.name.lowercased().hasPrefix(needle) }
    }

    /// The command a typed name stands for, matched whole rather than by prefix.
    public static func match(_ commands: [Command], name: String) -> Command? {
        let needle = name.lowercased()
        return commands.first { $0.name.lowercased() == needle }
    }

    /// How many rows the panel shows before it starts scrolling.
    public static let visibleRows = 8
}

/// One block of the panel: the rows of a group, and its header where there is
/// more than one group to tell apart.
public struct CommandSection: Identifiable, Sendable, Hashable {
    public let title: String?
    public let commands: [Command]

    public var id: String { title ?? "" }

    public init(title: String?, commands: [Command]) {
        self.title = title
        self.commands = commands
    }

    /// Section the rows by `group`, in the order the device listed them, and
    /// only when there is more than one group among the rows on screen. A
    /// single header over the whole list names nothing the list does not
    /// already say.
    public static func build(_ commands: [Command]) -> [CommandSection] {
        var order: [String?] = []
        var buckets: [String?: [Command]] = [:]
        for command in commands {
            if buckets[command.group] == nil { order.append(command.group) }
            buckets[command.group, default: []].append(command)
        }
        guard order.count > 1 else { return commands.isEmpty ? [] : [CommandSection(title: nil, commands: commands)] }
        return order.map { CommandSection(title: $0, commands: buckets[$0] ?? []) }
    }
}
