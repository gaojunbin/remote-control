package com.junbingao.remotecontrol.win.chat.timeline

import androidx.compose.runtime.Composable
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.TimelineEntry
import com.junbingao.remotecontrol.win.chat.blocks.AgentMessageRow
import com.junbingao.remotecontrol.win.chat.blocks.ApprovalCard
import com.junbingao.remotecontrol.win.chat.blocks.ErrorRow
import com.junbingao.remotecontrol.win.chat.blocks.NoticeRow
import com.junbingao.remotecontrol.win.chat.blocks.QuestionCard
import com.junbingao.remotecontrol.win.chat.blocks.ResumeStepRow
import com.junbingao.remotecontrol.win.chat.blocks.ThinkingRow
import com.junbingao.remotecontrol.win.chat.blocks.ToolRow
import com.junbingao.remotecontrol.win.chat.blocks.TurnEndRow
import com.junbingao.remotecontrol.win.chat.blocks.UserMessageRow
import com.junbingao.remotecontrol.win.chat.markdown.MarkdownText
import com.junbingao.remotecontrol.win.chat.resume.ResumeWords

/**
 * What the rows of a transcript can ask of the page: the requests the web's `TimelineHandlers`
 * carry. Each reports its own failure in the page's banner; `answer` is true when the device took
 * the answer, and a refusal keeps the card as it is.
 */
class TranscriptHandlers(
    val openFull: suspend (blockID: String) -> Unit,
    val approve: suspend (requestID: String, optionID: String) -> Unit,
    val answer: suspend (requestID: String, answers: Map<String, QuestionAnswer>) -> Boolean,
)

/** `ItemView` in `Timeline.tsx`: one row of the transcript, by kind. */
@Composable
fun TranscriptRow(item: TranscriptItem, children: List<TimelineEntry>, followsTool: Boolean, chat: ChatStore, handlers: TranscriptHandlers) {
    when (item) {
        is TranscriptItem.Pending -> UserMessageRow(
            UserMessagePayload(text = item.message.text, attachments = item.message.attachments, source = EventSource.remote),
            pending = item.message,
        )
        is TranscriptItem.Entry -> EntryRow(item.entry, children, followsTool, chat, handlers)
    }
}

object TranscriptRow {
    /**
     * Whether the row draws anything at all. The web's rows render `null` for an answer with no
     * text yet and for a kind this app does not know, and a row that renders nothing takes no gap
     * in the column.
     */
    fun draws(item: TranscriptItem): Boolean {
        val entry = (item as? TranscriptItem.Entry)?.entry ?: return true
        return when (val body = entry.body) {
            is SessionEventBody.AssistantText -> entry.text.isNotEmpty()
            is SessionEventBody.UserMessage, is SessionEventBody.Thinking, is SessionEventBody.ToolCall, is SessionEventBody.Approval,
            is SessionEventBody.Question, is SessionEventBody.Notice, is SessionEventBody.Error, is SessionEventBody.TurnCompleted -> true
            is SessionEventBody.Resume -> ResumeWords.rowText(body.payload) != null
            else -> false
        }
    }

    /** `.tool + .tool`. */
    fun isTool(item: TranscriptItem): Boolean = (item as? TranscriptItem.Entry)?.entry?.toolCall != null
}

@Composable
private fun EntryRow(entry: TimelineEntry, children: List<TimelineEntry>, followsTool: Boolean, chat: ChatStore, handlers: TranscriptHandlers) {
    when (val body = entry.body) {
        is SessionEventBody.UserMessage ->
            // A34: nobody typed these words, so they are drawn with the agent's own output rather
            // than in the person's bubble.
            if (body.payload.source == EventSource.agent) AgentMessageRow(body.payload.text) else UserMessageRow(body.payload)
        is SessionEventBody.AssistantText -> if (entry.text.isNotEmpty()) MarkdownText(entry.text)
        is SessionEventBody.Thinking -> ThinkingRow(entry.text, body.payload)
        is SessionEventBody.ToolCall -> {
            val nested = children.filter { TranscriptRow.draws(TranscriptItem.Entry(it)) }
            ToolRow(entry.id, body.payload, followsTool = followsTool, hasNested = nested.isNotEmpty(), onOpenFull = handlers.openFull) {
                for ((index, child) in nested.withIndex()) {
                    // The rows under a sub-agent's tool call draw none of their own.
                    EntryRow(child, emptyList(), followsTool = index > 0 && nested[index - 1].toolCall != null, chat, handlers)
                }
            }
        }
        is SessionEventBody.Approval -> ApprovalCard(body.payload, onDecide = handlers.approve)
        is SessionEventBody.Question -> QuestionCard(body.payload, chat, onAnswer = handlers.answer)
        is SessionEventBody.Notice -> NoticeRow(body.payload)
        is SessionEventBody.Error -> ErrorRow(body.payload)
        is SessionEventBody.TurnCompleted -> TurnEndRow(body.payload)
        is SessionEventBody.Resume -> ResumeStepRow(body.payload)
        else -> Unit
    }
}
