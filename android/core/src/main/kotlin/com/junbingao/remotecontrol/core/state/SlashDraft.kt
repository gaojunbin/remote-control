package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Command

/**
 * What a draft beginning with `/` means (amendment A27).
 *
 * One rule, read by the panel, by the hint under it and by Send, so the three can never disagree
 * about whether what was typed is a command or a message. The draft is a command draft only when
 * the slash is its very first character: a message that mentions a path halfway through a sentence
 * is prose, and a terminal treats it the same way.
 */
data class SlashDraft(
    /** The word after the slash, exactly as typed. */
    val name: String,
    /** Everything after the first run of spaces, or null when nothing follows. */
    val argument: String?,
    /** A space has been typed, so the name is finished and the panel is done. */
    val isComplete: Boolean,
) {
    companion object {
        /** How many rows the panel shows before it starts scrolling. */
        const val visibleRows = 8

        /** The draft read as a command, or null when it is ordinary text. */
        fun parse(draft: String): SlashDraft? {
            if (!draft.startsWith("/")) return null
            val body = draft.characters().drop(1)
            val separator = body.indexOfFirst(::isWhitespace)
            if (separator < 0) return SlashDraft(name = body.joinToString(""), argument = null, isComplete = false)
            val rest = body.drop(separator + 1).joinToString("").trimmed
            return SlashDraft(name = body.take(separator).joinToString(""), argument = rest.ifEmpty { null },
                              isComplete = true)
        }

        /**
         * The commands whose name begins with what has been typed. Case is ignored: a phone
         * capitalises the first letter of what looks like a sentence, and `/Compact` means what
         * `/compact` means.
         */
        fun filter(commands: List<Command>, query: String): List<Command> {
            if (query.isEmpty()) return commands
            val needle = query.lowercase()
            return commands.filter { it.name.lowercase().startsWith(needle) }
        }

        /** The command a typed name stands for, matched whole rather than by prefix. */
        fun match(commands: List<Command>, name: String): Command? {
            val needle = name.lowercase()
            return commands.firstOrNull { it.name.lowercase() == needle }
        }
    }
}

/** One block of the panel: the rows of a group, and its header where there is more than one group to tell apart. */
data class CommandSection(val title: String?, val commands: List<Command>) {
    val id: String get() = title ?: ""

    companion object {
        /**
         * Section the rows by `group`, in the order the device listed them, and only when there is
         * more than one group among the rows on screen. A single header over the whole list names
         * nothing the list does not already say.
         */
        fun build(commands: List<Command>): List<CommandSection> {
            val buckets = LinkedHashMap<String?, MutableList<Command>>()
            for (command in commands) buckets.getOrPut(command.group) { mutableListOf() }.add(command)
            if (buckets.size <= 1) return if (commands.isEmpty()) emptyList() else listOf(CommandSection(title = null, commands = commands))
            return buckets.map { (title, rows) -> CommandSection(title = title, commands = rows) }
        }
    }
}
