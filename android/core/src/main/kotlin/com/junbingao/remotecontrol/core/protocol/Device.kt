package com.junbingao.remotecontrol.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A labelled choice offered by an agent (model, permission mode, effort). The id is opaque and is
 * echoed back verbatim in `session.set`.
 */
@Serializable
data class AgentOption(val id: String, val label: String)

/** What one agent installed on one device can do. */
@Serializable
data class AgentInfo(
    val agent: String,
    val available: Boolean = false,
    val version: String? = null,
    val path: String? = null,
    val models: List<AgentOption> = emptyList(),
    @SerialName("default_model") val defaultModel: String? = null,
    @SerialName("permission_modes") val permissionModes: List<AgentOption> = emptyList(),
    @SerialName("default_permission_mode") val defaultPermissionMode: String? = null,
    val efforts: List<AgentOption> = emptyList(),
    @SerialName("default_effort") val defaultEffort: String? = null,
    /**
     * Amendment A21: the tiers this agent can run a session at beyond its standard speed, such as
     * Codex's `priority`. Empty when it has none, and an agent with an empty list draws no speed
     * control at all.
     */
    val speeds: List<AgentOption> = emptyList(),
    val capabilities: List<AgentCapability> = emptyList(),
    /** Amendment A10: how this agent's terminal sessions can be attached, or null when they can only be taken over or resumed. */
    val attach: AgentAttach? = null,
    /** Whether the device is prepared to attach the next terminal session. Apps use it only to word the hint on a `terminal` session. */
    @SerialName("attach_ready") val attachReady: Boolean = false,
    /** Whether `session.stop` works on a `shared` session. */
    @SerialName("shared_interrupt") val sharedInterrupt: Boolean = false,
    /**
     * Amendment A11: whether `session.set` reaches the live CLI on a `shared` session. The Codex
     * daemon applies model, permission mode and effort to the running thread; a Claude channel
     * cannot.
     */
    @SerialName("shared_settings") val sharedSettings: Boolean = false,
    /**
     * Amendment A40: with [sharedSettings] true, which of the four settings `session.set` really
     * changes on a `shared` session. Claude's shim types `/model` and `/effort` into the terminal
     * it owns and has no command for the permission mode, so it names the two it can do. Null —
     * what every other attachment reports — means all four.
     */
    @SerialName("shared_settings_keys") val sharedSettingsKeys: List<String>? = null,
    /**
     * Amendment A11: whether `session.send` attachments are delivered on a `shared` session. The
     * Codex daemon takes image inputs; a Claude channel has no way to hand bytes to a live CLI.
     */
    @SerialName("shared_attachments") val sharedAttachments: Boolean = false,
    /**
     * Amendment A33: how this agent is signed in on the device, one entry per vendor credential it
     * holds. Null when the device did not look, which is what an older client reports; empty when
     * the agent is installed and signed in nowhere. A `device.agents` reply carries the rate-limit
     * windows with them; `hello` and `agents.updated` never do.
     */
    val accounts: List<AgentAccount>? = null,
) {
    val id: String get() = agent

    /** Unknown agent ids render generically with the id as the label. */
    val displayName: String get() = AgentLabel.name(agent)

    fun supports(capability: AgentCapability): Boolean = capability in capabilities

    /**
     * Amendment A40: whether `session.set` changes one setting on a `shared` session of this
     * agent. An attachment that carries nothing shares nothing; one that names no keys shares all
     * four; one that names them shares exactly those, so a key this build does not know about is
     * not offered as a control that would be refused when tapped.
     */
    fun shares(setting: SharedSetting): Boolean {
        if (!sharedSettings) return false
        val keys = sharedSettingsKeys ?: return true
        return setting.rawValue in keys
    }

    fun modelLabel(id: String?): String? {
        if (id == null) return null
        return models.firstOrNull { it.id == id }?.label ?: id
    }

    fun permissionModeLabel(id: String?): String? {
        if (id == null) return null
        return permissionModes.firstOrNull { it.id == id }?.label ?: id
    }

    fun effortLabel(id: String?): String? {
        if (id == null) return null
        return efforts.firstOrNull { it.id == id }?.label ?: id
    }

    /**
     * Amendment A44: where an effort stands on this agent's own scale — 0 at its lowest level, 1
     * at its highest, the others evenly between — which is where the model card's gauge points its
     * needle. Null where there is no scale to read: fewer than two levels, or a value the agent
     * does not list.
     */
    fun effortPosition(id: String?): Double? {
        if (efforts.size <= 1 || id == null) return null
        val index = efforts.indexOfFirst { it.id == id }
        if (index < 0) return null
        return index.toDouble() / (efforts.size - 1).toDouble()
    }

    /** The label for a tier, or null for the standard speed. */
    fun speedLabel(id: String?): String? {
        if (id == null) return null
        return speeds.firstOrNull { it.id == id }?.label ?: id
    }

    /**
     * The same agent carrying these credentials: the fresh ones a `device.agents` reply brought,
     * or the stored ones with their windows dropped (A33).
     */
    fun with(accounts: List<AgentAccount>?): AgentInfo = copy(accounts = accounts)
}

/** A machine running the client daemon. */
@Serializable
data class Device(
    @SerialName("device_id") val deviceID: String,
    val name: String = deviceID,
    val platform: DevicePlatform = DevicePlatform.linux,
    val hostname: String = "",
    val arch: String = "",
    @SerialName("client_version") val clientVersion: String = "",
    /**
     * Amendment A22: the SHA-256 of the wheel this client was installed from, or null when it was
     * installed from source and cannot say.
     */
    @SerialName("client_build") val clientBuild: String? = null,
    /** The field is optional on the wire, and "absent" means idle (A22). */
    @SerialName("update_state") val updateState: DeviceUpdateState = DeviceUpdateState.idle,
    @SerialName("update_message") val updateMessage: String? = null,
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("latency_ms") val latencyMS: Int? = null,
    val agents: List<AgentInfo> = emptyList(),
    /**
     * Amendment A38: whether this machine offers a shell over the gateway. Null where the client
     * is older than the amendment or never said, which reads the same way as false: the row's tap
     * has nothing to open.
     */
    val terminal: Boolean? = null,
) {
    val id: String get() = deviceID

    val availableAgents: List<AgentInfo> get() = agents.filter { it.available }

    fun agent(id: String?): AgentInfo? {
        if (id == null) return null
        return agents.firstOrNull { it.agent == id }
    }

    /** Amendment A38: whether tapping this row can open a shell right now. */
    val offersTerminal: Boolean get() = terminal == true
}
