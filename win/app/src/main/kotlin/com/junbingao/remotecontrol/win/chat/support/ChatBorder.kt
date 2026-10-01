package com.junbingao.remotecontrol.win.chat.support

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A CSS border as the browser paints it: the band between the rounded border box and the padding
 * box, whose corners are the border radius less the width of the side each one meets. With a
 * width on only some sides — the tool row's top and bottom rules under a 12-point radius — the
 * band tapers to nothing along each corner, as Chrome draws it.
 */
data class ChatBorder(
    val top: Dp = 0.dp,
    val leading: Dp = 0.dp,
    val bottom: Dp = 0.dp,
    val trailing: Dp = 0.dp,
    val radius: Dp = 0.dp,
) {
    /** The same width on every side. */
    constructor(width: Dp, radius: Dp) : this(top = width, leading = width, bottom = width, trailing = width, radius = radius)

    /** The band in a box of `size` pixels, drawn with the even-odd rule so the inner box is a hole. */
    fun path(size: Size, density: Density): Path = with(density) {
        val radius = radius.toPx()
        val top = top.toPx()
        val leading = leading.toPx()
        val bottom = bottom.toPx()
        val trailing = trailing.toPx()
        val path = Path().apply { fillType = PathFillType.EvenOdd }
        val outer = Rect(0f, 0f, size.width, size.height)
        rounded(path, outer, CornerRadii(uniform = radius))
        val inner = Rect(
            leading,
            top,
            leading + max(0f, size.width - leading - trailing),
            top + max(0f, size.height - top - bottom),
        )
        val radii = CornerRadii(
            topLeading = Size(max(0f, radius - leading), max(0f, radius - top)),
            topTrailing = Size(max(0f, radius - trailing), max(0f, radius - top)),
            bottomLeading = Size(max(0f, radius - leading), max(0f, radius - bottom)),
            bottomTrailing = Size(max(0f, radius - trailing), max(0f, radius - bottom)),
        )
        rounded(path, inner, radii)
        path
    }

    data class CornerRadii(val topLeading: Size, val topTrailing: Size, val bottomLeading: Size, val bottomTrailing: Size) {
        constructor(uniform: Float) : this(Size(uniform, uniform), Size(uniform, uniform), Size(uniform, uniform), Size(uniform, uniform))

        fun scaled(by: Float): CornerRadii =
            CornerRadii(topLeading * by, topTrailing * by, bottomLeading * by, bottomTrailing * by)
    }

    companion object {
        /**
         * A rectangle with an elliptical radius at each corner, clamped the way CSS clamps radii that
         * do not fit, added to `path` as one closed figure.
         */
        fun rounded(path: Path, rect: Rect, radii: CornerRadii) {
            if (rect.width <= 0 || rect.height <= 0) return
            val scale = min(
                1f,
                minOf(
                    rect.width / max(0.0001f, radii.topLeading.width + radii.topTrailing.width),
                    rect.width / max(0.0001f, radii.bottomLeading.width + radii.bottomTrailing.width),
                    rect.height / max(0.0001f, radii.topLeading.height + radii.bottomLeading.height),
                    rect.height / max(0.0001f, radii.topTrailing.height + radii.bottomTrailing.height),
                ),
            )
            val r = if (scale < 1f) radii.scaled(scale) else radii
            path.moveTo(rect.left + r.topLeading.width, rect.top)
            path.lineTo(rect.right - r.topTrailing.width, rect.top)
            corner(path, rect.right - r.topTrailing.width, rect.top + r.topTrailing.height, r.topTrailing, from = -90.0)
            path.lineTo(rect.right, rect.bottom - r.bottomTrailing.height)
            corner(path, rect.right - r.bottomTrailing.width, rect.bottom - r.bottomTrailing.height, r.bottomTrailing, from = 0.0)
            path.lineTo(rect.left + r.bottomLeading.width, rect.bottom)
            corner(path, rect.left + r.bottomLeading.width, rect.bottom - r.bottomLeading.height, r.bottomLeading, from = 90.0)
            path.lineTo(rect.left, rect.top + r.topLeading.height)
            corner(path, rect.left + r.topLeading.width, rect.top + r.topLeading.height, r.topLeading, from = 180.0)
            path.close()
        }

        /** A quarter ellipse, clockwise from `from` degrees, in the sixteen steps the Mac draws it in. */
        private fun corner(path: Path, centerX: Float, centerY: Float, radii: Size, from: Double) {
            if (radii.width <= 0 || radii.height <= 0) {
                val angle = (from + 90) * PI / 180
                path.lineTo(centerX + radii.width * cos(angle).toFloat(), centerY + radii.height * sin(angle).toFloat())
                return
            }
            val steps = 16
            for (step in 1..steps) {
                val angle = (from + 90.0 * step / steps) * PI / 180
                path.lineTo(centerX + radii.width * cos(angle).toFloat(), centerY + radii.height * sin(angle).toFloat())
            }
        }
    }
}

/** A CSS border drawn over this view, the way `border` paints on top of the element's own background. */
fun Modifier.chatBorder(border: ChatBorder, color: Color): Modifier = drawWithCache {
    val path = border.path(size, this)
    onDrawWithContent {
        drawContent()
        drawPath(path, color)
    }
}
