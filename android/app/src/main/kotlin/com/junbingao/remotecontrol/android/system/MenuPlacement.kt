package com.junbingao.remotecontrol.android.system

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Where iOS 26 stands a menu, measured on the iPhone 17 (the composer's attach, language and
 * permission menus, `ref/ios`).
 *
 * A pull-down menu grows out of its button and covers it: its top on the button's top where it
 * fits below, else its bottom on the button's bottom; its leading edge on the button's leading
 * edge where it fits that way, else its trailing edge on the button's trailing edge; and never
 * outside [bounds], the safe area less 8 points. The permission menu shows the last rule — the
 * shield sits right of the middle, so the menu turns to end at the shield's trailing edge and is
 * then held 8 points in from the left.
 *
 * A context menu stands clear of the element it was opened from instead, [gap] below it or above
 * it, so the element stays readable over the dimmed screen.
 */
internal object MenuPlacement {
    fun place(source: Rect, card: IntSize, bounds: Rect, covers: Boolean, gap: Float): IntOffset {
        val y = if (covers) coveringY(source, card, bounds) else besideY(source, card, bounds, gap)
        return IntOffset(x(source, card, bounds).roundToInt(), y.roundToInt())
    }

    /** Where the menu grows from: the source's centre, as a fraction of the card. */
    fun origin(source: Rect, card: IntSize, at: IntOffset): TransformOrigin = TransformOrigin(
        ((source.center.x - at.x) / card.width.coerceAtLeast(1)).coerceIn(0f, 1f),
        ((source.center.y - at.y) / card.height.coerceAtLeast(1)).coerceIn(0f, 1f),
    )

    private fun x(source: Rect, card: IntSize, bounds: Rect): Float {
        val leading = source.left
        val x = if (leading + card.width <= bounds.right) leading else source.right - card.width
        return clamp(x, bounds.left, bounds.right - card.width)
    }

    private fun coveringY(source: Rect, card: IntSize, bounds: Rect): Float = when {
        source.top + card.height <= bounds.bottom -> source.top
        source.bottom - card.height >= bounds.top -> source.bottom - card.height
        else -> clamp(bounds.bottom - card.height, bounds.top, bounds.bottom - card.height)
    }

    private fun besideY(source: Rect, card: IntSize, bounds: Rect, gap: Float): Float {
        val below = source.bottom + gap
        val above = source.top - gap - card.height
        return when {
            below + card.height <= bounds.bottom -> below
            above >= bounds.top -> above
            else -> clamp(bounds.bottom - card.height, bounds.top, bounds.bottom - card.height)
        }
    }

    // A card larger than the room left keeps its leading or top edge in view.
    private fun clamp(value: Float, low: Float, high: Float): Float = if (high < low) low else value.coerceIn(low, high)
}
