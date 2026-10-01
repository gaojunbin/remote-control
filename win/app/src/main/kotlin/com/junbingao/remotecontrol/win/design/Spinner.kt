package com.junbingao.remotecontrol.win.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** `.spinner`: a 14 px ring of `--line-strong` whose top quarter is ink, turning once every 0.7 s. */
@Composable
fun Spinner(size: Dp = 14.dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val turn by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "spinner")
    Canvas(modifier.size(size).rotate(turn)) {
        val width = 2.dp.toPx()
        val inset = 1.dp.toPx()
        val ring = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
        drawArc(Palette.lineStrong, 0f, 360f, false, Offset(inset, inset), ring, style = Stroke(width))
        // A CSS border-top on a circle is the arc between the two upper diagonals: a quarter turn
        // centred on the top.
        drawArc(Palette.ink, 225f, 90f, false, Offset(inset, inset), ring, style = Stroke(width))
    }
}
