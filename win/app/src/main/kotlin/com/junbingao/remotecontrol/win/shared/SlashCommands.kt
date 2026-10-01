package com.junbingao.remotecontrol.win.shared

import com.junbingao.remotecontrol.core.protocol.Command
import com.junbingao.remotecontrol.core.state.trimmed

/**
 * `web/src/features/chat/commands.ts` — A27: reading the composer's draft as a slash command.
 *
 * Everything here is pure, so the panel, the send routing and the tests all read the draft the
 * same way. The names come from the device and obey `Command.name` of PROTOCOL.md §4.11: lower
 * case, `[a-z0-9_:.-]`, never with the slash.
 */
object SlashCommands {
    /** The draft read as `/name argument`, whether or not any agent offers it. */
    data class Typed(
        val name: String,
        /** Null when nothing but whitespace followed the name. */
        val argument: String?,
    )

    data class Match(val command: Command, val argument: String?)

    data class Section(
        /** Null when the list is not sectioned, or for commands with no group. */
        val group: String?,
        val items: List<Command>,
    )

    private const val NAME_CHARACTERS = "a-z0-9_:.-"
    private val queryPattern = Regex("/([$NAME_CHARACTERS]*)", RegexOption.IGNORE_CASE)
    private val typedPattern = Regex("/([$NAME_CHARACTERS]+)(?:[ \\t]+([\\s\\S]*))?", RegexOption.IGNORE_CASE)

    /**
     * The partial name the panel filters on, or null when the draft is not a command being typed.
     * `/` alone is an empty query, which is the whole list — that is the keystroke the panel opens on.
     */
    fun query(draft: String): String? = queryPattern.matchEntire(draft)?.groupValues?.get(1)?.lowercase()

    fun typed(draft: String): Typed? {
        val match = typedPattern.matchEntire(draft) ?: return null
        val argument = match.groupValues[2].trimmed
        return Typed(name = match.groupValues[1].lowercase(), argument = argument.ifEmpty { null })
    }

    /**
     * The listed command a draft names, or null. Anything the session does not offer is ordinary
     * text, which is how a terminal treats an unknown slash.
     */
    fun match(commands: List<Command>, draft: String): Match? {
        val typed = typed(draft) ?: return null
        val command = commands.firstOrNull { it.name.lowercase() == typed.name } ?: return null
        return Match(command = command, argument = typed.argument)
    }

    /** Prefix of the name, in the order the device listed them. §4.11. */
    fun filter(commands: List<Command>, query: String): List<Command> {
        val prefix = query.lowercase()
        if (prefix.isEmpty()) return commands
        return commands.filter { it.name.lowercase().startsWith(prefix) }
    }

    /**
     * The rows in the order they are drawn, sectioned by group only when the agent distinguishes
     * more than one — a single header says nothing about anything. Sections keep the order the
     * groups first appear in, so the device's own ordering survives.
     */
    fun sections(commands: List<Command>): List<Section> {
        val byGroup = LinkedHashMap<String?, MutableList<Command>>()
        for (command in commands) byGroup.getOrPut(command.group) { mutableListOf() } += command
        if (byGroup.size <= 1) return listOf(Section(group = null, items = commands))
        return byGroup.map { (group, items) -> Section(group = group, items = items) }
    }

    /**
     * What taking a row writes into the field: `/name ` when the command takes an argument, so the
     * hint shows where it goes, and `/name` when it does not, so a second Enter runs it.
     */
    fun completion(command: Command): String = if (command.takesArgument) "/${command.name} " else "/${command.name}"
}
