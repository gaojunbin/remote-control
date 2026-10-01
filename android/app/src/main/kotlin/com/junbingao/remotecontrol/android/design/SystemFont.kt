package com.junbingao.remotecontrol.android.design

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The iPhone's text styles — SwiftUI's `.largeTitle` to `.caption2` at the default text size —
 * set in the system sans at the iPhone's point sizes as sp (`docs/DESIGN.md` § "The Android
 * app": SF cannot ship outside Apple's platforms). Each keeps the iPhone's line height, so a
 * paragraph is as tall as it is on the iPhone, and sp follows the phone's font size as Dynamic
 * Type follows the iPhone's.
 *
 * The tracking is Roboto's correction towards SF's widths at each size and weight ([tracking]),
 * so a line breaks where the iPhone's does.
 */
object SystemFont {
    val largeTitle = style(34f, 41f)
    val title = style(28f, 34f)
    val title2 = style(22f, 28f)
    val title3 = style(20f, 25f)
    val headline = style(17f, 22f).weight(FontWeight.SemiBold)
    val body = style(17f, 22f)
    val callout = style(16f, 21f)
    val subheadline = style(15f, 20f)
    val footnote = style(13f, 18f)
    val caption = style(12f, 16f)
    val caption2 = style(11f, 13f)

    /** `Font.system(size:)`: a size outside the ramp, on the face's own line height. */
    fun system(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
        style(size, size * NATURAL_LINE).weight(weight)

    /**
     * How much looser Roboto is set than its own spacing so a Latin line is as long as SF's at the
     * same size and weight, in ems. SF runs wider than Roboto by an amount that changes with the
     * size, since SF Text tightens its own tracking from 12 points up and SF Display loosens it
     * again from 20, and that grows with the weight, since SF's medium and semibold widen more
     * than Roboto's do. Measured on the reference screenshots, string by string: the regular
     * table at each size the screens use, and the extra for medium (the primary button) and
     * semibold (row titles, an alert's title). Chinese is the same width in both faces, and
     * `Text` sets it with none.
     */
    fun tracking(size: Float, weight: FontWeight = FontWeight.Normal): Float = regular(size) + extra(weight)

    private fun regular(size: Float): Float {
        val table = Tracking
        if (size <= table.first().first) return table.first().second
        if (size >= table.last().first) return table.last().second
        val upper = table.indexOfFirst { it.first >= size }
        val (s0, t0) = table[upper - 1]
        val (s1, t1) = table[upper]
        return t0 + (t1 - t0) * (size - s0) / (s1 - s0)
    }

    private fun extra(weight: FontWeight): Float = when {
        weight.weight >= 600 -> SEMIBOLD_EXTRA
        weight.weight >= 500 -> MEDIUM_EXTRA
        else -> 0f
    }

    private const val MEDIUM_EXTRA = 0.0155f
    private const val SEMIBOLD_EXTRA = 0.022f

    /** SF's own line height for a size that is not a text style, about 1.19 of the size. */
    private const val NATURAL_LINE = 1.19f

    private fun style(size: Float, line: Float): TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking(size).em,
        // Every line exactly the style's leading tall, a Chinese one included; `Text` then trims
        // the box to SwiftUI's (`swiftUITextBox`).
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
}

// Outside the object, so it exists before the object's styles are built from it. Regular weight;
// the sizes from 20 up were measured on the bold and semibold titles they set, less the extra.
private val Tracking = listOf(
    10f to 0.023f, 11f to 0.03f, 12f to 0.028f, 13f to 0.017f, 15f to 0.014f,
    16f to 0.008f, 17f to 0.0025f, 20f to -0.002f, 22f to -0.002f, 28f to 0.003f, 34f to 0.008f,
)

/**
 * `.weight(_:)` on a text style, which also moves the system face's tracking to the weight's
 * ([SystemFont.tracking]); a monospaced style keeps the spacing it has.
 */
fun TextStyle.weight(weight: FontWeight): TextStyle =
    if (fontFamily == FontFamily.Monospace || !fontSize.isSp) {
        copy(fontWeight = weight)
    } else {
        copy(fontWeight = weight, letterSpacing = SystemFont.tracking(fontSize.value, weight).em)
    }

/** `design: .monospaced`: the same size and line in the system's monospace face. */
fun TextStyle.monospaced(): TextStyle = copy(fontFamily = FontFamily.Monospace)
