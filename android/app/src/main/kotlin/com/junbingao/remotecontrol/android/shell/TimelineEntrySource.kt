package com.junbingao.remotecontrol.android.shell

import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.state.TimelineEntry

/**
 * A storable event rebuilt from a rendered row, for the offline cache: the extension at the foot
 * of the iPhone's `AppModel.swift`. Streaming rows are stored as their finished form, so a cached
 * transcript holds whole answers rather than the last delta that happened to arrive; the rows that
 * only describe the session (status, meta, the queue) are not stored at all.
 */
val TimelineEntry.sourceEvent: SessionEvent?
    get() {
        val stored = when (val row = body) {
            is SessionEventBody.Status, is SessionEventBody.Meta, is SessionEventBody.Queue -> return null
            is SessionEventBody.AssistantText -> SessionEventBody.AssistantText(StreamTextPayload(text = text, done = true))
            is SessionEventBody.Thinking ->
                SessionEventBody.Thinking(StreamTextPayload(text = text, done = true, durationMS = row.payload.durationMS))
            else -> row
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
    get() = when (val row = body) {
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
        is SessionEventBody.Unknown -> row.kind
    }
