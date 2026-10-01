package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.Command

/** Slash commands (A27), part of [DemoFixtures]. */
sealed interface DemoCommandFixtures {
    /**
     * What each agent offers the moment `/` is typed, in the shape the device reports it: Codex a
     * fixed table with one source and so no groups, Grok Build the list its agent advertises over
     * ACP, pi its prompt templates, its skills, its extension commands and the device's own
     * `compact`. Amendment A40: Claude offers the one command the device can type into the
     * terminal the shim gives it, and nothing at all without the shim.
     */
    fun commands(agent: String): List<Command> = when (agent) {
        "claude" -> claudeCommands
        "codex" -> codexCommands
        "grok" -> grokCommands
        "pi" -> piCommands
        else -> emptyList()
    }

    /**
     * Amendment A40: the one command the device can type into a Claude terminal. Everything else
     * Claude offers changes something the shim would have to read back out of a picker, so only
     * this one is listed.
     */
    val claudeCommands: List<Command>
        get() = listOf(
            Command(name = "compact", description = "Summarise the conversation so far to free context",
                    group = "Built-in"),
        )

    val codexCommands: List<Command>
        get() = listOf(
            Command(name = "compact", description = "Summarise the conversation to free up context"),
            Command(name = "review", description = "Review the working tree's changes and report issues",
                    argument = "instructions"),
            Command(name = "init", description = "Write an AGENTS.md for this repository"),
            Command(name = "status", description = "Show the session's model, settings and token use"),
            Command(name = "usage", description = "Show account usage and when the limits reset"),
            Command(name = "skills", description = "List the skills this session can use"),
            Command(name = "hooks", description = "List the lifecycle hooks this session runs"),
            Command(name = "mcp", description = "List the MCP servers and the tools they bring"),
        )

    val grokCommands: List<Command>
        get() = listOf(
            Command(name = "compact", description = "Compress the conversation so far"),
            Command(name = "context", description = "Show what is filling the context window"),
            Command(name = "session-info", description = "Show this session's id, model and token use"),
            Command(name = "hooks-list", description = "List the hooks this project runs"),
            Command(name = "hooks-add", description = "Add a hook to this project", argument = "event:command"),
            Command(name = "plugins", description = "List the installed plugins"),
            Command(name = "goal", description = "Set the goal for a long task", argument = "goal"),
            Command(name = "loop", description = "Repeat a task until it passes", argument = "instructions"),
            Command(name = "workflow", description = "Run a saved workflow", argument = "name"),
            Command(name = "deep-research", description = "Research a question across the web",
                    argument = "question"),
            Command(name = "review", description = "Review the working tree's changes"),
            Command(name = "implement", description = "Implement a plan step by step", argument = "plan"),
        )

    val piCommands: List<Command>
        get() = listOf(
            Command(name = "release-notes", description = "Draft release notes from the commits since a tag",
                    argument = "tag", group = "Prompts"),
            Command(name = "changelog", description = "Write the changelog entry for today's work",
                    group = "Prompts"),
            Command(name = "standup", description = "Summarise yesterday's work for standup", group = "Prompts"),
            Command(name = "skill:pdf-tables", description = "Extract tables from a PDF into CSV",
                    group = "Skills"),
            Command(name = "skill:web-research", description = "Search the web and summarise what it finds",
                    argument = "question", group = "Skills"),
            Command(name = "skill:screenshot", description = "Take a screenshot of a running page",
                    argument = "url", group = "Skills"),
            Command(name = "remote-control:status",
                    description = "Show what the remote-control extension is attached to",
                    group = "Extensions"),
            Command(name = "remote-control:handoff", description = "Hand this session back to the terminal",
                    group = "Extensions"),
            Command(name = "compact", description = "Summarise the conversation to free up context",
                    argument = "instructions", group = "Built-in"),
        )
}
