package com.junbingao.remotecontrol.win.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.ExactHeight
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.exactHeight
import com.junbingao.remotecontrol.win.design.fraction
import com.junbingao.remotecontrol.win.design.stackChild
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * `display: flex; flex-wrap: wrap` with a gap, as `.account-meta` and `.terminal-status` lay out
 * their pieces: left to right, a piece that no longer fits starting the next line, and each one cut
 * to the line's width at most, where its own ellipsis takes over. Its lines keep the exact heights
 * their texts report, as the stacks around it do (`ExactHeight`).
 */
@Composable
fun SettingsFlexWrap(modifier: Modifier = Modifier, spacing: Dp = Space.sp2, lineSpacing: Dp = 2.dp, content: @Composable () -> Unit) {
    val exact = remember { ExactHeight() }
    Layout(content, modifier.exactHeight(exact)) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val lineGap = lineSpacing.toPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val lines = mutableListOf<FlexLine>()
        var line = FlexLine()
        for (measurable in measurables) {
            val placeable = measurable.measure(Constraints(maxWidth = width))
            val height = placeable.height + measurable.stackChild.fraction
            if (line.items.isNotEmpty() && line.width + gap + placeable.width > width) {
                lines += line
                line = FlexLine()
            }
            line.width += (if (line.items.isEmpty()) 0 else gap) + placeable.width
            line.height = max(line.height, height)
            line.items += placeable
        }
        if (line.items.isNotEmpty()) lines += line
        val total = lines.sumOf { it.height.toDouble() }.toFloat() + lineGap * max(0, lines.size - 1)
        val height = constraints.constrainHeight(total.roundToInt())
        exact.fraction = if (height == total.roundToInt()) total - height else 0f
        val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else lines.maxOfOrNull { it.width } ?: 0
        layout(layoutWidth, height) {
            var top = 0f
            for (flexLine in lines) {
                var x = 0
                for (placeable in flexLine.items) {
                    placeable.place(x, top.roundToInt())
                    x += placeable.width + gap
                }
                top += flexLine.height + lineGap
            }
        }
    }
}

private class FlexLine {
    val items = mutableListOf<Placeable>()
    var width = 0
    var height = 0f
}
