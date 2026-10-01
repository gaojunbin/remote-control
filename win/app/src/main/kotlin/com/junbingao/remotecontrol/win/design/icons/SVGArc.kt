package com.junbingao.remotecontrol.win.design.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.PathNode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The SVG elliptical arc (`A rx ry rotation large-arc sweep x y`), converted from the endpoint
 * form the path data uses to the centre form a curve can be drawn from (SVG 1.1, appendix F.6.5),
 * and drawn as cubic Béziers of at most a quarter turn each.
 */
internal object SVGArc {
    fun add(
        nodes: MutableList<PathNode>,
        p0: Offset,
        p1: Offset,
        radiusX: Float,
        radiusY: Float,
        degrees: Float,
        largeArc: Boolean,
        sweep: Boolean,
    ) {
        var rx = abs(radiusX.toDouble())
        var ry = abs(radiusY.toDouble())
        if (p0 == p1) return
        if (rx <= 0 || ry <= 0) {
            nodes += PathNode.LineTo(p1.x, p1.y)
            return
        }
        val phi = degrees * PI / 180
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        // Step 1: the start point in the ellipse's own frame, relative to the midpoint.
        val dx = (p0.x - p1.x) / 2.0
        val dy = (p0.y - p1.y) / 2.0
        val x1 = cosPhi * dx + sinPhi * dy
        val y1 = -sinPhi * dx + cosPhi * dy
        // Radii too small to reach are scaled up just enough (F.6.6).
        val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if (lambda > 1) {
            rx *= sqrt(lambda)
            ry *= sqrt(lambda)
        }
        // Step 2: the centre in that frame.
        val numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        val denominator = rx * rx * y1 * y1 + ry * ry * x1 * x1
        var factor = sqrt(max(0.0, numerator) / denominator)
        if (largeArc == sweep) factor = -factor
        val cx1 = factor * rx * y1 / ry
        val cy1 = -factor * ry * x1 / rx
        // Step 3: the centre in user space.
        val cx = cosPhi * cx1 - sinPhi * cy1 + (p0.x + p1.x) / 2.0
        val cy = sinPhi * cx1 + cosPhi * cy1 + (p0.y + p1.y) / 2.0
        // Step 4: the start angle and the sweep.
        val ux = (x1 - cx1) / rx
        val uy = (y1 - cy1) / ry
        val vx = (-x1 - cx1) / rx
        val vy = (-y1 - cy1) / ry
        val theta1 = angle(1.0, 0.0, ux, uy)
        var delta = angle(ux, uy, vx, vy)
        if (!sweep && delta > 0) delta -= 2 * PI
        if (sweep && delta < 0) delta += 2 * PI

        val segments = max(1, ceil(abs(delta) / (PI / 2)).toInt())
        val step = delta / segments
        val k = 4.0 / 3.0 * tan(step / 4)
        fun pointX(theta: Double) = cx + rx * cos(theta) * cosPhi - ry * sin(theta) * sinPhi
        fun pointY(theta: Double) = cy + rx * cos(theta) * sinPhi + ry * sin(theta) * cosPhi
        fun derivativeX(theta: Double) = -rx * sin(theta) * cosPhi - ry * cos(theta) * sinPhi
        fun derivativeY(theta: Double) = -rx * sin(theta) * sinPhi + ry * cos(theta) * cosPhi
        var theta = theta1
        for (index in 0 until segments) {
            val next = theta + step
            val ax = pointX(theta)
            val ay = pointY(theta)
            val bx = pointX(next)
            val by = pointY(next)
            val last = index == segments - 1
            nodes += PathNode.CurveTo(
                (ax + k * derivativeX(theta)).toFloat(), (ay + k * derivativeY(theta)).toFloat(),
                (bx - k * derivativeX(next)).toFloat(), (by - k * derivativeY(next)).toFloat(),
                if (last) p1.x else bx.toFloat(), if (last) p1.y else by.toFloat(),
            )
            theta = next
        }
    }

    private fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val dot = ux * vx + uy * vy
        val length = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
        var value = acos(max(-1.0, min(1.0, dot / length)))
        if (ux * vy - uy * vx < 0) value = -value
        return value
    }
}
