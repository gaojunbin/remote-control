package com.junbingao.remotecontrol.win.design.overlay

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset

/**
 * The overlays drawn right now, which is what Escape and a press outside act on. The web keeps
 * the same stack (`overlayStack` in `Modal.tsx`) because every handler sits on the document and
 * the newest has to be the one that closes.
 */
class OverlayRegistry {
    internal val entries = mutableStateListOf<OverlayEntry>()

    /** Where the layer sits in the window, which a trigger's bounds are measured from. */
    internal var origin: Offset = Offset.Zero

    val isEmpty: Boolean get() = entries.isEmpty()

    /** Whether a modal or the drawer is open, which is what blurs the page. */
    val isBlocking: Boolean get() = entries.any { !it.kind.isPopover }

    internal fun register(entry: OverlayEntry) {
        if (entry !in entries) entries += entry
    }

    internal fun unregister(entry: OverlayEntry) {
        entries -= entry
    }

    /** Escape: the newest overlay closes, and only that one. True when one did. */
    fun dismissNewest(): Boolean {
        val newest = entries.maxByOrNull { it.openedAt } ?: return false
        newest.dismiss()
        return true
    }

    /**
     * A press anywhere closes every popover it is not inside, and goes on to whatever is under it,
     * as a `mousedown` on the document does on the web. `at` is in the layer's coordinates.
     */
    fun pointerDown(at: Offset) {
        for (entry in entries.toList()) {
            if (!entry.kind.isPopover) continue
            // Not placed yet means not on screen yet: nothing to have missed.
            val panel = entry.panel ?: continue
            val trigger = entry.trigger
            if (!panel.contains(at) && trigger?.contains(at) != true) entry.dismiss()
        }
    }
}

internal val LocalOverlayRegistry = staticCompositionLocalOf<OverlayRegistry?> { null }
