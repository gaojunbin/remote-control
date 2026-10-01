package com.junbingao.remotecontrol.android.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * UIKit's scroll indicator on a lazy list: a thin capsule down the trailing edge, there while the
 * content moves and for a moment after, as long as the share of the content on screen and running
 * between the list's content insets, so it never passes under the bars.
 */
fun Modifier.scrollIndicator(state: LazyListState): Modifier = composed {
    val alpha = indicatorAlpha(state.isScrollInProgress)
    val ink = SystemColor.label.copy(alpha = ScrollIndicatorMetrics.ink)
    drawWithContent {
        drawContent()
        if (alpha == 0f) return@drawWithContent
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty()) return@drawWithContent
        // A lazy list knows only the rows it laid out, so the rest are taken to be as tall.
        val average = items.sumOf { it.size }.toFloat() / items.size
        val viewport = (info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding).toFloat()
        val content = average * info.totalItemsCount
        val scrolled = state.firstVisibleItemIndex * average + state.firstVisibleItemScrollOffset
        drawIndicator(ink.copy(alpha = ink.alpha * alpha), info.beforeContentPadding.toFloat(), viewport, content, scrolled)
    }
}

/**
 * The same on a scrolling column, whose content insets — the bars over it — are [top] and
 * [bottom]; with [flashesWhenFull] it also flashes once when the content first outgrows the
 * column, as a text view's does when its text starts to scroll.
 */
fun Modifier.scrollIndicator(state: ScrollState, top: Dp = 0.dp, bottom: Dp = 0.dp, flashesWhenFull: Boolean = false): Modifier = composed {
    val alpha = indicatorAlpha(state.isScrollInProgress, flash = flashesWhenFull && state.maxValue > 0)
    val ink = SystemColor.label.copy(alpha = ScrollIndicatorMetrics.ink)
    drawWithContent {
        drawContent()
        if (alpha == 0f || state.maxValue <= 0) return@drawWithContent
        val viewport = size.height - top.toPx() - bottom.toPx()
        drawIndicator(ink.copy(alpha = ink.alpha * alpha), top.toPx(), viewport, viewport + state.maxValue, state.value.toFloat())
    }
}

/**
 * How strongly the indicator shows: at once when the content starts to move, until a moment after
 * it stops, then fading — or, with [flash], for that moment on its own, as UIKit flashes it.
 */
@Composable
internal fun indicatorAlpha(scrolling: Boolean, flash: Boolean = false): Float {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, flash) {
        if (scrolling || flash) shown = true
        if (!scrolling) {
            delay(ScrollIndicatorMetrics.hold)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 100 else 350), label = "indicator")
    return alpha
}

private fun DrawScope.drawIndicator(ink: Color, top: Float, viewport: Float, content: Float, scrolled: Float) {
    if (content <= viewport || viewport <= 0f) return
    val inset = ScrollIndicatorMetrics.inset.toPx()
    val width = ScrollIndicatorMetrics.width.toPx()
    val track = viewport - inset * 2
    val length = (track * viewport / content).coerceIn(minOf(ScrollIndicatorMetrics.shortest.toPx(), track), track)
    val travel = (scrolled / (content - viewport)).coerceIn(0f, 1f)
    drawRoundRect(
        ink,
        topLeft = Offset(size.width - inset - width, top + inset + (track - length) * travel),
        size = Size(width, length),
        cornerRadius = CornerRadius(width / 2),
    )
}

/** The indicator's measurements, from the iPhone 17 reference screenshots. */
object ScrollIndicatorMetrics {
    /** Three points wide, three in from the edge: 396 to 399 on an iPhone 17. */
    val width = 3.dp
    val inset = 3.dp
    val shortest = 36.dp

    /** The label colour at this strength: 159 over the page's 245. */
    const val ink = 0.35f

    /** How long it stays after the content stops, before it fades. */
    const val hold = 1000L
}
