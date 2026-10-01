package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.Alignment
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
 * A `VStack`: its children top to bottom, `spacing` apart, each at the exact height it reports
 * (`ExactHeight`) and placed on the pixel its exact top rounds to — as SwiftUI draws the Mac's.
 * A child with a weight shares, by weight, the room the others leave when the stack's height is
 * bounded, as Compose's `Column` shares it.
 */
internal class VStackPolicy(
    private val spacing: Dp,
    private val alignment: Alignment.Horizontal,
    private val self: ExactHeight,
    private val frame: StackFrame,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gap = spacing.toPx()
        val gaps = gap * (measurables.size - 1).coerceAtLeast(0)
        val children = measurables.map { it.stackChild }
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val bounded = constraints.hasBoundedHeight
        val loose = Constraints(maxWidth = constraints.maxWidth)
        var used = 0f
        var weights = 0f
        for ((index, measurable) in measurables.withIndex()) {
            val child = children[index]
            if (bounded && child != null && child.weight > 0f) {
                weights += child.weight
                continue
            }
            val room = if (bounded) (constraints.maxHeight - used - gaps).toInt().coerceAtLeast(0) else Constraints.Infinity
            val placeable = measurable.measure(loose.copy(maxHeight = room))
            placeables[index] = placeable
            used += placeable.height + child.fraction
        }
        if (weights > 0f) {
            val free = (constraints.maxHeight - used - gaps).coerceAtLeast(0f)
            for ((index, measurable) in measurables.withIndex()) {
                val child = children[index] ?: continue
                if (child.weight <= 0f) continue
                val share = (free * child.weight / weights).roundToInt()
                val placeable = measurable.measure(loose.copy(minHeight = if (child.fill) share else 0, maxHeight = share))
                placeables[index] = placeable
                used += placeable.height + child.fraction
            }
        }
        val exact = used + gaps
        val width = constraints.constrainWidth(placeables.maxOfOrNull { it!!.width } ?: 0)
        val height = constraints.constrainHeight(exact.roundToInt())
        self.fraction = if (height == exact.roundToInt()) exact - height else 0f
        return layout(width, height) {
            // Where each child goes is told before any is placed: what a child holds asks.
            var top = frame.base(coordinates)
            val spans = placeables.mapIndexed { index, placeable ->
                val y = top.roundToInt()
                StackFrame.Span(y, y + placeable!!.height, top - y).also { top += placeable.height + children[index].fraction + gap }
            }
            frame.placed(coordinates, spans)
            for ((index, placeable) in placeables.withIndex()) {
                val child = children[index]
                placeable!!.place((child?.horizontal ?: alignment).align(placeable.width, width, layoutDirection), spans[index].start)
            }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.minIntrinsicWidth(Constraints.Infinity) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        stacked(measurables) { it.minIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        stacked(measurables) { it.maxIntrinsicHeight(width) }

    private fun IntrinsicMeasureScope.stacked(measurables: List<IntrinsicMeasurable>, height: (IntrinsicMeasurable) -> Int): Int =
        measurables.sumOf(height) + (spacing.toPx() * max(0, measurables.size - 1)).roundToInt()
}

/** The fraction a measured child reported, if it reports one. */
internal val StackChild?.fraction: Float get() = this?.exact?.fraction ?: 0f
