package com.junbingao.remotecontrol.win.chat.support

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/**
 * Where a style's first baseline is drawn, in whole pixels below the top of its text. Compose
 * reports a fractional baseline and Skia puts the glyphs on a whole pixel by a rounding of its
 * own: 14 px on a 1.65 line reports 33.48 and is drawn at 34, 12 px on a 1.6 line reports 28.74
 * and is drawn at 28. A line box that put the reported baseline, rounded, on the browser's
 * baseline would leave those texts a pixel off the Mac's, and they are the timeline's most common
 * type — a message's prose, a code block's lines. So the drawn baseline is read once per style from
 * the pixels of a Latin letter drawn offscreen, the foot of its stem, as the foundation reads the
 * primary face's baseline from one (`PrimaryBaseline`).
 */
internal object DrawnBaseline {
    private data class Key(
        val size: Float,
        val weight: Int,
        val mono: Boolean,
        val lineBox: Float,
        val density: Float,
        val fontScale: Float,
    )

    private val known = ConcurrentHashMap<Key, Int>()

    fun of(style: ComposeTextStyle, weight: Int, mono: Boolean, lineBox: Float, measurer: TextMeasurer, density: Density): Int {
        val key = Key(style.fontSize.value, weight, mono, lineBox, density.density, density.fontScale)
        return known.getOrPut(key) { measure(style, measurer, density) }
    }

    private fun measure(style: ComposeTextStyle, measurer: TextMeasurer, density: Density): Int {
        val layout = measurer.measure("H", style.copy(color = Color.Black), maxLines = 1, density = density)
        val reported = layout.firstBaseline.roundToInt()
        val width = layout.size.width.coerceAtLeast(1)
        val height = (layout.size.height + 2).coerceAtLeast(1)
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
            drawText(layout)
        }
        val pixels = bitmap.toPixelMap()
        // The stem is the column holding the most ink; its foot is the baseline.
        var stem = -1
        var most = 0
        for (x in 0 until width) {
            var count = 0
            for (y in 0 until height) if (pixels[x, y].alpha > 0.5f) count++
            if (count > most) {
                most = count
                stem = x
            }
        }
        if (stem < 0) return reported
        var foot = -1
        for (y in 0 until height) if (pixels[stem, y].alpha > 0.5f) foot = y
        return if (foot < 0) reported else foot + 1
    }
}
