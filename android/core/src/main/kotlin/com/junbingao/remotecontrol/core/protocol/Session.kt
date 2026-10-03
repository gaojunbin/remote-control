package com.junbingao.remotecontrol.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitInfo(
    val branch: String? = null,
    val dirty: Boolean = false,
    val ahead: Int = 0,
    val behind: Int = 0,
    val worktree: Boolean = false,
)

@Serializable
data class TurnMarker(@SerialName("turn_id") val turnID: String, @SerialName("started_at") val startedAt: Long)

@Serializable
data class TodoCounts(val total: Int, val done: Int)

@Serializable
data class SessionUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("context_used") val contextUsed: Int? = null,
    @SerialName("context_window") val contextWindow: Int? = null,
    @SerialName("cost_usd") val costUSD: Double? = null,
) {
    /** 0…1, or null when the device did not report a window. */
    val contextFraction: Double?
        get() {
            val used = contextUsed ?: return null
            val window = contextWindow ?: return null
            if (window <= 0) return null
            return minOf(1.0, maxOf(0.0, used.toDouble() / window.toDouble()))
        }
}

/**
 * One conversation with one agent on one device. Global identity is (`deviceID`, `sessionID`);
 * `sessionID` alone is only device-local.
 */
@Serializable
data class Session(
    @SerialName("session_id") val sessionID: String,
    @SerialName("device_id") val deviceID: String,
    val agent: String = "",
    val title: String = "",
    val cwd: String = "",
    val git: GitInfo? = null,
    val state: SessionState = SessionState.idle,
    @SerialName("state_detail") val stateDetail: String? = null,
    val origin: EventSource = EventSource.remote,
    val control: SessionControl = SessionControl.none,
    val model: String? = null,
    @SerialName("permission_mode") val permissionMode: String? = null,
    val effort: String? = null,
    /** Amendment A21: the tier from `AgentInfo.speeds` this session runs at. Null is the agent's standard speed. */
    val speed: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("last_seq") val lastSeq: Int = 0,
    val archived: Boolean = false,
    val turn: TurnMarker? = null,
    val todos: TodoCounts? = null,
    val usage: SessionUsage? = null,
    val queued: Int = 0,
    /**
     * Amendment A35: the resume the device has scheduled for this session after a usage limit
     * (7.2), or null when there is none. It is what the notice above the transcript is drawn from.
     */
    val resume: SessionResume? = null,
    /**
     * Amendment A47: the session stopped working and waits for the person, and nobody on the
     * account has opened it since. The gateway's alone — a device never sends it — so a gateway
     * that predates it sends nothing, which reads as false. It is what the red dot is drawn from.
     */
    val unseen: Boolean = false,
) {
    /** Unique across devices, unlike `sessionID`. */
    val id: String get() = "$deviceID/$sessionID"

    /**
     * The composer is disabled while a live CLI process owns the input. Amendment A10: `shared` is
     * deliberately excluded — a live CLI owns the session but the device is attached to it, so
     * this app may still type.
     */
    val isControlledByTerminal: Boolean get() = control == SessionControl.terminal

    /**
     * Amendment A10: a live CLI process owns the session and the device is attached to it.
     * Composer, approvals and queue behave as for `remote`.
     */
    val isAttached: Boolean get() = control == SessionControl.shared

    /** Last path component of the working directory, for a compact subtitle. */
    val folderName: String
        get() {
            val trimmed = if (cwd.endsWith("/")) cwd.dropLast(1) else cwd
            return trimmed.split("/").lastOrNull { it.isNotEmpty() } ?: trimmed
        }
}
