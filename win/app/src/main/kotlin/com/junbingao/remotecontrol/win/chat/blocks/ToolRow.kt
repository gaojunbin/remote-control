package com.junbingao.remotecontrol.win.chat.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.state.DotTone
import com.junbingao.remotecontrol.win.chat.support.ChatBorder
import com.junbingao.remotecontrol.win.chat.support.LocalChatButtonHovered
import com.junbingao.remotecontrol.win.chat.support.chatBare
import com.junbingao.remotecontrol.win.chat.support.chatBorder
import com.junbingao.remotecontrol.win.design.Badge
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.Dot
import com.junbingao.remotecontrol.win.design.DotStyle
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.shared.Format
import kotlinx.coroutines.delay

/**
 * `web/src/features/chat/blocks/ToolRow.tsx`: one line per tool call — its icon, name, monospace
 * title, diff counts, result chip and duration — that opens to the input, the diff and the
 * output. A running tool shows its live output without being opened, and the rows a sub-agent
 * produced hang under it whether it is open or not.
 *
 * `followsTool` is `.tool + .tool`: a row right after another tool row draws no top rule.
 */
@Composable
fun ToolRow(
    blockID: String,
    tool: ToolCallPayload,
    followsTool: Boolean,
    hasNested: Boolean,
    onOpenFull: suspend (String) -> Unit,
    nested: @Composable () -> Unit,
) {
    val stage = LocalPreviewStage.current
    // Null until the row is first clicked. A render opens every row there is something to open
    // in, as clicks would, from the row's first layout on.
    var toggled by remember { mutableStateOf<Boolean?>(null) }
    val open = toggled ?: (stage == "chat.tools.open" && ToolRowModel(tool, open = false).hasDetail)
    val model = ToolRowModel(tool, open)
    VStack(
        Modifier
            // `margin: -1px 0`: neighbouring rules overlap the gap by a point.
            .verticalMargin((-1).dp)
            .chatBorder(border(model, followsTool), Palette.line)
            .then(if (model.running) Modifier.background(Palette.surfaceSunken, RoundedCornerShape(Radius.md)) else Modifier)
            // The rules take their point: top and bottom always, the sides while the tool runs.
            .padding(horizontal = if (model.running) 1.dp else 0.dp, vertical = 1.dp)
            .fillMaxWidth(),
        spacing = 0.dp,
        alignment = Alignment.Start,
    ) {
        ToolHead(tool, model, onToggle = { toggled = !open })
        if (model.expanded) {
            ToolBody(blockID, tool, model, hasNested, onOpenFull, nested)
        } else if (hasNested) {
            ToolChildren(margin = Space.sp5, modifier = Modifier.padding(bottom = Space.sp2), content = nested)
        }
    }
}

/**
 * `.tool.running` and `.tool:has(.tool-body)` round the rules; a row right after another draws
 * its top rule transparent, except a running one, whose own `border` comes later in the sheet.
 */
private fun border(model: ToolRowModel, followsTool: Boolean): ChatBorder {
    val radius = if (model.running || model.expanded) Radius.md else 0.dp
    if (model.running) return ChatBorder(width = 1.dp, radius = radius)
    return ChatBorder(top = if (followsTool) 0.dp else 1.dp, bottom = 1.dp, radius = radius)
}

/** A negative CSS margin: the box is `-margin` shorter at either end than what it draws. */
private fun Modifier.verticalMargin(margin: Dp): Modifier = layout { measurable, constraints ->
    val inset = margin.roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = -2 * inset))
    layout(placeable.width, (placeable.height + 2 * inset).coerceAtLeast(0)) { placeable.place(0, inset) }
}

/**
 * `.tool-head`: the row's button, lit on hover while there is something to open, and tinted red
 * on a failed call.
 */
@Composable
private fun ToolHead(tool: ToolCallPayload, model: ToolRowModel, onToggle: () -> Unit) {
    Disabled(!model.hasDetail) {
        Button(onToggle, Modifier.fillMaxWidth(), style = chatBare, accessibilityLabel = tool.tool) {
            val hovered = LocalChatButtonHovered.current
            val fill = if (hovered && model.hasDetail) Palette.surfaceHover else if (model.failed) Palette.dangerSoft else Color.Transparent
            HStack(
                Modifier
                    .fillMaxWidth()
                    .background(fill, RoundedCornerShape(Radius.sm))
                    .padding(vertical = 9.dp, horizontal = Space.sp3),
                spacing = Space.sp2,
            ) {
                Marker(tool, model)
                Text(tool.tool, css(FontSize.fs13, weight = FontWeight.Medium), softWrap = false)
                if (model.showsTitle) {
                    Text(
                        tool.title,
                        css(FontSize.fs12, mono = true),
                        Modifier.weight(1f),
                        color = Palette.inkSecondary,
                        lineLimit = 1,
                    )
                }
                tool.diff?.let { DiffStat(it) }
                tool.summary?.let { Badge(it, if (model.failed) Badge.Tone.error else Badge.Tone.neutral) }
                if (!model.showsTitle) Spacer(Modifier.weight(1f))
                TrailingLabel(tool, model)
            }
        }
    }
}

/** `.tool-marker`: a pulsing green dot while the tool runs, its icon after. */
@Composable
private fun Marker(tool: ToolCallPayload, model: ToolRowModel) {
    WithForeground(Palette.inkTertiary) {
        Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
            if (model.running) {
                Dot(DotStyle.Tone(DotTone.working), pulses = true)
            } else {
                Icon(ToolIcon.icon(tool.kind), size = 13.dp)
            }
        }
    }
}

/** `.tool-right`: "running 3.2s" while it runs, ticking once a second. */
@Composable
private fun TrailingLabel(tool: ToolCallPayload, model: ToolRowModel) {
    var now by remember { mutableLongStateOf(Format.nowMillis) }
    if (model.running) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000)
                now = Format.nowMillis
            }
        }
    }
    Text(model.trailing(tool, now), css(FontSize.fs12), color = Palette.inkTertiary, softWrap = false)
}
