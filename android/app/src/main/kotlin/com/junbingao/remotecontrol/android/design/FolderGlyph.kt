package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * The mark before a session's working directory: lucide's `Folder`, drawn here so it matches the
 * web app's and the laptop before a device. A closed folder seen from the front — a body with a
 * tab rising on the left of its top edge — every corner rounded to the same radius, no fill.
 */
object FolderShape {
    /**
     * The outline's corners on the grid, walked clockwise from the bottom left corner: along the
     * bottom, up the right side, along the top to the tab, up the tab's slope, along the tab's
     * top, and down the left side.
     */
    val corners: List<Offset> = listOf(
        Offset(2f, 20f), Offset(22f, 20f), Offset(22f, 6f),
        Offset(11.02f, 6f), Offset(8.99f, 3f), Offset(2f, 3f),
    )
    const val cornerRadius = 2f

    fun path(rect: Rect): Path {
        val unit = min(rect.width, rect.height) / OutlineGlyph.grid
        fun place(p: Offset) = Offset(rect.left + p.x * unit, rect.top + p.y * unit)
        val path = Path()
        // Start midway along the bottom edge, so every corner is one arc.
        var current = place(Offset((corners[0].x + corners[1].x) / 2, corners[0].y))
        path.moveTo(current.x, current.y)
        for (index in 1..corners.size) {
            val corner = place(corners[index % corners.size])
            val next = place(corners[(index + 1) % corners.size])
            current = path.arcToTangents(current, corner, next, cornerRadius * unit)
        }
        path.close()
        return path
    }
}

/** `FolderShape` stroked in the ink beside a footnote-sized path, 14 at the default text size. */
@Composable
fun FolderGlyph(modifier: Modifier = Modifier) {
    val side = scaledMetric(14.dp, SystemFont.footnote)
    val ink = Theme.ink
    Canvas(modifier.size(side)) {
        drawPath(FolderShape.path(Rect(Offset.Zero, size)), ink, style = OutlineGlyph.stroke(size.minDimension))
    }
}
