import Foundation

/// One slash command a session offers (protocol 4.11, amendment A27).
///
/// `name` is what the user types after the slash and what goes back on the wire
/// unchanged. `argument` is a placeholder rather than a value: it names what may
/// follow the command, and is absent when the command takes nothing. `group`
/// says where the command came from — `Built-in`, `Skills`, `Prompts`,
/// `Extensions` — and is absent when the agent draws no distinction.
///
/// Nothing here is translated. The words are the agent's own, in the language
/// the terminal would print them in.
public struct Command: Codable, Sendable, Hashable, Identifiable {
    public let name: String
    public let description: String
    public let argument: String?
    public let group: String?

    public var id: String { name }

    /// What the panel draws and what the field is written with.
    public var slash: String { "/" + name }

    public var takesArgument: Bool { argument?.isEmpty == false }

    public init(name: String, description: String, argument: String? = nil, group: String? = nil) {
        self.name = name
        self.description = description
        self.argument = argument
        self.group = group
    }

    /// The text the transcript shows for running this command, which is exactly
    /// what the device echoes back as the message (6.3).
    public func line(argument value: String?) -> String {
        guard let value, !value.isEmpty else { return slash }
        return "\(slash) \(value)"
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        name = try values.decode(String.self, forKey: .name)
        description = try values.decodeIfPresent(String.self, forKey: .description) ?? ""
        // An empty string is a device saying nothing, not a placeholder or a
        // section with no name; either would draw a gap the reader cannot read.
        argument = try values.decodeIfPresent(String.self, forKey: .argument).flatMap { $0.isEmpty ? nil : $0 }
        group = try values.decodeIfPresent(String.self, forKey: .group).flatMap { $0.isEmpty ? nil : $0 }
    }
}
