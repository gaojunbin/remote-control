package com.junbingao.remotecontrol.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Result of `session.subscribe`. `resync: true` means the gateway buffer no longer covers
 * `since_seq`, so the timeline must be rebuilt from history.
 */
@Serializable
data class SubscribeResult(
    val session: Session,
    val events: List<SessionEvent> = emptyList(),
    val resync: Boolean = false,
    /**
     * Amendment A6: the messages waiting behind the current turn, so a freshly opened chat shows
     * the queue without waiting for the next snapshot event.
     */
    val queue: QueuePayload? = null,
)

@Serializable
data class SessionResult(val session: Session)

@Serializable
data class SendResult(val accepted: SendAcceptance, @SerialName("queued_id") val queuedID: String? = null)

@Serializable
data class HistoryResult(
    val events: List<SessionEvent> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class BlockResult(val event: SessionEvent)

/**
 * Amendment A27: the slash commands a session offers now. An empty list is an answer rather than
 * a failure — a device with no live process may know of none — so the app draws nothing and asks
 * again the next time `/` is typed.
 */
@Serializable
data class CommandsResult(val commands: List<Command> = emptyList())

@Serializable
data class DirectoryEntry(
    val name: String = "",
    val path: String,
    @SerialName("is_git") val isGit: Boolean = false,
) {
    val id: String get() = path
}

@Serializable
data class RecentDirectory(val path: String, @SerialName("last_used") val lastUsed: Long = 0) {
    val id: String get() = path
}

@Serializable
data class DirectoryListing(
    val path: String,
    val parent: String? = null,
    val entries: List<DirectoryEntry> = emptyList(),
    val recent: List<RecentDirectory> = emptyList(),
)

@Serializable
data class GitStatus(
    @SerialName("is_repo") val isRepo: Boolean = false,
    val branch: String? = null,
    val dirty: Boolean? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
) {
    val gitInfo: GitInfo?
        get() {
            if (!isRepo) return null
            return GitInfo(branch = branch, dirty = dirty ?: false, ahead = ahead ?: 0, behind = behind ?: 0)
        }
}

@Serializable
data class AgentsResult(val agents: List<AgentInfo>)

/**
 * Amendment A22: what a device says when it takes an update on. `from` is the build it is leaving,
 * which is the only thing the app learns that it did not already know.
 */
@Serializable
data class DeviceUpdateResult(val accepted: Boolean = false, val from: String? = null)
