package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.screens.chat.markdown.MarkdownText
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.ErrorPayload
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.NoticeLevel
import com.junbingao.remotecontrol.core.protocol.NoticePayload
import com.junbingao.remotecontrol.core.protocol.ResumePayload
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.StopReason
import com.junbingao.remotecontrol.core.protocol.TurnCompletedPayload
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.RelativeTime
import com.junbingao.remotecontrol.core.state.ResumeText
import com.junbingao.remotecontrol.core.state.TimelineEntry

/**
 * One timeline row. Sub-agent output is drawn indented under its parent tool call rather than
 * interleaved with the main answer.
 */
@Composable
fun TimelineRow(entry: TimelineEntry, chat: ChatStore, nested: Boolean = false) {
    when (val body = entry.body) {
        // Amendment A34: what nobody typed is not drawn as though somebody had. The person's
        // bubble holds the person's words alone.
        is SessionEventBody.UserMessage ->
            if (body.payload.source == EventSource.agent) AgentMessageRow(body.payload) else UserMessageRow(body.payload, entry.pending)
        is SessionEventBody.AssistantText -> MarkdownText(entry.text, Modifier.padding(vertical = 2.dp))
        is SessionEventBody.Thinking -> ThinkingRow(entry.text, body.payload.durationMS, body.payload.done)
        is SessionEventBody.ToolCall -> ToolCallRow(entry, body.payload, chat, nested)
        is SessionEventBody.Approval -> ApprovalCard(entry, body.payload, chat)
        is SessionEventBody.Question -> QuestionCard(body.payload, chat)
        is SessionEventBody.Notice -> NoticeRow(body.payload)
        is SessionEventBody.Error -> ErrorRow(body.payload)
        is SessionEventBody.TurnCompleted -> TurnFooter(body.payload)
        is SessionEventBody.Resume -> ResumeRow(body.payload)
        is SessionEventBody.TurnStarted, is SessionEventBody.Todos, is SessionEventBody.Status,
        is SessionEventBody.Meta, is SessionEventBody.Queue -> Unit
        is SessionEventBody.Unknown -> Text(
            L10n.string("This device sent a %@ block that this app version cannot show yet.", body.kind),
            style = SystemFont.footnote,
            color = Theme.inkSecondary,
        )
    }
}

object TimelineRows {
    /**
     * Whether a row draws nothing at all, as an empty view does on the iPhone, which a stack then
     * leaves out of its spacing. The transcript lists only the rows that draw something.
     */
    fun drawsNothing(entry: TimelineEntry): Boolean = when (val body = entry.body) {
        is SessionEventBody.TurnStarted, is SessionEventBody.Todos, is SessionEventBody.Status,
        is SessionEventBody.Meta, is SessionEventBody.Queue -> true
        is SessionEventBody.Resume -> ResumeText.row(body.payload) == null
        else -> false
    }
}

/**
 * Amendment A10: the small grey pill under a message the CLI read as mid-turn data, which the
 * device has to inject again.
 */
@Composable
internal fun DeliveryChip(label: String) {
    Foreground(Theme.inkSecondary) {
        Row(
            Modifier
                .background(Theme.surface, CapsuleShape)
                .padding(horizontal = Theme.Space.tight, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Sf.clock, font = SystemFont.caption2)
            Text(label, style = SystemFont.caption)
        }
    }
}

@Composable
private fun NoticeRow(payload: NoticePayload) {
    val tint = when (payload.level) {
        NoticeLevel.error -> Theme.danger
        NoticeLevel.warn -> Theme.attention
        else -> Theme.inkSecondary
    }
    NoteLine(payload.text, tint)
}

@Composable
private fun ErrorRow(payload: ErrorPayload) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.danger.copy(alpha = 0.08f), ContinuousShape(Theme.Radius.control))
            .padding(Theme.Space.small)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(payload.message, style = SystemFont.footnote, color = Theme.danger)
        payload.code?.let { Text(it, style = SystemFont.caption2, color = Theme.inkSecondary) }
    }
}

@Composable
private fun TurnFooter(payload: TurnCompletedPayload) {
    // Amendment A35: a turn the vendor's window ended says so and says when it comes back. How
    // long it ran before it was refused tells nobody anything.
    val text = payload.limit?.let { ResumeText.turnEnd(it) } ?: run {
        val duration = RelativeTime.duration(milliseconds = payload.durationMS)
        when (payload.stopReason) {
            StopReason.interrupted -> L10n.string("Stopped after %@", duration)
            StopReason.error -> L10n.string("Ended with an error after %@", duration)
            else -> L10n.string("Finished in %@", duration)
        }
    }
    val rule = Theme.border
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Theme.Space.tight)
            .semantics(mergeDescendants = true) {}
            .testTag(if (payload.limit == null) "chat.turnFooter" else "chat.turnFooter.limit"),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(0.5.dp).background(rule))
        Text(text, style = Theme.Text.caption, color = Theme.inkSecondary)
        Box(Modifier.weight(1f).height(0.5.dp).background(rule))
    }
}

/**
 * Amendment A35: what the device did about a resume, in the notice voice. The moment of resuming
 * draws nothing — the prompt in the person's bubble and the turn it starts say it — so this row is
 * absent for `fired`.
 */
@Composable
private fun ResumeRow(payload: ResumePayload) {
    val text = ResumeText.row(payload) ?: return
    NoteLine(text, Theme.inkSecondary, Modifier.testTag("chat.resumeRow"))
}

/** A small dot and a line in the notice voice, the dot standing on the first line's middle. */
@Composable
private fun NoteLine(text: String, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight),
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .background(tint, CircleShape),
        )
        Text(text, Modifier.weight(1f), style = SystemFont.footnote, color = Theme.inkSecondary)
    }
}
