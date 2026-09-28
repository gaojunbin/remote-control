import Foundation
import RCCore

/// `web/src/features/chat/commands.ts` — A27: reading the composer's draft as a
/// slash command.
///
/// Everything here is pure, so the panel, the send routing and the tests all
/// read the draft the same way. The names come from the device and obey
/// `Command.name` of PROTOCOL.md §4.11: lower case, `[a-z0-9_:.-]`, never with
/// the slash.
public enum SlashCommands {
    /// The draft read as `/name argument`, whether or not any agent offers it.
    public struct Typed: Sendable, Hashable {
        public let name: String
        /// Nil when nothing but whitespace followed the name.
        public let argument: String?
    }

    public struct Match: Sendable, Hashable {
        public let command: Command
        public let argument: String?
    }

    public struct Section: Sendable, Hashable {
        /// Nil when the list is not sectioned, or for commands with no group.
        public let group: String?
        public let items: [Command]
    }

    private static let nameCharacters = "a-z0-9_:.-"

    /// The partial name the panel filters on, or nil when the draft is not a
    /// command being typed. `/` alone is an empty query, which is the whole
    /// list — that is the keystroke the panel opens on.
    public static func query(_ draft: String) -> String? {
        guard let match = draft.wholeMatch(of: try! Regex("/([\(nameCharacters)]*)").ignoresCase()),
              let name = match.output[1].substring else { return nil }
        return String(name).lowercased()
    }

    public static func typed(_ draft: String) -> Typed? {
        let pattern = try! Regex("/([\(nameCharacters)]+)(?:[ \\t]+([\\s\\S]*))?").ignoresCase()
        guard let match = draft.wholeMatch(of: pattern), let name = match.output[1].substring else { return nil }
        let argument = (match.output[2].substring.map(String.init) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return Typed(name: String(name).lowercased(), argument: argument.isEmpty ? nil : argument)
    }

    /// The listed command a draft names, or nil. Anything the session does not
    /// offer is ordinary text, which is how a terminal treats an unknown slash.
    public static func match(_ commands: [Command], draft: String) -> Match? {
        guard let typed = typed(draft),
              let command = commands.first(where: { $0.name.lowercased() == typed.name }) else { return nil }
        return Match(command: command, argument: typed.argument)
    }

    /// Prefix of the name, in the order the device listed them. §4.11.
    public static func filter(_ commands: [Command], query: String) -> [Command] {
        let prefix = query.lowercased()
        guard !prefix.isEmpty else { return commands }
        return commands.filter { $0.name.lowercased().hasPrefix(prefix) }
    }

    /// The rows in the order they are drawn, sectioned by group only when the
    /// agent distinguishes more than one — a single header says nothing about
    /// anything. Sections keep the order the groups first appear in, so the
    /// device's own ordering survives.
    public static func sections(_ commands: [Command]) -> [Section] {
        var order: [String?] = []
        var byGroup: [String?: [Command]] = [:]
        for command in commands {
            if byGroup[command.group] == nil { order.append(command.group) }
            byGroup[command.group, default: []].append(command)
        }
        guard order.count > 1 else { return [Section(group: nil, items: commands)] }
        return order.map { Section(group: $0, items: byGroup[$0] ?? []) }
    }

    /// What taking a row writes into the field: `/name ` when the command takes
    /// an argument, so the hint shows where it goes, and `/name` when it does
    /// not, so a second Enter runs it.
    public static func completion(for command: Command) -> String {
        command.takesArgument ? "/\(command.name) " : "/\(command.name)"
    }
}
