package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/**
 * Where the primary face puts a line's first baseline, in the whole pixels Compose reports a
 * baseline in. SwiftUI reports a line's baseline from the primary font whatever script the line
 * holds, and centres a taller face that sets the rest — Chinese — about that line, so the Mac
 * draws Chinese half a point above where Latin of the same style sits. Compose reports the line's
 * own baseline, which the taller face has moved, and a line box aligning it would undo that half
 * point. So the line box aligns this one, read once per style from a Latin letter.
 */
internal object PrimaryBaseline {
    private data class Key(
        val size: Float,
        val weight: FontWeight,
        val mono: Boolean,
        val lineBox: Float,
        val density: Float,
        val fontScale: Float,
    )

    private val known = ConcurrentHashMap<Key, Int>()

    fun of(style: ComposeTextStyle, weight: FontWeight, mono: Boolean, lineBox: Float, measurer: TextMeasurer, density: Density): Int {
        val key = Key(style.fontSize.value, weight, mono, lineBox, density.density, density.fontScale)
        return known.getOrPut(key) { measurer.measure("X", style, maxLines = 1, density = density).firstBaseline.roundToInt() }
    }
}
