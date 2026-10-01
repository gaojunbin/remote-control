package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.modifier.ModifierLocalModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Text on the browser's baselines: the first baseline `baseline` below the top of the first
 * line's box, the last one as far below the top of the last, and the block ending one box below
 * its last line's top. Compose reports a text's baselines in whole pixels, rounded as Skia rounds
 * the glyphs it draws, so the text is moved by whole pixels and its glyphs land where asked.
 *
 * The box is as wide as the text, in whole device pixels, as SwiftUI measures the Mac's. Given a
 * fixed height — a button's, a pill's, with `fillMaxHeight` — the line centres itself in it. The
 * line is then drawn where the Mac's would be: SwiftUI puts a line box on a whole point (with
 * `snapToPoint`, as the browser puts one its container centres: 4.25 px down is drawn at 4) or a
 * whole pixel from its exact place, which the stack around it knows (`StackFrame`). A text left
 * to its own height reports the fraction of a pixel its box was rounded by (`exact`).
 */
internal fun Modifier.cssLineBox(lineBox: Float, baseline: Float, snapToPoint: Boolean, exact: ExactHeight): Modifier =
    this then CSSLineBoxElement(lineBox, baseline, snapToPoint, exact)

private data class CSSLineBoxElement(
    val lineBox: Float,
    val baseline: Float,
    val snapToPoint: Boolean,
    val exact: ExactHeight,
) : ModifierNodeElement<CSSLineBoxNode>() {
    override fun create() = CSSLineBoxNode(this)

    override fun update(node: CSSLineBoxNode) {
        node.element = this
    }
}

private class CSSLineBoxNode(var element: CSSLineBoxElement) : Modifier.Node(), LayoutModifierNode, ModifierLocalModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val first = placeable[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: 0
        val last = placeable[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: first
        val unit = density
        val boxPx = element.lineBox * unit
        val baselinePx = (element.baseline * unit).roundToInt()
        val contentPx = max(boxPx, last - first + boxPx)
        val centred = constraints.hasFixedHeight && constraints.maxHeight > contentPx
        val height = if (centred) constraints.maxHeight else constraints.constrainHeight(contentPx.roundToInt())
        element.exact.fraction = if (!centred && height == contentPx.roundToInt()) contentPx - height else 0f
        // The line's place in its box, as drawn, in whole pixels of the grid it rounds to.
        val grid = if (element.snapToPoint) unit else 1f
        val inset = if (centred) (height - contentPx) / 2 else 0f
        val estimate = (roundHalfUp(inset / grid) * grid).roundToInt()
        return layout(placeable.width, height, mapOf(FirstBaseline to estimate + baselinePx, LastBaseline to estimate + baselinePx + (last - first))) {
            val node = coordinates
            val top = if (node == null) {
                estimate
            } else {
                val frame = ModifierLocalStackFrame.current
                val boxTop = frame?.lineTop(node) ?: node.positionInRoot().y
                val exactTop = boxTop + (frame?.offsetOf(node) ?: 0f)
                (roundHalfUp((exactTop + inset) / grid) * grid - boxTop).roundToInt()
            }
            placeable.place(0, top + baselinePx - first)
        }
    }
}
