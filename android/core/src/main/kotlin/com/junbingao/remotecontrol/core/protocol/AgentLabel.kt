package com.junbingao.remotecontrol.core.protocol

/**
 * The name an agent id is shown under. A `Session` carries only the id, so the list and the chip
 * need the mapping without a full `AgentInfo` at hand. An id nobody knows renders as itself
 * rather than as "Unknown".
 *
 * What stands in for an agent where a name does not fit is its logo, which is a drawing rather
 * than a string: each app's design layer holds it.
 */
object AgentLabel {
    /** Product names, never translated. Amendment A26 withdrew Cursor. */
    fun name(agent: String): String = when (agent) {
        "claude" -> "Claude Code"
        "codex" -> "Codex"
        "grok" -> "Grok Build"
        "pi" -> "pi"
        else -> agent
    }

    /** The letter an agent with no logo of its own is marked by: the first of its id, upper-cased. `docs/DESIGN.md` § "Agents". */
    fun initial(agent: String): String {
        if (agent.isEmpty()) return ""
        return agent.substring(0, Character.charCount(agent.codePointAt(0))).uppercase()
    }
}

/** What the agent chip on a session row says. */
val Session.agentLabel: String get() = AgentLabel.name(agent)
