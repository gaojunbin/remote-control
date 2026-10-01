package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * An `HStack`: its children leading to trailing, `spacing` apart, as tall as the tallest at the
 * exact heights they report (`ExactHeight`), each placed across by its alignment on the pixel its
 * exact top rounds to. Each child is offered the width the ones before it leave, as Compose's
 * `Row` offers it; one with a weight shares, by weight, what the others leave. On
 * `Alignment.FirstTextBaseline` the children's first baselines line up, a view without text
 * standing on its bottom edge, as SwiftUI's do.
 */
internal class HStackPolicy(
    private val spacing: Dp,
    private val alignment: Alignment.Vertical,
    private val self: ExactHeight,
    private val frame: StackFrame,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gap = spacing.roundToPx()
        val gaps = gap * (measurables.size - 1).coerceAtLeast(0)
        val children = measurables.map { it.stackChild }
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val bounded = constraints.hasBoundedWidth
        val loose = Constraints(maxHeight = constraints.maxHeight)
        var used = 0
        var weights = 0f
        for ((index, measurable) in measurables.withIndex()) {
            val child = children[index]
            if (bounded && child != null && child.weight > 0f) {
                weights += child.weight
                continue
            }
            val room = if (bounded) (constraints.maxWidth - used - gaps).coerceAtLeast(0) else Constraints.Infinity
            val placeable = measurable.measure(loose.copy(maxWidth = room))
            placeables[index] = placeable
            used += placeable.width
        }
        if (weights > 0f) {
            val free = (constraints.maxWidth - used - gaps).coerceAtLeast(0)
            for ((index, measurable) in measurables.withIndex()) {
                val child = children[index] ?: continue
                if (child.weight <= 0f) continue
                val share = (free * child.weight / weights).roundToInt()
                val placeable = measurable.measure(loose.copy(minWidth = if (child.fill) share else 0, maxWidth = share))
                placeables[index] = placeable
                used += placeable.width
            }
        }
        val laid = placeables.map { it!! }
        val heights = laid.mapIndexed { index, placeable -> placeable.height + children[index].fraction }
        val baselines = if (alignment == Alignment.FirstTextBaseline) laid.map { it.firstBaseline() } else null
        val exact = if (baselines != null) {
            laid.indices.maxOf { baselines[it] } + laid.indices.maxOf { heights[it] - baselines[it] }
        } else {
            heights.maxOrNull() ?: 0f
        }
        val width = constraints.constrainWidth(used + gaps)
        val height = constraints.constrainHeight(exact.roundToInt())
        val natural = height == exact.roundToInt()
        self.fraction = if (natural) exact - height else 0f
        val room = if (natural) exact else height.toFloat()
        return layout(width, height) {
            // Where each child goes is told before any is placed: what a child holds asks.
            val base = frame.base(coordinates)
            val spans = ArrayList<StackFrame.Span>(laid.size)
            val tops = IntArray(laid.size)
            var x = 0
            for ((index, placeable) in laid.withIndex()) {
                val child = children[index]
                val top = base + when {
                    child?.vertical != null -> across(child.vertical!!, heights[index], room, placeable.height, height)
                    baselines != null -> baselines.max() - baselines[index]
                    else -> across(alignment, heights[index], room, placeable.height, height)
                }
                tops[index] = top.roundToInt()
                spans += StackFrame.Span(x, x + placeable.width, top - tops[index])
                x += placeable.width + gap
            }
            frame.placed(coordinates, spans)
            for ((index, placeable) in laid.withIndex()) placeable.place(spans[index].start, tops[index])
        }
    }

    /** Where `alignment` puts a child `size` tall in `room`, exactly for SwiftUI's three. */
    private fun across(alignment: Alignment.Vertical, size: Float, room: Float, laidSize: Int, laidRoom: Int): Float = when (alignment) {
        Alignment.Top -> 0f
        Alignment.CenterVertically -> (room - size) / 2
        Alignment.Bottom -> room - size
        else -> alignment.align(laidSize, laidRoom).toFloat()
    }

    private fun Placeable.firstBaseline(): Float =
        this[FirstBaseline].takeIf { it != AlignmentLine.Unspecified }?.toFloat() ?: height.toFloat()

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.sumOf { it.minIntrinsicWidth(height) } + spacing.roundToPx() * max(0, measurables.size - 1)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.sumOf { it.maxIntrinsicWidth(height) } + spacing.roundToPx() * max(0, measurables.size - 1)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.maxOfOrNull { it.minIntrinsicHeight(Constraints.Infinity) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.maxOfOrNull { it.maxIntrinsicHeight(Constraints.Infinity) } ?: 0
}

/** SwiftUI's `HStack(alignment: .firstTextBaseline)`: the children's first baselines in a line. */
val Alignment.Companion.FirstTextBaseline: Alignment.Vertical get() = FirstTextBaselineAlignment

private object FirstTextBaselineAlignment : Alignment.Vertical {
    override fun align(size: Int, space: Int): Int = 0
}
