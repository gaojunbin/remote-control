package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.swiftRounded
import kotlin.math.max
import kotlin.math.min

/**
 * How large the terminal's type is, in points (amendment A38, `docs/DESIGN.md` § "The terminal": a
 * pinch changes the type size, which is remembered).
 *
 * The bounds are the ones a phone can actually show: below the minimum a character is smaller than
 * a touch, above the maximum a shell prompt no longer fits on one line at any width.
 */
object TerminalTypeSize {
    const val minimum: Double = 8.0
    const val maximum: Double = 24.0

    /** What a terminal opens at before anyone has pinched one. */
    const val standard: Double = 12.0

    fun clamp(value: Double): Double {
        if (!value.isFinite()) return standard
        return min(max(minimum, value), maximum)
    }

    /** The size a pinch of this scale lands on, rounded to a whole point so a slow pinch steps rather than shimmers. */
    fun scaled(base: Double, by: Double): Double {
        if (!by.isFinite() || by <= 0) return clamp(base)
        return clamp((base * by).swiftRounded().toDouble())
    }
}
