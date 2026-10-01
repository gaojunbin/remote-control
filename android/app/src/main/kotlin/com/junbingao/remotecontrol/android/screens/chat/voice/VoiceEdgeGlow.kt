package com.junbingao.remotecontrol.android.screens.chat.voice

import android.graphics.BlurMaskFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The light that runs around the edge of the display while dictation listens.
 *
 * It is a stroke, not a fill: a soft multi-colour gradient following the screen's own rounded
 * corners, breathing with the measured input level. On a light page a low-alpha, heavily blurred
 * stroke reads as light spilling in from outside the screen rather than as a coloured border.
 */
@Composable
fun VoiceEdgeGlow(active: Boolean, level: Double, reduceMotion: Boolean, cornerRadius: Dp = DisplayCorner.fallbackRadius) {
    val energy = remember { Animatable(0f) }
    LaunchedEffect(level, reduceMotion) {
        val next = if (level.isFinite()) level.coerceIn(0.0, 1.0).toFloat() else 0f
        if (reduceMotion) {
            energy.snapTo(next)
            return@LaunchedEffect
        }
        // Rising fast and falling slow is what makes it read as breathing rather than as a meter.
        energy.animateTo(next, tween(if (next > energy.value) 140 else 320, easing = EaseOut))
    }
    if (active) VoiceGlowField(energy.value.toDouble(), reduceMotion, cornerRadius)
}

@Composable
fun VoiceGlowField(energy: Double, reduceMotion: Boolean, cornerRadius: Dp) {
    val input = if (energy.isFinite()) energy.coerceIn(0.0, 1.0) else 0.0
    // Reduce Motion gets a ring that never moves with the voice: one fixed width, and a slow
    // opacity pulse so it is still clearly listening.
    val expansion = if (reduceMotion) 0.3 else input
    val emphasis = if (reduceMotion) 0.0 else input
    val pulse = if (reduceMotion) {
        rememberInfiniteTransition(label = "glow pulse").animateFloat(
            initialValue = 0.55f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(2400, easing = EaseInOut), RepeatMode.Reverse),
            label = "glow pulse",
        ).value
    } else {
        1f
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = pulse
                clip = true
            },
    ) {
        val radius = cornerRadius.toPx()
        // The light draws in a hair from the edge as it swells; what spills past the screen is cut
        // at the screen, not at the smaller rectangle.
        scale(scaleX = (1 - expansion * 0.010).toFloat(), scaleY = (1 - expansion * 0.004).toFloat()) {
            // Three strokes on the same path: a thin bright rim, a soft skirt and a wide low halo.
            // The page has to stay readable underneath, so the halo is the only wide one and it is
            // the faintest by far. The resting light is plainly there and full voice roughly
            // doubles the swing: the glow is meant to be seen, not found.
            lightSource(radius, width = 34 + expansion * 30, blur = 22 + expansion * 10, opacity = 0.22 + emphasis * 0.20)
            lightSource(radius, width = 12 + expansion * 12, blur = 8 + expansion * 3, opacity = 0.36 + emphasis * 0.24)
            lightSource(radius, width = 4 + expansion * 4, blur = 3.0, opacity = 0.60 + emphasis * 0.30)
        }
    }
}

/** One stroke of the light, in points, on the display's rounded rectangle, centred on its edge. */
private fun DrawScope.lightSource(radius: Float, width: Double, blur: Double, opacity: Double) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width.dp.toPx()
        alpha = (opacity.coerceIn(0.0, 1.0) * 255).toInt()
        shader = SweepGradient(size.width / 2, size.height / 2, GlowSpectrum.colors, GlowSpectrum.stops).apply {
            // SwiftUI's angular gradient starts at -90°, the top of the screen; Android's sweep
            // starts at three o'clock.
            setLocalMatrix(Matrix().apply { setRotate(-90f, size.width / 2, size.height / 2) })
        }
        maskFilter = BlurMaskFilter((blur * GlowSpectrum.blurScale).dp.toPx().coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRoundRect(RectF(0f, 0f, size.width, size.height), radius, radius, paint)
    }
}

/** The colours the light runs through, clockwise from the top. */
internal object GlowSpectrum {
    val colors = intArrayOf(
        rgb(1.0, 0.25, 0.48), rgb(1.0, 0.18, 0.42), rgb(0.84, 0.16, 0.90), rgb(0.40, 0.36, 1.0),
        rgb(0.30, 0.48, 1.0), rgb(0.70, 0.24, 0.98), rgb(1.0, 0.65, 0.24), rgb(1.0, 0.25, 0.48),
    )
    val stops = floatArrayOf(0f, 0.12f, 0.28f, 0.46f, 0.60f, 0.76f, 0.94f, 1f)

    /**
     * SwiftUI's blur radius is the Gaussian's spread itself; a mask filter's radius is about 1.73
     * of it, so the figures the iPhone chose by eye are scaled to land as soft.
     */
    const val blurScale = 1.73

    private fun rgb(red: Double, green: Double, blue: Double): Int =
        android.graphics.Color.rgb((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
}

/**
 * How round the display's own corners are.
 *
 * The iPhone cannot read the figure and follows its bottom safe area instead: a device with a home
 * indicator has a rounded display and a radius close to this; anything squarer reads correctly with
 * a small one, and the light is blurred far past the error either way. Android says it where the
 * display has one (`SafeArea.displayCorner`), and the glow takes that first.
 */
object DisplayCorner {
    val fallbackRadius = 55.dp
    val squareRadius = 14.dp

    fun radius(bottomSafeArea: Dp): Dp = if (bottomSafeArea > 0.dp) fallbackRadius else squareRadius
}
