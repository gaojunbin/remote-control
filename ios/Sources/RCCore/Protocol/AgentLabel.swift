import Foundation

/// The name an agent id is shown under. A `Session` carries only the id, so the
/// list and the chip need the mapping without a full `AgentInfo` at hand.
/// An id nobody knows renders as itself rather than as "Unknown".
///
/// What stands in for an agent where a name does not fit is its logo, which is
/// a drawing rather than a string: `AgentLogo` in `RCUI` holds it.
public enum AgentLabel {
    /// Product names, never translated. Amendment A26 withdrew Cursor.
    public static func name(_ agent: String) -> String {
        switch agent {
        case "claude": "Claude Code"
        case "codex": "Codex"
        case "grok": "Grok Build"
        case "pi": "pi"
        default: agent
        }
    }

    /// The letter an agent with no logo of its own is marked by: the first of
    /// its id, upper-cased. `docs/DESIGN.md` § "Agents".
    public static func initial(_ agent: String) -> String {
        agent.prefix(1).uppercased()
    }
}

extension Session {
    /// What the agent chip on a session row says.
    public var agentLabel: String { AgentLabel.name(agent) }
}
