import Foundation

/// The name an agent id is shown under. A `Session` carries only the id, so the
/// list and the chip need the mapping without a full `AgentInfo` at hand.
/// An id nobody knows renders as itself rather than as "Unknown".
public enum AgentLabel {
    public static func name(_ agent: String) -> String {
        switch agent {
        case "claude": "Claude Code"
        case "codex": "Codex"
        default: agent
        }
    }
}

extension Session {
    /// What the agent chip on a session row says.
    public var agentLabel: String { AgentLabel.name(agent) }
}
