package com.junbingao.remotecontrol.win.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.ExactHeight
import com.junbingao.remotecontrol.win.design.StackFrame
import com.junbingao.remotecontrol.win.design.exactHeight
import com.junbingao.remotecontrol.win.design.fraction
import com.junbingao.remotecontrol.win.design.stackChild
import com.junbingao.remotecontrol.win.design.stackFrame
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * SwiftUI's `.frame(height:)` and `.frame(minHeight:)` around a row's content, which the lists'
 * rows are drawn in: the content as wide as the frame, at its leading edge, and centred in its
 * height at the exact place SwiftUI puts it. Three lines of 1.45 are 63.25 tall, so a row's lines
 * start a fraction of a pixel off the grid, and each rounds to the pixel the Mac's does — a Compose
 * `Box` would round the content's top first and move every line a point.
 *
 * `height` fixes the frame; otherwise it is as tall as the content and at least `minHeight`.
 */
@Composable
internal fun ExactFrame(modifier: Modifier = Modifier, height: Dp? = null, minHeight: Dp = 0.dp, content: @Composable () -> Unit) {
    val exact = remember { ExactHeight() }
    val frame = remember { StackFrame(vertical = true) }
    Layout(content, modifier.exactHeight(exact).stackFrame(frame)) { measurables, constraints ->
        val fixed = height?.toPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else null
        val child = measurables.first()
        val placeable = child.measure(
            Constraints(
                minWidth = width ?: 0,
                maxWidth = width ?: Constraints.Infinity,
                maxHeight = fixed?.roundToInt() ?: constraints.maxHeight,
            ),
        )
        val inner = placeable.height + child.stackChild.fraction
        val total = fixed ?: max(minHeight.toPx(), inner)
        val laid = constraints.constrainHeight(total.roundToInt())
        exact.fraction = if (laid == total.roundToInt()) total - laid else 0f
        layout(width ?: placeable.width, laid) {
            val top = frame.base(coordinates) + (total - inner) / 2
            val y = top.roundToInt()
            frame.placed(coordinates, listOf(StackFrame.Span(y, y + placeable.height, top - y)))
            placeable.place(0, y)
        }
    }
}
