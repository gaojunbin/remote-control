package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import com.junbingao.remotecontrol.win.design.Space

/**
 * `display: flex; flex-wrap: wrap; align-items: center; gap: N`: items left to right, a new line
 * where the next one does not fit, each line as tall as its tallest item with the others centred in
 * it.
 */
@Composable
fun ChipFlow(modifier: Modifier = Modifier, spacing: Dp = Space.sp2, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val items = measurables.map { it.measure(Constraints()) }
        val limit = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val lines = mutableListOf<MutableList<Placeable>>()
        var line = mutableListOf<Placeable>()
        var lineWidth = 0
        for (item in items) {
            val needed = if (line.isEmpty()) item.width else lineWidth + gap + item.width
            if (line.isNotEmpty() && needed > limit) {
                lines += line
                line = mutableListOf()
            }
            lineWidth = if (line.isEmpty()) item.width else lineWidth + gap + item.width
            line += item
        }
        if (line.isNotEmpty()) lines += line
        val heights = lines.map { row -> row.maxOf { it.height } }
        val height = heights.sum() + gap * maxOf(0, lines.size - 1)
        val natural = lines.maxOfOrNull { row -> row.sumOf { it.width } + gap * (row.size - 1) } ?: 0
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else natural
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            var y = 0
            for ((index, row) in lines.withIndex()) {
                var x = 0
                for (item in row) {
                    item.place(x, y + (heights[index] - item.height) / 2)
                    x += item.width + gap
                }
                y += heights[index] + gap
            }
        }
    }
}
