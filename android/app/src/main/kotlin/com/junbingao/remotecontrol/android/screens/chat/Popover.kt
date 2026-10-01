package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.continuousPath
import com.junbingao.remotecontrol.android.system.Glass
import com.junbingao.remotecontrol.android.system.PresentationKind
import com.junbingao.remotecontrol.android.system.PresentationOptions
import com.junbingao.remotecontrol.android.system.Present
import com.junbingao.remotecontrol.android.system.glassPanel
import com.junbingao.remotecontrol.android.system.safeArea

/** Which side of its source a popover stands on: above it with the arrow pointing down, or below. */
enum class PopoverEdge { above, below }

/**
 * `.popover(isPresented:arrowEdge:)` with `.presentationCompactAdaptation(.popover)`, as the
 * iPhone draws it: a glass card beside the control that opened it, an arrow pointing at that
 * control, and no dimming. A tap outside, Back or the control itself closes it.
 *
 * It is presented in the window's own presentation stack, as the app's menus are, so a picture of
 * the screen carries it and Back reaches it first; the card stands where [PopoverPlacement] says,
 * which a menu's covering rule then keeps exactly, because the rect it is handed is the card's own.
 */
@Composable
fun Popover(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    anchor: Rect?,
    edge: PopoverEdge = PopoverEdge.above,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val safe = safeArea()
    var card by remember { mutableStateOf(IntSize.Zero) }
    var placed by remember { mutableStateOf<Rect?>(null) }
    val target = anchor?.let { source ->
        with(density) {
            PopoverPlacement.place(
                source = source,
                card = Size(card.width.toFloat(), card.height.toFloat()),
                screen = Size(view.width.toFloat(), view.height.toFloat()),
                top = (safe.top + PopoverMetrics.margin).toPx(),
                bottom = view.height - (safe.bottom + PopoverMetrics.margin).toPx(),
                margin = PopoverMetrics.margin.toPx(),
                edge = edge,
            )
        }
    }
    Present(
        PresentationKind.menu,
        isPresented = isPresented,
        onDismiss = onDismiss,
        options = PresentationOptions(anchor = target?.rect, dismissible = true, dimmed = false),
    ) {
        val arrowX = anchor?.let { source -> placed?.let { source.center.x - it.left } }
        val pointsUp = target?.edge == PopoverEdge.below
        val shape = remember(arrowX, pointsUp) { PopoverShape(arrowX, pointsUp) }
        Box(
            Modifier
                .onGloballyPositioned {
                    card = it.size
                    placed = it.boundsInRoot()
                }
                // The iPhone's popover glass blurs what is under it out of reading; with no blur
                // to draw, the card is the glass's colour and lets nothing through.
                .glassPanel(shape, fill = Glass.panel.copy(alpha = 1f))
                .padding(top = if (pointsUp) PopoverMetrics.arrowHeight else 0.dp, bottom = if (pointsUp) 0.dp else PopoverMetrics.arrowHeight),
        ) { content() }
    }
}

/** Where a popover's card goes, arrow included. */
internal object PopoverPlacement {
    data class Placement(val rect: Rect, val edge: PopoverEdge)

    /**
     * Centred on its source and kept [margin] from the screen's sides; on [edge] when the card
     * fits there between [top] and [bottom], on the other side when it does not.
     */
    fun place(source: Rect, card: Size, screen: Size, top: Float, bottom: Float, margin: Float, edge: PopoverEdge): Placement {
        val left = (source.center.x - card.width / 2).coerceIn(margin, (screen.width - margin - card.width).coerceAtLeast(margin))
        val above = source.top - card.height
        val below = source.bottom
        val fitsAbove = above >= top
        val fitsBelow = below + card.height <= bottom
        val chosen = when (edge) {
            PopoverEdge.above -> if (fitsAbove || !fitsBelow) PopoverEdge.above else PopoverEdge.below
            PopoverEdge.below -> if (fitsBelow || !fitsAbove) PopoverEdge.below else PopoverEdge.above
        }
        val y = if (chosen == PopoverEdge.above) above else below
        return Placement(Rect(left, y, left + card.width, y + card.height), chosen)
    }
}

/** The popover's measurements, from the iPhone 17 reference screenshots (`44-model-card`). */
object PopoverMetrics {
    val corner = 24.dp
    val arrowHeight = 13.dp
    val arrowHalfWidth = 14.dp

    /** The nearest the card comes to the screen's edges. */
    val margin = 8.dp
}

/** The card's continuous rectangle and the arrow out of the edge that faces the source. */
private class PopoverShape(private val arrowX: Float?, private val pointsUp: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val arrow = with(density) { PopoverMetrics.arrowHeight.toPx() }
        val half = with(density) { PopoverMetrics.arrowHalfWidth.toPx() }
        val corner = with(density) { PopoverMetrics.corner.toPx() }
        val body = if (pointsUp) Rect(0f, arrow, size.width, size.height) else Rect(0f, 0f, size.width, size.height - arrow)
        val card = continuousPath(body, corner)
        val x = arrowX?.coerceIn(corner + half, size.width - corner - half) ?: return Outline.Generic(card)
        val edge = if (pointsUp) body.top else body.bottom
        val tip = if (pointsUp) edge - arrow else edge + arrow
        val inward = if (pointsUp) 1f else -1f
        val point = Path().apply {
            moveTo(x - half, edge + inward)
            quadraticTo(x - half * 0.42f, edge - inward * arrow * 0.15f, x - half * 0.14f, tip + inward * arrow * 0.16f)
            quadraticTo(x, tip - inward * 0.6f, x + half * 0.14f, tip + inward * arrow * 0.16f)
            quadraticTo(x + half * 0.42f, edge - inward * arrow * 0.15f, x + half, edge + inward)
            close()
        }
        return Outline.Generic(Path.combine(PathOperation.Union, card, point))
    }

    override fun equals(other: Any?): Boolean = other is PopoverShape && other.arrowX == arrowX && other.pointsUp == pointsUp

    override fun hashCode(): Int = (arrowX?.hashCode() ?: 0) * 31 + pointsUp.hashCode()
}
