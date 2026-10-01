package com.junbingao.remotecontrol.android.system

import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.junbingao.remotecontrol.android.design.LocalAppearance

/**
 * Draws [content] and, above it, everything [presenter] holds, in the order it was presented:
 * UIKit's presentation stack drawn in one window, so a picture of the screen carries its sheet,
 * its alert or its menu, and Back reaches the topmost first.
 */
@Composable
fun PresentationHost(presenter: Presenter, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPresenter provides presenter) {
        Box(Modifier.fillMaxSize()) {
            content()
            val layers = presenter.layers.toList()
            layers.forEachIndexed { index, layer ->
                // A sheet over a sheet stands lower than the ones it covers, so it knows how many there are.
                val sheetsUnder = layers.subList(0, index).count { it.kind == PresentationKind.sheet }
                key(layer) { PresentedLayer(presenter, layer, sheetsUnder) }
            }
        }
    }
}

@Composable
private fun PresentedLayer(presenter: Presenter, layer: Presentation, sheetsUnder: Int) {
    val transition = rememberTransition(layer.visibility, label = "presentation")
    if (!transition.currentState && !transition.targetState && !transition.isRunning) {
        SideEffect { presenter.forget(layer) }
        return
    }
    val body: @Composable () -> Unit = {
        val locals = layer.locals
        if (locals != null) CompositionLocalProvider(locals) { layer.content() } else layer.content()
    }
    when (layer.kind) {
        PresentationKind.sheet -> SheetLayer(layer, sheetsUnder, transition, body)
        PresentationKind.fullScreenCover -> CoverLayer(transition, body)
        PresentationKind.alert -> AlertLayer(transition, body)
        PresentationKind.confirmationDialog -> DialogLayer(layer, transition, body)
        PresentationKind.menu -> MenuLayer(layer, transition, body)
    }
}

/** How far a presentation has come in, 0 to 1, over iOS's own durations. */
@Composable
internal fun Transition<Boolean>.progress(durationIn: Int, durationOut: Int = durationIn): Float {
    val still = LocalAppearance.current.reduceMotion
    val value by animateFloat(
        transitionSpec = {
            tween(
                durationMillis = if (still) 0 else if (targetState) durationIn else durationOut,
                easing = IosEasing,
            )
        },
        label = "progress",
    ) { if (it) 1f else 0f }
    return value
}

/** The dimming behind a presentation, which a tap dismisses through when it may. */
@Composable
internal fun Scrim(progress: Float, onTap: (() -> Unit)?) {
    val dim = com.junbingao.remotecontrol.android.design.SystemColor.dimming
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress }
            .background(dim)
            .then(
                if (onTap != null) {
                    Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap)
                } else {
                    // Even an alert's scrim takes the touches, so nothing behind it can be reached.
                    Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {}
                },
            ),
    )
}

/** A full-screen cover: up from the bottom over everything, nothing dimmed behind it. */
@Composable
private fun CoverLayer(transition: Transition<Boolean>, body: @Composable () -> Unit) {
    val progress = transition.progress(IosDurations.present, IosDurations.dismiss)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = (1 - progress) * size.height },
    ) { body() }
}

/** UIKit's presentation curve: quick off the mark, long to settle. */
internal val IosEasing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

internal object IosDurations {
    const val present = 420
    const val dismiss = 320
    const val alertIn = 250
    const val alertOut = 180
    const val menuIn = 220
    const val menuOut = 160
}
