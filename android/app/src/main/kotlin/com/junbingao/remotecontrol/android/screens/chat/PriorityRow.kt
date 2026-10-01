package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * SwiftUI's `HStack` as it shares a line that is too short for everything on it: a child with a
 * higher `.layoutPriority` is offered the room first, children of one priority split what is left
 * evenly — the one that needs least first, so what it does not use goes to the rest — and a
 * `Spacer` takes whatever nobody wanted. A child that is given no room at all is not drawn, as a
 * `Text` proposed no width draws nothing, while the spacing around it stays.
 *
 * That is what lets the chat header give up its working directory before the machine's name, and
 * a command row its description before the command: Compose's own `Row` measures in order and
 * would cut whatever came last.
 */
@Composable
fun PriorityRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val widths = PriorityRowWidths.of(measurables, constraints, gap, this)
        val spacers = measurables.indices.filter { measurables[it].flex?.isSpacer == true }
        // Everything but a spacer is offered its share and may draw less of it, as a cut-short
        // `Text` reports the width of what it drew; the spacers take what that leaves.
        val placeables = measurables.mapIndexed { index, measurable ->
            val width = widths[index]
            when {
                index in spacers -> null
                width <= 0 -> null
                else -> measurable.measure(Constraints(minWidth = 0, maxWidth = width, maxHeight = constraints.maxHeight))
            }
        }
        val drawn = IntArray(measurables.size) { index -> placeables[index]?.width ?: if (index in spacers) widths[index] else 0 }
        if (spacers.isNotEmpty()) {
            val slack = measurables.indices.filter { it !in spacers }.sumOf { widths[it] - drawn[it] }
            spacers.forEach { drawn[it] += slack / spacers.size }
        }
        val spacerPlaceables = spacers.associateWith { index ->
            measurables[index].measure(Constraints.fixed(drawn[index].coerceAtLeast(0), 0))
        }
        val used = drawn.sum() + gap * (measurables.size - 1).coerceAtLeast(0)
        val width = if (constraints.hasBoundedWidth) max(used, constraints.minWidth).coerceAtMost(constraints.maxWidth) else used
        val height = max(placeables.maxOfOrNull { it?.height ?: 0 } ?: 0, constraints.minHeight)
        layout(width, height) {
            var x = 0
            measurables.indices.forEach { index ->
                val placeable = placeables[index] ?: spacerPlaceables[index]
                if (placeable != null) placeable.place(x, verticalAlignment.align(placeable.height, height))
                x += drawn[index] + gap
            }
        }
    }
}

/** `.layoutPriority(_:)`: this child gives way to higher ones and before lower ones; it can be cut short. */
fun Modifier.layoutPriority(priority: Int): Modifier = this.then(RowFlex(priority = priority.toDouble(), minLength = null))

/** A child that can be cut short at the lowest priority, as every `Text` in a SwiftUI stack can. */
fun Modifier.flexible(): Modifier = layoutPriority(0)

/** `Spacer(minLength:)`: the room nobody else asked for, and never less than [minLength]. */
@Composable
fun RowSpacer(minLength: Dp = 0.dp) {
    Box(RowFlex(priority = Double.NEGATIVE_INFINITY, minLength = minLength))
}

private class RowFlex(val priority: Double, val minLength: Dp?) : ParentDataModifier {
    val isSpacer: Boolean get() = minLength != null

    override fun Density.modifyParentData(parentData: Any?): Any = this@RowFlex
}

private val Measurable.flex: RowFlex? get() = parentData as? RowFlex

/** The arithmetic of [PriorityRow], apart from drawing. */
internal object PriorityRowWidths {
    fun of(measurables: List<Measurable>, constraints: Constraints, gap: Int, density: Density): IntArray {
        val ideal = measurables.map { measurable ->
            val flex = measurable.flex
            if (flex?.isSpacer == true) 0 else measurable.maxIntrinsicWidth(constraints.maxHeight)
        }
        val spacers = measurables.map { measurable ->
            measurable.flex?.minLength?.let { with(density) { it.roundToPx() } }
        }
        val priorities = measurables.map { measurable -> measurable.flex?.takeIf { !it.isSpacer }?.priority }
        val visible = with(density) { MinimumVisible.roundToPx() }
        return distribute(ideal, priorities, spacers, constraints.maxWidth, constraints.hasBoundedWidth, gap, visible)
    }

    /**
     * Less than this is no room at all: a word cut down to under an ellipsis's width, or a
     * separator dot to a sliver of itself, is drawn as nothing, as SwiftUI draws a `Text` that
     * cannot fit even its ellipsis.
     */
    private val MinimumVisible = 12.dp

    /**
     * [priorities] is null for a child that is never cut short, [spacers] the minimum of a spacer
     * and null for anything else. Fixed children take what they need; then each priority, highest
     * first, splits what is left; then the spacers share the rest.
     */
    fun distribute(
        ideal: List<Int>,
        priorities: List<Double?>,
        spacers: List<Int?>,
        maxWidth: Int,
        bounded: Boolean,
        gap: Int,
        minimumVisible: Int = 0,
    ): IntArray {
        val count = ideal.size
        val widths = IntArray(count)
        val spacerIndices = (0 until count).filter { spacers[it] != null }
        if (!bounded) {
            for (index in 0 until count) widths[index] = spacers[index] ?: ideal[index]
            return widths
        }
        var remaining = maxWidth - gap * (count - 1).coerceAtLeast(0) - spacerIndices.sumOf { spacers[it] ?: 0 }
        for (index in 0 until count) {
            if (spacers[index] == null && priorities[index] == null) {
                widths[index] = ideal[index]
                remaining -= ideal[index]
            }
        }
        remaining = max(remaining, 0)
        val levels = (0 until count).mapNotNull { priorities[it] }.distinct().sortedDescending()
        for (level in levels) {
            val group = (0 until count).filter { priorities[it] == level }.sortedBy { ideal[it] }
            var left = group.size
            for (index in group) {
                val offer = remaining / left
                val cut = ideal[index] > offer
                val width = if (cut && offer < minimumVisible) 0 else min(ideal[index], offer)
                widths[index] = width
                remaining -= width
                left -= 1
            }
        }
        if (spacerIndices.isNotEmpty()) {
            val share = remaining / spacerIndices.size
            for (index in spacerIndices) widths[index] = (spacers[index] ?: 0) + share
        }
        return widths
    }
}
