package com.junbingao.remotecontrol.android.system

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.design.widestLine
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.icons.SfSymbol
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * One button of a row's trailing swipe: its words and symbol and the tint it is drawn in — the
 * iPhone's `.tint(...)` on a swipe button, or red for a destructive one.
 */
data class SwipeAction(
    val title: String,
    val symbol: SfSymbol,
    val tint: Color,
    val tag: String? = null,
    val action: () -> Unit,
)

/**
 * `.swipeActions(edge: .trailing)` as iOS 26 draws it: the row slides left and uncovers its
 * buttons as capsules, each over its name in the secondary ink, listed from the edge inwards as
 * SwiftUI lists them — so [actions] is given edge-first and read left to right in reverse. The
 * buttons follow the row ([SwipeMetrics]): every capsule is as wide as the longest name, as tall
 * as the row leaves room for above the names, and the row slides as far as they need, however many
 * there are. A symbol is drawn in its filled form, as SwiftUI draws a swipe button's. A long swipe
 * runs the one nearest the edge; a tap on the row while it is open closes it. Assistive technology
 * reaches the same actions as custom actions on the row.
 */
@Composable
fun SwipeActions(actions: List<SwipeAction>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (actions.isEmpty()) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val buttonWidth = SwipeMetrics.buttonWidth(widestLine(actions.map { it.title }, SwipeMetrics.label))
    val reveal = with(density) { SwipeMetrics.reveal(actions.size, buttonWidth).toPx() }
    val offset = remember { Animatable(0f) }
    // The row's width at its last layout, which a full swipe is measured against.
    val rowWidth = remember { FloatArray(1) }
    val scope = rememberCoroutineScope()
    fun settle(to: Float) = scope.launch { offset.animateTo(to, tween(300, easing = IosEasing)) }
    val shown = actions.reversed()
    Layout(
        modifier = modifier
            .clipToBounds()
            .draggable(
                rememberDraggableState { delta ->
                    val furthest = maxOf(reveal * 2.2f, rowWidth[0])
                    scope.launch { offset.snapTo((offset.value + delta).coerceIn(-furthest, 0f)) }
                },
                Orientation.Horizontal,
                onDragStopped = { velocity ->
                    when {
                        -offset.value > SwipeMetrics.fullSwipe(reveal, rowWidth[0]) -> {
                            settle(0f)
                            actions.first().action()
                        }
                        offset.value < -reveal / 2 || velocity < -800f -> settle(-reveal)
                        else -> settle(0f)
                    }
                },
            )
            .semantics {
                customActions = actions.map { item ->
                    CustomAccessibilityAction(item.title) {
                        item.action()
                        true
                    }
                }
            },
        content = {
            Box(
                Modifier.then(
                    if (offset.value != 0f) {
                        Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { settle(0f) }
                    } else {
                        Modifier
                    },
                ),
            ) { content() }
            for (item in shown) SwipeButton(item) {
                settle(0f)
                item.action()
            }
        },
    ) { measurables, constraints ->
        val row = measurables[0].measure(constraints)
        rowWidth[0] = row.width.toFloat()
        val width = buttonWidth.roundToPx()
        val buttons = measurables.drop(1).map { it.measure(Constraints.fixed(width, row.height)) }
        val gap = SwipeMetrics.gap.roundToPx()
        val margin = SwipeMetrics.margin.roundToPx()
        val tray = margin * 2 + width * buttons.size + gap * (buttons.size - 1)
        layout(row.width, row.height) {
            val shift = offset.value.roundToInt()
            // The buttons stand where the row was, uncovered as it moves; they are laid under it.
            var x = row.width + shift.coerceAtLeast(-tray) + margin
            for (button in buttons) {
                button.place(x, 0)
                x += width + gap
            }
            row.place(shift, 0)
        }
    }
}

/**
 * One button: the capsule and, under it, the name, standing together in the middle of the row's
 * height, which the row hands down as this button's own.
 */
@Composable
private fun SwipeButton(item: SwipeAction, onClick: () -> Unit) {
    Layout(
        modifier = Modifier
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .then(if (item.tag != null) Modifier.testTag(item.tag) else Modifier),
        content = {
            Box(Modifier.background(item.tint, CapsuleShape), contentAlignment = Alignment.Center) {
                Icon(Sf.filled(item.symbol), font = SystemFont.body.weight(FontWeight.Medium), tint = Color.White)
            }
            Text(
                item.title,
                Modifier.wrapContentWidth(unbounded = true),
                style = SwipeMetrics.label,
                color = SystemColor.secondaryLabel,
                alignment = TextAlign.Center,
                lineLimit = 1,
            )
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val capsuleHeight = SwipeMetrics.capsuleHeight(constraints.maxHeight.toDp()).roundToPx()
        val capsule = measurables[0].measure(Constraints.fixed(width, capsuleHeight))
        val label = measurables[1].measure(Constraints(maxWidth = width))
        val labelGap = SwipeMetrics.labelGap.roundToPx()
        val top = (constraints.maxHeight - (capsuleHeight + labelGap + label.height)) / 2
        layout(width, constraints.maxHeight) {
            capsule.place(0, top)
            label.place((width - label.width) / 2, top + capsuleHeight + labelGap)
        }
    }
}

/** The swipe's measurements, from the iPhone 17 reference screenshots. */
object SwipeMetrics {
    /** A button's name: the footnote, under its capsule. */
    val label = SystemFont.footnote

    /** Between two buttons, and between the outer two and the edges of what the row uncovers. */
    val gap = 10.dp
    val margin = 10.dp

    /** From a capsule's foot to the top of its name. */
    val labelGap = 4.dp

    /**
     * A capsule is never taller than 50 points, and in a shorter row it gives up height so it and
     * its name fit with 28 points to spare: 38 in the Users screen's 66-point rows, 50 in a device's.
     */
    val tallest = 50.dp
    private val spare = 28.dp

    fun capsuleHeight(rowHeight: Dp): Dp = min(tallest, max(rowHeight - spare, 24.dp))

    /**
     * Every capsule is as wide as the longest name — Reset password's 96, Show quota's 71 — and no
     * narrower than the tallest capsule, so a lone short name still has a capsule of its own.
     */
    fun buttonWidth(longestName: Dp): Dp = max(longestName, tallest)

    /** How far the row slides to uncover [count] buttons [width] wide: 328 points for the Users screen's three. */
    fun reveal(count: Int, width: Dp) = width * count + gap * (count - 1) + margin * 2

    /**
     * How far a drag must take the row, in pixels, for letting go to run the button nearest the
     * edge rather than leave the buttons open: well past what they need, and never less than most
     * of the row, so a short row of narrow buttons is not run by a drag meant to open it.
     */
    fun fullSwipe(reveal: Float, rowWidth: Float): Float = maxOf(reveal * 1.6f, rowWidth * 0.6f)
}

/** The tint the iPhone gives a destructive swipe button. */
val destructiveTint: Color
    @Composable get() = Theme.danger
