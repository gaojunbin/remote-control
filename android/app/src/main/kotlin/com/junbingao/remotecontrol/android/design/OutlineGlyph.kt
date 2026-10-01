package com.junbingao.remotecontrol.android.design

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * What the app's line-drawn marks share (`docs/DESIGN.md` § "The device row" and § "The session
 * row"): lucide's 24-unit grid, a 1.5-unit stroke, round caps and joins, no fill. The laptop
 * before a device and the folder before a working directory are both drawn to it, so they read
 * as one hand.
 */
object OutlineGlyph {
    const val grid = 24f
    const val strokeUnits = 1.5f

    fun strokeWidth(size: Float): Float = size * strokeUnits / grid

    fun stroke(size: Float): Stroke =
        Stroke(width = strokeWidth(size), cap = StrokeCap.Round, join = StrokeJoin.Round)
}
