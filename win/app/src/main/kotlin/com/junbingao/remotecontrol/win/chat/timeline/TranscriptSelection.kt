package com.junbingao.remotecontrol.win.chat.timeline

import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.ResumeStatus
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.state.OptimisticMessage
import com.junbingao.remotecontrol.core.state.Timeline
import com.junbingao.remotecontrol.core.state.TimelineDetail
import com.junbingao.remotecontrol.core.state.TimelineEntry
import kotlin.math.max

/**
 * One row the transcript draws: a block the device sent, or a message this app has sent that the
 * device has not echoed yet (A12), which always sorts last and carries no `seq`.
 */
sealed interface TranscriptItem {
    data class Entry(val entry: TimelineEntry) : TranscriptItem
    data class Pending(val message: OptimisticMessage) : TranscriptItem

    val id: String
        get() = when (this) {
            is Entry -> entry.id
            is Pending -> message.id
        }
}

/**
 * `selectView` in `web/src/stores/timeline.ts`, over the core's transcript: the top-level rows one
 * detail level draws, and the rows nested under each tool call (`docs/DESIGN.md` § "The timeline"
 * → **Two levels of detail**).
 *
 * The web keeps a row only for the kinds it draws — never `status`, `meta`, `queue`, `todos` or
 * `turn_started`, never a turn that simply completed and never a resume that `fired` — so those
 * are left out here whatever the core holds for them. Simple drops the agent's workings along
 * with everything nested under them, except a card that needs an answer, which comes up to the top
 * level rather than going with the tool row that held it.
 */
class TranscriptSelection(timeline: Timeline, detail: TimelineDetail) {
    val roots: List<TranscriptItem>
    val children: Map<String, List<TimelineEntry>>

    /** How many rows the web's `timeline.order` would hold, whatever the level: it decides between "No messages yet" and "Start of the conversation". */
    val rowCount: Int

    /** The newest `seq` the transcript holds, live or from history: the web's `lastSeq`, which a first page of history moves and an older one does not. */
    val newestSeq: Int

    init {
        val drawn = HashMap<String, TimelineEntry>()
        var newest = timeline.lastSeq
        var count = 0
        val entries = timeline.entries
        for (entry in entries) {
            newest = max(newest, entry.latestSeq)
            if (!isRow(entry)) continue
            count += 1
            drawn[entry.id] = entry
        }
        val roots = mutableListOf<TranscriptItem>()
        val children = LinkedHashMap<String, MutableList<TimelineEntry>>()
        for (entry in entries) {
            if (!isRow(entry) || !isDrawn(entry, detail)) continue
            val parent = entry.parentID?.let { drawn[it] }
            if (parent != null && isDrawn(parent, detail)) {
                children.getOrPut(parent.id) { mutableListOf() } += entry
            } else if (parent == null || entry.needsReply) {
                roots += TranscriptItem.Entry(entry)
            }
        }
        for (message in timeline.optimistic) {
            if (timeline.entry(id = message.id) == null) roots += TranscriptItem.Pending(message)
        }
        this.roots = roots
        this.children = children
        rowCount = count
        newestSeq = newest
    }

    /** The key of the first drawn row, so a prepended history page can be told from a tail append. */
    val firstKey: String? get() = roots.firstOrNull()?.id

    /** The key of the last drawn row: a change means a genuinely new block. */
    val lastKey: String? get() = roots.lastOrNull()?.id

    companion object {
        /** `isRenderable`: whether the web keeps a row for this block at all. */
        fun isRow(entry: TimelineEntry): Boolean = when (val body = entry.body) {
            is SessionEventBody.Status, is SessionEventBody.Meta, is SessionEventBody.Queue, is SessionEventBody.Todos,
            is SessionEventBody.TurnStarted -> false
            is SessionEventBody.TurnCompleted -> body.payload.stopReason != StopReason.completed
            is SessionEventBody.Resume -> body.payload.status != ResumeStatus.fired
            else -> true
        }

        /**
         * `drawnAt`: Detailed draws every row, Simple leaves out the agent's workings — thinking, tool
         * calls that are not a slash command's own output (A27), and messages another agent put in
         * the conversation (A34).
         */
        fun isDrawn(entry: TimelineEntry, detail: TimelineDetail): Boolean =
            detail == TimelineDetail.detailed || !isWorkings(entry)

        fun isWorkings(entry: TimelineEntry): Boolean = when (val body = entry.body) {
            is SessionEventBody.Thinking -> true
            is SessionEventBody.ToolCall -> !body.payload.tool.startsWith("/")
            is SessionEventBody.UserMessage -> body.payload.source == EventSource.agent
            else -> false
        }
    }
}
