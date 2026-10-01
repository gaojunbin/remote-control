package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.text.font.FontWeight
import kotlin.math.floor

/**
 * The web's type, set the way its stylesheets set it: a size, a weight, a `line-height` and a
 * `letter-spacing`, in the system face (`-apple-system` on a Mac, Segoe UI Variable on Windows) or
 * its monospaced cut (`ui-monospace`).
 *
 * A browser gives every line a box of `line-height × font-size` and puts the baseline in it by a
 * rule of its own; Compose gives a line the font's own height. `Text` with a style puts the text
 * on the browser's baseline and gives it the browser's height, so a block of text takes the room,
 * and sits where, the web's does. Measured against Chrome at every size and weight the web uses,
 * the baseline sits at ⌊(L − (round(ascent) + round(descent))) ÷ 2⌋ + round(ascent) from the top of
 * each line of height L.
 */
data class TextStyle(
    val size: Float = FontSize.fs14,
    val weight: FontWeight = FontWeight.Normal,
    val mono: Boolean = false,
    /** The CSS `line-height` multiplier. The body's is 1.5. */
    val lineHeight: Float = 1.5f,
    /** The CSS `letter-spacing` in em. */
    val tracking: Float = 0f,
) {
    /** One line's box: `line-height × font-size`. */
    val lineBox: Float get() = size * lineHeight

    /** Where the browser puts the baseline in one line's box. */
    val baseline: Float
        get() {
            val (ascent, descent) = SystemFace.face(size, weight, mono).metrics(size)
            val a = roundHalfUp(ascent)
            val d = roundHalfUp(descent)
            return floor((lineBox - (a + d)) / 2) + a
        }
}

/** The same, spelled as a CSS rule: `css(FontSize.fs13, weight = FontWeight.Medium)`. */
fun css(
    size: Float,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: Float = 1.5f,
    mono: Boolean = false,
    tracking: Float = 0f,
): TextStyle = TextStyle(size = size, weight = weight, mono = mono, lineHeight = lineHeight, tracking = tracking)

/**
 * The line a text without a CSS rule takes, as SwiftUI gives one to the Mac's text drawn in the
 * environment's font: its height and where its baseline sits. It is not the font's ascent and
 * descent: SwiftUI rounds them its own way (15 points for 12-point SF, 19 for both 15 and 16), the
 * same whatever script the line holds, because the primary font decides it. Those are measured
 * values for the Mac's face; elsewhere the line is the rounded ascent over the descent rounded up.
 */
internal data class NaturalLine(val height: Float, val baseline: Float) {
    companion object {
        private val macFace = System.getProperty("os.name").orEmpty().startsWith("Mac")

        /** SwiftUI's lines for SF and SF Mono at the web's sizes, whatever the weight. */
        private val swiftUI = mapOf(
            11f to NaturalLine(14f, 11f), 12f to NaturalLine(15f, 12f), 13f to NaturalLine(16f, 13f),
            14f to NaturalLine(17f, 14f), 15f to NaturalLine(19f, 15f), 16f to NaturalLine(19f, 15f),
            17f to NaturalLine(20f, 16f), 22f to NaturalLine(26f, 21f), 30f to NaturalLine(35f, 29f),
        )

        fun of(size: Float, weight: FontWeight, mono: Boolean): NaturalLine {
            if (macFace) swiftUI[size]?.let { return it }
            val (ascent, descent) = SystemFace.face(size, weight, mono).metrics(size)
            val baseline = roundHalfUp(ascent)
            return NaturalLine(baseline + kotlin.math.ceil(descent), baseline)
        }
    }
}

/** `Math.round`, and Swift's `.rounded()` for the positive values type deals in: halves go up. */
internal fun roundHalfUp(value: Float): Float = floor(value + 0.5f)
