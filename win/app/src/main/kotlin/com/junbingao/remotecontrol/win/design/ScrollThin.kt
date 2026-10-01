package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * `.scroll-thin`: `base.css` sets `scrollbar-width: thin`, which browsers honour with the
 * platform's thin scroll bar. On Windows it is drawn as Windows 11 draws its own: over the
 * content, taking no room, shown while the content scrolls or the pointer is on it, and gone
 * again a moment after (`ThinScrollbar`). A render never scrolls, so it shows none, as the Mac
 * renderer's overlay scrollers show none.
 */
@Composable
fun ThinScrollView(
    axes: Orientation = Orientation.Vertical,
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        val scroll = if (axes == Orientation.Vertical) Modifier.verticalScroll(state) else Modifier.horizontalScroll(state)
        ScrolledContent(axes == Orientation.Vertical, scroll, content)
        ThinScrollbar(rememberScrollbarAdapter(state), axes, Modifier.matchParentSize())
    }
}

/**
 * What a scroll view holds, at the top. The Mac's scroll view is drawn on a whole pixel, and what
 * it holds is laid out afresh from there — none of the fraction of a pixel the views above the
 * scroll view left is carried in — centred in its height rounded to the pixel: content 0.2 px
 * taller than a whole pixel starts 0.1 px higher, and a line exactly between two points drops
 * onto the upper one. The content's exact height (`ExactHeight`) says by how much.
 */
@Composable
private fun ScrolledContent(vertical: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val frame = remember { StackFrame(vertical = true, origin = true) }
    Layout(content, modifier.stackFrame(frame)) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val fraction = if (vertical) measurables.firstNotNullOfOrNull { it.stackChild?.exact }?.fraction ?: 0f else 0f
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(placeables.maxOfOrNull { it.width } ?: 0, height) {
            val top = -fraction / 2
            val y = top.roundToInt()
            frame.placed(coordinates, listOf(StackFrame.Span(y, y + height, top - y)))
            for (placeable in placeables) placeable.place(0, y)
        }
    }
}

object ScrollThin {
    /**
     * The room the thin scroll bar takes beside content that overflows: none, because it is drawn
     * over the content.
     */
    val gutter: Dp = 0.dp
}
