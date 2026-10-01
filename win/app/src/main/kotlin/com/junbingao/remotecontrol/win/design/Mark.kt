package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * `web/src/layout/Mark.tsx`: the product mark, the app icon drawn inline at any size — a
 * near-black rounded square with three white dots at the corners of a downward-pointing triangle,
 * joined by grey bars that stop short of the dots. Keep it identical to `web/public/icon.svg`,
 * the iOS `AppIcon` and the Mac's own.
 */
@Composable
fun Mark(size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val unit = this.size.width / 64
        scale(unit, unit, pivot = Offset.Zero) {
            drawRoundRect(Color.hex(0x161616), Offset.Zero, Size(64f, 64f), CornerRadius(14f, 14f))
            val bar = Color.hex(0x9A9A9A)
            drawLine(bar, Offset(24.3f, 22.7f), Offset(39.7f, 22.7f), strokeWidth = 2.1f)
            drawLine(bar, Offset(21.74f, 27.35f), Offset(29.06f, 38.95f), strokeWidth = 2.1f)
            drawLine(bar, Offset(42.26f, 27.35f), Offset(34.94f, 38.95f), strokeWidth = 2.1f)
            for ((x, y) in listOf(18.8f to 22.7f, 45.2f to 22.7f, 32.0f to 43.6f)) {
                drawCircle(Color.White, radius = 3.9f, center = Offset(x, y))
            }
        }
    }
}
