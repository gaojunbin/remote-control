package com.junbingao.remotecontrol.win.design.overlay

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged

/**
 * Escape for a field inside an overlay rather than for the overlay: while the view this modifies,
 * or a field in it, has the focus, it stands as the newest overlay — one with nothing to draw — so
 * the one Escape closes is `onEscape`, as a view on the Mac registers with the overlay layer for
 * it. The directory picker's new folder name closes its row this way, and the picker around it
 * stays open.
 */
fun Modifier.claimsEscape(onEscape: () -> Unit): Modifier = composed {
    val registry = LocalOverlayRegistry.current
    val action = rememberUpdatedState(onEscape)
    val claim = remember(registry) { EscapeClaim(registry) { action.value() } }
    DisposableEffect(claim) { onDispose { claim.hold(false) } }
    onFocusChanged { claim.hold(it.hasFocus) }
}

/** One view's hold on Escape, taken anew — the newest of the overlays — each time the view takes the focus. */
internal class EscapeClaim(private val registry: OverlayRegistry?, val dismiss: () -> Unit) {
    var openedAt = 0
        private set
    private var holding = false

    fun hold(focused: Boolean) {
        if (focused == holding) return
        holding = focused
        val registry = registry ?: return
        if (focused) {
            openedAt = OverlayClock.next()
            registry.claim(this)
        } else {
            registry.release(this)
        }
    }
}
