package com.junbingao.remotecontrol.win.chat.blocks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.tool-body`: what an open tool row shows — the input, the diff, the output — and the rows
 * nested under it, 8 points apart. A running tool that was not opened shows its live output alone.
 */
@Composable
fun ToolBody(
    blockID: String,
    tool: ToolCallPayload,
    model: ToolRowModel,
    hasNested: Boolean,
    onOpenFull: suspend (String) -> Unit,
    nested: @Composable () -> Unit,
) {
    VStack(
        Modifier.fillMaxWidth().padding(start = Space.sp5, end = Space.sp3, bottom = Space.sp3),
        spacing = Space.sp2,
        alignment = Alignment.Start,
    ) {
        val input = tool.input
        if (model.showsInput && input != null) {
            ToolSection(label = S.chat.input) {
                OutputBox(JSONText.stringify(input), truncated = tool.inputTruncated, onOpenFull = { onOpenFull(blockID) })
            }
        }
        val diff = tool.diff
        if (model.showsDiff && diff != null) DiffView(diff)
        if (model.showsOutput) {
            ToolSection(label = if (model.showsInput) S.chat.output else null) {
                OutputBox(tool.output ?: "", truncated = tool.outputTruncated, live = model.running, onOpenFull = { onOpenFull(blockID) })
            }
        }
        if (hasNested) ToolChildren { nested() }
    }
}

/** `.tool-section`: an optional 11-point caption over its pane. */
@Composable
private fun ToolSection(label: String?, content: @Composable () -> Unit) {
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        if (label != null) Text(label, css(FontSize.fs11), Modifier.padding(bottom = 4.dp), color = Palette.inkTertiary)
        content()
    }
}

/**
 * `.tool-children`: a sub-agent's rows, on a rule 12 points in from the body — or, under a closed
 * row, 20 in from the row (`.collapsed-children`).
 */
@Composable
fun ToolChildren(margin: Dp = Space.sp3, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    VStack(
        modifier
            .padding(start = margin)
            .drawBehind { drawRect(Palette.line, size = Size(1.dp.toPx(), size.height)) }
            .padding(start = Space.sp3 + 1.dp)
            .fillMaxWidth(),
        spacing = Space.sp2,
        alignment = Alignment.Start,
    ) { content() }
}
