package com.junbingao.remotecontrol.win.chat.support

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.ExactHeight
import com.junbingao.remotecontrol.win.design.FirstTextBaseline
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.StackFrame
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.ThinScrollbar
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.exactHeight
import com.junbingao.remotecontrol.win.design.fraction
import com.junbingao.remotecontrol.win.design.stackChild
import com.junbingao.remotecontrol.win.design.stackFrame
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * `max-width: N%`: the content is offered at most that share of the width it is given, takes what
 * it needs of it, and sits at the leading or trailing edge of the full width.
 */
@Composable
fun ChatFractionalWidth(fraction: Float, trailing: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val exact = remember { ExactHeight() }
    Layout(content, modifier.exactHeight(exact)) { measurables, constraints ->
        val child = measurables.firstOrNull() ?: return@Layout layout(0, 0) {}
        val offered = if (constraints.hasBoundedWidth) (constraints.maxWidth * fraction).toInt() else Constraints.Infinity
        val placeable = child.measure(Constraints(maxWidth = offered))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        exact.fraction = child.stackChild.fraction
        layout(width, placeable.height) { placeable.place(if (trailing) width - placeable.width else 0, 0) }
    }
}

/**
 * An inline-level box on a line of its own, as a block that holds only an inline control draws
 * it: the line takes the height of the parent's strut — its font at its line height — or of the
 * content, whichever reaches further from the shared baseline. A `<button>` inside a `<div>` is
 * 21 points tall on a 14-point, 1.5 line, not the 19.5 of its own 13-point text.
 */
@Composable
fun ChatStrutLine(
    size: Float = FontSize.fs14,
    lineHeight: Float = 1.5f,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    HStack(modifier, spacing = 0.dp, alignment = Alignment.FirstTextBaseline) {
        Text(" ", css(size, lineHeight = lineHeight), Modifier.zeroWidth(), softWrap = false)
        content()
    }
}

/** No width of its own: what it holds is laid out at its own size and overflows to the trailing side. */
private fun Modifier.zeroWidth(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxHeight = constraints.maxHeight))
    layout(0, placeable.height) { placeable.place(0, 0) }
}

/**
 * A scrolling pane as tall as its content up to `maxHeight`, then scrolling: `max-height` with
 * `overflow: auto` on a `white-space: pre` block, whose long lines scroll sideways. The content is
 * offered at least the pane's width, so what it lays out from the leading edge stays there, and it
 * is laid out afresh from the pane's own pixel, as the Mac's scroll view lays out what it holds:
 * its text rounds its lines from there. The pane is the content's exact height while it fits. The
 * thin scroll bars take no room (`ScrollThin.gutter`). Only the axes that overflow scroll, so a
 * wheel over a pane with nothing to scroll moves the transcript around it.
 */
@Composable
fun ChatBoundedScroll(maxHeight: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val viewport = remember { IntArray(1) }
    val exact = remember { ExactHeight() }
    val frame = remember { StackFrame(vertical = true, origin = true) }
    val bounded = maxHeight != Dp.Infinity
    Box(modifier.exactHeight(exact)) {
        Box(
            Modifier
                .heightIn(min = 1.dp)
                .then(if (bounded) Modifier.heightIn(max = maxHeight).verticalScroll(vertical, enabled = vertical.maxValue > 0) else Modifier)
                .layout { measurable, constraints ->
                    viewport[0] = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .horizontalScroll(horizontal, enabled = horizontal.maxValue > 0),
        ) {
            Layout(content, Modifier.stackFrame(frame)) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints.copy(minWidth = max(constraints.minWidth, viewport[0]))) }
                val width = placeables.maxOfOrNull { it.width } ?: 0
                val height = placeables.maxOfOrNull { it.height } ?: 0
                val fits = !bounded || height <= maxHeight.roundToPx()
                exact.fraction = if (fits && measurables.size == 1) measurables[0].stackChild.fraction else 0f
                layout(width, height) {
                    frame.placed(coordinates, listOf(StackFrame.Span(0, height, 0f)))
                    for (placeable in placeables) placeable.place(0, 0)
                }
            }
        }
        ThinScrollbar(rememberScrollbarAdapter(horizontal), Orientation.Horizontal, Modifier.matchParentSize())
        if (bounded) ThinScrollbar(rememberScrollbarAdapter(vertical), Orientation.Vertical, Modifier.matchParentSize())
    }
}

/**
 * Block children in a horizontally scrolling `pre`: each as wide as the widest of them and at least
 * the width the pane offers, so a tinted line runs edge to edge. They stack at the exact heights
 * they report, as the Mac's do, so twenty lines of 19.2 points end where twenty of the Mac's do.
 *
 * A child's tint (`tints`, by position) is painted here, from the child's exact top to its exact
 * bottom, each rounded to the pixel: tints of whole-pixel children stacked at fractional places
 * would otherwise leave a hairline between two neighbours where the Mac's meet.
 */
@Composable
fun ChatEqualWidthColumn(tints: List<Color?> = emptyList(), modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val exact = remember { ExactHeight() }
    val bands = remember { ArrayList<Pair<IntRange, Color>>() }
    Layout(
        content,
        modifier.exactHeight(exact).drawBehind {
            for ((rows, color) in bands) drawRect(color, Offset(0f, rows.first.toFloat()), Size(size.width, (rows.last - rows.first).toFloat()))
        },
    ) { measurables, constraints ->
        val widest = measurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val width = max(constraints.minWidth, widest)
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
        val tops = IntArray(placeables.size)
        bands.clear()
        var top = 0f
        for ((index, placeable) in placeables.withIndex()) {
            tops[index] = top.roundToInt()
            top += placeable.height + measurables[index].stackChild.fraction
            tints.getOrNull(index)?.let { bands += (tops[index]..top.roundToInt()) to it }
        }
        val height = top.roundToInt()
        exact.fraction = top - height
        layout(width, height) {
            for ((index, placeable) in placeables.withIndex()) placeable.place(0, tops[index])
        }
    }
}

/**
 * `display: flex; flex-wrap: wrap; gap`: children at their own size, left to right, starting a
 * new line when the next one does not fit, each centred in its line.
 */
@Composable
fun ChatWrapRow(spacing: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val placeables = measurables.map { it.measure(Constraints()) }
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val rows = mutableListOf<MutableList<Int>>()
        var rowWidth = 0
        for ((index, placeable) in placeables.withIndex()) {
            val row = rows.lastOrNull()
            val needed = if (row == null || row.isEmpty()) placeable.width else rowWidth + gap + placeable.width
            if (row == null || (needed > available && row.isNotEmpty())) {
                rows += mutableListOf(index)
                rowWidth = placeable.width
            } else {
                row += index
                rowWidth = needed
            }
        }
        val rowHeights = rows.map { row -> row.maxOf { placeables[it].height } }
        val widest = rows.maxOfOrNull { row -> row.sumOf { placeables[it].width } + gap * (row.size - 1) } ?: 0
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else widest
        val height = rowHeights.sum() + gap * max(0, rows.size - 1)
        layout(width, constraints.constrainHeight(height)) {
            var y = 0
            for ((rowIndex, row) in rows.withIndex()) {
                var x = 0
                for (index in row) {
                    val placeable = placeables[index]
                    placeable.place(x, y + (rowHeights[rowIndex] - placeable.height) / 2)
                    x += placeable.width + gap
                }
                y += rowHeights[rowIndex] + gap
            }
        }
    }
}

/**
 * `width: fit-content` under a `max-width`: what the content needs, never more than `maximum` —
 * how a box with `margin: auto` sizes in a flex column.
 */
@Composable
fun ChatFitWidth(maximum: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val exact = remember { ExactHeight() }
    Layout(content, modifier.exactHeight(exact)) { measurables, constraints ->
        val child = measurables.firstOrNull() ?: return@Layout layout(0, 0) {}
        val limit = min(if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE, maximum.roundToPx())
        val placeable = child.measure(Constraints(maxWidth = limit, maxHeight = constraints.maxHeight))
        exact.fraction = child.stackChild.fraction
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
