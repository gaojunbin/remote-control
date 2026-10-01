package com.junbingao.remotecontrol.android.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** The layout of the text a [swiftUITextBox] wraps, handed over as the text is measured. */
internal class TextBoxMeasure {
    var result: TextLayoutResult? = null
}

/**
 * SwiftUI's box for a run of text, measured from the reference screenshots: the first baseline
 * one SF ascent below the top and the box ending one SF descent below the last baseline, with
 * the style's leading between lines — so two lines of caption are 16 apart and a single line is
 * SF's own 1.19 of the size tall, whatever Roboto's metrics are.
 *
 * Android gives a line holding Chinese the CJK face's taller metrics; the box is still placed by
 * the baselines, so a single line of Chinese stands where the iPhone's does. Lines after the first
 * keep Android's spacing for it, which is wider than the iPhone's by about a quarter of the size.
 */
internal fun Modifier.swiftUITextBox(style: TextStyle, measure: TextBoxMeasure): Modifier {
    val size = style.fontSize
    if (!size.isSp) return this
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val result = measure.result
        if (result == null) {
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else {
            val s = size.toPx()
            val top = (result.firstBaseline - SfAscent * s).roundToInt()
            val bottom = (result.lastBaseline + SfDescent * s).roundToInt()
            val height = (bottom - top).coerceAtLeast(0).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(
                placeable.width,
                height,
                mapOf(FirstBaseline to placeable[FirstBaseline] - top, LastBaseline to placeable[LastBaseline] - top),
            ) {
                placeable.place(0, -top)
            }
        }
    }
}

// SF Pro's ascent and descent, in ems.
private const val SfAscent = 0.952f
private const val SfDescent = 0.241f
