package com.junbingao.remotecontrol.android.system

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.continuousPath
import kotlinx.coroutines.launch

/**
 * `.sheet(isPresented:)`: a card up from the bottom over a dimmed screen, as iOS 26 draws one —
 * the full width, its top corners rounded at 38 points and its foot following the display's own
 * corners, standing at the large detent from just under the status bar. A drag down, a tap on the
 * dimming and Back dismiss it unless [dismissible] is false (`interactiveDismissDisabled`).
 * More than one detent shows the grabber, as iOS does.
 *
 * The content paints its own page: most of the iPhone's sheets are a navigation stack on the
 * canvas, which [background] gives them.
 */
@Composable
fun Sheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    detents: List<SheetDetent> = listOf(SheetDetent.large),
    dismissible: Boolean = true,
    background: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    Present(
        PresentationKind.sheet,
        isPresented,
        onDismiss,
        PresentationOptions(detents = detents, dismissible = dismissible),
    ) {
        val page = if (background == Color.Unspecified) Theme.canvas else background
        Box(Modifier.fillMaxSize().background(page)) { content() }
    }
}

/** `.fullScreenCover(isPresented:)`: the whole screen, up from the bottom, dismissed only by the screen. */
@Composable
fun FullScreenCover(isPresented: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Present(PresentationKind.fullScreenCover, isPresented, onDismiss, PresentationOptions(dismissible = false), content)
}

/** Whether the screen being drawn is inside a sheet, which moves its bar under the sheet's own top. */
val LocalInSheet = compositionLocalOf { false }

@Composable
internal fun SheetLayer(layer: Presentation, transition: Transition<Boolean>, body: @Composable () -> Unit) {
    val progress = transition.progress(IosDurations.present, IosDurations.dismiss)
    val safe = safeArea()
    val options = layer.options
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    Box(Modifier.fillMaxSize()) {
        Scrim(progress, if (options.dismissible) layer.onDismiss else null)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val height = SheetMetrics.height(options.detents.last(), maxHeight, safe)
            val heightPx = with(density) { height.toPx() }
            fun settle(velocity: Float) {
                scope.launch {
                    if (options.dismissible && (drag.value > heightPx * 0.25f || velocity > 1200f)) {
                        layer.onDismiss()
                        drag.snapTo(0f)
                    } else {
                        drag.animateTo(0f, tween(250, easing = IosEasing))
                    }
                }
            }
            // Pulling the content down past its top drags the sheet, as a sheet's scroll view does.
            val overscroll = remember(options.dismissible) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (drag.value > 0f && available.y < 0f) {
                            val taken = maxOf(available.y, -drag.value)
                            scope.launch { drag.snapTo(drag.value + taken) }
                            return Offset(0f, taken)
                        }
                        return Offset.Zero
                    }

                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (options.dismissible && available.y > 0f && source == NestedScrollSource.UserInput) {
                            scope.launch { drag.snapTo(drag.value + available.y) }
                            return Offset(0f, available.y)
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (drag.value > 0f) {
                            settle(available.y)
                            return available
                        }
                        return Velocity.Zero
                    }
                }
            }
            val corner = SheetMetrics.bottomCorner(safe)
            CompositionLocalProvider(LocalInSheet provides true) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(height)
                        .graphicsLayer {
                            translationY = (1 - progress) * size.height + drag.value
                        }
                        .clip(SheetShape(SheetMetrics.topCorner, corner))
                        .nestedScroll(overscroll)
                        .draggable(
                            rememberDraggableState { delta ->
                                if (options.dismissible) scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) }
                            },
                            Orientation.Vertical,
                            onDragStopped = { velocity -> settle(velocity) },
                        ),
                ) {
                    body()
                    if (options.detents.size > 1) Grabber(Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

@Composable
private fun Grabber(modifier: Modifier) {
    Box(
        modifier
            .padding(top = 5.dp)
            .size(36.dp, 5.dp)
            .background(SystemColor.tertiaryLabel, CapsuleShape),
    )
}

/**
 * A sheet's outline: its top corners at [top], and its foot at [bottom], the display's own corner
 * radius, so the card sits in the screen's curve as the iPhone's does. Both are continuous.
 */
class SheetShape(private val top: Dp, private val bottom: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val t = with(density) { top.toPx() }
        val b = with(density) { bottom.toPx() }
        val upper = continuousPath(Rect(0f, 0f, size.width, size.height + t * 2), t)
        val lower = continuousPath(Rect(0f, -b * 2, size.width, size.height), b)
        val path = Path()
        path.op(upper, lower, androidx.compose.ui.graphics.PathOperation.Intersect)
        return Outline.Generic(path)
    }
}

/** The sheet's measurements, from the iPhone 17 reference screenshots. */
object SheetMetrics {
    val topCorner = 38.dp

    /** The bar inside a sheet stands this far under the sheet's top edge. */
    val barTop = 16.dp

    /** The display's corner where the window says it, and a square foot where there is none. */
    fun bottomCorner(safe: SafeArea): Dp = safe.displayCorner

    fun height(detent: SheetDetent, screen: Dp, safe: SafeArea): Dp = when (detent) {
        SheetDetent.large -> screen - safe.top
        SheetDetent.medium -> screen / 2
        is SheetDetent.height -> detent.value
    }
}
