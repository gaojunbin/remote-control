package com.junbingao.remotecontrol.android.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Core Graphics' `addArc(tangent1End:tangent2End:radius:)`: a line from [from] towards [corner],
 * then an arc of [radius] tangent to both the line in and the line out towards [next]. Returns
 * where the arc ends, which is where the next corner's line starts. A straight corner (the lines
 * in and out continue each other) is a plain line to it.
 */
fun Path.arcToTangents(from: Offset, corner: Offset, next: Offset, radius: Float): Offset {
    val inward = normalized(from - corner)
    val outward = normalized(next - corner)
    val cosine = (inward.x * outward.x + inward.y * outward.y).coerceIn(-1f, 1f)
    val angle = acos(cosine)
    if (radius <= 0f || angle < 1e-4f || abs(angle - Math.PI.toFloat()) < 1e-4f) {
        lineTo(corner.x, corner.y)
        return corner
    }
    val reach = radius / tan(angle / 2)
    val start = corner + inward * reach
    val end = corner + outward * reach
    val bisector = normalized(inward + outward)
    val center = corner + bisector * (radius / sin(angle / 2))
    lineTo(start.x, start.y)
    val startAngle = Math.toDegrees(atan2((start.y - center.y).toDouble(), (start.x - center.x).toDouble()))
    var sweep = Math.toDegrees(atan2((end.y - center.y).toDouble(), (end.x - center.x).toDouble())) - startAngle
    while (sweep > 180) sweep -= 360
    while (sweep < -180) sweep += 360
    arcTo(
        Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
        startAngle.toFloat(), sweep.toFloat(), forceMoveTo = false,
    )
    return end
}

private fun normalized(v: Offset): Offset {
    val length = sqrt(v.x * v.x + v.y * v.y)
    return if (length == 0f) v else Offset(v.x / length, v.y / length)
}
