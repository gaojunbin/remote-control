package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Amendment A44: the model card's glyph on the phone, a gauge after ChatGPT's effort icon
 * (`docs/DESIGN.md` § "The control row").
 *
 * An open arc from lower left over the top to lower right, on a track in the line colour, filled
 * in green up to a needle whose position is the session's effort: the agent's lowest level at the
 * left end, its highest at the right, the others evenly between (`AgentInfo.effortPosition`, the
 * core's). With no position — no levels, or a value the agent does not list — the needle stands
 * upright and nothing is filled. A faster tier adds a small bolt at the corner (A21). Drawn from
 * shapes rather than an image, so it keeps its weight at every text size.
 *
 * The hub is a filled disc where the reference draws a ring: at this size a ring with the needle
 * at either end of the arc reads as a magnifying glass.
 */
@Composable
fun EffortGauge(position: Double?, isFast: Boolean, modifier: Modifier = Modifier) {
    // About the height of the symbols beside it at the body size, and growing with them.
    val side = scaledMetric(21.dp, SystemFont.body)
    val track = Theme.border
    val fill = Theme.running
    val ink = Theme.ink
    Box(modifier.size(side).clearAndSetSemantics {}) {
        Canvas(
            Modifier
                .size(side)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            val rect = Rect(Offset.Zero, size)
            val line = GaugeGeometry.stroke(size.minDimension)
            drawPath(GaugeGeometry.arc(rect, to = 1.0), track, style = line)
            if (position != null && position > 0) drawPath(GaugeGeometry.arc(rect, to = position), fill, style = line)
            drawPath(GaugeGeometry.needle(rect, EffortGauge.needleAngle(position)), ink, style = line)
            drawPath(GaugeGeometry.hub(rect), ink)
            // The bolt sits off the lower-right corner with a clear ring cut out of the arc under
            // it, as a symbol's own badge has, so it never runs into the arc's end or a needle
            // pointing there.
            if (isFast) {
                val spot = GaugeGeometry.boltSpot(size.minDimension)
                drawCircle(Color.Black, spot.width / 2, spot.center, blendMode = BlendMode.Clear)
            }
        }
        if (isFast) {
            val spot = GaugeGeometry.boltSpot(side.value)
            Box(
                Modifier
                    .offset(spot.left.dp, spot.top.dp)
                    .size(spot.width.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Sf.boltFill, font = SystemFont.system(side.value * 0.42f, FontWeight.SemiBold), tint = ink)
            }
        }
    }
}

object EffortGauge {
    /** Where the needle points, in degrees: along the arc at a position, straight up where there is none. */
    fun needleAngle(position: Double?): Double = GaugeGeometry.angle(position ?: GaugeGeometry.upright)
}

/** The gauge's proportions, in fractions of its square. */
object GaugeGeometry {
    /** The arc runs clockwise from lower left, through the top, to lower right. */
    const val start = 135.0
    const val sweep = 270.0

    /** Halfway along the sweep is straight up. */
    const val upright = 0.5
    const val lineWidth = 0.09f

    /** The track's centre line, so its outer edge meets the square's sides. */
    const val radius = 0.5f - lineWidth / 2

    /**
     * Where the needle turns and the arc is centred. The arc is open at the bottom, so this sits
     * below the square's centre: the ink then has as much room above it as below.
     */
    val pivot = Offset(0.5f, (0.5 + radius * (1 - sin(PI / 4)) / 2).toFloat())

    /** A little wider than the needle, so the pivot reads as round. */
    const val hubRadius = 0.085f

    /** The needle runs from the pivot to about two thirds of the radius, clear of the arc. */
    const val needleEnd = 0.32f

    fun stroke(size: Float): Stroke =
        Stroke(width = size * lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun angle(position: Double): Double = start + sweep * position.coerceIn(0.0, 1.0)

    fun center(rect: Rect): Offset = point(0.0, 0f, rect)

    fun point(angle: Double, distance: Float, rect: Rect): Offset {
        val unit = min(rect.width, rect.height)
        val radians = angle * PI / 180
        return Offset(
            rect.left + (pivot.x + distance * cos(radians).toFloat()) * unit,
            rect.top + (pivot.y + distance * sin(radians).toFloat()) * unit,
        )
    }

    /** The arc from the gauge's left end to a position along it. */
    fun arc(rect: Rect, to: Double): Path {
        val unit = min(rect.width, rect.height)
        val center = center(rect)
        val r = radius * unit
        return Path().apply {
            arcTo(
                Rect(center.x - r, center.y - r, center.x + r, center.y + r),
                start.toFloat(), (angle(to) - start).toFloat(), forceMoveTo = true,
            )
        }
    }

    /** The needle, from the pivot out towards the arc. */
    fun needle(rect: Rect, angle: Double): Path = Path().apply {
        val from = center(rect)
        val to = point(angle, needleEnd, rect)
        moveTo(from.x, from.y)
        lineTo(to.x, to.y)
    }

    /** The round hub the needle turns on. */
    fun hub(rect: Rect): Path = Path().apply {
        val unit = min(rect.width, rect.height)
        val r = hubRadius * unit
        val c = center(rect)
        addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r))
    }

    /**
     * The bolt's place, beyond the lower-right corner: a square of 0.56 of the gauge whose
     * bottom-right corner is 0.3 of the gauge right of and 0.2 below the gauge's own.
     */
    fun boltSpot(size: Float): Rect {
        val side = size * 0.56f
        val right = size + size * 0.3f
        val bottom = size + size * 0.2f
        return Rect(right - side, bottom - side, right, bottom)
    }
}
