package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.MessageDelivery
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.state.OptimisticMessage
import com.junbingao.remotecontrol.core.state.ResumeText
import kotlinx.coroutines.delay

/**
 * The person's own words. [pending] is set while this is the app's own copy, shown before the
 * device has echoed the message back (amendment A12).
 */
@Composable
internal fun UserMessageRow(payload: UserMessagePayload, pending: OptimisticMessage?) {
    // A send that has waited far longer than any request takes has not been confirmed, and the
    // row stops saying it is on its way.
    var isUnconfirmed by remember { mutableStateOf(false) }
    // One sleep per pending row, waking exactly when the wording changes, rather than a clock the
    // whole transcript redraws from. Amendment A14: a steered row has no such moment, because the
    // device confirmed it and only the agent's next step can move it on.
    LaunchedEffect(pending?.id) {
        if (pending == null || pending.isSteering) {
            isUnconfirmed = false
            return@LaunchedEffect
        }
        isUnconfirmed = pending.isUnconfirmed()
        if (isUnconfirmed) return@LaunchedEffect
        delay(pending.remainingBeforeUnconfirmed())
        isUnconfirmed = true
    }
    // The person's words sit on the right and hug their text, leaving room on the left, so what
    // was said is told from what was answered at a glance (`docs/DESIGN.md` § "The timeline"). A
    // short message stays short; a long one wraps against the margin the room on the left keeps.
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = Theme.Space.large * 2)
            .semantics(mergeDescendants = true) { contentDescription = UserMessageWords.spoken(payload, pending, isUnconfirmed) }
            .testTag(UserMessageWords.identifier(payload, pending, isUnconfirmed)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Bubble(payload, pending, isUnconfirmed)
    }
}

@Composable
private fun Bubble(payload: UserMessagePayload, pending: OptimisticMessage?, isUnconfirmed: Boolean) {
    Column(
        Modifier
            // The bubble is slightly back until the device has it, so the reader can tell what
            // has landed from what is still on its way.
            .alpha(if (pending == null || isUnconfirmed) 1f else 0.55f)
            .background(Theme.surfaceSunken, ContinuousShape(Theme.Radius.card))
            .padding(Theme.Space.small + 2.dp),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.tight),
    ) {
        SelectionContainer {
            Text(payload.text, Modifier.hugsLines(payload.text, SystemFont.body), style = SystemFont.body, color = Theme.ink)
        }
        if (payload.attachments.isNotEmpty()) {
            Foreground(Theme.inkSecondary) {
                Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Sf.paperclip, font = SystemFont.caption)
                    Text(payload.attachments.joinToString(", ") { it.name }, style = SystemFont.caption, lineLimit = 1)
                }
            }
        }
        when {
            payload.delivery == MessageDelivery.absorbed -> DeliveryChip(L10n.string("will be re-sent"))
            // A word, not a spinner: the message is already on screen, and a turning wheel would
            // say the app is busy when it is not.
            pending != null -> Text(
                UserMessageWords.pendingLabel(pending, isUnconfirmed),
                style = SystemFont.caption,
                color = if (isUnconfirmed) Theme.attention else Theme.inkSecondary,
            )
            // Amendment A35: the one message the device writes for the person. The transcript says
            // who continued the work and why.
            payload.source == EventSource.resume -> Text(
                ResumeText.sentForYou,
                Modifier.testTag("chat.message.resumeCaption"),
                style = SystemFont.caption,
                color = Theme.inkSecondary,
            )
            payload.source == EventSource.terminal ->
                Text(L10n.string("sent from the terminal"), style = SystemFont.caption, color = Theme.inkSecondary)
            payload.source == EventSource.queue ->
                Text(L10n.string("sent from the queue"), style = SystemFont.caption, color = Theme.inkSecondary)
        }
    }
}

/** What a message row says about itself, to the eye and to assistive technology. */
internal object UserMessageWords {
    /**
     * What the app's own copy of a message says about itself while it waits. Amendment A14: a
     * steered message is on the device already; what it is waiting for is the agent, which reads it
     * at its next step.
     */
    fun pendingLabel(pending: OptimisticMessage, isUnconfirmed: Boolean): String {
        if (pending.isSteering) return L10n.string("the agent will read it at its next step")
        return L10n.string(if (isUnconfirmed) "Delivery unconfirmed" else "Sending…")
    }

    /** The chip is inside one combined element, so its words reach a screen reader through the bubble's own label. */
    fun spoken(payload: UserMessagePayload, pending: OptimisticMessage?, isUnconfirmed: Boolean): String {
        val said = L10n.string("You said: %@", payload.text)
        if (payload.source == EventSource.resume && payload.delivery == null && pending == null) {
            return said + L10n.string(", sent for you after the limit reset")
        }
        if (payload.delivery == MessageDelivery.absorbed) return said + L10n.string(", will be re-sent")
        if (pending == null) return said
        if (pending.isSteering) return said + L10n.string(", the agent will read it at its next step")
        return said + L10n.string(if (isUnconfirmed) ", delivery unconfirmed" else ", sending")
    }

    /** Amendment A10: the row names its delivery state, so a test and a screen reader reach the chip even though the bubble is one element. */
    fun identifier(payload: UserMessagePayload, pending: OptimisticMessage?, isUnconfirmed: Boolean): String {
        payload.delivery?.let { return "chat.message.${it.rawValue}" }
        if (pending == null) return "chat.message"
        if (pending.isSteering) return "chat.message.steering"
        return if (isUnconfirmed) "chat.message.unconfirmed" else "chat.message.sending"
    }
}
