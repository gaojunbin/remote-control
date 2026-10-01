package com.junbingao.remotecontrol.android.system

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.LocalAppearance
import com.junbingao.remotecontrol.android.design.LocalForeground
import com.junbingao.remotecontrol.android.design.SystemColor
import kotlin.math.floor

/** The sizes `ProgressView` comes in: `.controlSize(.mini)`, the default, and `.large`. */
enum class IndicatorSize(val side: Dp) { mini(14.dp), regular(20.dp), large(37.dp) }

/**
 * `ProgressView()`: the iPhone's spinner — eight spokes round a centre, the brightest stepping
 * round once a second and the rest fading behind it. It is drawn, not Material's arc, because the
 * spinner is one of the pieces the iPhone's shape is kept for. Reduce Motion holds it still.
 */
@Composable
fun ActivityIndicator(
    modifier: Modifier = Modifier,
    size: IndicatorSize = IndicatorSize.regular,
    tint: Color = Color.Unspecified,
) {
    val inherited = LocalForeground.current
    val ink = when {
        tint != Color.Unspecified -> tint
        inherited != Color.Unspecified -> inherited
        else -> SystemColor.systemGray
    }
    val still = LocalAppearance.current.reduceMotion
    val phase = if (still) 0f else rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = SPOKES.toFloat(),
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "spinner",
    ).value
    val lead = floor(phase).toInt()
    Canvas(modifier.size(size.side).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val radius = this.size.minDimension / 2
        val width = radius * 0.24f
        for (spoke in 0 until SPOKES) {
            val behind = (lead - spoke + SPOKES) % SPOKES
            val alpha = 1f - behind * (0.85f / SPOKES)
            rotate(spoke * 360f / SPOKES) {
                drawLine(
                    ink.copy(alpha = ink.alpha * alpha),
                    start = Offset(center.x, center.y - radius * 0.48f),
                    end = Offset(center.x, center.y - radius + width / 2),
                    strokeWidth = width,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private const val SPOKES = 8
