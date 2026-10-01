package com.junbingao.remotecontrol.win.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * One axis of a scroll view: how much there is, how much of it shows, and how far it has moved
 * from the start.
 */
data class ScrollAxis(val content: Float, val visible: Float, val offset: Float) {
    val overflows: Boolean get() = content > visible + 0.5f
    val maxOffset: Float get() = max(0f, content - visible)
    val knobProportion: Float get() = if (content > 0) min(1f, visible / content) else 1f

    /** The scroller's value, 0 at the start and 1 at the end. */
    val value: Float get() = if (maxOffset > 0) (offset / maxOffset).coerceIn(0f, 1f) else 0f

    fun offset(value: Float): Float = value.coerceIn(0f, 1f) * maxOffset

    /** A click in the track: WebKit's page, seven eighths of what shows or all of it but 40 px, whichever is more. */
    val page: Float get() = max(visible * 0.875f, visible - 40)
}

/**
 * The thin scroll bar over a scrolling pane, as Windows 11 draws its own: a rounded knob against
 * the trailing (or bottom) edge that appears while the content moves or the pointer is on it, is
 * dragged to scroll, and fades a moment after. It takes no room and draws nothing while there is
 * nothing to scroll.
 */
@Composable
fun ThinScrollbar(adapter: ScrollbarAdapter, axes: Orientation = Orientation.Vertical, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var moving by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(adapter) {
        snapshotFlow { adapter.scrollOffset }.drop(1).collect {
            moving = true
            delay(900)
            moving = false
        }
    }
    val axis = ScrollAxis(adapter.contentSize.toFloat(), adapter.viewportSize.toFloat(), adapter.scrollOffset.toFloat())
    val shown = axis.overflows && (moving || hovered || dragging)
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) Motion.durFast else Motion.dur))
    val thick = hovered || dragging
    val vertical = axes == Orientation.Vertical
    // Only the strip along the edge answers the pointer; the content under the rest keeps it.
    Box(modifier) {
        Canvas(
            Modifier
                .align(if (vertical) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(if (vertical) Modifier.fillMaxHeight().width(12.dp) else Modifier.fillMaxWidth().height(12.dp))
                .hoverable(source, enabled = axis.overflows)
                .pointerInput(adapter) {
                    detectDragGestures(onDragStart = { dragging = true }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { change, amount ->
                        change.consume()
                        val track = if (vertical) size.height else size.width
                        val delta = if (vertical) amount.y else amount.x
                        val latest = ScrollAxis(adapter.contentSize.toFloat(), adapter.viewportSize.toFloat(), adapter.scrollOffset.toFloat())
                        if (track > 0 && latest.knobProportion < 1) {
                            val moved = delta / (track * (1 - latest.knobProportion)) * latest.maxOffset
                            scope.launch { adapter.scrollTo((latest.offset + moved).toDouble()) }
                        }
                    }
                },
        ) {
            if (!axis.overflows || alpha == 0f) return@Canvas
            val inset = 2.dp.toPx()
            val width = (if (thick) 8.dp else 4.dp).toPx()
            val track = (if (vertical) size.height else size.width) - 2 * inset
            val length = max(24.dp.toPx(), track * axis.knobProportion)
            val start = inset + (track - length) * axis.value
            val color = Palette.ink.copy(alpha = (if (thick) 0.5f else 0.35f) * alpha)
            val topLeft = if (vertical) Offset(size.width - inset - width, start) else Offset(start, size.height - inset - width)
            val knob = if (vertical) Size(width, length) else Size(length, width)
            drawRoundRect(color, topLeft, knob, CornerRadius(width / 2, width / 2))
        }
    }
}
