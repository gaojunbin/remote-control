package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.ApprovalPayload
import com.junbingao.remotecontrol.core.protocol.ErrorPayload
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.NoticePayload
import com.junbingao.remotecontrol.core.protocol.QuestionPayload
import com.junbingao.remotecontrol.core.protocol.ResumePayload
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.protocol.TurnCompletedPayload
import com.junbingao.remotecontrol.core.protocol.TurnStartedPayload
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import kotlin.math.min

// From RCCore's `Timeline.swift`, split for its length.

/**
 * One renderable row. Block events collapse into a single entry keyed by `block_id`; everything
 * else gets a synthetic id derived from its `seq`.
 *
 * A value: [merge] answers the entry a later event makes, and the [Timeline] puts it in this one's
 * place.
 */
@ConsistentCopyVisibility
data class TimelineEntry internal constructor(
    val id: String,
    /**
     * Position in the transcript. Amendment A8: a block that keeps streaming takes the seq of its
     * first appearance, so it holds its place while its `seq` rises.
     */
    val seq: Int,
    /** The raw seq of the event that created this entry, which is what a history cursor may be built from. */
    val eventSeq: Int,
    /** The newest seq applied to this entry, for late-frame rejection. */
    val latestSeq: Int,
    val ts: Long,
    val parentID: String?,
    val body: SessionEventBody,
    /** Accumulated streaming text for `assistant_text` and `thinking`. */
    val text: String,
    /**
     * Amendment A12: set on a row this app has sent and the device has not confirmed. Such a row
     * holds no device `seq` and is never stored in the transcript, so nothing it does can move the
     * replay cursor.
     */
    val pending: OptimisticMessage? = null,
) {
    /**
     * Apply a later event for the same block. A `delta` appends; anything else replaces the entry
     * wholesale, per the protocol's replacement rule.
     */
    internal fun merge(event: SessionEvent): TimelineEntry {
        val merged = when (val next = event.body) {
            is SessionEventBody.AssistantText -> streamed(next.payload)
            is SessionEventBody.Thinking -> streamed(next.payload)
            else -> initialText(next)
        }
        // A later event may be the first to name the block's origin, and an earlier one may arrive
        // out of order; the earliest position wins.
        return copy(latestSeq = event.seq, seq = min(seq, event.orderSeq), eventSeq = min(eventSeq, event.seq),
                    ts = event.ts, body = event.body, text = merged)
    }

    private fun streamed(payload: StreamTextPayload): String {
        val delta = payload.delta
        val full = payload.text
        return when {
            delta != null && full == null -> text + delta
            full != null -> full
            else -> text
        }
    }

    val userMessage: UserMessagePayload? get() = (body as? SessionEventBody.UserMessage)?.payload
    val assistantText: StreamTextPayload? get() = (body as? SessionEventBody.AssistantText)?.payload
    val thinking: StreamTextPayload? get() = (body as? SessionEventBody.Thinking)?.payload
    val toolCall: ToolCallPayload? get() = (body as? SessionEventBody.ToolCall)?.payload
    val approval: ApprovalPayload? get() = (body as? SessionEventBody.Approval)?.payload
    val question: QuestionPayload? get() = (body as? SessionEventBody.Question)?.payload
    val notice: NoticePayload? get() = (body as? SessionEventBody.Notice)?.payload
    val errorPayload: ErrorPayload? get() = (body as? SessionEventBody.Error)?.payload
    val turnStarted: TurnStartedPayload? get() = (body as? SessionEventBody.TurnStarted)?.payload
    val turnCompleted: TurnCompletedPayload? get() = (body as? SessionEventBody.TurnCompleted)?.payload

    /** Amendment A35: what the device did about a resume after a usage limit. */
    val resume: ResumePayload? get() = (body as? SessionEventBody.Resume)?.payload

    /** Rows a sub-agent produced hang under their parent tool call. */
    val isNested: Boolean get() = parentID != null

    /** A card that puts a decision to the reader. Neither level hides one, and not even an answered one: a row must not disappear as it is answered. */
    val needsReply: Boolean get() = approval != null || question != null

    /**
     * Whether a detail level draws this row at all.
     *
     * Simple shows what is written to the reader and nothing else: their own messages, the agent's
     * prose, the approval and question cards, notices, errors, and the end of a turn that stopped
     * or failed. A turn that simply finished ends with the answer itself, so its footer says nothing
     * the transcript does not already show. A send this app has not had echoed back is always drawn,
     * whatever it is going to become.
     *
     * Amendment A34: a message another agent put into the conversation is the agent's working
     * rather than the reader's own words, so Simple hides it as it hides the rest — which is why the
     * rule reads a message's `source` and not only its kind.
     */
    fun isDrawn(at: TimelineDetail): Boolean {
        if (!isRenderable) return false
        if (at != TimelineDetail.simple) return true
        if (pending != null) return true
        return when (body) {
            is SessionEventBody.UserMessage -> body.payload.source != EventSource.agent
            // Amendment A35: a resume row is the device saying what it did about a session the
            // limit stopped, which is written to the reader exactly as a notice is.
            is SessionEventBody.AssistantText, is SessionEventBody.Approval, is SessionEventBody.Question,
            is SessionEventBody.Notice, is SessionEventBody.Error, is SessionEventBody.Resume -> true
            is SessionEventBody.TurnCompleted ->
                body.payload.stopReason == StopReason.interrupted || body.payload.stopReason == StopReason.error
            // Amendment A27: a tool call named after a slash command is the whole answer to
            // something the reader asked for by name, so it is drawn at every level. Simple hides
            // the agent's own working, not the reply.
            is SessionEventBody.ToolCall -> body.payload.tool.startsWith("/")
            else -> false
        }
    }

    /** Entries that carry no visible content of their own. */
    val isRenderable: Boolean
        get() = when (body) {
            is SessionEventBody.Todos, is SessionEventBody.Status, is SessionEventBody.Meta,
            is SessionEventBody.Queue -> false
            is SessionEventBody.AssistantText, is SessionEventBody.Thinking -> text.isNotEmpty() || !isStreaming
            // Amendment A35: the moment of resuming is the prompt in the person's bubble and the
            // turn it starts; `fired` adds nothing to either.
            is SessionEventBody.Resume -> body.payload.status.isDrawn
            else -> true
        }

    val isStreaming: Boolean
        get() = when (body) {
            is SessionEventBody.AssistantText -> !body.payload.done
            is SessionEventBody.Thinking -> !body.payload.done
            is SessionEventBody.ToolCall -> body.payload.status == ToolStatus.running
            else -> false
        }

    companion object {
        internal operator fun invoke(event: SessionEvent, id: String): TimelineEntry = TimelineEntry(
            id = id, seq = event.orderSeq, eventSeq = event.seq, latestSeq = event.seq, ts = event.ts,
            parentID = event.parentBlockID, body = event.body, text = initialText(event.body),
        )

        /** The row a sent message takes until the device's own event replaces it. */
        internal operator fun invoke(pending: OptimisticMessage): TimelineEntry = TimelineEntry(
            id = pending.id,
            // Nothing the device sent can sort after this, and nothing here can be mistaken for a
            // real event seq.
            seq = Int.MAX_VALUE, eventSeq = Int.MAX_VALUE, latestSeq = Int.MAX_VALUE,
            ts = pending.sentAt.toEpochMilli(), parentID = null,
            body = SessionEventBody.UserMessage(UserMessagePayload(text = pending.text, attachments = pending.attachments,
                                                                   source = EventSource.remote)),
            text = "", pending = pending,
        )

        private fun initialText(body: SessionEventBody): String = when (body) {
            is SessionEventBody.AssistantText -> body.payload.text ?: body.payload.delta ?: ""
            is SessionEventBody.Thinking -> body.payload.text ?: body.payload.delta ?: ""
            else -> ""
        }
    }
}
