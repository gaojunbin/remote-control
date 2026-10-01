package com.junbingao.remotecontrol.android.icons

/** How one layer of a symbol is laid on: in the ink, or cut out of what is under it. */
enum class LayerPaint {
    /** Lines drawn at the symbol's weight, as every lucide glyph is. */
    stroke,

    /** A closed outline filled, for the `.fill` variants SF Symbols carries. */
    fill,

    /** Filled and then outlined, so a filled shape keeps its outline's size. */
    fillAndStroke,

    /** Lines cut out of the layers below: the white check in a filled circle. */
    cutStroke,

    /** A shape cut out of the layers below: the hole in a filled gear. */
    cutFill,
}

/**
 * One layer of a symbol: some paths on lucide's 24-unit grid, painted one way. [alpha] is the
 * symbol's own secondary layer (the grey screen of a filled desktop), and [weight] scales the
 * stroke for a part SF draws heavier or lighter than the rest.
 */
class SymbolLayer(
    val paths: List<String>,
    val paint: LayerPaint,
    val alpha: Float = 1f,
    val weight: Float = 1f,
)

/**
 * One SF Symbol the iPhone draws, as the nearest lucide glyph (`docs/DESIGN.md` § "The Android
 * app"). [name] is the iPhone's own spelling, so a screen that is handed a symbol by name — a row
 * action, an empty state — finds it with [Sf.named]. [scale] corrects lucide's padded 24-unit box
 * towards the size the symbol has on the iPhone at the same point size, measured from the
 * reference screenshots where the symbol appears in them. [aspect] is the symbol's frame, as wide
 * as that much of its height: SF's frame hugs a narrow symbol's ink, so a chevron at the trailing
 * edge of a row stands where the iPhone's does rather than inside a square of padding.
 */
class SfSymbol(
    val name: String,
    val layers: List<SymbolLayer>,
    val scale: Float = 1f,
    val aspect: Float = 1f,
) {
    override fun toString(): String = "SfSymbol($name)"
}

internal fun stroke(paths: List<String>, weight: Float = 1f) =
    SymbolLayer(paths, LayerPaint.stroke, weight = weight)

internal fun fill(paths: List<String>, alpha: Float = 1f) = SymbolLayer(paths, LayerPaint.fill, alpha = alpha)

internal fun fillAndStroke(paths: List<String>, weight: Float = 1f) = SymbolLayer(paths, LayerPaint.fillAndStroke, weight = weight)

internal fun cutStroke(paths: List<String>, weight: Float = 1f) =
    SymbolLayer(paths, LayerPaint.cutStroke, weight = weight)

internal fun cutFill(paths: List<String>) = SymbolLayer(paths, LayerPaint.cutFill)
