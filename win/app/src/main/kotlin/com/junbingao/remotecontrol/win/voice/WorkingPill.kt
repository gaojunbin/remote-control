package com.junbingao.remotecontrol.win.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space

/**
 * `web/src/features/voice/WorkingPill.tsx`: the composer's primary slot while the app, and not the
 * person, has the next move — Send's pill with a spinner in it (`docs/DESIGN.md` § "The composer" →
 * **Done becomes a spinner, and the spinner becomes Send**). Deliberately not a button, and not a
 * disabled one either: a control that looks live and does nothing is the one thing the row must
 * never show. It says in words what is being waited for, because a spinner alone says only that
 * something is happening.
 */
@Composable
fun WorkingPill(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(34.dp)
            .widthIn(min = 34.dp)
            .background(Palette.ink, RoundedCornerShape(percent = 50))
            .padding(horizontal = Space.sp3)
            .clearAndSetSemantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        InverseSpinner()
    }
}

/** `.working-pill .spinner`: the ring read against the ink fill, not the page. */
@Composable
private fun InverseSpinner() {
    val transition = rememberInfiniteTransition(label = "working-pill")
    val turn by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "working-pill")
    Canvas(Modifier.size(14.dp).rotate(turn)) {
        val width = 2.dp.toPx()
        // `Circle().inset(by: 1).stroke(lineWidth: 2)`: the stroke centred on a circle 1 inside the box.
        val inset = 1.dp.toPx()
        val ring = Size(size.width - 2 * inset, size.height - 2 * inset)
        drawArc(Color.White.copy(alpha = 0.4f), 0f, 360f, false, Offset(inset, inset), ring, style = Stroke(width))
        // The circle's path from three o'clock, trimmed to its top quarter.
        drawArc(Palette.inkInverse, 225f, 90f, false, Offset(inset, inset), ring, style = Stroke(width))
    }
}
