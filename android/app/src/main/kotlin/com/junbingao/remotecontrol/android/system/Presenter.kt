package com.junbingao.remotecontrol.android.system

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** What kind of thing is presented, which decides how it enters, where it sits and what is behind it. */
enum class PresentationKind { sheet, fullScreenCover, alert, confirmationDialog, menu }

/**
 * One thing presented over the app — a sheet, a full-screen cover, an alert, a confirmation
 * dialog or a menu — drawn by [PresentationHost] above everything, as UIKit presents above the
 * view that asked. It keeps the composition locals of the place that presented it, as a SwiftUI
 * sheet keeps its environment.
 */
class Presentation internal constructor(val kind: PresentationKind) {
    internal var content: @Composable () -> Unit by mutableStateOf({})
    internal var locals: CompositionLocalContext? by mutableStateOf(null)
    internal var onDismiss: () -> Unit = {}
    internal var options: PresentationOptions by mutableStateOf(PresentationOptions())

    /** Visible while true; the host plays the exit and forgets the layer once it has finished. */
    internal val visibility = MutableTransitionState(false)
}

/** What a presentation asks of its host beyond its kind. */
data class PresentationOptions(
    /** The sheet's detents, largest last; `.large` alone unless the screen asks for others. */
    val detents: List<SheetDetent> = listOf(SheetDetent.large),
    /** Whether a drag, a tap outside or Back may dismiss it. */
    val dismissible: Boolean = true,
    /** A menu's source, in root coordinates, which the menu opens against. */
    val anchor: androidx.compose.ui.geometry.Rect? = null,
    /** Whether the screen dims behind it: a context menu's does, a pull-down menu's does not. */
    val dimmed: Boolean = false,
)

/** How tall a sheet stands: iOS's `.medium`, `.large`, or a height of its own. */
sealed interface SheetDetent {
    data object medium : SheetDetent
    data object large : SheetDetent
    data class height(val value: androidx.compose.ui.unit.Dp) : SheetDetent
}

/**
 * Everything presented over the app, bottom first. Back dismisses the one on top before it
 * leaves a screen (`docs/DESIGN.md` § "The Android app"), which [dismissTop] is for.
 */
class Presenter {
    internal val layers = mutableStateListOf<Presentation>()

    /** Whether anything is presented, which is what Back asks first. */
    val isPresenting: Boolean get() = layers.any { it.visibility.targetState }

    internal fun show(layer: Presentation) {
        if (layer !in layers) layers.add(layer)
        layer.visibility.targetState = true
    }

    internal fun hide(layer: Presentation) {
        layer.visibility.targetState = false
    }

    internal fun forget(layer: Presentation) {
        layers.remove(layer)
    }

    /** Asks the topmost presentation to go, as its own dismissal would; false when there is none. */
    fun dismissTop(): Boolean {
        val top = layers.lastOrNull { it.visibility.targetState } ?: return false
        if (top.options.dismissible) top.onDismiss()
        return true
    }
}

val LocalPresenter = staticCompositionLocalOf { Presenter() }

/**
 * Presents [content] as [kind] while [isPresented] holds, the way SwiftUI's `.sheet(isPresented:)`
 * and its siblings do: the screen owns the flag, and [onDismiss] is how a drag, a tap outside or
 * Back asks the screen to clear it.
 */
@Composable
fun Present(
    kind: PresentationKind,
    isPresented: Boolean,
    onDismiss: () -> Unit,
    options: PresentationOptions = PresentationOptions(),
    content: @Composable () -> Unit,
) {
    val presenter = LocalPresenter.current
    val layer = remember(kind) { Presentation(kind) }
    val latest by rememberUpdatedState(content)
    val dismiss by rememberUpdatedState(onDismiss)
    layer.locals = currentCompositionLocalContext
    layer.options = options
    layer.onDismiss = { dismiss() }
    layer.content = { latest() }
    LaunchedEffect(isPresented) {
        if (isPresented) presenter.show(layer) else presenter.hide(layer)
    }
    DisposableEffect(layer) { onDispose { presenter.forget(layer) } }
}
