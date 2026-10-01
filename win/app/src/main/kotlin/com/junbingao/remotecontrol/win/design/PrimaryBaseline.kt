package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/**
 * Where the primary face's line puts its first baseline, as drawn: in whole pixels below the top
 * of the text. SwiftUI reports a line's baseline from the primary font whatever script the line
 * holds, and centres a taller face that sets the rest — Chinese — about that line, so the Mac
 * draws Chinese half a point above where Latin of the same style sits. Compose reports the line's
 * own baseline, which the taller face has moved, and a line box aligning it would undo that half
 * point. So the line box aligns this one, read once per style from a Latin letter.
 *
 * It is read from the letter's pixels, the foot of its stem, not from the baseline Compose reports:
 * Compose reports a fraction (33.48 for 14 px on a 1.65 line) that Skia does not round as the
 * report rounds — it draws that line at 34, and 12 px on a 1.6 line, reported at 28.74, at 28. A
 * line box that put the report on the browser's baseline would leave a quarter of the web's
 * styles a pixel off the Mac's.
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
        return known.getOrPut(key) {
            val layout = measurer.measure("H", style.copy(color = Color.Black), maxLines = 1, density = density)
            drawn(layout, density) ?: layout.firstBaseline.roundToInt()
        }
    }

    /** The row below the foot of the letter's stem — the column holding the most ink — or null if nothing was drawn. */
    private fun drawn(layout: TextLayoutResult, density: Density): Int? {
        val width = layout.size.width.coerceAtLeast(1)
        val height = layout.size.height + 2
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
            drawText(layout)
        }
        val pixels = bitmap.toPixelMap()
        val stem = (0 until width).maxByOrNull { x -> (0 until height).count { y -> pixels[x, y].alpha > 0.5f } } ?: return null
        val foot = (0 until height).lastOrNull { y -> pixels[stem, y].alpha > 0.5f } ?: return null
        return foot + 1
    }
}
