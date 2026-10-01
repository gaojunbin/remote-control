package com.junbingao.remotecontrol.win.chat.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.state.OptimisticMessage
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.chat.support.ChatFractionalWidth
import com.junbingao.remotecontrol.win.chat.support.ChatStrutLine
import com.junbingao.remotecontrol.win.chat.support.ChatText
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.shared.Attach
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * `web/src/features/chat/blocks/UserMessageRow.tsx`: the person's own words, in a light grey
 * bubble on the right that hugs them (`docs/DESIGN.md` § "The timeline"). The bubble is inert: a
 * message is a record, not a control.
 *
 * `pending` is A12: set while this app is still waiting for the device to echo the send.
 */
@Composable
fun UserMessageRow(message: UserMessagePayload, pending: OptimisticMessage? = null) {
    val layout = LocalLayoutClass.current
    ChatFractionalWidth(fraction = if (layout.maxWidth1023) 0.88f else 0.78f, trailing = true) {
        Bubble(message, pending)
    }
}

@Composable
private fun Bubble(message: UserMessagePayload, pending: OptimisticMessage?) {
    VStack(
        Modifier
            .alpha(if (pending == null) 1f else 0.62f)
            .background(Palette.surfaceMuted, RoundedCornerShape(Radius.lg))
            .padding(vertical = 10.dp, horizontal = Space.sp4),
        spacing = 0.dp,
        alignment = Alignment.Start,
    ) {
        originLabel(message)?.let { origin ->
            Text(origin, css(FontSize.fs11, lineHeight = 1.55f), Modifier.padding(bottom = 2.dp), color = Palette.inkTertiary)
        }
        ChatText(ChatPreText.display(message.text), css(FontSize.fs14, lineHeight = 1.55f))
        if (message.attachments.isNotEmpty()) {
            WithForeground(Palette.inkSecondary) {
                HStack(Modifier.padding(top = 6.dp), spacing = 5.dp) {
                    Icon(LucideIcon.paperclip, size = 12.dp)
                    Text(S.chat.attachments(message.attachments.size), css(FontSize.fs12, lineHeight = 1.55f))
                }
            }
        }
        val delivery = Attach.deliveryLabel(message.delivery?.rawValue)
        if (pending != null) {
            BubbleLine { PendingChip(pending, it) }
        } else if (delivery != null) {
            BubbleLine { DeliveryChip(delivery, it) }
        }
    }
}

/**
 * The caption above the words. A message this app or another one sent needs none; a
 * terminal-typed one says where it was typed. A34: another agent's words never reach this row.
 * A35: the one prompt the device wrote for the person, once the usage limit reset, says so.
 */
private fun originLabel(message: UserMessagePayload): String? = when (message.source) {
    EventSource.terminal -> S.chat.fromTerminal
    EventSource.resume -> S.chat.fromResume
    else -> null
}

/**
 * The chip's line inside the bubble: an inline box on a line of its own, 6 points below the text,
 * on the bubble's 14-point, 1.55 strut.
 */
@Composable
private fun BubbleLine(content: @Composable (Modifier) -> Unit) {
    ChatStrutLine(size = FontSize.fs14, lineHeight = 1.55f) {
        content(Modifier.padding(top = 6.dp))
    }
}

/** `.delivery-chip`: where a message sent into an attached terminal got to. */
@Composable
fun DeliveryChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        css(FontSize.fs11, lineHeight = 1.55f),
        modifier.background(Palette.surface, CircleShape).padding(vertical = 1.dp, horizontal = 7.dp),
        color = Palette.inkTertiary,
        lineLimit = 1,
        softWrap = false,
    )
}

/**
 * The chip on a send the device has not confirmed, re-read on a slow clock. A14: a steered
 * message is accepted at once but reaches the agent only at its next step, so it says so instead
 * of counting towards a delivery problem it does not have.
 */
@Composable
private fun PendingChip(message: OptimisticMessage, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(message.id) {
        while (true) {
            delay(5_000)
            now = Instant.now()
        }
    }
    DeliveryChip(label(message, now), modifier)
}

private fun label(message: OptimisticMessage, now: Instant): String = when {
    message.isSteering -> S.chat.steering
    message.isUnconfirmed(at = now) -> S.composer.deliveryUnconfirmed
    else -> S.chat.sending
}
