package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle

/**
 * How glyphs are rasterised. The web draws its type on a Mac with `-webkit-font-smoothing:
 * antialiased` — plain greyscale antialiasing, without the stem darkening macOS adds by default —
 * and the Mac app turns the darkening off to match. On a Mac, where the renderer's pictures are
 * compared with the Mac's, Skia is asked for the same: greyscale coverage, no hinting (which on a
 * Mac is what asks CoreGraphics to dilate the outlines), glyphs at fractional positions. On Windows
 * text is drawn as the platform draws it.
 */
@OptIn(ExperimentalTextApi::class)
object TextRendering {
    private val matchesMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

    val settings: FontRasterizationSettings =
        if (matchesMac) {
            FontRasterizationSettings(
                smoothing = FontSmoothing.AntiAlias,
                hinting = FontHinting.None,
                subpixelPositioning = true,
                autoHintingForced = false,
            )
        } else {
            FontRasterizationSettings.PlatformDefault
        }

    val platformStyle = PlatformTextStyle(spanStyle = null, paragraphStyle = PlatformParagraphStyle(settings))

    /**
     * Skia scales a glyph's coverage by the luminance of the colour it is drawn in: at 15 px, ink
     * text comes out 7 % lighter than CoreGraphics draws the same outline, and light text about as
     * much heavier. The Mac's text, like the browser's, is the outline's own coverage. Drawn in the
     * one grey whose glyphs carry their outlines' coverage — `#6B6B6B`, measured against Skia's
     * own filled paths — and recoloured as a whole, a text carries the Mac's ink. On a Mac only,
     * where the pictures are compared; elsewhere null, and text is drawn in its own colour.
     */
    val neutralInk: Color? = if (matchesMac) Color(0xFF6B6B6B) else null
}

/**
 * The text this modifies, drawn in `TextRendering.neutralInk` and recoloured `color` on the way
 * to the canvas, in a layer a line taller and wider than the box so no glyph that overhangs it is
 * cut.
 */
internal fun Modifier.recoloured(color: Color): Modifier = drawWithContent {
    val paint = Paint().apply { colorFilter = ColorFilter.tint(color, BlendMode.SrcIn) }
    val room = size.height
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(-room, -room, size.width + room, size.height + room), paint)
        drawContent()
        canvas.restore()
    }
}
