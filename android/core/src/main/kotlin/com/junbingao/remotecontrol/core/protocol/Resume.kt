package com.junbingao.remotecontrol.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Amendment A35: why a turn ended when the vendor's usage window was used up.
 *
 * The device reads this from the agent's own signal — Claude Code's 429 result, Codex's
 * `usageLimitExceeded` — never from the sentence the vendor wrote, so an app can say "the limit"
 * without parsing anybody's prose.
 */
@Serializable
data class LimitStop(
    /** The window that was hit, as `AgentLimit.window_minutes`: 300 for five hours, 10080 for a week. Absent when the device could not tell. */
    @SerialName("window_minutes") val windowMinutes: Int? = null,
    /** When the window resets, or null when the vendor named no time. */
    @SerialName("resets_at") val resetsAt: Long? = null,
)

/**
 * Amendment A35: the one resume a session can have pending after a usage limit stopped it. It
 * travels on the session summary, so every screen that holds a `Session` knows the session is
 * paused and when it comes back.
 */
@Serializable
data class SessionResume(
    /** When the device will send the resume prompt. */
    val at: Long = 0,
    /**
     * True when the vendor named no reset time and `at` was computed from the window's length,
     * which is what makes the app say "about".
     */
    val estimated: Boolean = false,
    /** How many resumes have already run into the limit again. The device drops the resume after the third. */
    val attempts: Int = 0,
    /** The window that was hit, when the device knows it. */
    @SerialName("window_minutes") val windowMinutes: Int? = null,
) {
    val date: Instant get() = Instant.ofEpochMilli(at)
}

/** Amendment A35: what the device did about a session the limit stopped. */
@JvmInline
@Serializable(with = ResumeStatus.Serializer::class)
value class ResumeStatus(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    /**
     * The moment of resuming is not a row of its own: the prompt appears in the person's bubble
     * and the turn it starts speaks for itself (`docs/DESIGN.md` § "Paused by the usage limit").
     */
    val isDrawn: Boolean get() = this != fired

    companion object {
        val scheduled = ResumeStatus("scheduled")
        val rescheduled = ResumeStatus("rescheduled")
        val fired = ResumeStatus("fired")
        val cancelled = ResumeStatus("cancelled")
        val dropped = ResumeStatus("dropped")
    }

    object Serializer : WireEnumSerializer<ResumeStatus>("ResumeStatus", ::ResumeStatus)
}

/**
 * The body of a `resume` event (protocol 5.15). Like `notice`, it changes no state: the pending
 * resume itself travels as `Session.resume`.
 */
@Serializable
data class ResumePayload(
    val status: ResumeStatus = ResumeStatus(""),
    /** For `scheduled` and `rescheduled`: when the prompt will be sent. */
    val at: Long? = null,
    val estimated: Boolean = false,
    /** For `rescheduled`: how many resumes have run into the limit again. */
    val attempts: Int? = null,
    /** For `cancelled` and `dropped`: why, in the device's words, one line. */
    val reason: String? = null,
)

/**
 * What `session.resume_set` will accept: at least a minute ahead and no more than eight days out
 * (protocol 6.3). The app checks them first so a time the device would refuse never leaves the
 * picker.
 */
object ResumeBounds {
    val leadTime: Duration = 60.seconds
    val horizon: Duration = 8.days

    fun earliest(from: Instant = Instant.now()): Instant = from.plus(leadTime.toJavaDuration())

    fun latest(from: Instant = Instant.now()): Instant = from.plus(horizon.toJavaDuration())

    fun allows(date: Instant, now: Instant = Instant.now()): Boolean =
        date >= earliest(from = now) && date <= latest(from = now)
}
