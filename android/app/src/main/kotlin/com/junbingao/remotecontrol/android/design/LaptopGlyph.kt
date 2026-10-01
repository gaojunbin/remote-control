package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * The device row's mark: a minimal outline laptop, the same drawing the web app gets from
 * lucide's `LaptopMinimal` (`docs/DESIGN.md` § "The device row"). A rounded rectangle for the
 * screen over one horizontal line for the base, no fill, round caps and joins.
 */
object LaptopShape {
    /** The screen and the base on `OutlineGlyph.grid`, scaled to the rect they are drawn in. */
    val screen = Rect(3f, 4f, 21f, 16f)
    const val screenCorner = 2f
    const val baseY = 20f
    val baseX = 2f..22f

    /**
     * Where the base line sits in the glyph's box. The row puts it on the title's first baseline,
     * as the foot of a letter would: `Modifier.alignBy { (it.measuredHeight * baselineFraction).roundToInt() }`
     * beside a title aligned with `alignByBaseline()`.
     */
    const val baselineFraction = baseY / OutlineGlyph.grid

    fun path(rect: Rect): Path {
        val unit = min(rect.width, rect.height) / OutlineGlyph.grid
        val path = Path()
        path.addRoundRect(
            RoundRect(
                Rect(
                    rect.left + screen.left * unit, rect.top + screen.top * unit,
                    rect.left + screen.right * unit, rect.top + screen.bottom * unit,
                ),
                CornerRadius(screenCorner * unit),
            ),
        )
        path.moveTo(rect.left + baseX.start * unit, rect.top + baseY * unit)
        path.lineTo(rect.left + baseX.endInclusive * unit, rect.top + baseY * unit)
        return path
    }
}

/**
 * `LaptopShape` stroked in the ink at the size of a row title, 20 beside a 16 callout title and
 * following the font size with it; `LaptopShape.baselineFraction` says where its base line is.
 */
@Composable
fun LaptopGlyph(modifier: Modifier = Modifier) {
    val side = scaledMetric(20.dp, SystemFont.callout)
    val ink = Theme.ink
    Canvas(modifier.size(side)) {
        drawPath(LaptopShape.path(Rect(Offset.Zero, size)), ink, style = OutlineGlyph.stroke(size.minDimension))
    }
}
