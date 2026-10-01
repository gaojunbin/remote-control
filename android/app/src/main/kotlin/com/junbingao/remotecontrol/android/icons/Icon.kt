package com.junbingao.remotecontrol.android.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.junbingao.remotecontrol.android.design.LocalFont
import com.junbingao.remotecontrol.android.design.LocalForeground
import com.junbingao.remotecontrol.android.design.SystemColor

/**
 * `Image(systemName:)`: a symbol set in a font, as SwiftUI sets one — as large as the text it
 * stands beside and as heavy as that text's weight, in the foreground colour above it unless it
 * is given one.
 *
 * [font] is the font the iPhone gives the symbol (`.font(.footnote.weight(.semibold))`); the
 * nearest [LocalFont] when it gives none. Nothing is read aloud unless [contentDescription] says
 * what the symbol means, as an image the iPhone marks `accessibilityHidden` reads nothing.
 */
@Composable
fun Icon(
    symbol: SfSymbol,
    modifier: Modifier = Modifier,
    font: TextStyle = LocalFont.current,
    tint: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    val points = if (font.fontSize.isSpecified) font.fontSize else 17.sp
    val side = with(LocalDensity.current) { points.toDp() } * (SfMetrics.boxPerPoint * symbol.scale)
    Icon(symbol, side, font.fontWeight ?: FontWeight.Normal, modifier, tint, contentDescription)
}

/**
 * A symbol in a box of its own [side] — a tab bar item, a swipe action — for the places the
 * iPhone sizes the symbol by the control rather than by the text beside it.
 */
@Composable
fun Icon(
    symbol: SfSymbol,
    side: Dp,
    weight: FontWeight,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    val inherited = LocalForeground.current
    val color = when {
        tint != Color.Unspecified -> tint
        inherited != Color.Unspecified -> inherited
        else -> SystemColor.label
    }
    val units = SfMetrics.strokeUnits(weight)
    val layers = SymbolPaths.of(symbol)
    val described = if (contentDescription == null) Modifier else Modifier.semantics {
        this.contentDescription = contentDescription
        role = Role.Image
    }
    Canvas(
        modifier
            .size(side)
            // An offscreen layer, so a cut-out clears the symbol's own ink and not the screen.
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .then(described),
    ) {
        scale(size.minDimension / SfMetrics.GRID, pivot = Offset.Zero) {
            symbol.layers.forEachIndexed { index, layer -> draw(layer, layers[index], color, units) }
        }
    }
}

private fun DrawScope.draw(layer: SymbolLayer, paths: List<Path>, color: Color, units: Float) {
    val ink = color.copy(alpha = color.alpha * layer.alpha)
    val line = Stroke(width = units * layer.weight, cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (path in paths) when (layer.paint) {
        LayerPaint.stroke -> drawPath(path, ink, style = line)
        LayerPaint.fill -> drawPath(path, ink, style = Fill)
        LayerPaint.fillAndStroke -> {
            drawPath(path, ink, style = Fill)
            drawPath(path, ink, style = line)
        }
        LayerPaint.cutStroke -> drawPath(path, Color.Black, style = line, blendMode = BlendMode.Clear)
        LayerPaint.cutFill -> drawPath(path, Color.Black, style = Fill, blendMode = BlendMode.Clear)
    }
}

/** How a lucide glyph is set to stand in for an SF Symbol of the same point size. */
object SfMetrics {
    const val GRID = 24f

    /**
     * The glyph's box per point of the font it is set in. lucide pads its 24-unit box, so the
     * box is larger than the point size; 1.13 makes the common glyphs (the microphone, the
     * shield) as tall as the iPhone's at body size, and each symbol's own scale corrects the rest.
     */
    const val boxPerPoint = 1.13f

    /**
     * The stroke, in grid units, for a font weight. SF Symbols' regular weight at body size is
     * about 1.4 points thick, which is 1.75 units at the box above; each weight step adds a
     * quarter of a unit, as the symbols thicken with the text.
     */
    fun strokeUnits(weight: FontWeight): Float = 1.75f + (weight.weight - 400) / 100f * 0.25f

    /** A text size the box follows, for a caller that sizes a symbol by a font it holds. */
    fun box(points: TextUnit, scale: Float = 1f): Float = points.value * boxPerPoint * scale
}

/** Each symbol's paths parsed once, on the grid, for every frame that draws it. */
private object SymbolPaths {
    private val parsed = HashMap<SfSymbol, List<List<Path>>>()

    fun of(symbol: SfSymbol): List<List<Path>> = parsed.getOrPut(symbol) {
        symbol.layers.map { layer -> layer.paths.map { PathParser().parsePathString(it).toPath() } }
    }
}

/**
 * Where the symbol's ink runs across lucide's grid at a stroke of [units]: its paths' extent,
 * widened by half a stroke where a layer is stroked. A cut takes nothing away from the extent.
 * For laying words beside the ink, as SwiftUI lays them beside an SF Symbol, which has no
 * padding of its own, rather than beside lucide's padded box.
 */
internal fun SfSymbol.inkSpan(units: Float): ClosedFloatingPointRange<Float> {
    var left = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    SymbolPaths.of(this).forEachIndexed { index, paths ->
        val layer = layers[index]
        if (layer.paint == LayerPaint.cutStroke || layer.paint == LayerPaint.cutFill) return@forEachIndexed
        val reach = if (layer.paint == LayerPaint.fill) 0f else units * layer.weight / 2
        for (path in paths) {
            val bounds = path.getBounds()
            left = minOf(left, bounds.left - reach)
            right = maxOf(right, bounds.right + reach)
        }
    }
    return if (left > right) 0f..SfMetrics.GRID else left..right
}
