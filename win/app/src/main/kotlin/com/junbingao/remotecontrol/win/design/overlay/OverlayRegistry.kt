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

    /** The views holding Escape (`claimsEscape`), which stand among the overlays but draw nothing. */
    private val claims = mutableListOf<EscapeClaim>()

    /** Where the layer sits in the window, which a trigger's bounds are measured from. */
    internal var origin: Offset = Offset.Zero

    val isEmpty: Boolean get() = entries.isEmpty() && claims.isEmpty()

    /** Whether a modal or the drawer is open, which is what blurs the page. */
    val isBlocking: Boolean get() = entries.any { !it.kind.isPopover }

    internal fun register(entry: OverlayEntry) {
        if (entry !in entries) entries += entry
    }

    internal fun unregister(entry: OverlayEntry) {
        entries -= entry
    }

    internal fun claim(claim: EscapeClaim) {
        if (claim !in claims) claims += claim
    }

    internal fun release(claim: EscapeClaim) {
        claims -= claim
    }

    /** Escape: the newest overlay, or the newest view holding Escape, closes, and only that one. True when one did. */
    fun dismissNewest(): Boolean {
        val overlay = entries.maxByOrNull { it.openedAt }
        val claim = claims.maxByOrNull { it.openedAt }
        when {
            claim != null && (overlay == null || claim.openedAt > overlay.openedAt) -> claim.dismiss()
            overlay != null -> overlay.dismiss()
            else -> return false
        }
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
