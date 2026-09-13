import Foundation

/// The name an agent id is shown under, and the mark that stands in for it
/// where a name does not fit. A `Session` carries only the id, so the list and
/// the chip need the mapping without a full `AgentInfo` at hand.
/// An id nobody knows renders as itself rather than as "Unknown".
public enum AgentLabel {
    /// Product names, never translated. Amendment A25 added the last three.
    public static func name(_ agent: String) -> String {
        switch agent {
        case "claude": "Claude Code"
        case "codex": "Codex"
        case "grok": "Grok Build"
        case "cursor": "Cursor"
        case "pi": "pi"
        default: agent
        }
    }

    /// The one- or two-letter mark the new-session form's agent control and the
    /// filter menu draw. `docs/DESIGN.md` § "Agents": the mark and the name tell
    /// the agents apart, not a colour per vendor. An agent nobody knows is
    /// marked by its own first letter.
    public static func mark(_ agent: String) -> String {
        switch agent {
        case "claude": "C"
        case "codex": "X"
        case "grok": "G"
        case "cursor": "Cu"
        case "pi": "π"
        default: agent.prefix(1).uppercased()
        }
    }
}

extension Session {
    /// What the agent chip on a session row says.
    public var agentLabel: String { AgentLabel.name(agent) }
}
