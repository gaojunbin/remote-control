package com.junbingao.remotecontrol.android.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.android.design.LocalAppearance

/**
 * Draws [navigator]'s top screen with UIKit's push: the new screen slides in from the trailing
 * edge over the one it covers, which drifts a third of the way back; Back plays it in reverse.
 * Each screen keeps its own saved state (its scroll position, its fields) while it is covered.
 */
@Composable
fun NavigationStack(navigator: Navigator, modifier: Modifier = Modifier, destination: @Composable (Any) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val entry = navigator.top
    val still = LocalAppearance.current.reduceMotion
    CompositionLocalProvider(LocalNavigator provides navigator) {
        AnimatedContent(
            targetState = entry,
            modifier = modifier.fillMaxSize(),
            transitionSpec = {
                val duration = if (still) 0 else PUSH_MILLIS
                // Entries are numbered as they are made, so a newer one arriving is a push.
                val pushing = targetState.id > initialState.id
                if (pushing) {
                    slideInHorizontally(tween(duration, easing = PushEasing)) { it } togetherWith
                        slideOutHorizontally(tween(duration, easing = PushEasing)) { -it / 3 }
                } else {
                    slideInHorizontally(tween(duration, easing = PushEasing)) { -it / 3 } togetherWith
                        slideOutHorizontally(tween(duration, easing = PushEasing)) { it }
                }.apply { targetContentZIndex = if (pushing) 1f else -1f }
            },
            contentKey = { it.id },
            label = "navigation",
        ) { shown ->
            holder.SaveableStateProvider(shown.id) {
                Box(Modifier.fillMaxSize()) { destination(shown.route) }
            }
        }
    }
}

private const val PUSH_MILLIS = 350
private val PushEasing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.85f, 0.25f, 1f)
