package com.junbingao.remotecontrol.win.design.overlay

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class PopoverAlign { start, end }
enum class PopoverSide { top, bottom }

/**
 * `web/src/components/popoverPlacement.ts`: where a popover panel goes, in window coordinates (CSS
 * px).
 *
 * The panel is drawn in the window's overlay layer rather than inside its trigger, so it is placed
 * against the window, which is what keeps it out of every clipped list surface and every scrolling
 * pane it is anchored in.
 */
object PopoverPlacement {
    /** Gap between the trigger and the panel, and the margin kept to the window. */
    const val gap = 6f
    const val edge = 8f

    /** Exactly one of `top` and `bottom` is set; the other side is left free. */
    data class Placement(val left: Float, val top: Float?, val bottom: Float?)

    /**
     * Anchors the panel to `trigger`, flipping to the other side only when the asked-for one cannot
     * hold the panel and the other one can hold more of it, and keeping the panel inside the window
     * horizontally.
     */
    fun place(trigger: Rect, panel: Size, viewport: Size, align: PopoverAlign, side: PopoverSide): Placement {
        val below = viewport.height - trigger.bottom - gap - edge
        val above = trigger.top - gap - edge

        var placeBelow = side == PopoverSide.bottom
        if (placeBelow && panel.height > below && above > below) {
            placeBelow = false
        } else if (!placeBelow && panel.height > above && below > above) {
            placeBelow = true
        }

        val preferred = if (align == PopoverAlign.start) trigger.left else trigger.right - panel.width
        val left = min(max(preferred, edge), max(edge, viewport.width - panel.width - edge))

        return Placement(
            left = jsRound(left),
            top = if (placeBelow) jsRound(trigger.bottom + gap) else null,
            bottom = if (placeBelow) null else jsRound(viewport.height - trigger.top + gap),
        )
    }

    /** The panel's top edge, whichever side it was placed on. */
    fun top(placement: Placement, panelHeight: Float, viewportHeight: Float): Float =
        placement.top ?: (viewportHeight - (placement.bottom ?: 0f) - panelHeight)

    /** `Math.round`. */
    private fun jsRound(value: Float): Float = floor(value + 0.5f)
}
