package com.junbingao.remotecontrol.win.design.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Shadow
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.boxShadow
import com.junbingao.remotecontrol.win.design.stackChild
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * `.overlay`: the dimmed backdrop under a modal or the drawer, which a press on closes
 * (`onMouseDown` on the web: the press, not the release). It takes the press, so nothing under it
 * sees one.
 */
@Composable
internal fun Backdrop(dismiss: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val shown = remember { Animatable(0f) }
    // `rc-fade` over `--dur`.
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.ease(Motion.dur, reduceMotion)) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown.value }
            .background(Palette.overlay)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    dismiss()
                }
            },
    )
}

/**
 * A modal where `.overlay` puts it: centred with 16 px kept to the window, or, at 640 px and
 * narrower, a sheet on the bottom edge — still no wider than it asked for — with its lower corners
 * square. Its height is capped at 88 % of the window (820 at most), or 92 % as a sheet.
 */
@Composable
internal fun ModalFrame(width: Dp, startsInField: Boolean, viewport: Size, dismiss: () -> Unit, content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val sheet = viewport.width <= 640
    val shape = ModalShape(sheet)
    val risen = remember { Animatable(0f) }
    LaunchedEffect(Unit) { risen.animateTo(1f, Motion.ease(Motion.dur, reduceMotion)) }
    val fields = remember { DialogFields() }
    Box(Modifier.fillMaxSize()) {
        Backdrop(dismiss)
        WholePointCenter(bottom = sheet, modifier = Modifier.fillMaxSize().padding(if (sheet) 0.dp else Space.sp4)) {
            Box(
                Modifier
                    // `rc-rise`: up 8 px and from 99 % over `--dur`.
                    .graphicsLayer {
                        alpha = risen.value
                        translationY = (1 - risen.value) * 8.dp.toPx()
                        scaleX = 0.99f + 0.01f * risen.value
                        scaleY = scaleX
                    }
                    .boxShadow(Shadow.modal, shape)
                    .clip(shape)
                    .background(Palette.surface)
                    // The width a modal asks for holds as a sheet too: the web sets it inline, and
                    // the narrow rule leaves it alone.
                    .widthIn(max = width)
                    .fillMaxWidth()
                    .heightIn(max = (if (sheet) viewport.height * 0.92f else min(viewport.height * 0.88f, 820f)).dp),
            ) {
                CompositionLocalProvider(LocalDialogFields provides fields) { content() }
            }
        }
    }
    DialogFocus(startsInField, fields)
}

/** The modal's 16 px radius, square at the bottom when it is a sheet. */
internal fun ModalShape(sheet: Boolean) = RoundedCornerShape(
    topStart = Radius.lg,
    topEnd = Radius.lg,
    bottomStart = if (sheet) 0.dp else Radius.lg,
    bottomEnd = if (sheet) 0.dp else Radius.lg,
)

/**
 * `.drawer-overlay` + `.drawer`: the full height of the window on its right, 480 px wide or the
 * whole width at 640 and narrower, sliding in from 24 px to the right.
 */
@Composable
internal fun DrawerFrame(viewport: Size, dismiss: () -> Unit, content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val width = if (viewport.width <= 640) viewport.width else min(480f, viewport.width)
    val slid = remember { Animatable(0f) }
    LaunchedEffect(Unit) { slid.animateTo(1f, Motion.ease(Motion.dur, reduceMotion)) }
    val fields = remember { DialogFields() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Backdrop(dismiss)
        Box(
            Modifier
                .graphicsLayer {
                    alpha = slid.value
                    translationX = (1 - slid.value) * 24.dp.toPx()
                }
                .width(width.dp)
                .fillMaxHeight()
                .boxShadow(Shadow.modal, RectangleShape)
                .background(Palette.surface),
        ) {
            CompositionLocalProvider(LocalDialogFields provides fields) { content() }
        }
    }
    // Its head holds the close button, which is where focus goes: no field.
    DialogFocus(startsInField = false, fields)
}

/**
 * `.popover-panel`, placed by `PopoverPlacement` against its trigger: at least 200 px wide, at
 * most 360 or the window less 32, a 4 px inset, a 12 px radius and `--shadow-pop`. It is measured
 * and placed in one pass, as the web's layout effect places it before the browser paints, and
 * rises into view (`rc-pop`: from 2 px up and transparent, over `--dur-fast`).
 */
@Composable
internal fun PopoverFrame(entry: OverlayEntry, align: PopoverAlign, side: PopoverSide, viewport: Size, content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.ease(Motion.durFast, reduceMotion)) }
    val shape = RoundedCornerShape(Radius.md)
    val trigger = entry.trigger
    Layout(
        content = {
            Box(
                Modifier
                    .graphicsLayer {
                        alpha = shown.value
                        translationY = -(1 - shown.value) * 2.dp.toPx()
                    }
                    .boxShadow(Shadow.pop, shape)
                    .background(Palette.surface, shape)
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 200.dp, max = min(360f, viewport.width - 32).dp)
                    .padding(Space.sp1),
            ) { content() }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val panel = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (trigger == null) return@layout
            val unit = density
            val size = Size(panel.width / unit, panel.height / unit)
            val placement = PopoverPlacement.place(
                Rect(trigger.left / unit, trigger.top / unit, trigger.right / unit, trigger.bottom / unit),
                size, viewport, align, side,
            )
            val top = PopoverPlacement.top(placement, size.height, viewport.height)
            val x = (placement.left * unit).roundToInt()
            val y = (top * unit).roundToInt()
            entry.panel = Rect(x.toFloat(), y.toFloat(), (x + panel.width).toFloat(), (y + panel.height).toFloat())
            panel.place(x, y)
        }
    }
}

/**
 * Centres its content, or sets it on the bottom edge, on a whole CSS px: the browser puts a box
 * that a flex container centres 327.5 px down at 328. Where nothing bounds its height — inside a
 * scroll view — it is as tall as its content and at least `minimumHeight`.
 */
@Composable
fun WholePointCenter(bottom: Boolean = false, minimumHeight: Dp = 0.dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val child = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val childWidth = child?.width ?: 0
        val childHeight = child?.height ?: 0
        // The content's exact height, as the Mac centres it: a card of fractional lines is not
        // the whole pixels it was laid out in (`ExactHeight`).
        val exactHeight = childHeight + (measurables.firstOrNull()?.stackChild?.exact?.fraction ?: 0f)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else childWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else max(childHeight, minimumHeight.roundToPx())
        layout(width, height) {
            val unit = density
            val x = (kotlin.math.floor((width - childWidth) / 2f / unit + 0.5f) * unit).roundToInt()
            val y = if (bottom) height - childHeight else (kotlin.math.floor((height - exactHeight) / 2f / unit + 0.5f) * unit).roundToInt()
            child?.place(x, y)
        }
    }
}
