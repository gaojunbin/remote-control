package com.junbingao.remotecontrol.android.system

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
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
 * buttons as capsules, 63 by 49, each over its name in the secondary ink, listed from the edge
 * inwards as SwiftUI lists them — so [actions] is given edge-first and read left to right in
 * reverse. A long swipe runs the one nearest the edge; a tap on the row while it is open closes
 * it. Assistive technology reaches the same actions as custom actions on the row.
 */
@Composable
fun SwipeActions(actions: List<SwipeAction>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (actions.isEmpty()) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val reveal = with(density) { SwipeMetrics.reveal(actions.size).toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun settle(to: Float) = scope.launch { offset.animateTo(to, tween(300, easing = IosEasing)) }
    val shown = actions.reversed()
    Layout(
        modifier = modifier
            .clipToBounds()
            .draggable(
                rememberDraggableState { delta ->
                    scope.launch { offset.snapTo((offset.value + delta).coerceIn(-reveal * 2.2f, 0f)) }
                },
                Orientation.Horizontal,
                onDragStopped = { velocity ->
                    when {
                        offset.value < -reveal * 1.6f -> {
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
            Row(
                Modifier.padding(horizontal = SwipeMetrics.margin),
                horizontalArrangement = Arrangement.spacedBy(SwipeMetrics.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (item in shown) SwipeButton(item) {
                    settle(0f)
                    item.action()
                }
            }
        },
    ) { measurables, constraints ->
        val row = measurables[0].measure(constraints)
        val buttons = measurables[1].measure(Constraints(maxHeight = row.height))
        layout(row.width, row.height) {
            val shift = offset.value.roundToInt()
            // The buttons stand where the row was, uncovered as it moves; they are laid under it.
            buttons.place(IntOffset(row.width + shift.coerceAtLeast(-buttons.width), (row.height - buttons.height) / 2))
            row.place(IntOffset(shift, 0))
        }
    }
}

@Composable
private fun SwipeButton(item: SwipeAction, onClick: () -> Unit) {
    Column(
        Modifier
            .width(SwipeMetrics.pillWidth)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .then(if (item.tag != null) Modifier.testTag(item.tag) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SwipeMetrics.labelGap),
    ) {
        Box(
            Modifier
                .size(SwipeMetrics.pillWidth, SwipeMetrics.pillHeight)
                .background(item.tint, CapsuleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.symbol, font = SystemFont.body.weight(androidx.compose.ui.text.font.FontWeight.Medium), tint = Color.White)
        }
        // The name may be wider than its capsule, as "Show quota" is on the iPhone.
        Text(
            item.title,
            Modifier.wrapContentWidth(unbounded = true),
            style = SystemFont.footnote,
            color = SystemColor.secondaryLabel,
            alignment = TextAlign.Center,
            lineLimit = 1,
        )
    }
}

/** The swipe's measurements, from the iPhone 17 reference screenshots. */
object SwipeMetrics {
    val pillWidth = 70.dp
    val pillHeight = 50.dp
    val gap = 12.dp
    val margin = 10.67.dp
    val labelGap = 7.dp

    /** How far the row slides to uncover [count] buttons: 255 points for three. */
    fun reveal(count: Int) = pillWidth * count + gap * (count - 1) + margin * 2
}

/** The tint the iPhone gives a destructive swipe button. */
val destructiveTint: Color
    @Composable get() = Theme.danger
