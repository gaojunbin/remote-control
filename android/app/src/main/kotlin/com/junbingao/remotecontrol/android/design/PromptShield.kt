package com.junbingao.remotecontrol.android.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.LayerPaint
import com.junbingao.remotecontrol.android.icons.Lucide
import com.junbingao.remotecontrol.android.icons.SfSymbol
import com.junbingao.remotecontrol.android.icons.SymbolLayer

/**
 * Amendment A44: the permission mode's glyph on the phone, a shield with a prompt, `>_`, inside
 * it (`docs/DESIGN.md` § "The control row").
 *
 * The shield is the symbol the iPhone uses, `shield` — here lucide's — so its weight and size
 * follow the font like the glyphs beside it, and the prompt is drawn into it at the shield's own
 * stroke, in whatever the foreground is.
 */
@Composable
fun PromptShield(modifier: Modifier = Modifier, font: TextStyle = LocalFont.current, tint: Color = Color.Unspecified) {
    Icon(PromptShape.symbol, modifier, font = font, tint = tint)
}

/**
 * `>` and `_` in the shield's frame. The fractions are the iPhone's, measured from SF's shield
 * drawn at 200 points: its inside runs from 19 % to 81 % across the middle and from 15 % down to
 * the point at 85 %, widest in the upper half — which is where the prompt sits, centred across
 * it, the way a terminal draws one. The frame here is lucide's shield with its stroke, so the
 * same fractions land in the same place inside it.
 */
object PromptShape {
    const val lineWidth = 0.07f

    /** The chevron's two ends and its point, then the underscore's two ends. */
    val chevron = listOf(Offset(0.335f, 0.375f), Offset(0.465f, 0.47f), Offset(0.335f, 0.565f))
    val underscore = listOf(Offset(0.54f, 0.565f), Offset(0.675f, 0.565f))

    /** lucide's shield, outer edge of its stroke included, on the 24-unit grid. */
    private val frameLeft = 3f
    private val frameTop = 1.28f
    private val frameWidth = 18f
    private val frameHeight = 21.67f

    private fun place(point: Offset): String =
        "${frameLeft + point.x * frameWidth} ${frameTop + point.y * frameHeight}"

    /** The prompt as path data on the grid, in the shield's frame. */
    val paths: List<String> = listOf(
        "M${place(chevron[0])}L${place(chevron[1])}L${place(chevron[2])}",
        "M${place(underscore[0])}L${place(underscore[1])}",
    )

    /** The shield and its prompt, one symbol, so they share a size, a weight and a colour. */
    val symbol = SfSymbol(
        name = "shield.prompt",
        layers = listOf(
            SymbolLayer(Lucide.shield, LayerPaint.stroke),
            SymbolLayer(paths, LayerPaint.stroke),
        ),
        scale = 0.97f,
    )
}
