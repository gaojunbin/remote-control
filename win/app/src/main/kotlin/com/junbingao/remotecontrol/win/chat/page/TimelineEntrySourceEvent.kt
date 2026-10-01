package com.junbingao.remotecontrol.win.chat.page

import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.state.TimelineEntry

/**
 * A storable event rebuilt from a row, for the transcript cache a conversation opens on next time
 * (the iPhone app keeps this in RCUI, which the Mac does not link).
 *
 * Streaming rows are stored in their finished form, so a cached transcript holds whole answers
 * rather than the last delta that happened to arrive.
 */
val TimelineEntry.sourceEvent: SessionEvent?
    get() {
        val stored: SessionEventBody = when (val content = body) {
            is SessionEventBody.Status, is SessionEventBody.Meta, is SessionEventBody.Queue -> return null
            is SessionEventBody.AssistantText -> SessionEventBody.AssistantText(StreamTextPayload(text = text, done = true))
            is SessionEventBody.Thinking ->
                SessionEventBody.Thinking(StreamTextPayload(text = text, done = true, durationMS = content.payload.durationMS))
            else -> content
        }
        return SessionEvent(
            seq = seq,
            ts = ts,
            kind = wireKind,
            blockID = if (id.startsWith("seq:")) null else id,
            parentBlockID = parentID,
            body = stored,
        )
    }

private val TimelineEntry.wireKind: String
    get() = when (val content = body) {
        is SessionEventBody.UserMessage -> SessionEvent.userMessageKind
        is SessionEventBody.AssistantText -> SessionEvent.assistantTextKind
        is SessionEventBody.Thinking -> SessionEvent.thinkingKind
        is SessionEventBody.ToolCall -> SessionEvent.toolCallKind
        is SessionEventBody.Todos -> SessionEvent.todosKind
        is SessionEventBody.Approval -> SessionEvent.approvalKind
        is SessionEventBody.Question -> SessionEvent.questionKind
        is SessionEventBody.TurnStarted -> SessionEvent.turnStartedKind
        is SessionEventBody.TurnCompleted -> SessionEvent.turnCompletedKind
        is SessionEventBody.Status -> SessionEvent.statusKind
        is SessionEventBody.Meta -> SessionEvent.metaKind
        is SessionEventBody.Queue -> SessionEvent.queueKind
        is SessionEventBody.Notice -> SessionEvent.noticeKind
        is SessionEventBody.Error -> SessionEvent.errorKind
        is SessionEventBody.Resume -> SessionEvent.resumeKind
        is SessionEventBody.Unknown -> content.kind
    }
