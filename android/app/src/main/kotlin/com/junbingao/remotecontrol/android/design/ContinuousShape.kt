package com.junbingao.remotecontrol.android.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * `RoundedRectangle(cornerRadius:style: .continuous)`: Apple's squircle corner, whose curvature
 * eases in along the edge instead of starting at a tangent point, as every card, sheet and cell on
 * the iPhone is drawn. A circular corner of the same radius reads visibly sharper beside it.
 *
 * The curve is the published reconstruction of UIKit's: each corner starts 1.528 radii from the
 * corner along both edges and is three cubic segments. Where the side is too short for that,
 * UIKit gives up the easing and draws a plain circular corner of at most half the side, which is
 * what a capsule is; so does this.
 */
class ContinuousShape(private val radius: Dp, private val corners: Corners = Corners.all) : Shape {
    /** Which corners are rounded: a list's first row rounds its top, its last row its foot. */
    enum class Corners { all, top, bottom, none }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val bounds = Rect(Offset.Zero, size)
        return when (corners) {
            Corners.all -> Outline.Generic(continuousPath(bounds, r))
            Corners.none -> Outline.Rectangle(bounds)
            // The corners that stay square are pushed past the edge and cut off by the bounds.
            Corners.top -> Outline.Generic(clipped(continuousPath(Rect(0f, 0f, size.width, size.height + r * 2), r), bounds))
            Corners.bottom -> Outline.Generic(clipped(continuousPath(Rect(0f, -r * 2, size.width, size.height), r), bounds))
        }
    }

    private fun clipped(path: Path, bounds: Rect): Path {
        val box = Path().apply { addRect(bounds) }
        return Path().apply { op(path, box, androidx.compose.ui.graphics.PathOperation.Intersect) }
    }

    override fun equals(other: Any?): Boolean =
        other is ContinuousShape && other.radius == radius && other.corners == corners

    override fun hashCode(): Int = radius.hashCode() * 31 + corners.hashCode()
}

/** `Capsule()`: ends that are half circles, whatever the length. */
val CapsuleShape = ContinuousShape(9999.dp)

/** The continuous rounded rectangle's outline in [rect], corners of radius [radius]. */
fun continuousPath(rect: Rect, radius: Float): Path {
    val path = Path()
    val shortest = min(rect.width, rect.height)
    val limit = shortest / 2
    if (radius <= 0f) {
        path.addRect(rect)
        return path
    }
    if (radius * EXTENT > limit) {
        val circular = min(radius, limit)
        path.addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                rect, androidx.compose.ui.geometry.CornerRadius(circular, circular),
            ),
        )
        return path
    }
    val l = rect.left
    val t = rect.top
    val r = rect.right
    val b = rect.bottom
    val k = radius
    path.moveTo(l + EXTENT * k, t)
    // Top edge, then the top-right corner.
    path.lineTo(r - EXTENT * k, t)
    path.cubicTo(r - C1 * k, t, r - C2 * k, t, r - P1X * k, t + P1Y * k)
    path.cubicTo(r - C3 * k, t + C4 * k, r - C4 * k, t + C3 * k, r - P1Y * k, t + P1X * k)
    path.cubicTo(r, t + C2 * k, r, t + C1 * k, r, t + EXTENT * k)
    // Right edge, then the bottom-right corner.
    path.lineTo(r, b - EXTENT * k)
    path.cubicTo(r, b - C1 * k, r, b - C2 * k, r - P1Y * k, b - P1X * k)
    path.cubicTo(r - C4 * k, b - C3 * k, r - C3 * k, b - C4 * k, r - P1X * k, b - P1Y * k)
    path.cubicTo(r - C2 * k, b, r - C1 * k, b, r - EXTENT * k, b)
    // Bottom edge, then the bottom-left corner.
    path.lineTo(l + EXTENT * k, b)
    path.cubicTo(l + C1 * k, b, l + C2 * k, b, l + P1X * k, b - P1Y * k)
    path.cubicTo(l + C3 * k, b - C4 * k, l + C4 * k, b - C3 * k, l + P1Y * k, b - P1X * k)
    path.cubicTo(l, b - C2 * k, l, b - C1 * k, l, b - EXTENT * k)
    // Left edge, then the top-left corner.
    path.lineTo(l, t + EXTENT * k)
    path.cubicTo(l, t + C1 * k, l, t + C2 * k, l + P1Y * k, t + P1X * k)
    path.cubicTo(l + C4 * k, t + C3 * k, l + C3 * k, t + C4 * k, l + P1X * k, t + P1Y * k)
    path.cubicTo(l + C2 * k, t, l + C1 * k, t, l + EXTENT * k, t)
    path.close()
    return path
}

// UIKit's continuous corner, in radii from the corner (the reconstruction PaintCode published).
private const val EXTENT = 1.52866483f
private const val C1 = 1.08849323f
private const val C2 = 0.86840689f
private const val P1X = 0.63149399f
private const val P1Y = 0.07491100f
private const val C3 = 0.37282392f
private const val C4 = 0.16905899f
