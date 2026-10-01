package com.junbingao.remotecontrol.win.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import com.junbingao.remotecontrol.win.design.ExactHeight
import com.junbingao.remotecontrol.win.design.StackFrame
import com.junbingao.remotecontrol.win.design.exactHeight
import com.junbingao.remotecontrol.win.design.fraction
import com.junbingao.remotecontrol.win.design.stackChild
import com.junbingao.remotecontrol.win.design.stackFrame
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * `display: flex; flex-wrap: wrap` with a `gap`: each item at its own width, a new line when the
 * next one does not fit, and an item wider than a whole line narrowed to the line, where it
 * truncates as an `overflow: hidden` item does. Lines are as tall as their tallest item; the items
 * sit at the top of theirs (`align-items: stretch` on text of one size), or centred in it.
 *
 * Heights are kept exact, as the stacks keep them: a line of 12 px captions at 1.45 is 17.4 tall,
 * and the next line and whatever follows the row start where the Mac's do.
 */
@Composable
internal fun WrapRow(
    spacing: Dp,
    lineSpacing: Dp,
    centred: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val exact = remember { ExactHeight() }
    val frame = remember { StackFrame(vertical = true) }
    Layout(content, modifier.exactHeight(exact).stackFrame(frame)) { measurables, constraints ->
        val limit = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val gap = spacing.roundToPx()
        val between = lineSpacing.toPx()
        val placeables = measurables.map { item ->
            item.measure(Constraints(maxWidth = min(item.maxIntrinsicWidth(Constraints.Infinity), limit)))
        }
        val heights = placeables.mapIndexed { index, placeable -> placeable.height + measurables[index].stackChild.fraction }
        val lines = arrange(placeables.map { it.width }, heights, limit, gap)
        val total = lines.sumOf { it.height.toDouble() }.toFloat() + between * max(0, lines.size - 1)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else lines.maxOfOrNull { it.width } ?: 0
        val height = constraints.constrainHeight(total.roundToInt())
        exact.fraction = if (height == total.roundToInt()) total - height else 0f
        layout(width, height) {
            var top = frame.base(coordinates)
            val spans = ArrayList<StackFrame.Span>(placeables.size)
            val positions = IntArray(placeables.size * 2)
            for (line in lines) {
                var x = 0
                for (index in line.items) {
                    val itemTop = if (centred) top + (line.height - heights[index]) / 2 else top
                    val y = itemTop.roundToInt()
                    spans += StackFrame.Span(y, y + placeables[index].height, itemTop - y)
                    positions[index * 2] = x
                    positions[index * 2 + 1] = y
                    x += placeables[index].width + gap
                }
                top += line.height + between
            }
            frame.placed(coordinates, spans.sortedBy { it.start })
            for ((index, placeable) in placeables.withIndex()) placeable.place(positions[index * 2], positions[index * 2 + 1])
        }
    }
}

private class Line {
    val items = mutableListOf<Int>()
    var width = 0
    var height = 0f
}

/** The items, by index, in lines of at most `limit` — every line holding at least one. */
private fun arrange(widths: List<Int>, heights: List<Float>, limit: Int, gap: Int): List<Line> {
    val lines = mutableListOf<Line>()
    var current = Line()
    for (index in widths.indices) {
        val needed = if (current.items.isEmpty()) widths[index] else current.width + gap + widths[index]
        if (current.items.isNotEmpty() && needed > limit) {
            lines += current
            current = Line()
        }
        current.width = if (current.items.isEmpty()) widths[index] else current.width + gap + widths[index]
        current.height = max(current.height, heights[index])
        current.items += index
    }
    if (current.items.isNotEmpty()) lines += current
    return lines
}
