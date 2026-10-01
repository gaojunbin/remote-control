package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AttachmentInfo
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

// From RCCore's `Timeline.swift`, split for its length.

/**
 * Amendment A12: a message this app has sent that the device has not echoed back yet. The app mints
 * the `session.send` request id and the device returns it as the `user_message` block id, so the
 * row can be shown at once and replaced in place when the event arrives.
 */
data class OptimisticMessage(
    /** The `session.send` request id, which becomes the device's `block_id`. */
    val id: String,
    val text: String,
    val attachments: List<AttachmentInfo> = emptyList(),
    /** When the send left the app, for the unconfirmed timeout. */
    val sentAt: Instant = Instant.now(),
    /**
     * Amendment A14: the device answered `accepted: "steered"`, so it has the message and the
     * agent will read it at its next step. Such a row waits for as long as the turn takes and is
     * never in doubt.
     */
    val isSteering: Boolean = false,
) {
    fun isUnconfirmed(at: Instant = Instant.now()): Boolean {
        if (isSteering) return false
        return elapsed(at) >= unconfirmedAfter
    }

    /** Time left before this row says so, or zero once it has. */
    fun remainingBeforeUnconfirmed(at: Instant = Instant.now()): Duration =
        maxOf(Duration.ZERO, unconfirmedAfter - elapsed(at))

    private fun elapsed(now: Instant): Duration = java.time.Duration.between(sentAt, now).toKotlinDuration()

    companion object {
        /**
         * How long a send may wait before the row stops claiming it is on its way. Well past any
         * request timeout: what has not been confirmed by now is not slow, it is lost.
         */
        val unconfirmedAfter: Duration = 60.seconds
    }
}
