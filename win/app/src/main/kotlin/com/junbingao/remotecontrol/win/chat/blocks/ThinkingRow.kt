package com.junbingao.remotecontrol.win.chat.blocks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.win.chat.support.ChatStrutLine
import com.junbingao.remotecontrol.win.chat.support.ChatText
import com.junbingao.remotecontrol.win.chat.support.LocalChatButtonHovered
import com.junbingao.remotecontrol.win.chat.support.chatBare
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import kotlin.math.roundToInt

/**
 * `web/src/features/chat/blocks/ThinkingRow.tsx`: one quiet row, "Thought for 12s" or
 * "Thinking…", that opens to what the agent thought.
 */
@Composable
fun ThinkingRow(text: String, thinking: StreamTextPayload) {
    var open by remember { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    val turn by animateFloatAsState(if (open) 90f else 0f, Motion.ease(Motion.durFast, reduceMotion))
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        ChatStrutLine {
            Disabled(text.isEmpty()) {
                // An `inline-flex` box whose items are centred sits on the line by its first
                // item's baseline, which an icon has none of: its bottom edge stands in for one.
                Button({ open = !open }, Modifier.iconBaseline(ICON_SIZE), style = chatBare) {
                    val hovered = LocalChatButtonHovered.current
                    WithForeground(if (hovered && text.isNotEmpty()) Palette.ink else Palette.inkSecondary) {
                        HStack(spacing = 6.dp) {
                            Icon(LucideIcon.chevronRight, size = ICON_SIZE, modifier = Modifier.rotate(turn), color = Palette.inkTertiary)
                            Text(label(thinking), css(FontSize.fs13))
                        }
                    }
                }
            }
        }
        if (open && text.isNotEmpty()) {
            ChatText(
                ChatPreText.display(text),
                css(FontSize.fs13, lineHeight = 1.6f),
                Modifier
                    .padding(top = Space.sp2)
                    .drawBehind { drawRect(Palette.line, size = Size(2.dp.toPx(), size.height)) }
                    .padding(start = Space.sp4 + 2.dp)
                    .fillMaxWidth(),
                color = Palette.inkSecondary,
            )
        }
    }
}

private val ICON_SIZE: Dp = 12.dp

private fun label(thinking: StreamTextPayload): String {
    if (!thinking.done) return S.chat.thinking
    val spent = Format.duration((thinking.durationMS ?: 0).toDouble())
    return S.chat.thoughtFor(spent.ifEmpty { "—" })
}

/** The box's first baseline at its icon's bottom edge, the icon centred in it. */
private fun Modifier.iconBaseline(icon: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val baseline = ((placeable.height + icon.toPx()) / 2).roundToInt()
    layout(placeable.width, placeable.height, mapOf(FirstBaseline to baseline)) { placeable.place(0, 0) }
}
