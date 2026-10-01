package com.junbingao.remotecontrol.win.design.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.LocalContentColor
import java.util.concurrent.ConcurrentHashMap

/** One element of an icon, on lucide's 24-unit grid. */
sealed interface IconNode {
    data class Path(val data: String) : IconNode
    data class Circle(val cx: Float, val cy: Float, val r: Float) : IconNode
    data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float) : IconNode
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float, val rx: Float, val ry: Float) : IconNode
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : IconNode
    data class Polyline(val points: String) : IconNode
    data class Polygon(val points: String) : IconNode

    /** The element as path nodes; ellipses and rounded corners are the quarter curves CoreGraphics draws. */
    val pathNodes: List<PathNode>
        get() = when (this) {
            is Path -> SVGPath.parse(data)
            is Circle -> ellipse(cx, cy, r, r)
            is Ellipse -> ellipse(cx, cy, rx, ry)
            is Rect -> roundedRect(x, y, width, height, rx, ry)
            is Line -> listOf(PathNode.MoveTo(x1, y1), PathNode.LineTo(x2, y2))
            is Polyline -> lines(points, close = false)
            is Polygon -> lines(points, close = true)
        }
}

/** The circle constant of a quarter turn drawn as one cubic curve. */
private const val KAPPA = 0.5522847498f

private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): List<PathNode> {
    val kx = rx * KAPPA
    val ky = ry * KAPPA
    return listOf(
        PathNode.MoveTo(cx + rx, cy),
        PathNode.CurveTo(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry),
        PathNode.CurveTo(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy),
        PathNode.CurveTo(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry),
        PathNode.CurveTo(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy),
        PathNode.Close,
    )
}

private fun roundedRect(x: Float, y: Float, width: Float, height: Float, radiusX: Float, radiusY: Float): List<PathNode> {
    if (radiusX <= 0 && radiusY <= 0) {
        return listOf(
            PathNode.MoveTo(x, y), PathNode.LineTo(x + width, y), PathNode.LineTo(x + width, y + height),
            PathNode.LineTo(x, y + height), PathNode.Close,
        )
    }
    val rx = minOf(radiusX, width / 2)
    val ry = minOf(radiusY, height / 2)
    val kx = rx * KAPPA
    val ky = ry * KAPPA
    val right = x + width
    val bottom = y + height
    return listOf(
        PathNode.MoveTo(x + rx, y),
        PathNode.LineTo(right - rx, y),
        PathNode.CurveTo(right - rx + kx, y, right, y + ry - ky, right, y + ry),
        PathNode.LineTo(right, bottom - ry),
        PathNode.CurveTo(right, bottom - ry + ky, right - rx + kx, bottom, right - rx, bottom),
        PathNode.LineTo(x + rx, bottom),
        PathNode.CurveTo(x + rx - kx, bottom, x, bottom - ry + ky, x, bottom - ry),
        PathNode.LineTo(x, y + ry),
        PathNode.CurveTo(x, y + ry - ky, x + rx - kx, y, x + rx, y),
        PathNode.Close,
    )
}

private fun lines(points: String, close: Boolean): List<PathNode> {
    val offsets = SVGPath.points(points)
    if (offsets.isEmpty()) return emptyList()
    val nodes = mutableListOf<PathNode>(PathNode.MoveTo(offsets[0].x, offsets[0].y))
    for (point in offsets.drop(1)) nodes += PathNode.LineTo(point.x, point.y)
    if (close) nodes += PathNode.Close
    return nodes
}

private val vectors = ConcurrentHashMap<Pair<LucideIcon, Float>, ImageVector>()

/**
 * The icon as a Compose vector on lucide's 24-unit grid: every element one path, stroked 2 grid
 * units wide with round caps and joins and no fill, so it scales with the icon as lucide's default
 * does (not `absoluteStrokeWidth`). Made once per stroke width and kept.
 */
fun LucideIcon.imageVector(strokeWidth: Float = 2f): ImageVector = vectors.getOrPut(this to strokeWidth) {
    ImageVector.Builder(name = id, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = nodes.flatMap { it.pathNodes },
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}

/**
 * A lucide icon as lucide-react draws it: a `size` square, the 24-unit grid scaled into it, in
 * whatever ink its surroundings set — lucide's `currentColor`.
 */
@Composable
fun Icon(icon: LucideIcon, size: Dp = 24.dp, strokeWidth: Float = 2f, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val ink = if (color != Color.Unspecified) color else LocalContentColor.current
    Image(
        painter = rememberVectorPainter(icon.imageVector(strokeWidth)),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(ink),
    )
}
