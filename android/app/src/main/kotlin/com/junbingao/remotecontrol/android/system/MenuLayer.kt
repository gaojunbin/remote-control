package com.junbingao.remotecontrol.android.system

import androidx.compose.animation.core.Transition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize

/**
 * A presented menu: the card where [MenuPlacement] stands it, growing out of its source as it
 * comes in, over a dimmed screen for a context menu and over a clear one for a pull-down menu,
 * where a tap anywhere outside closes it and reaches nothing behind.
 */
@Composable
internal fun MenuLayer(layer: Presentation, transition: Transition<Boolean>, body: @Composable () -> Unit) {
    val progress = transition.progress(IosDurations.menuIn, IosDurations.menuOut)
    val anchor = layer.options.anchor
    val covers = !layer.options.dimmed
    val safe = safeArea()
    Box(Modifier.fillMaxSize()) {
        if (layer.options.dimmed) {
            Scrim(progress, layer.onDismiss)
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = layer.onDismiss),
            )
        }
        Layout(content = { Box { body() } }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val card = measurables.first().measure(Constraints())
            val size = IntSize(card.width, card.height)
            val margin = MenuMetrics.margin.toPx()
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            val bounds = Rect(margin, safe.top.toPx() + margin, width - margin, height - safe.bottom.toPx() - margin)
            val source = anchor ?: Rect(width / 2, height / 2, width / 2, height / 2)
            val at = MenuPlacement.place(source, size, bounds, covers, gap = MenuMetrics.contextGap.toPx())
            val origin = MenuPlacement.origin(source, size, at)
            layout(constraints.maxWidth, constraints.maxHeight) {
                card.placeWithLayer(at) {
                    alpha = progress
                    val scale = 0.85f + 0.15f * progress
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = origin
                }
            }
        }
    }
}
