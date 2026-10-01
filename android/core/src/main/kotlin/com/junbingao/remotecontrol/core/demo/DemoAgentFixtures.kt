package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.AccountMethod
import com.junbingao.remotecontrol.core.protocol.AgentAccount
import com.junbingao.remotecontrol.core.protocol.AgentAttach
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AgentOption

/** The demo's agents, part of [DemoFixtures]: read them as `DemoFixtures.claude`. */
sealed interface DemoAgentFixtures {
    val claude: AgentInfo
        get() = AgentInfo(
            agent = "claude", available = true, version = "2.1.266", path = "/usr/local/bin/claude",
            models = listOf(AgentOption(id = "claude-sonnet-4-5", label = "Sonnet 4.5"),
                            AgentOption(id = "claude-opus-4-1", label = "Opus 4.1")),
            defaultModel = "claude-sonnet-4-5",
            permissionModes = listOf(AgentOption(id = "default", label = "Ask before edits"),
                                     AgentOption(id = "acceptEdits", label = "Auto-accept edits"),
                                     AgentOption(id = "plan", label = "Plan mode"),
                                     AgentOption(id = "bypassPermissions", label = "Bypass permissions")),
            defaultPermissionMode = "acceptEdits",
            efforts = listOf(AgentOption(id = "medium", label = "Medium"), AgentOption(id = "high", label = "High")),
            defaultEffort = "high",
            capabilities = listOf(AgentCapability.worktree, AgentCapability.takeover, AgentCapability.interrupt,
                                  AgentCapability.queue, AgentCapability.attachments, AgentCapability.effort,
                                  AgentCapability.history, AgentCapability.commands),
            // Amendment A42: Stop types Escape into the same pseudo-terminal.
            attach = AgentAttach.channel, attachReady = true, sharedInterrupt = true,
            // Amendment A40: the shim runs the CLI inside a pseudo-terminal the device owns, so
            // the device types `/model`, `/effort` and `/compact` into it as the person would.
            // There is no command it could type for the permission mode, so that one stays the
            // terminal's — which is exactly what the two keys say.
            sharedSettings = true, sharedSettingsKeys = listOf("model", "effort"),
            accounts = listOf(AgentAccount(provider = "anthropic", method = AccountMethod.account, plan = "max",
                                           tier = "Max 5x", email = "me@example.com")),
        )

    /**
     * The same agent on a machine where the `claude` shim was never installed, so its terminal
     * sessions cannot be attached (amendment A10) and nothing can be typed into them (A40): no
     * shared settings and no commands.
     */
    val claudeWithoutShim: AgentInfo
        get() = AgentInfo(
            agent = "claude", available = true, version = "2.1.266", path = "/usr/local/bin/claude",
            models = claude.models, defaultModel = claude.defaultModel,
            permissionModes = claude.permissionModes, defaultPermissionMode = "default",
            efforts = claude.efforts, defaultEffort = claude.defaultEffort,
            capabilities = listOf(AgentCapability.worktree, AgentCapability.takeover, AgentCapability.interrupt,
                                  AgentCapability.queue, AgentCapability.attachments, AgentCapability.effort,
                                  AgentCapability.history),
            attach = AgentAttach.channel, attachReady = false, sharedInterrupt = false,
            accounts = listOf(AgentAccount(provider = "anthropic", method = AccountMethod.account, plan = "pro",
                                           email = "me@example.com")),
        )

    /**
     * Amendment A11: Codex behind a running app-server daemon. Everything the daemon relays is
     * true here — an interrupt, the thread settings and image inputs — so a `shared` session
     * keeps every control.
     */
    val codex: AgentInfo
        get() = AgentInfo(
            agent = "codex", available = true, version = "0.154.0",
            path = "/Users/me/.codex/packages/standalone/current/bin/codex",
            models = listOf(AgentOption(id = "gpt-5.4-codex", label = "GPT-5.4 Codex"),
                            AgentOption(id = "gpt-5.4-codex-mini", label = "GPT-5.4 Codex mini")),
            defaultModel = "gpt-5.4-codex",
            permissionModes = listOf(AgentOption(id = "untrusted", label = "Ask for everything"),
                                     AgentOption(id = "on-request", label = "Ask when needed"),
                                     AgentOption(id = "never", label = "Never ask")),
            defaultPermissionMode = "on-request",
            efforts = listOf(AgentOption(id = "low", label = "Low"),
                             AgentOption(id = "medium", label = "Medium"),
                             AgentOption(id = "high", label = "High")),
            defaultEffort = "medium",
            speeds = listOf(AgentOption(id = "priority", label = "Fast")),
            capabilities = listOf(AgentCapability.worktree, AgentCapability.interrupt, AgentCapability.queue,
                                  AgentCapability.steer, AgentCapability.attachments, AgentCapability.effort,
                                  AgentCapability.history, AgentCapability.commands),
            attach = AgentAttach.daemon, attachReady = true, sharedInterrupt = true,
            sharedSettings = true, sharedAttachments = true,
            accounts = listOf(AgentAccount(provider = "openai", method = AccountMethod.account, plan = "pro",
                                           email = "me@example.com")),
        )

    /**
     * The same agent on a machine where the app-server daemon is not running, so its terminal
     * threads cannot be attached (amendments A10 and A11).
     */
    val codexWithoutDaemon: AgentInfo
        get() = AgentInfo(
            agent = "codex", available = true, version = codex.version, path = codex.path,
            models = codex.models, defaultModel = codex.defaultModel,
            permissionModes = codex.permissionModes, defaultPermissionMode = codex.defaultPermissionMode,
            efforts = codex.efforts, defaultEffort = codex.defaultEffort, speeds = codex.speeds,
            capabilities = codex.capabilities,
            attach = AgentAttach.daemon, attachReady = false,
            accounts = listOf(AgentAccount(provider = "openai", method = AccountMethod.account, plan = "plus",
                                           email = "me@example.com")),
        )

    /**
     * Amendment A25: Grok Build, driven over its ACP JSON-RPC. Amendment A28: its terminals join
     * one leader process per machine, which the device joins too, so `session/cancel` and
     * `session/set_config_option` from here act on the session everyone is in — and a prompt
     * carries no images. Exactly what `protocol/fixtures/objects/agent.grok.json` advertises.
     */
    val grok: AgentInfo
        get() = AgentInfo(
            agent = "grok", available = true, version = "1.0.30", path = "/Users/me/.grok/bin/agent",
            models = listOf(AgentOption(id = "grok-4.6", label = "Grok 4.6"),
                            AgentOption(id = "grok-4.5", label = "Grok 4.5")),
            defaultModel = "grok-4.6",
            permissionModes = listOf(AgentOption(id = "default", label = "Ask when needed"),
                                     AgentOption(id = "acceptEdits", label = "Auto-accept edits"),
                                     AgentOption(id = "auto", label = "Auto mode"),
                                     AgentOption(id = "dontAsk", label = "Deny unless allowed"),
                                     AgentOption(id = "plan", label = "Plan mode"),
                                     AgentOption(id = "bypassPermissions", label = "Bypass permissions")),
            defaultPermissionMode = "default",
            efforts = listOf(AgentOption(id = "low", label = "Low"),
                             AgentOption(id = "medium", label = "Medium"),
                             AgentOption(id = "high", label = "High"),
                             AgentOption(id = "xhigh", label = "Extra high")),
            defaultEffort = "high",
            capabilities = listOf(AgentCapability.worktree, AgentCapability.interrupt, AgentCapability.queue,
                                  AgentCapability.effort, AgentCapability.history, AgentCapability.commands),
            attach = AgentAttach.leader, attachReady = true, sharedInterrupt = true, sharedSettings = true,
            accounts = listOf(AgentAccount(provider = "xai", method = AccountMethod.account, plan = null,
                                           email = "me@example.com")),
        )

    /**
     * Amendment A28: the same agent on a machine whose `~/.grok/config.toml` leaves
     * `[cli] use_leader` off, so a `grok` started there runs its own backend and nothing can join
     * it. What the leader relays is a property of the leader, not of this machine, so only the
     * readiness differs.
     */
    val grokWithoutLeader: AgentInfo
        get() = AgentInfo(
            agent = "grok", available = true, version = grok.version, path = grok.path,
            models = grok.models, defaultModel = grok.defaultModel,
            permissionModes = grok.permissionModes, defaultPermissionMode = grok.defaultPermissionMode,
            efforts = grok.efforts, defaultEffort = grok.defaultEffort,
            capabilities = grok.capabilities,
            attach = AgentAttach.leader, attachReady = false, sharedInterrupt = true, sharedSettings = true,
            // Amendment A33: installed, signed in nowhere.
            accounts = emptyList(),
        )

    /**
     * Amendment A26: the pi coding agent behind the device's own extension, which pi loads into
     * every session it runs. The extension enforces pi's three permission modes and carries an
     * interrupt, the session settings and image inputs, so a `shared` pi session keeps every
     * control. Exactly what `protocol/fixtures/objects/agent.pi.json` advertises.
     */
    val pi: AgentInfo
        get() = AgentInfo(
            agent = "pi", available = true, version = "0.85.1", path = "/Users/me/.local/bin/pi",
            models = listOf(AgentOption(id = "anthropic/claude-sonnet-4-5", label = "Claude Sonnet 4.5"),
                            AgentOption(id = "openai/gpt-5", label = "GPT-5")),
            defaultModel = "anthropic/claude-sonnet-4-5",
            permissionModes = listOf(AgentOption(id = "untrusted", label = "Ask for everything"),
                                     AgentOption(id = "on-request", label = "Ask when needed"),
                                     AgentOption(id = "never", label = "Never ask")),
            defaultPermissionMode = "on-request",
            efforts = listOf(AgentOption(id = "off", label = "Off"),
                             AgentOption(id = "low", label = "Low"),
                             AgentOption(id = "medium", label = "Medium"),
                             AgentOption(id = "high", label = "High")),
            defaultEffort = "medium",
            capabilities = listOf(AgentCapability.worktree, AgentCapability.interrupt, AgentCapability.queue,
                                  AgentCapability.steer, AgentCapability.attachments, AgentCapability.effort,
                                  AgentCapability.history, AgentCapability.commands),
            attach = AgentAttach.extension, attachReady = true, sharedInterrupt = true,
            sharedSettings = true, sharedAttachments = true,
            // Amendment A33: pi signs in per provider, so it holds one credential per vendor —
            // here a subscription and a relayed key.
            accounts = listOf(AgentAccount(provider = "anthropic", method = AccountMethod.account, plan = "max",
                                           email = "me@example.com"),
                              AgentAccount(provider = "openai", method = AccountMethod.apiKey,
                                           endpoint = "api.relay.example")),
        )
}
